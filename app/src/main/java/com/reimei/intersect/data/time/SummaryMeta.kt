package com.reimei.intersect.data.time

/**
 * T4.1 —— 总结触发条件。
 *
 * 四类触发(轮数/token 合成一类是因为它们共用同一个「攒够了」语义):
 * 1. [ROUNDS] / [TOKENS]:攒够轮数或 token;
 * 2. [SCENE_SWITCH]:场景切换;
 * 3. [DIRECTOR_MANUAL]:导演手动;
 * 4. [PERIOD_CROSS]:跨时段自动。
 *
 * 阈值一律来自 [TimeThresholds],这里不写任何数字。正在总结时([SummaryTriggerInput.summaryInFlight])
 * 一律返回 null —— 单飞,重复触发会拿同一批内容生成两条总结。
 */
enum class SummaryTrigger(val label: String) {
  ROUNDS("轮数"),
  TOKENS("token"),
  SCENE_SWITCH("场景切换"),
  DIRECTOR_MANUAL("导演手动"),
  PERIOD_CROSS("跨时段"),
}

data class SummaryTriggerInput(
  val roundsSinceSummary: Int,
  val tokensSinceSummary: Int,
  val sceneSwitched: Boolean = false,
  val directorManual: Boolean = false,
  val periodChanged: Boolean = false,
  val summaryInFlight: Boolean = false,
)

object SummaryTriggers {
  /** 全部触发类型(单测逐个覆盖)。 */
  val ALL: List<SummaryTrigger> = SummaryTrigger.entries

  /**
   * 决定这一轮该不该总结、以及算哪一类触发。
   *
   * 优先级(从高到低):导演手动 > 跨时段 > 场景切换 > 轮数 > token —— 更高的先命中,
   * 只为让日志里的原因更贴近「用户/剧情直接引起的」那一类。
   */
  fun decide(input: SummaryTriggerInput): SummaryTrigger? {
    if (input.summaryInFlight) return null
    if (input.directorManual) return SummaryTrigger.DIRECTOR_MANUAL
    if (input.periodChanged) return SummaryTrigger.PERIOD_CROSS
    if (input.sceneSwitched) return SummaryTrigger.SCENE_SWITCH
    if (input.roundsSinceSummary >= TimeThresholds.SUMMARY_INTERVAL_ROUNDS) return SummaryTrigger.ROUNDS
    if (input.tokensSinceSummary >= TimeThresholds.SUMMARY_INTERVAL_TOKENS) return SummaryTrigger.TOKENS
    return null
  }
}

/**
 * T4.2 —— 一个总结段的**时间元数据**。
 *
 * 字段与契约 [TimeContracts.SUMMARY_META] 一一对应:`span` / `start_period` / `end_period` /
 * `noun_hint` / `elapsed` / `key_events` / `fallback`。
 *
 * [nounHint] 是给人看的措辞,**不能替代结构化时段**(验收清单):注入时 `end_period` 与名词
 * 同时出现,模型据前者理解事实、据后者挑措辞。
 */
data class SummaryMeta(
  val span: String,
  val endPeriod: Period,
  val nounHint: FallbackResult,
  val elapsedMinutes: Int,
  val keyEvents: List<String> = emptyList(),
  val startPeriod: Period? = null,
) {
  /** 契约字段 `fallback`:直接写降级级别名。 */
  val fallback: FallbackLevel get() = nounHint.level

  fun toJson(): JsonValue.Obj = jobj(
    "span" to jstr(span),
    "start_period" to startPeriod?.let { jstr(it.name) },
    "end_period" to jstr(endPeriod.name),
    "noun_hint" to jstr(nounHint.text),
    "elapsed" to jnum(elapsedMinutes),
    "key_events" to jarr(keyEvents.map { jstr(it) }),
    "fallback" to jstr(fallback.name),
  )

  /**
   * T4.4 —— 元数据的**注入文本**:附在总结块末尾、低显著性。
   *
   * 「低显著性」的实现方式:标题里直接写明「非台词」、字段名用系统口吻(`跨度`/`结束时段`),
   * 且不出现任何「请你现在说...」这类祈使句式 —— 它看起来就该像一条日志尾巴,而不是剧情。
   */
  fun format(): String = buildString {
    append(TimeInject.META_HEADER)
    append("|非台词]\n")
    append("跨度:").append(span).append('\n')
    startPeriod?.let { append("起始时段:").append(it.name).append('(').append(PeriodNouns.defaultNoun(it)).append(")\n") }
    append("结束时段:").append(endPeriod.name).append('(').append(PeriodNouns.defaultNoun(endPeriod)).append(")\n")
    append("名词提示:").append(nounHint.text).append('\n')
    append("经过:").append(elapsedMinutes).append(" 分钟\n")
    if (keyEvents.isNotEmpty()) append("关键事件:").append(keyEvents.joinToString("、")).append('\n')
    append("fallback:").append(fallback.name)
  }
}

