package com.reimei.intersect.data.memory

import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.ApiProbe
import com.reimei.intersect.data.ApiUsage
import com.reimei.intersect.data.DevApiClient
import com.reimei.intersect.data.DevApiResult
import com.reimei.intersect.data.MetaInfo
import com.reimei.intersect.data.TokenEstimator
import org.json.JSONArray
import org.json.JSONObject

/** 一次微总结的结果;[update] 是紧接着要并进 rolling_meta 的状态改动。 */
sealed class MicroSummaryResult {
  data class Ok(
    val text: String,
    val score: Int,
    val update: RollingMetaUpdate,
    val usage: ApiUsage?,
  ) : MicroSummaryResult()

  data class Fail(val message: String) : MicroSummaryResult()
}

/**
 * 微总结:每 20 轮一次,把这一批原文压成 250~600 token 的记忆正文,**同时**让模型给出
 * 0~100 的重要度评分与这批对话结束时的状态元信息。
 *
 * 与扮演请求完全分开:独立的一次 POST、独立的 system、低温度(0.3),
 * 不让总结和扮演互相污染 —— 这条纪律沿用了旧的 [com.reimei.intersect.data.MemorySummarizer]。
 *
 * 输出格式用固定标签([BODY_TAG] / [SCORE_TAG] / [STATE_TAG])而不是 JSON:
 * 模型偶尔会在 JSON 里带注释或多余字段,固定标签的容错更省事(解析见 [parse])。
 * [parse] 与 [userContent] 都是纯函数,能在 JVM 单测里直接跑。
 */
object MicroSummarizer {
  /** 总结用的温度:要稳,不要发挥。 */
  const val TEMPERATURE = 0.3

  /** 正文长度上下限(需求给的 250~600 token)。 */
  const val MIN_TOKENS = 250
  const val MAX_TOKENS = 600

  /**
   * 请求的 `max_tokens` 要比正文上限**更宽**:`[正文]` 之后还要输出 `[评分]` 与五行 `[状态]`,
   * 卡在 600 会把标签切掉 —— 评分回落默认值、状态更新整段静默失效。
   */
  const val REQUEST_MAX_TOKENS = MAX_TOKENS + 160

  /**
   * 一次总结请求最多送进去多少 token。
   *
   * 20 轮通常远不到这个量;但用户可能粘长文,超过服务商上下文窗口时整次请求会被拒(而且这种
   * 失败要等到下一次攒满 20 轮才会再试)。超了就按轮平均分配、逐轮截断 —— 覆盖轮次仍然连续。
   */
  const val MAX_INPUT_TOKENS = 6000

  /** 模型没给评分时的兜底值:中性,不影响任何阈值判断。 */
  const val SCORE_DEFAULT = 60

  /** 结果的三段标签。改这里要同步改 [parse] 与提示词。 */
  const val BODY_TAG = "[正文]"
  const val SCORE_TAG = "[评分]"
  const val STATE_TAG = "[状态]"

  /** 状态块里认识的键(与 [MetaInfo.block] 的字段名一致)。 */
  const val KEY_FAVOR = "好感"
  const val KEY_HEALTH = "生命"
  const val KEY_HUNGER = "饥饿"
  const val KEY_TIME = "时间"
  const val KEY_LOCATION = "地点"

  /** 总结规则(system)。**逐字常量**:同一批轮次重跑时提示词不变,便于对比结果。 */
  val SYSTEM_PROMPT: String = listOf(
    "你是长对话的记忆压缩器。把用户给出的若干轮角色扮演对话压缩成一段记忆正文,并同时给出这段内容的重要度评分与对话结束时的状态。",
    "要求:",
    "1. 用第三人称,以「用户」和「角色」称呼双方,写清发生了什么、说定了什么、还有哪些没解决的线索。",
    "2. 保留对后续对话有用的东西:称呼、关系、地点、物品、约定、情绪走向;不要逐轮罗列,不要复述原话。",
    "3. 正文长度控制在 $MIN_TOKENS~$MAX_TOKENS token 之间,按内容多少取合适的长度,不要为凑长度注水。",
    "4. 严格按下面的格式输出,不要代码块、不要标题以外的任何解释:",
    BODY_TAG,
    "记忆正文",
    SCORE_TAG,
    "0~100 的整数,表示这段内容对后续剧情的重要程度",
    STATE_TAG,
    "$KEY_FAVOR:0~100 的整数",
    "$KEY_HEALTH:0~100 的整数",
    "$KEY_HUNGER:0~100 的整数",
    "$KEY_TIME:当前时间或时段文本",
    "$KEY_LOCATION:当前地点文本",
    "5. 状态只在有变化或不确定度低时写,拿不准的那一行整行省略,不要编造。",
    "6. 正文里不要出现「好感:xx」这类字段行,不要写数值结算。",
    "7. 用户消息末尾可能带一段由程序生成的「[元信息]」块(好感 / 生命 / 饥饿等纯名词状态,以及当前生效的 Buff 词条),",
    "   它是这批对话结束时的即时状态,不是台词:按剧情把状态变化写进正文(该亲近的写亲近、该受伤的写受伤、该饿的写饿),",
    "   但不要复述字段行,也不要把它算进「对话轮次」。",
    "8. Buff 词条只允许「饥饿 / 失血 / 中毒 / 重病」四个,不要编造新词条。",
  ).joinToString("\n")

