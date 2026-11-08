package com.reimei.intersect.data

import org.json.JSONArray
import org.json.JSONObject

/** 一次总结的结果;[actualTokens] 是生成后用 [TokenEstimator] 估出来的长度。 */
sealed class MemorySummaryResult {
  data class Ok(
    val text: String,
    val actualTokens: Int,
    val usage: ApiUsage?,
  ) : MemorySummaryResult()

  data class Fail(val message: String) : MemorySummaryResult()
}

/**
 * 即时记忆的总结请求。
 *
 * 与角色扮演请求**完全分开**:独立的一次 POST、独立的 system、低温度(0.3),
 * 只把这一批 20 轮原文交给模型,要求它只回一段记忆正文 —— 不让总结和扮演互相污染。
 *
 * - temperature 0.3:总结要稳,不要发挥;
 * - max_tokens = 目标长度 × 1.4:给模型留一点余量,再靠事后的二次压缩 / 截断收到目标以内;
 * - 生成后立刻用 [TokenEstimator] 估长度,超了先让模型自己压缩一次,还是超就直接截断。
 *
 * 走的是同一个 [DevApiClient](OpenAI 兼容协议),不额外引库。
 */
object MemorySummarizer {
  /** 总结用的温度:落在需求给的 0.2~0.4 之间。 */
  const val TEMPERATURE = 0.3

  /** max_tokens 相对目标长度的倍数(需求:1.3~1.5 倍)。 */
  private const val MAX_TOKEN_RATIO = 1.4

  /**
   * 一次总结请求最多送进去多少 token。
   *
   * 20 轮对话通常远不到这个量;但用户可能粘长文,超过服务商的上下文窗口时整次请求会被拒,
   * 而这种失败会每轮重试一次。超了就把每轮原文按比例截断(而不是从轮次里挑),
   * 这样覆盖轮次仍然连续,标签也仍然对得上。
   */
  private const val MAX_INPUT_TOKENS = 6000

  /**
   * 内置的总结规则模板。
   *
   * 开发者模式下可以在记忆库页把它换成自己的版本(见 [MemoryPromptStore]);
   * 文本里的 `{target}` / `{min}` 发请求前会被换成当前滑块的目标长度。
   */
  const val DEFAULT_TEMPLATE: String =
    "你是长对话的记忆压缩器。把用户给出的若干轮角色扮演对话,压缩成一段可以直接放进上下文的记忆正文。\n" +
      "要求:\n" +
      "1. 用第三人称,以「用户」和「角色」称呼双方,写清发生了什么、说定了什么、有哪些还没解决的线索。\n" +
      "2. 保留对后续对话有用的东西:称呼、关系、地点、物品、约定、情绪走向;不要逐轮罗列,不要复述原话。\n" +
      "3. 只输出记忆正文,不要标题、编号、解释、前言或收尾语,不要用代码块包起来。\n" +
      "4. 长度控制在 {min}~{target} token 之间。"

  /** 模板里的占位符。 */
  const val TARGET_SLOT = "{target}"
  const val MIN_SLOT = "{min}"

  /**
   * 把模板里的占位符换成具体数字。
   *
   * 模板里没写占位符也照发 —— 用户可能就是不想被长度约束,那就让模型的输出长度交给
   * max_tokens 与事后的压缩/截断来兜。
   */
  fun expand(template: String, targetTokens: Int): String {
    val floor = (targetTokens * 0.7).toInt().coerceAtLeast(1)
    return template
      .replace(TARGET_SLOT, targetTokens.toString())
      .replace(MIN_SLOT, floor.toString())
  }

  /** 总结规则(system):[template] 为空时用 [DEFAULT_TEMPLATE]。 */
  fun systemPrompt(targetTokens: Int, template: String = DEFAULT_TEMPLATE): String =
    expand(template.ifBlank { DEFAULT_TEMPLATE }, targetTokens)

