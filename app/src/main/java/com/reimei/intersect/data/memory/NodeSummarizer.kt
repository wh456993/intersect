package com.reimei.intersect.data.memory

import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.ApiProbe
import com.reimei.intersect.data.ApiUsage
import com.reimei.intersect.data.BugLog
import com.reimei.intersect.data.DevApiClient
import com.reimei.intersect.data.DevApiResult
import com.reimei.intersect.data.TokenEstimator
import org.json.JSONArray
import org.json.JSONObject

/** 一次大总结的结果。 */
sealed class NodeSummaryResult {
  data class Ok(val text: String, val usage: ApiUsage?) : NodeSummaryResult()
  data class Fail(val message: String) : NodeSummaryResult()
}

/**
 * 节点大总结:把 3 条连续的微总结再压成一段 10~50 词的「主线」。
 *
 * 为什么还要这一层:微总结是逐段的细节,条数一多就会把上下文撑长;主线只保留
 * 「这段剧情走到哪了」,让请求体里的大总结始终是一条短文本。
 *
 * 与 [MicroSummarizer] 同一套纪律:独立请求、低温度、纯文本输出(不做 JSON)。
 *
 * 额外一层防线:实测 deepseek-flash 这类模型会把英文推理过程当正文吐出来
 * (「We need answer Chinese only ... Maybe: "正文"」),而微总结那层的 [MicroSummarizer.clean]
 * 只认得代码块围栏,压不住这种泄漏。所以这里除了在提示词末尾加硬约束,还在 [sanitize]
 * 里做一次后处理清洗,并用 [isBodyUsable] 把「清洗完还没有中文主线」的结果直接判失败 ——
 * 脏数据宁可这一轮不写 node_summary,也不进库污染后面每一轮的请求体。
 */
object NodeSummarizer {
  const val TEMPERATURE = 0.3

  /** 10~50 词 → 中文大致 40~120 token,截断上限给一点余量。 */
  const val MAX_TOKENS = 120

  const val SYSTEM_PROMPT: String =
    "你把多段已经压缩过的角色扮演对话记忆合并成一条主线大总结。\n" +
      "要求:\n" +
      "1. 用第三人称,以「用户」和「角色」称呼双方,只写主线:关系走到哪一步、当前目标、悬而未决的线索。\n" +
      "2. 去掉细节与修饰,不要逐段罗列,不要复述原话,不要编造没有发生过的事。\n" +
      "3. 长度控制在 10~50 个词。\n" +
      "4. 只输出主线正文,不要标题、编号、解释或代码块。\n" +
      "【强制要求】直接输出最终的主线正文,禁止输出任何思考过程、分析过程、英文解释或前缀。一旦包含,视为违规。"

  /** 清洗后的正文至少要有这么多汉字(≈10 个中文词),不够就当没拿到正文。 */
  const val MIN_CJK_CHARS = 10

  /**
   * 思考过程泄漏的显著标记。[sanitize] 会从**最后一个**标记之后开始取正文 ——
   * 模型通常是「先推理一大段、最后给答案」,所以取最后一段而不是第一段。
   */
  private val THINKING_MARKERS = listOf(
    "We need", "we need", "We must", "we must", "I need", "I should",
    "Let me", "let me", "The user", "Maybe:", "maybe:",
    "Analysis:", "Reasoning:", "Answer:",
  )

  /** 连排的英文单词(3 个以上)—— 正常中文正文里不会出现,出现就当推理残留。 */
  private val LATIN_RUN = Regex("[A-Za-z][A-Za-z'’\\-]*(?:\\s+[A-Za-z][A-Za-z'’\\-]*){2,}")

  /** 包在正文外面的装饰字符("正文" / **正文** / 正文: 之类),清洗时剥掉。 */
  private val WRAPPERS = "\"'“”‘’《》「」『』::-—–*# ".toSet()

  /** 大总结请求的 user 正文:三段微总结按时间顺序排好。 */
  fun userContent(microSummaries: List<String>): String = buildString {
    append("需要合并的记忆共 ")
    append(microSummaries.size)
    append(" 段,按时间先后排列:\n\n")
    append(microSummaries.mapIndexed { index, text -> "第 ${index + 1} 段:\n${text.trim()}" }.joinToString("\n\n"))
  }

  fun payload(model: String, microSummaries: List<String>): String =
    JSONObject()
      .put("model", model)
      .put(
        "messages",
        JSONArray()
          .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
          .put(JSONObject().put("role", "user").put("content", userContent(microSummaries)))
      )
      .put("temperature", TEMPERATURE)
      .put("max_tokens", MAX_TOKENS)
      .put("stream", false)
      .toString()

