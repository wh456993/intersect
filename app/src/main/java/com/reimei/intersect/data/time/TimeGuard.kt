package com.reimei.intersect.data.time

/** 违规种类(T6.2 / T6.3)。 */
enum class GuardFinding(val label: String) {
  /** AI 自己报了时间(「现在是下午三点」「14:05」)。 */
  ANNOUNCE_TIME("违规报时"),

  /** AI 复述了系统注入的标签(`[时间事实]`、`[时段变更]`...)。 */
  RESTATE_TAG("复述系统标签"),
}

data class GuardHit(val finding: GuardFinding, val matched: String, val sentence: String)

data class GuardResult(val text: String, val hits: List<GuardHit>) {
  val rewritten: Boolean get() = hits.isNotEmpty()

  /** 洗完之后只剩空文本 ⇒ 调用方应该重新生成,而不是发一段空的出去。 */
  val needsRegenerate: Boolean get() = hits.isNotEmpty() && text.isBlank()
}

/**
 * T6.1–T6.3 —— AI 输出的读只约束与后处理。
 *
 * 三层防线:
 * 1. **提示词**(T6.1):规则块([TimeRules.RULES_TEXT])里写明「无权修改 / 无权播报精确时刻」,
 *    且不含任何动态值(单测断言:没有数字、没有时段名、没有标签字面量);
 * 2. **违规报时检测**(T6.2):命中「现在...点」「14:05」这类模式,把整句删掉;
 * 3. **系统标签复述检测**(T6.3):命中 `[时间事实]` / `[时段变更]` 这类标签,把标签清掉。
 *
 * 这是**后处理**,在模型输出进会话之前跑;它不写时钟、不改状态。
 */
object OutputGuard {
  /** 违规报时的模式(固定表,新增说法要补单测)。 */
  val ANNOUNCE_PATTERNS: List<Regex> = listOf(
    // 现在是 3 点 / 现在下午三点 / 现在 14 点
    Regex("现在\\s*(大约|大概|已经|是)?\\s*(上午|下午|中午|晚上|凌晨|清晨|傍晚)?\\s*[0-9零一二三四五六七八九十两]{1,3}\\s*[点時时]"),
    // 三点二十分 / 三点半
    Regex("[0-9零一二三四五六七八九十两]{1,3}\\s*点\\s*(半|[0-9零一二三四五六七八九十]{1,3}\\s*分)"),
    // 14:05 / 9:30
    Regex("[0-9]{1,2}\\s*[::]\\s*[0-9]{2}"),
    // 2025年1月15日
    Regex("[0-9]{4}\\s*年\\s*[0-9]{1,2}\\s*月\\s*[0-9]{1,2}\\s*[日号]"),
  )

  /** 系统标签:两类 —— 枚举出来的字面量,以及「时间/时段开头的中括号标签」这种变体。 */
  val TAG_TOKENS: List<String> = listOf(
    TimeInject.FACT_HEADER,
    TimeInject.RULES_HEADER,
    TimeInject.TEMP_HEADER,
    TimeInject.FUSE_HEADER,
    TimeInject.DIRECTOR_HEADER,
    TimeInject.META_HEADER + "|非台词]",
    TimeInject.META_HEADER + "]",
  )

  /** 兜住变体:以「时间」或「时段」开头的中括号标签(含全角括号)。 */
  val TAG_REGEX: Regex = Regex("[\\[【](?:时间|时段)[^\\]】]{0,24}[\\]】]")

  private val SENTENCE_SPLIT = Regex("(?<=[。!?!?\\n])")

  /** 扫描:返回全部命中(不改文本)。 */
  fun scan(text: String): List<GuardHit> {
    if (text.isEmpty()) return emptyList()
    val hits = mutableListOf<GuardHit>()
    SENTENCE_SPLIT.split(text).forEach { sentence ->
      ANNOUNCE_PATTERNS.forEach { pattern ->
        pattern.find(sentence)?.let { match ->
          hits.add(GuardHit(GuardFinding.ANNOUNCE_TIME, match.value, sentence))
        }
      }
      TAG_REGEX.findAll(sentence).forEach { match ->
        hits.add(GuardHit(GuardFinding.RESTATE_TAG, match.value, sentence))
      }
    }
    return hits
  }

  /**
   * 后处理:删掉违规报时的整句、清掉复述的标签。
   *
   * 为什么整句删而不是只删匹配片段:只删片段会留下语法残句(「现在是,我该出门了」),
   * 模型看到上下文还容易再补一次;整句删更干净,而且命中本来就不该出现在角色台词里。
   */
  fun sanitize(text: String): GuardResult {
    val hits = scan(text)
    if (hits.isEmpty()) return GuardResult(text, hits)

    var out = text
    hits.filter { it.finding == GuardFinding.ANNOUNCE_TIME }
      .map { it.sentence }
      .distinct()
      .forEach { sentence -> out = out.replace(sentence, "") }

    out = out.replace(TAG_REGEX, "")
    TAG_TOKENS.forEach { token -> out = out.replace(token, "") }

    // 清掉删句留下的空行与行内连续空格,别把空洞留给模型
    out = out.split('\n')
      .map { it.replace(Regex("[ \\t]{2,}"), " ").trim() }
      .filter { it.isNotEmpty() }
      .joinToString("\n")

    return GuardResult(out, hits)
  }
}

/**
 * T6.4 —— 角色查询时间的路径。
 *
 * 角色(或用户)问「现在几点」时,程序侧才把**精确时间**以临时事实的形式注入**这一轮**:
 * 1. [issue] 把事实登记进 [TempTimeFactStore];
 * 2. 这一轮的 `round_type` 必须是 [RoundType.TEMP_QUERY],注入层才会把它取出来放进请求体;
 * 3. 取一次就没了([TempTimeFactStore.consume]),下一轮无论什么类型都不再残留。
 */
object CharacterTimeQuery {
  /** 登记一份临时时间事实,返回它的文本(仅供日志/调试)。 */
  fun issue(
    state: ClockState,
    store: TempTimeFactStore,
    atRound: Int = 0,
    logger: TimeLogger = TimeLogs.sink,
    at: Long = System.currentTimeMillis(),
  ): String {
    val text = TimeInject.tempFactBlock(state, atRound).text
    store.issue(text, atRound, at)
    logger.log(
      TimeLogEntry(
        TimeLogCategory.INJECT,
        "角色查询时间:登记临时事实",
        at,
        mapOf("round" to atRound.toString(), "period" to state.period.name),
      )
    )
    return text
  }

  /** 只有查询轮能看到精确时间。 */
  fun visibleOnlyInQueryRound(roundType: RoundType): Boolean = roundType == RoundType.TEMP_QUERY
}
