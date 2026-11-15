package com.reimei.intersect.data.time

import android.content.Context
import android.content.SharedPreferences

/**
 * T2.6 —— 时钟账本落盘:按角色一份,一个键一个 JSON 文档(状态 + 快照 + 事务日志)。
 *
 * 和 [com.reimei.intersect.data.MemoryStore] 同一套做法:读不出来就当「还没有时钟」,
 * 不让一次坏数据把页面挡住。序列化本身走 [ClockLedger.encode] / [ClockLedger.decode]
 * (纯 Kotlin),所以往返、裁剪、回滚都能在 JVM 单测里覆盖 —— 这里只有 prefs 的读写。
 */
object ClockStore {
  private const val PREFS = "scene_clock"
  private const val KEY_PREFIX = "ledger_"

  fun load(context: Context, characterId: String): ClockLedger =
    ClockLedger.decode(prefsOf(context).getString(keyOf(characterId), null))

  fun save(context: Context, characterId: String, ledger: ClockLedger) {
    prefsOf(context).edit().putString(keyOf(characterId), ledger.encode()).apply()
  }

  /** 删角色时顺手清掉时钟,不留孤儿条目。 */
  fun remove(context: Context, characterId: String) {
    prefsOf(context).edit().remove(keyOf(characterId)).apply()
  }

  private fun keyOf(characterId: String) = "$KEY_PREFIX$characterId"

  private fun prefsOf(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
