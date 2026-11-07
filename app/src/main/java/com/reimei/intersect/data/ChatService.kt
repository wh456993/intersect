package com.reimei.intersect.data

import org.json.JSONArray
import org.json.JSONObject

/** 一次对话请求的结果;[message] 在成功时是模型回复的正文,失败时是给用户看的原因。 */
sealed class ChatReply {
  /** [usage] 是这次调用的用量(服务商没回时为 null);成功即计费,由调用方落流水。 */
  data class Ok(val text: String, val usage: ApiUsage? = null) : ChatReply()
  data class Fail(val message: String) : ChatReply()
}

/**
 * 聊天页发消息时的对话请求。
 *
 * 请求体只有 model + messages(+ stream=false),不塞 temperature / max_tokens 之类的调节项。
 * 唯一的额外内容是这个角色的设定:名称 / 身份 / 性格会被拼成一条 system 消息放在最前面
 * (见 [systemPrompt]),其余「你是谁」之类的系统提示一概不加。
 *
 * 走的是 [DevApiClient](HttpURLConnection,OpenAI 兼容协议),和 API KEY 管理页的
 * 「测试连接」「拉取模型」同一条链路,不再引新的网络库。
 */
object ChatService {
  /** 发消息时最多回头看多少条历史:只为了连贯,不指望模型记住很早的内容。 */
  const val HISTORY_LIMIT = 20

  /** 历史消息的角色:我发的是 user,对方发的是 assistant。 */
  private fun roleOf(msg: Msg): String = if (msg.from.id == User.Me.id) "user" else "assistant"

  /**
   * 这个角色的设定,拼成一条 system 消息的内容。
   *
   * 只写用户填过的东西:名称总是有,身份 / 性格没填就不提这一项。角色设定每轮都带上,
   * 模型才知道自己该以什么身份、什么脾气说话。
   */
  fun systemPrompt(friend: User): String =
    systemPrompt(friend.name, friend.role, friend.personality)

  /**
   * 同上,但直接给三项字段 —— 开发者控制台的角色设定不走通讯录,也用它拼。
   */
  fun systemPrompt(name: String, role: String?, personality: String?): String {
    val nameText = name.trim()
    val roleText = role?.trim().orEmpty()
    val personalityText = personality?.trim().orEmpty()
    return buildString {
      append("你是")
      append(nameText.ifEmpty { "对方" })
      if (roleText.isNotEmpty()) {
        append("。身份:")
        append(roleText)
      }
      if (personalityText.isNotEmpty()) {
        append("。性格:")
        append(personalityText)
      }
      append("。")
    }
  }

  /**
   * 请求体:模型名 + [系统提示(角色设定)] + 消息列表。
   *
   * [history] 末尾必须是刚发出去的那条(用户消息),它和之前的历史一起按顺序转成
   * user / assistant 两种角色 —— 这是 OpenAI 兼容协议里最基础的一段,不掺别的东西。
   */
  fun payload(model: String, friend: User, history: List<Msg>, items: List<Item> = emptyList()): String {
    val messages = JSONArray()
    val system = systemPrompt(friend)
    if (system.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", system))
    }
    history.takeLast(HISTORY_LIMIT).forEach { msg ->
      // 用户消息先过一遍物品管理器:提到某件物品的名称就把它的名称与简介一起交给模型
      val text = if (msg.from.id == User.Me.id) injectItems(msg.text, items) else msg.text
      messages.put(
        JSONObject()
          .put("role", roleOf(msg))
          // Self-insert 会话的消息带着时间:把它一并交给模型,它才知道「现在几点」
          .put("content", msg.time?.let { "[$it] $text" } ?: text)
      )
    }
    return JSONObject()
      .put("model", model)
      .put("messages", messages)
      .put("stream", false)
      .toString()
  }

  /**
   * 物品注入:扫描用户这句话里出现的物品名称,把命中的物品(名称 + 简介)拼到话尾。
   *
   * 名称用 [SublimeFuzzy] 模糊匹配:不必一字不差地连续出现,忽略大小写、允许中间隔着
   * 空格或标点(如「药 水」仍算「药水」),让关键词扫描比原来的 `contains` 更宽容。
   * 只在用户消息上做:物品是用户带着的东西,不该塞进对方的回复里。简介留空的物品只带名称。
   */
  private fun injectItems(text: String, items: List<Item>): String {
    val matched = items.filter { it.name.isNotBlank() && SublimeFuzzy.matches(it.name, text) }
    if (matched.isEmpty()) return text
    val block = matched.joinToString("\n") { item ->
      if (item.description.isBlank()) "「${item.name}」" else "「${item.name}」:${item.description}"
    }
    return "$text\n\n[物品] $block"
  }

  /**
   * 把这个会话目前的消息交出去,换回一条回复。
   *
   * [friend] 是会话的对方:它的名称 / 身份 / 性格决定这一轮的角色设定。
   * 参数缺一项就直说缺哪项,不发一个注定失败的请求。
   */
  suspend fun reply(apiKey: ApiKey?, friend: User, history: List<Msg>, items: List<Item> = emptyList()): ChatReply {
    if (apiKey == null) {
      return ChatReply.Fail("还没有注册 API KEY,先去「我 → API KEY 管理」添加一条")
    }
    if (apiKey.model.isBlank()) {
      return ChatReply.Fail("这条 API KEY 没有填模型名,先在 API KEY 里选一个模型")
    }
    if (!ApiKey.isUrlOk(apiKey.url)) {
      return ChatReply.Fail("这条 API KEY 的接口地址不对,要以 http:// 或 https:// 开头")
    }
    if (apiKey.key.isBlank()) {
      return ChatReply.Fail("这条 API KEY 没有填 KEY")
    }
    if (history.isEmpty()) return ChatReply.Fail("没有可以发送的内容")

    val result = DevApiClient.chat(
      apiKey = apiKey.key,
      payload = payload(apiKey.model, friend, history, items),
      endpoint = ApiProbe.chatEndpoint(apiKey.url),
    )
    return when (result) {
      // 用量随回复一起带回去:只有调用方知道这次请求属于哪个密钥组、哪个聊天窗口
      is DevApiResult.Ok -> ChatReply.Ok(result.text, result.usage)
      is DevApiResult.Fail ->
        // statusCode 为 0 表示连响应都没拿到(连不上 / 超时),这时 message 是底层异常
        ChatReply.Fail(
          if (result.statusCode == 0) "请求没有发出去:${result.message}" else result.message
        )
    }
  }
}
