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
 */
data class Msg(val from: User, val text: String, val time: String? = null) {
  var read: Boolean by mutableStateOf(false)
}
