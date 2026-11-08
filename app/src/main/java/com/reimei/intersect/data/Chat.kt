package com.reimei.intersect.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一个会话:[msgs] 必须是可观察列表(mutableStateListOf),否则新增/删除消息不会触发界面重组。
 *
 * 这里不是 data class:对方信息与关联的 API KEY 都是可观察字段(见下),
 * 改了要立刻反映到界面上,而 data class 的构造参数没法这样委托。
 */
class Chat(friend: User, var msgs: MutableList<Msg>) {
  /**
   * 会话里的对方(含角色名称/身份/性格)。
   *
   * 用 mutableStateOf 而不是普通 var:角色设置页改完名称,聊天页标题和聊天信息页的名片
   * 得马上跟着变,否则要等下一次重组才看得到。
   */
  var friend: User by mutableStateOf(friend)

  /**
   * 这个会话关联的 API KEY(调用哪家服务、用哪个模型都跟着它走)。
   *
   * null 表示「跟随默认」:实际生效的是 API KEY 列表里的第一条,见 MainViewModel.apiKeyOf。
   * 用 mutableStateOf 而不是普通 var,是因为在聊天信息页里换一条 KEY 要立刻反映到界面上。
   */
  var apiKeyId: String? by mutableStateOf(null)
}

/**
 * 一条聊天消息。
 *
 * [time] 是可选的本地时间(精确到分钟):Self-insert 会话里由 [TimeManager] 注入,
 * 用来标记「这一轮发生在几点几分」;Avatar-based 会话保持 null,界面与请求都不变。
 *
 * [memory] 是这一轮落盘时留下的记忆索引,只挂在 AI 回复那条消息上(见 [MemoryStamp])。
 * 普通界面不显示,开发者模式下才在主聊天里露出来。
 */
data class Msg(
  val from: User,
  val text: String,
  val time: String? = null,
  /**
   * 这一轮的记忆索引。是 var:从备份合并时,本机那条老消息(没有索引)可以被回填一份,
   * 而不是让备份里那份索引白白丢掉。
   */
  var memory: MemoryStamp? = null,
) {
  var read: Boolean by mutableStateOf(false)
}

/**
 * 从 [target] 起把 [msgs] 截断(回滚到它之前),返回被删掉的条数;
 * 目标不在列表里时返回 -1,列表一个字都不动。
 *
 * 按**对象身份**定位,不用 [Msg] 的 equals:内容相同的两条消息是各自独立的,
 * 回滚一条不该把另一条也带走 —— 这是「删除」与「回滚」共用的性质,也是最容易被后人
 * 改回 `indexOf` / `removeAll` 的地方,所以抽成纯函数由单测钉住。
 *
 * 逐条 `removeAt` 而不是 `subList(...).clear()`:[msgs] 在界面里是 SnapshotStateList,
 * 逐条删除是它一定支持的最小操作集。
 */
fun truncateAt(msgs: MutableList<Msg>, target: Msg): Int {
  val index = msgs.indexOfFirst { it === target }
  if (index < 0) return -1
  val removed = msgs.size - index
  while (msgs.size > index) msgs.removeAt(msgs.lastIndex)
  return removed
}

/**
 * 一轮对话落盘时留下的记忆索引,只给存储层与开发者模式看。
 *
 * [characterUid] 是这一轮对方角色的唯一 ID(内置角色 intersect 固定 0,见 [UidStore]),
 * [userUid] 是用户自己的 UID(固定 1),[storedAt] 是这条记忆真正写进设备的本地时间,
 * 单位是秒(epoch second),显示时再按本地时区格式化。
 *
 * 一条消息对应一轮:「用户说一句 + AI 回一句」只产生这一份,挂在 AI 那条回复上,
 * 所以同一轮不会重复记帐。老的聊天记录没有这一项,界面上就不显示。
 */
data class MemoryStamp(
  val characterUid: Long,
  val userUid: Long,
  val storedAt: Long,
) {
  /** 开发者模式下显示的那一行:双方的号 + 精确到秒的落盘时间。 */
  fun label(): String = "记忆索引:角色 UID $characterUid · 用户 UID $userUid · 存入 " +
    TimeManager.formatSecond(storedAt)
}