  /**
   * 总结请求的 user 正文:这一批轮次的完整原文,末尾追加当前元信息块(纯名词)。
   *
   * 元信息不带任何数值(见 [MetaInfo.block]),让模型知道「这批对话开始时角色是什么状态」,
   * 它据此写状态变化并把变化写进 [STATE_TAG] 段。
   */
  fun userContent(rounds: List<String>, meta: MetaInfo? = null): String {
    val fitted = fitInput(rounds)
    val body = buildString {
      append("需要压缩的对话共 ")
      append(fitted.size)
      append(" 轮,原文如下:\n\n")
      append(fitted.joinToString("\n\n"))
    }
    return if (meta == null) body else "$body\n\n${meta.block()}"
  }

  /**
   * 把这一批原文的总量压在 [MAX_INPUT_TOKENS] 以内。
   *
   * 没超就原样返回;超了按轮平均分配预算、逐轮截断 —— 每轮都留一点,
   * 覆盖轮次与标签都不用改。
   */
  fun fitInput(rounds: List<String>): List<String> {
    if (rounds.isEmpty()) return rounds
    val total = rounds.sumOf { TokenEstimator.estimate(it) }
    if (total <= MAX_INPUT_TOKENS) return rounds
    val budget = (MAX_INPUT_TOKENS / rounds.size).coerceAtLeast(120)
    return rounds.map { round -> TokenEstimator.truncate(round, budget) }
  }

  /**
   * 微总结请求的报文(system + user + 温度 + max_tokens)。
   *
   * [maxTokens] 是正文长度上限,由调用方从记忆库页滑块读出来(夹在 250~600);缺省用 [MAX_TOKENS]。
   */
  fun payload(
    model: String,
    rounds: List<String>,
    meta: MetaInfo? = null,
    maxTokens: Int = MAX_TOKENS,
  ): String =
    JSONObject()
      .put("model", model)
      .put(
        "messages",
        JSONArray()
          .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
          .put(JSONObject().put("role", "user").put("content", userContent(rounds, meta)))
      )
      .put("temperature", TEMPERATURE)
      .put("max_tokens", requestMaxTokens(maxTokens))
      .put("stream", false)
      .toString()

  /** 请求的 max_tokens:正文上限 + 标签余量([正文] 之后还有评分与状态)。 */
  fun requestMaxTokens(maxTokens: Int = MAX_TOKENS): Int =
    maxTokens.coerceIn(MIN_TOKENS, MAX_TOKENS) + (REQUEST_MAX_TOKENS - MAX_TOKENS)

  /** 解析后的一段微总结:正文 + 评分 + 状态改动(纯数据,单测直接断言)。 */
  data class Parsed(val text: String, val score: Int, val update: RollingMetaUpdate)

  /**
   * 解析模型返回的三段文本。
   *
   * 容错优先级:标签缺失就按「整段都是正文」处理,评分缺失给 [SCORE_DEFAULT],
   * 状态块缺失就是没有状态改动 —— 一次格式跑偏不该让整次总结作废。
   */
  fun parse(raw: String): Parsed {
    val cleaned = clean(raw)
    val scoreAt = cleaned.indexOf(SCORE_TAG)
    val stateAt = cleaned.indexOf(STATE_TAG)
    val bodyEnd = listOf(scoreAt, stateAt).filter { it >= 0 }.minOrNull() ?: cleaned.length
    val body = cleaned.substring(0, bodyEnd).removePrefix(BODY_TAG).trim()
    val scoreText = when {
      scoreAt < 0 -> ""
      stateAt > scoreAt -> cleaned.substring(scoreAt + SCORE_TAG.length, stateAt)
      else -> cleaned.substring(scoreAt + SCORE_TAG.length)
    }
    val stateText = if (stateAt >= 0) cleaned.substring(stateAt + STATE_TAG.length) else ""
    return Parsed(
      text = body,
      score = (firstInt(scoreText) ?: SCORE_DEFAULT).coerceIn(0, 100),
      update = parseState(stateText),
    )
  }