  /**
   * 清洗模型返回的正文:把英文思考过程、分析前缀、包装引号全部去掉,只留中文主线。
   *
   * 四步(每一步都防误伤正常中文正文):
   * 1. 有思考标记 → 从最后一个标记之后开始取;
   * 2. 没标记但整段以连排英文开头 → 从第一个汉字开始取;
   * 3. 汉字段落后面又跟着连排英文 → 截到最后一个汉字(连带其后的中文标点)为止;
   * 4. 剥掉外层引号/冒号等装饰,去掉空行。
   *
   * 纯函数,单测直接钉。
   */
  fun sanitize(raw: String): String {
    var text = MicroSummarizer.clean(raw)
    if (text.isBlank()) return ""

    // 1. 英文推理 + 标记:取最后一段(只有标记后面确实还有中文正文时才切,
    //    避免「中文正文在前、英文推理在后」时把中文整段扔掉)
    var start = 0
    THINKING_MARKERS.forEach { marker ->
      val at = text.lastIndexOf(marker)
      if (at >= 0) start = maxOf(start, at + marker.length)
    }
    if (start in 1 until text.length && text.substring(start).any { isHan(it) }) {
      text = text.substring(start)
    }

    // 2. 没有标记,但开头是连排英文:从第一个汉字开始取
    val firstHan = text.indexOfFirst { isHan(it) }
    if (firstHan > 0 && LATIN_RUN.containsMatchIn(text.substring(0, firstHan))) {
      text = text.substring(firstHan)
    }

    // 3. 正文后面又跟着英文推理:截到最后一个汉字(连带后面的中文标点)
    val lastHan = text.indexOfLast { isHan(it) }
    if (lastHan >= 0 && lastHan < text.lastIndex) {
      val tail = text.substring(lastHan + 1)
      if (LATIN_RUN.containsMatchIn(tail)) {
        var end = lastHan + 1
        while (end < text.length && (isHan(text[end]) || isHanPunct(text[end]))) end++
        text = text.substring(0, end)
      }
    }

    // 4. 剥装饰 + 去空行
    return text.lines()
      .map { line -> line.trim().trim { it in WRAPPERS } }
      .filter { it.isNotEmpty() }
      .joinToString("\n")
      .trim()
  }

  /** 一段正文里的汉字个数(标点、英文、数字都不算)。 */
  fun cjkLength(text: String): Int = text.count { isHan(it) }

  /** 清洗后的正文能不能用:汉字不足 [MIN_CJK_CHARS] 就当这次大总结没成功。 */
  fun isBodyUsable(text: String): Boolean = cjkLength(text) >= MIN_CJK_CHARS

  /**
   * 这段正文**看起来**有没有被推理过程污染(有思考标记,或出现连排英文)。
   *
   * 与 [sanitize] 分工不同:sanitize 负责「尽量把中文正文救回来」,它对付不了纯英文文本
   * (没有中文可取,只能原样返回)。所以「洗不干净要不要删」不能只靠「清洗前后是否相同」判断 ——
   * 得先确认它本来就脏,否则一句又短又干净的中文老总结会被误判成脏数据删掉。
   */
  fun looksDirty(raw: String): Boolean =
    THINKING_MARKERS.any { raw.contains(it) } || LATIN_RUN.containsMatchIn(raw)

  /** 汉字(含扩展 A):判断「中文正文从哪开始 / 到哪结束」只认它,标点不算。 */
  private fun isHan(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

  /** 中文标点:截断正文时要连带保留(不要因为清洗把句号吃掉)。 */
  private fun isHanPunct(ch: Char): Boolean = ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF

  /** 跑一次大总结。正文同样做长度兜底截断,不引入二次压缩。 */
  suspend fun summarize(apiKey: ApiKey?, microSummaries: List<String>): NodeSummaryResult {
    if (microSummaries.isEmpty()) return NodeSummaryResult.Fail("还没有可以合并的微总结")
    apiKey ?: return NodeSummaryResult.Fail("还没有注册 API KEY")
    if (apiKey.model.isBlank()) return NodeSummaryResult.Fail("这条 API KEY 没有填模型名")
    if (!ApiKey.isUrlOk(apiKey.url)) return NodeSummaryResult.Fail("这条 API KEY 的接口地址不对")
    if (apiKey.key.isBlank()) return NodeSummaryResult.Fail("这条 API KEY 没有填 KEY")

    val result = DevApiClient.chat(
      apiKey = apiKey.key,
      payload = payload(apiKey.model, microSummaries),
      endpoint = ApiProbe.chatEndpoint(apiKey.url),
    )
    return when (result) {
      is DevApiResult.Fail -> NodeSummaryResult.Fail(result.message)
      is DevApiResult.Ok -> {
        val text = sanitize(result.text)
        when {
          text.isBlank() -> NodeSummaryResult.Fail("大总结请求返回了空正文")
          !isBodyUsable(text) -> {
            // 清洗完还剩不到 10 个汉字:多半是整段都在用英文推理。这一轮宁可不写,也不落库。
            BugLog.log(
              "总结",
              "大总结正文不可用(汉字 ${cjkLength(text)} < $MIN_CJK_CHARS)· 已丢弃:${text.take(80)}"
            )
            NodeSummaryResult.Fail("大总结没有返回中文主线(汉字 ${cjkLength(text)} 个)")
          }
          else -> NodeSummaryResult.Ok(TokenEstimator.truncate(text, MAX_TOKENS), result.usage)
        }
      }
    }
  }
}
