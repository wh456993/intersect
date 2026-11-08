package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 角色唯一 ID(UID)的分配:只给存储层用的编号,对用户不可见,和昵称 / 创建时间 / 签名都无关。
 *
 * 规律是一条直线:内置角色 intersect 固定占 [INTERSECT_UID](0),用户自己固定占 [ME_UID](1),
 * 其余角色从 [FIRST_FREE_UID](2)起按**创建顺序**逐个递增。号发出去就跟着角色记录走
 * ([User.uid]),于是备份 / 恢复 / 换机都带着它;删掉的角色号不回收(计数器只往前走),
 * 所以同一个角色永远是同一个号,不同角色不会撞号。
 *
 * 这里只管发号与计数器:每轮对话的记忆索引用它发出来的号(见 [MemoryStamp])。
 */
object UidStore {
  private const val PREFS = "uid_store"
  private const val KEY_NEXT = "next_uid"

  /** 内置角色 intersect 的号,永远不变。 */
  const val INTERSECT_UID = 0L

  /** 用户自己的号(「我」),每轮记忆索引里都记它。 */
  const val ME_UID = 1L

  /** 第一个可以发给角色的号:0 与 1 已经被上面两个占住。 */
  const val FIRST_FREE_UID = 2L

  /**
   * 发一个新号,并把计数器推到下一个。
   *
   * 计数器用 commit 同步落地:号紧跟着就要写进消息的记忆索引,先落盘才不会有
   * 「消息带着号、计数器却没动」的窗口 —— 那会让下一张角色卡拿到同一个号。
   */
  fun nextUid(context: Context): Long {
    val prefs = prefsOf(context)
    return synchronized(this) {
      val uid = nextFree(prefs.getLong(KEY_NEXT, FIRST_FREE_UID))
      prefs.edit().putLong(KEY_NEXT, uid + 1).commit()
      uid
    }
  }

  /**
   * 登记一个外部带进来的号(恢复备份、老数据),让计数器至少跳到它后面。
   *
   * 只往前走:比当前计数器小的号不会把计数器拉回去,之后发的新号也不会和它撞。
   */
  fun reserve(context: Context, uid: Long) {
    val prefs = prefsOf(context)
    synchronized(this) {
      val current = prefs.getLong(KEY_NEXT, FIRST_FREE_UID)
      val next = counterAfter(current, uid)
      if (next != current) prefs.edit().putLong(KEY_NEXT, next).commit()
    }
  }

  /** 计数器该给出的下一个号:低于 [FIRST_FREE_UID] 的值一律拉回起点,永远不发出 0 与 1。 */
  internal fun nextFree(counter: Long): Long =
    if (counter < FIRST_FREE_UID) FIRST_FREE_UID else counter

  /** 已有 [taken] 这个号之后,计数器至少要跳到哪:至少比它大一个,且不低于起点。 */
  internal fun counterAfter(counter: Long, taken: Long): Long =
    maxOf(nextFree(counter), taken + 1)

  private fun prefsOf(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