  /** 解析 [STATE_TAG] 段的 `键:值` 行;不认识的键、解析不出的数值一律忽略。 */
  fun parseState(text: String): RollingMetaUpdate {
    var favor: Int? = null
    var health: Int? = null
    var hunger: Int? = null
    var time: String? = null
    var location: String? = null
    text.lineSequence().forEach { line ->
      val trimmed = line.trim().removePrefix("-").trim()
      val cut = trimmed.indexOfFirst { it == ':' || it == ':' }
      if (cut <= 0) return@forEach
      val key = trimmed.substring(0, cut).trim()
      val value = trimmed.substring(cut + 1).trim()
      if (value.isEmpty()) return@forEach
      when (key) {
        KEY_FAVOR -> favor = firstInt(value)?.coerceIn(0, 100)
        KEY_HEALTH -> health = firstInt(value)?.coerceIn(0, 100)
        KEY_HUNGER -> hunger = firstInt(value)?.coerceIn(0, 100)
        KEY_TIME -> time = value.take(40)
        KEY_LOCATION -> location = value.take(40)
      }
    }
    return RollingMetaUpdate(
      favorability = favor,
      health = health,
      hunger = hunger,
      timeText = time,
      location = location,
    )
  }

  /**
   * 跑一次微总结。
   *
   * 缺 KEY / 模型名 / 地址时直接说缺哪一项,不发注定失败的请求。
   * 正文超长就按 [MAX_TOKENS] 截断 —— 轻量架构不做「二次压缩」那一套。
   */
  suspend fun summarize(
    apiKey: ApiKey?,
    rounds: List<String>,
    meta: MetaInfo? = null,
    maxTokens: Int = MAX_TOKENS,
  ): MicroSummaryResult {
    if (rounds.isEmpty()) return MicroSummaryResult.Fail("还没有可以总结的轮次")
    apiKey ?: return MicroSummaryResult.Fail("还没有注册 API KEY,先去「我 → API KEY 管理」添加一条")
    if (apiKey.model.isBlank()) return MicroSummaryResult.Fail("这条 API KEY 没有填模型名,先在 API KEY 里选一个模型")
    if (!ApiKey.isUrlOk(apiKey.url)) return MicroSummaryResult.Fail("这条 API KEY 的接口地址不对,要以 http:// 或 https:// 开头")
    if (apiKey.key.isBlank()) return MicroSummaryResult.Fail("这条 API KEY 没有填 KEY")

    val result = DevApiClient.chat(
      apiKey = apiKey.key,
      payload = payload(apiKey.model, rounds, meta, maxTokens),
      endpoint = ApiProbe.chatEndpoint(apiKey.url),
    )
    return when (result) {
      is DevApiResult.Fail -> MicroSummaryResult.Fail(result.message)
      is DevApiResult.Ok -> {
        val parsed = parse(result.text)
        if (parsed.text.isBlank()) {
          MicroSummaryResult.Fail("总结请求返回了空正文")
        } else {
          MicroSummaryResult.Ok(
            text = TokenEstimator.truncate(parsed.text, maxTokens.coerceIn(MIN_TOKENS, MAX_TOKENS)),
            score = parsed.score,
            update = parsed.update,
            usage = result.usage,
          )
        }
      }
    }
  }

  /** 去掉模型偶尔带上的代码块围栏与「总结:」这类前缀。 */
  internal fun clean(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("```")) {
      text = text.removePrefix("```")
      text = text.substringAfter('\n', "")
      text = text.substringBeforeLast("```")
    }
    text = text.trim()
    listOf("总结:", "记忆正文:").firstOrNull { text.startsWith(it) }
      ?.let { text = text.removePrefix(it).trim() }
    return text
  }

  /** 一段文本里的第一个整数;没有则 null。 */
  internal fun firstInt(text: String): Int? =
    Regex("\\d+").find(text)?.value?.toIntOrNull()
}
