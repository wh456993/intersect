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
 * 请求体里的一条消息:role + content。
 *
 * 单独抽出来是为了让「消息顺序与记忆注入规则」变成可以直接单测的纯数据:
 * [ChatService.messages] 只决定顺序与内容,把 JSON 那一步留给 [ChatService.payload]。
 */
data class ChatMessage(val role: String, val content: String)

/**
 * 聊天页发消息时的对话请求。
 *
 * 请求体只有 model + messages(+ stream=false),不塞 temperature / max_tokens 之类的调节项。
 * 唯一的额外内容是角色设定与记忆:角色设定拼成一条 system 消息放在最前面(见 [systemPrompt]),
 * 即时记忆层的总结作为**单独一条 user 记忆块**插在历史与当前输入之间(见 [MemoryInject]).
 *
 * 走的是 [DevApiClient](HttpURLConnection,OpenAI 兼容协议),和 API KEY 管理页的
 * 「测试连接」「拉取模型」同一条链路,不再引新的网络库。
 */
object ChatService {
  /**
   * 最多注入多少轮「还没总结」的原文。
   *
   * 正常情况下攒够 [ImmediateMemoryState.SUMMARY_EVERY] 轮就会自动总结一次,所以未总结的
   * 永远不到 20 轮;这个上限兜的是另外两种情况:总结一直失败(未总结的越攒越多),
   * 以及升级上来的老会话(从没有过记忆状态,第一次请求时历史早已超过 20 轮)——
   * 两种情况下都只注入**最近**的 20 轮原文,更早的由总结覆盖。
   */
  const val HISTORY_ROUND_LIMIT = ImmediateMemoryState.SUMMARY_EVERY

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
   * 这一轮请求的 system 内容:角色卡 + 记忆使用规则。
   *
   * 两段每轮一字不改(角色设定没改、总结规则是常量),内容稳定 —— 服务商的前缀缓存
   * 才有机会命中。记忆本身不放在 system 里:它是会变的,而 system 要尽量不动。
   */
  fun systemWithMemory(friend: User): String = "${systemPrompt(friend)}\n\n${MemoryInject.RULES}"

  /**
   * 请求体,消息顺序是固定的四段:
   *
   * 1. system:角色卡 + 记忆使用规则;
   * 2. 历史:只留**最近没被总结过**的原文(已总结的整轮由记忆块代替);
   * 3. 一条独立的 user 记忆块:最近 [ImmediateMemoryState.INJECT_COUNT] 条即时记忆总结,
   *    没有总结时整块省略;
   * 4. 最后单独一条 user:用户本轮输入(可带时间戳与物品上下文)。
   *
   * 第 3 段与第 4 段**绝不合并**:记忆块是角色的过去经历,当前输入才是用户此刻说的话,
   * 混在一条消息里模型就会把记忆当成用户刚说的内容。
   *
   * [history] 末尾必须是刚发出去的那条(用户消息);[memory] 为 null 表示还没接记忆库。
   */
  fun payload(
    model: String,
    friend: User,
    history: List<Msg>,
    items: List<Item> = emptyList(),
    memory: ImmediateMemoryState? = null,
  ): String {
    val messages = JSONArray()
    messages(friend, history, items, memory).forEach { message ->
      messages.put(JSONObject().put("role", message.role).put("content", message.content))
    }
    return JSONObject()
      .put("model", model)
      .put("messages", messages)
      .put("stream", false)
      .toString()
  }

  /**
   * [payload] 的消息序列本身(纯数据,不碰 JSON)。
   *
   * 顺序就是上面那四段:system → 未总结原文 → 记忆块 → 当前输入。
   * 单独抽成函数是为了让这个契约能被单元测试直接钉住。
   */
  fun messages(
    friend: User,
    history: List<Msg>,
    items: List<Item> = emptyList(),
    memory: ImmediateMemoryState? = null,
  ): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    out += ChatMessage("system", systemWithMemory(friend))

    // 当前输入单列出来:它必须排在记忆块之后
    val current = history.lastOrNull()?.takeIf { it.from.id == User.Me.id }
    val past = if (current == null) history else history.dropLast(1)

    // 已总结的轮次不再注入原文:从 coveredRounds 之后切,再按上限兜一层。
    // 这里必须夹一次范围:删消息 / 恢复备份后落盘的覆盖轮次可能比实际轮次还大,
    // 不夹的话 drop() 会把整段历史丢光(模型只剩记忆块)。
    val rounds = MemoryRounds.group(past)
    val covered = (memory?.coveredRounds ?: 0).coerceIn(0, rounds.size)
    rounds
      .drop(covered)
      .takeLast(HISTORY_ROUND_LIMIT)
      .forEach { round -> round.forEach { msg -> out += ChatMessage(roleOf(msg), contentOf(msg, items)) } }

    // 记忆块:只放最近几条即时记忆总结,一条都没有时整块省略
    val summaries = memory?.latest.orEmpty()
    if (summaries.isNotEmpty()) out += ChatMessage("user", MemoryInject.block(summaries))

    current?.let { out += ChatMessage(roleOf(it), contentOf(it, items)) }
    return out
  }

  /** 一条消息的正文:用户消息先过物品注入,Self-insert 会话带上时间。 */
  private fun contentOf(msg: Msg, items: List<Item>): String {
    val text = if (msg.from.id == User.Me.id) injectItems(msg.text, items) else msg.text
    return msg.time?.let { "[$it] $text" } ?: text
  }

  /**
   * 物品注入:扫描用户这句话里出现的物品名称,把命中的物品(名称 + 简介)拼到话尾。
   *
   * 名称用 [SublimeFuzzy] 模糊匹配:不必一字不差地连续出现,忽略大小写、允许中间隔着
   * 空格或标点(如「药 水」仍算「药水」),让关键词扫描比原来的 `contains` 更宽容。
   * 只在用户消息上做:物品是用户带着的东西,不该塞进对方的回复里。简介留空的物品只带名称。
   *
   * 主聊天与开发者控制台共用这一份:两边拼出来的物品段落必须一字不差。
   */
  fun injectItems(text: String, items: List<Item>): String {
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
   * [memory] 是这个角色的即时记忆状态:决定注入哪些总结、跳过哪些已总结的原文。
   * 参数缺一项就直说缺哪项,不发一个注定失败的请求。
   */
  suspend fun reply(
    apiKey: ApiKey?,
    friend: User,
    history: List<Msg>,
    items: List<Item> = emptyList(),
    memory: ImmediateMemoryState? = null,
  ): ChatReply {
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
      payload = payload(apiKey.model, friend, history, items, memory),
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