  /** 第一次总结请求的报文:system = 总结规则,user = 这一批轮次的完整原文。 */
  fun payload(
    model: String,
    targetTokens: Int,
    rounds: List<String>,
    template: String = DEFAULT_TEMPLATE,
  ): String {
    val fitted = fitInput(rounds)
    val user = buildString {
      append("需要压缩的对话共 ")
      append(fitted.size)
      append(" 轮,原文如下:\n\n")
      append(fitted.joinToString("\n\n"))
    }
    return JSONObject()
      .put("model", model)
      .put("messages", JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt(targetTokens, template)))
        .put(JSONObject().put("role", "user").put("content", user)))
      .put("temperature", TEMPERATURE)
      .put("max_tokens", (targetTokens * MAX_TOKEN_RATIO).toInt())
      .put("stream", false)
      .toString()
  }

  /** 二次压缩的报文:把第一次的结果再压一遍,上限直接给目标长度。 */
  private fun compressPayload(model: String, targetTokens: Int, draft: String): String {
    val system = "把下面这段对话记忆压缩到 $targetTokens token 以内,保留事实与未解决的线索,删掉修饰与重复。" +
      "只输出压缩后的正文,不要标题、解释或代码块。"
    return JSONObject()
      .put("model", model)
      .put("messages", JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", draft.trim())))
      .put("temperature", TEMPERATURE)
      .put("max_tokens", targetTokens)
      .put("stream", false)
      .toString()
  }

  /**
   * 跑一次总结。
   *
   * [rounds] 是这一批轮次的原文(每项一轮,已按「第 N 轮」排好);
   * [targetTokens] 是滑块定下的目标长度;[template] 是 system 里的总结规则
   * (开发者模式可以在记忆库页改,见 [MemoryPromptStore])。
   * 缺 KEY / 模型名 / 地址时直接说缺哪一项,不发注定失败的请求。
   */
  suspend fun summarize(
    apiKey: ApiKey?,
    rounds: List<String>,
    targetTokens: Int,
    template: String = DEFAULT_TEMPLATE,
  ): MemorySummaryResult {
    if (rounds.isEmpty()) return MemorySummaryResult.Fail("还没有可以总结的轮次")
    if (apiKey == null) return MemorySummaryResult.Fail("还没有注册 API KEY,先去「我 → API KEY 管理」添加一条")
    if (apiKey.model.isBlank()) return MemorySummaryResult.Fail("这条 API KEY 没有填模型名,先在 API KEY 里选一个模型")
    if (!ApiKey.isUrlOk(apiKey.url)) return MemorySummaryResult.Fail("这条 API KEY 的接口地址不对,要以 http:// 或 https:// 开头")
    if (apiKey.key.isBlank()) return MemorySummaryResult.Fail("这条 API KEY 没有填 KEY")

    val endpoint = ApiProbe.chatEndpoint(apiKey.url)
    val target = targetTokens.coerceIn(ImmediateMemoryState.TARGET_MIN, ImmediateMemoryState.TARGET_MAX)

    val first = DevApiClient.chat(
      apiKey = apiKey.key,
      payload = payload(apiKey.model, target, rounds, template),
      endpoint = endpoint,
    )
    val firstText = when (first) {
      is DevApiResult.Fail -> return MemorySummaryResult.Fail(first.message)
      is DevApiResult.Ok -> clean(first.text)
    }
    if (firstText.isBlank()) return MemorySummaryResult.Fail("总结请求返回了空正文")

    var body = firstText
    var tokens = TokenEstimator.estimate(body)

    // 超了目标长度:先请模型自己压一遍,压成功且真的更短才采用
    if (tokens > target) {
      val second = DevApiClient.chat(
        apiKey = apiKey.key,
        payload = compressPayload(apiKey.model, target, body),
        endpoint = endpoint,
      )
      if (second is DevApiResult.Ok) {
        val shorter = clean(second.text)
        val shorterTokens = TokenEstimator.estimate(shorter)
        if (shorter.isNotBlank() && shorterTokens in 1 until tokens) {
          body = shorter
          tokens = shorterTokens
        }
      }
    }
    // 还是超:直接截断,宁可句子不完整也不让总结把上下文撑爆
    if (tokens > target) {
      body = TokenEstimator.truncate(body, target)
      tokens = TokenEstimator.estimate(body)
    }
    return MemorySummaryResult.Ok(body, tokens, (first as DevApiResult.Ok).usage)
  }

  /**
   * 把这一批原文的总量压在 [MAX_INPUT_TOKENS] 以内。
   *
   * 没超就原样返回;超了按轮平均分配预算、逐轮截断 —— 每轮都留一点,
   * 覆盖轮次与标签都不用改。
   */
  private fun fitInput(rounds: List<String>): List<String> {
    if (rounds.isEmpty()) return rounds
    val total = rounds.sumOf { TokenEstimator.estimate(it) }
    if (total <= MAX_INPUT_TOKENS) return rounds
    val budget = (MAX_INPUT_TOKENS / rounds.size).coerceAtLeast(120)
    return rounds.map { round -> TokenEstimator.truncate(round, budget) }
  }

  /** 去掉模型偶尔带上的代码块围栏与「总结:」这类前缀。 */
  /**
   * 收尾清洗:模型偶尔会裹一层代码块,或者自己加个「总结:」标题。
   *
   * 这些都不该进记忆正文(它们会被原样注入到下一轮请求里),在这里剥掉。
   */
  private fun clean(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("```")) {
      text = text.removePrefix("```")
      text = text.substringAfter('\n', "")
      text = text.substringBeforeLast("```")
    }
    text = text.trim()
    val prefix = listOf("总结:", "记忆正文:")
    prefix.firstOrNull { text.startsWith(it) }?.let { text = text.removePrefix(it).trim() }
    return text
  }
}