/**
 * T4.3 —— 总结段的继承与合并(pending 补丁在这里被**吸收**,属于运行时数据流)。
 *
 * 三件事:
 * 1. **继承**:上一段的 `end_period` 就是本段的 `start_period`(时间连续,不是各算各的);
 * 2. **合并 pending 补丁**:导演改过时间时,补丁提供名词措辞,并在 `key_events` 里留一条跳变记录 ——
 *    这样「导演把时间从黄昏跳到清晨」在总结里看得见,而不是悄悄换了时段;
 * 3. **fallback 标记**:措辞取自补丁 ⇒ DIRECTOR;否则走默认名词表 ⇒ DEFAULT_NOUN(有标记、有日志)。
 */
object SummaryChain {
  /**
   * @param previous 上一段的元数据(第一段时 null)。
   * @param patch 这一轮被吸收的导演补丁(没有则 null)。
   */
  fun next(
    previous: SummaryMeta?,
    patch: DirectorPatch?,
    span: String,
    endPeriod: Period,
    elapsedMinutes: Int,
    keyEvents: List<String> = emptyList(),
    logger: TimeLogger = TimeLogs.sink,
    at: Long = System.currentTimeMillis(),
  ): SummaryMeta {
    val startPeriod = previous?.endPeriod ?: endPeriod
    val events = keyEvents.toMutableList()
    if (patch != null) {
      val noun = patch.noun?.takeIf { it.isNotBlank() } ?: PeriodNouns.defaultNoun(patch.period)
      events.add("导演时间调整:${patch.op}→$noun")
      // 导演面板改过场景字段时,把 `<旧值变更新值>` 那几行也带进总结 ——
      // 否则「改完太晚、这一轮正好是总结轮」会把变更明细吞掉(注入计划只留总结段)。
      patch.changes.forEach { events.add(it.line()) }
    }
    val hint = TimeFallback.resolve(directorNoun = patch?.noun, period = endPeriod, exactMinute = null)
    if (hint.degraded) {
      logger.log(
        TimeLogEntry(
          TimeLogCategory.FALLBACK,
          "总结名词降级:${hint.level.name}",
          at,
          mapOf("period" to endPeriod.name, "text" to hint.text),
        )
      )
    }
    return SummaryMeta(
      span = span,
      endPeriod = endPeriod,
      nounHint = hint,
      elapsedMinutes = elapsedMinutes,
      keyEvents = events,
      startPeriod = startPeriod,
    )
  }
}

/**
 * T4.5 —— 导演轻量事件:**不触发完整总结**。
 *
 * 导演改完时间,下一轮以 `round_type=director_event` 注入一条格式固定的轻量事件
 * ([TimeTemplates.DIRECTOR_EVENT_TEMPLATE]),让模型知道时间变了,但不为此生成一段总结。
 */
object DirectorEvents {
  /** 轻量事件是否触发总结 —— 恒 false(这里显式写成函数,好让验收测试断言这条规则)。 */
  fun triggersSummary(): Boolean = false

  /**
   * 注入文本。
   *
   * 两种形态,由补丁自带的变更明细决定:
   * 1. 有条目明细(导演面板改过场景)⇒ 每行一条 `<旧值变更新值>`,例如
   *    `<2026.5.6 8:22变更2026.5.7 9:40>`(格式见 [TimeTemplates.DIRECTOR_CHANGE_LINE]);
   * 2. 没有明细(`/time` 之类的单点操作)⇒ 沿用固定模板 `操作 / 时段 / 名词`。
   */
  fun format(patch: DirectorPatch): String {
    if (patch.changes.isEmpty()) {
      return TimeTemplates.DIRECTOR_EVENT_TEMPLATE
        .replace("{op}", patch.op.name)
        .replace("{period}", patch.period.name)
        .replace("{noun}", patch.noun?.takeIf { it.isNotBlank() } ?: PeriodNouns.defaultNoun(patch.period))
    }
    return buildString {
      append(TimeInject.DIRECTOR_HEADER)
      patch.changes.forEach { change ->
        append('\n')
        append(change.line())
      }
    }
  }
}
