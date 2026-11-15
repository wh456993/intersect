package com.reimei.intersect.data

import com.reimei.intersect.data.memory.MemoryRepository
import com.reimei.intersect.data.memory.MemorySnapshot
import com.reimei.intersect.data.time.InjectSegment
import com.reimei.intersect.data.time.InjectionPlan
import com.reimei.intersect.data.time.TimeRounds
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
 * 单独抽出来是为了让「消息顺序与注入规则」变成可以直接单测的纯数据:
 * [ChatService.messages] 只决定顺序与内容,把 JSON 那一步留给 [ChatService.payload]。
 */
data class ChatMessage(val role: String, val content: String)

/**
 * 聊天页发消息时的对话请求,报文按**分段组装模型**拼:静态前缀稳定、动态状态独立成条。
 *
 * 报文结构严格且唯一(3 条或 4 条,首轮对话少一条 assistant):
 *
 * | # | role | 内容 | 稳定性 |
 * |---|---|---|---|
 * | 1 | `system` | 角色卡 + 元信息规则 + 记忆规则 + 时间规则 | **逐字节恒定** |
 * | 2 | `user` | 状态快照:元信息(rolling_meta)+ 记忆库(大总结 + 微总结) | 每轮可变 |
 * | 3 | `assistant` | 历史里的**最近一轮**角色回复 | 可变 |
 * | 4 | `user` | 用户本轮输入(带 `[时间]` 前缀与物品信息) | 可变 |
 *
 * 三条纪律:
 * 1. `system` 里**不许**出现时间戳、随机数、token 数或任何随轮次变化的内容 ——
 *    内容稳定,服务商的前缀缓存才有机会命中;会变的状态一律走第 2 条。
 * 2. 记忆不再单独成条:`node_summary` / `micro_summary` 只作为状态快照的一部分出现,
 *    旧版「记忆总结块直接充当时间总结块」的特殊报文形态已废弃。
 * 3. 时间子系统的旁路注入(临时时间事实 / 导演事件)仍然保留,但**并进状态快照那一条**,
 *    报文条数不变:它们和快照一样是「系统注入的事实」,不是谁说的话。
 *
 * 走的是 [DevApiClient](HttpURLConnection,OpenAI 兼容协议),和 API KEY 管理页的
 * 「测试连接」「拉取模型」同一条链路,不再引新的网络库。
 */
object ChatService {
  /**
   * 请求体的采样温度:模板里固定 0.7。
   *
   * 总结请求另有一套(0.3,见 [com.reimei.intersect.data.memory.MicroSummarizer.TEMPERATURE]) —— 总结要稳,扮演要活。
   */
  const val TEMPERATURE = 0.7

  /** 静态设定卡的开场白(模板第一行)。 */
  const val CARD_INTRO = "请严格遵循以下静态设定进行角色扮演:"

  /**
   * 状态快照那条 user 的首行(固定标注):告诉模型这段是事实参考,不是用户发言。
   *
   * 这一行是**报文契约的一部分**,单测与面板都按它定位快照,改动要同步改断言。
   */
  const val SNAPSHOT_HEADER = "【以下为系统注入的状态快照,不是用户发言】"

  /** 快照首行之后的补充说明(仍是固定文本,不含任何动态内容)。 */
  const val SNAPSHOT_HINT = "请作为事实参考,不要复述字段名。"

  /** 状态快照里的记忆库小节标题。 */
  const val MEMORY_SECTION = "【记忆库】"

  /** 记忆库里「主线大总结」的小标题。 */
  const val NODE_SECTION = "[主线大总结]"

  /** 记忆库里「近期微总结」的小标题。 */
  const val MICRO_SECTION = "[近期微总结]"

  /**
   * 静态设定卡 —— 报文第 1 条(`【角色身份】` / `【世界观】` / `【背景】` + 元信息规则)。
   *
   * 只写用户填过的东西:名称总是有,性格 / 身份 / 世界观 / 背景没填就**整段或整行省略**
   * (模板约定「未出现的行 = 该字段未登记」)。整卡每轮一字不改:内容稳定,服务商的
   * 前缀缓存才有机会命中 —— 会变的状态走 [snapshotBlock],不进这里。
   *
   * 世界观与背景是角色代入专属字段([User.worldview] / [User.background]):本体代入时它们
   * 恒为 null,所以卡片自然只剩角色身份。
   */
  fun characterCard(friend: User): String = buildString {
    append(CARD_INTRO)
    append("\n\n【角色身份】\n名称:").append(friend.name.trim().ifEmpty { "对方" })
    friend.personality?.trim().takeIf { !it.isNullOrEmpty() }?.let { append("\n性格:").append(it) }
    friend.role?.trim().takeIf { !it.isNullOrEmpty() }?.let { append("\n身份:").append(it) }
    worldviewSection(friend.worldview)?.let { append("\n\n").append(it) }
    friend.background?.trim().takeIf { !it.isNullOrEmpty() }
      ?.let { append("\n\n【背景】\n来历与处境:").append(it) }
    append("\n\n").append(MetaInfo.RULES)
  }

  /** 世界观四项:填过一项才出段,一项都没填时整段省略(返回 null)。 */
  private fun worldviewSection(worldview: Worldview?): String? {
    val filled = worldview?.takeIf { !it.isEmpty } ?: return null
    val lines = Worldview.LABELS.zip(filled.parts).mapNotNull { (label, value) ->
      value?.trim()?.takeIf { it.isNotEmpty() }?.let { "$label:$it" }
    }
    if (lines.isEmpty()) return null
    return "【世界观】\n" + lines.joinToString("\n")
  }

  /**
   * 同上,但直接给三项字段 —— 开发者控制台的角色设定不走通讯录,也用它拼。
   *
   * 控制台的报文是另一套(它自己拼历史与角色卡),所以这里保持简版的「你是X。身份:...」,
   * 不要把它改成模板卡:改了会连带改变控制台的报文形状。
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
   * 这一轮请求的 system 内容:角色卡(含元信息规则)+ 记忆使用规则 + 时间规则。
   *
   * 拼装顺序就是需求给的顺序:**角色卡 → 元信息规则 → 记忆规则 → 时间规则**;
   * 元信息规则是 [characterCard] 末尾那段常量([MetaInfo.RULES]),所以这里不再重复追加。
   *
   * 三段都是常量(设定卡没改、两段规则是常量),内容稳定 —— 服务商的前缀缓存才有机会命中。
   * 记忆、元信息与时间事实都不放在 system 里:它们会变,而 system 要逐字节不动。
   */
  fun systemWithMemory(friend: User, time: InjectionPlan? = null): String {
    val base = "${characterCard(friend)}\n\n${MemoryInject.RULES}"
    // 时间规则块是常量(见 [TimeRules.RULES_TEXT]):放在 system 末尾而不是单独一条 user,
    // 既省一条消息也不会被模型当成「本轮新事实」。
    val rules = time?.blocks?.firstOrNull { it.segment == InjectSegment.RULES }?.text
    return if (rules.isNullOrBlank()) base else "$base\n\n$rules"
  }

  /**
   * 状态快照 —— 报文第 2 条 user 的正文,按固定顺序拼三段:
   *
   * 1. 首行固定标注([SNAPSHOT_HEADER])+ 补充说明;
   * 2. 元信息块(来自 memory.db 的 rolling_meta,见 [MetaInfo.block],**只有名词没有数字**);
   * 3. 记忆库:最新 1 条大总结 + 最近 3 条微总结([memoryBlock])。
   *
   * [time] 里的旁路块(临时时间事实 / 导演事件)追加在最后 —— 它们和快照一样是系统注入的
   * 事实,合进同一条才不破坏「报文只有 3~4 条」这个结构。
   *
   * 物品小节**不放这里**:物品仍按原做法扫关键词拼到用户话尾(见 [injectItems])。
   */
  fun snapshotBlock(
    meta: MetaInfo,
    memory: MemorySnapshot? = null,
    time: InjectionPlan? = null,
  ): String = buildString {
    append(SNAPSHOT_HEADER)
    append('\n').append(SNAPSHOT_HINT)
    append("\n\n").append(meta.block())
    val memoryText = memoryBlock(memory)
    if (memoryText.isNotEmpty()) {
      append("\n\n").append(MEMORY_SECTION).append('\n').append(memoryText)
    }
    // 时间旁路注入:规则块已在 system 里(messageBlocks 会把它过滤掉),
    // 这里只会有临时时间事实 / 导演事件这类动态块。
    //
    // 记忆总结相关的分段(SUMMARY / SUMMARY_META)在这里被丢掉:那是记忆内容与它的段落元数据,
    // 现在只有 memory.db 一条来源,再让它们成段就会与快照里的记忆重复 ——
    // 这条过滤就是「记忆总结块不再充当时间总结块」在装配层的落点。
    time?.let { plan ->
      TimeRounds.messageBlocks(plan)
        .filter { !it.isEmpty && it.segment != InjectSegment.SUMMARY && it.segment != InjectSegment.SUMMARY_META }
        .forEach { block -> append("\n\n").append(block.text) }
    }
  }

  /**
   * 记忆库小节的正文:大总结在前、微总结在后(都是第三人称的「角色过去经历」)。
   *
   * 微总结按**时间顺序**编号:[memory] 里给的是新 → 旧,这里反过来,否则模型会把最新的
   * 一段当成最早的,剧情容易倒着走。
   *
   * 没有记忆时返回空串,调用方据此整节省略 —— 不要留一个空标题给模型。
   */
  fun memoryBlock(memory: MemorySnapshot?): String {
    if (memory == null || memory.isEmpty) return ""
    val parts = mutableListOf<String>()
    memory.nodeSummary.trim().takeIf { it.isNotEmpty() }
      ?.let { parts += "$NODE_SECTION\n$it" }
    val micros = memory.microSummaries
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .take(MemoryRepository.SNAPSHOT_MICRO_COUNT)
      .reversed()
    if (micros.isNotEmpty()) {
      parts += MICRO_SECTION + "\n" + micros.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")
    }
    return parts.joinToString("\n\n")
  }

  /**
   * 请求体:模型 + 温度 + 分段消息 + 非流式。
   *
   * [history] 末尾必须是刚发出去的那条(用户消息);[memory] 为 null 表示这个角色还没有记忆库;
   * [meta] 应当已经过 [SceneTime.applyTo](时间由时钟决定,不由用户手填)。
   */
  fun payload(
    model: String,
    friend: User,
    history: List<Msg>,
    items: List<Item> = emptyList(),
    memory: MemorySnapshot? = null,
    time: InjectionPlan? = null,
    meta: MetaInfo = MetaInfo.empty(friend.id),
  ): String {
    val messages = JSONArray()
    messages(friend, history, items, memory, time, meta).forEach { message ->
      messages.put(JSONObject().put("role", message.role).put("content", message.content))
    }
    return JSONObject()
      .put("model", model)
      .put("temperature", TEMPERATURE)
      .put("messages", messages)
      .put("stream", false)
      .toString()
  }

  /**
   * [payload] 的消息序列本身(纯数据,不碰 JSON)。
   *
   * 顺序就是分段模型:**system → 状态快照 → 最近一轮回复 → 当前输入**。
   * 单独抽成函数是为了让这个契约能被单元测试直接钉住(见 ChatServiceSegmentPayloadTest)。
   *
   * 只注入**最近一轮**角色回复是模板的规定:更早的对话由记忆库摘要负责(见 [snapshotBlock]),
   * 不再把未总结的原文整段塞进请求体。
   */
  fun messages(
    friend: User,
    history: List<Msg>,
    items: List<Item> = emptyList(),
    memory: MemorySnapshot? = null,
    time: InjectionPlan? = null,
    meta: MetaInfo = MetaInfo.empty(friend.id),
  ): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    out += ChatMessage("system", systemWithMemory(friend, time))

    // 当前输入单列出来:它必须排在快照与最近一轮回复之后
    val current = history.lastOrNull()?.takeIf { it.from.id == User.Me.id }
    val past = if (current == null) history else history.dropLast(1)

    out += ChatMessage("user", snapshotBlock(meta, memory, time))

    // 历史只留最近一轮角色回复(模板的 {{历史对话_最近一轮AI回复}});首轮对话时整条省略
    past.lastOrNull { it.from.id != User.Me.id }
      ?.let { out += ChatMessage("assistant", contentOf(it, items)) }

    current?.let { out += ChatMessage("user", contentOf(it, items)) }
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
   * [friend] 是会话的对方:它的名称 / 身份 / 性格 / 世界观 / 背景决定这一轮的角色设定。
   * [memory] 是这个角色从 memory.db 读出的状态快照(最新大总结 + 最近 3 条微总结)。
   * [meta] 是这个角色的即时元信息(**已套好时间**):决定快照里的状态名词。
   * 参数缺一项就直说缺哪项,不发一个注定失败的请求。
   */
  suspend fun reply(
    apiKey: ApiKey?,
    friend: User,
    history: List<Msg>,
    items: List<Item> = emptyList(),
    memory: MemorySnapshot? = null,
    time: InjectionPlan? = null,
    meta: MetaInfo = MetaInfo.empty(friend.id),
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

    // 黑匣子:请求发出去之前先落一行「发了什么规格的请求」(正文与 KEY 都不进日志)
    BugLog.log(
      "网络",
      "→ POST ${ApiProbe.chatEndpoint(apiKey.url)} · model=${apiKey.model} · " +
        "历史 ${history.size} 条 · 记忆 ${if (memory == null || memory.isEmpty) "无" else "有"}"
    )

    val result = DevApiClient.chat(
      apiKey = apiKey.key,
      payload = payload(apiKey.model, friend, history, items, memory, time, meta),
      endpoint = ApiProbe.chatEndpoint(apiKey.url),
    )
    // 黑匣子:只记「发了什么规格的请求、回没回」—— 不记 KEY,也不记对话正文
    when (result) {
      is DevApiResult.Ok -> BugLog.log(
        "网络",
        "← 成功 ${result.statusCode} · ${result.text.length} 字 · model=${apiKey.model}"
      )
      is DevApiResult.Fail -> BugLog.log(
        "网络",
        "← 失败 ${result.statusCode} · model=${apiKey.model} · ${result.message}"
      )
    }
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
