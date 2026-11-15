package com.reimei.intersect.data.time

/**
 * T3.1 —— **稳定规则块**。
 *
 * 这是普通轮里唯一允许出现的时间相关内容:一条**常量**文本,不含任何动态时间
 * (`RULES_TEXT` 里没有数字时刻、没有日期、没有时段名),所以它可以长期待在前缀里,
 * 不会破坏服务商的前缀缓存。
 *
 * 措辞上的两条硬约束:
 * 1. 明确写「无权修改 / 无权播报精确时刻」(T6.1 的提示词只读约束就在这里);
 * 2. **不出现任何系统标签的字面量**(例如不写 `[时间事实]`):标签只在真正注入时出现,
 *    规则块自己念一遍会让模型更容易复述(T6.3),也让「普通轮上下文不含时间标签」这种
 *    断言变得无法验证。
 */
object TimeRules {
  val RULES_TEXT: String = """
    [时间规则]
    1. 时间由程序侧维护,你无权修改它,也无权播报精确时刻或日期。
    2. 本轮不会向你提供当前时间。需要时间感时,沿用场景描写里已经出现过的说法,不要自己报点、报日期、「现在几点」。
    3. 若收到系统注入的时间标签(如时段变更、临时时间事实、导演时间事件),只把它当作场景状态:不要复述标签本身,也不要把它当台词念出来。
    4. 总结末尾的时间元信息是给系统看的状态标记,不是台词,不要在回复里提及或引用。
    5. 不要声称自己暂停、重置、调整或跳转了时间。
  """.trimIndent()
}

/** 一段注入内容:它的分段(决定白名单)与最终文本。 */
data class InjectBlock(val segment: InjectSegment, val text: String) {
  val isEmpty: Boolean get() = text.isBlank()
}

/**
 * 一轮请求的注入计划。
 *
 * [blocks] 的顺序就是注入顺序;分段白名单校验见 [violations] / [requireValid] ——
 * 普通轮只要出现一段动态时间事实就是违规(T3.2)。
 */
data class InjectionPlan(val roundType: RoundType, val blocks: List<InjectBlock>) {
  val segments: List<InjectSegment> get() = blocks.map { it.segment }

  fun text(): String = blocks.filter { !it.isEmpty }.joinToString("\n\n") { it.text }

  fun contains(segment: InjectSegment): Boolean = segments.contains(segment)

  /** 含任何动态时间事实吗(普通轮必须为 false)。 */
  fun hasTimeFact(): Boolean = segments.any { it.dynamicTimeFact }

  /** 违反白名单的分段(空表示合法)。 */
  fun violations(): List<InjectSegment> = segments.filter { InjectionWhitelist.violates(roundType, it) }

  fun requireValid() {
    val bad = violations()
    check(bad.isEmpty()) { "${roundType.label}出现不允许的注入分段:${bad.map { it.label }}" }
  }
}

/**
 * T3.2–T3.5 —— 注入层的四种组合,由 [RoundOrchestrator] 按轮次类型硬编码分支决定。
 *
 * 文本格式约定(都是固定标题 + 结构化字段,便于模型区分「状态」与「台词」):
 * - 规则块 `[时间规则]`
 * - 临时查询 `[临时时间事实]`(精确时间,用后即焚)
 * - 跨时段保险丝 `[时段变更] <时段名>`
 * - 导演轻量事件 `[导演时间事件]`
 * - 总结元数据 `[时间元信息|非台词]`
 *
 * **普通轮禁止出现的标记**:[FACT_HEADER]。它是「精确时间事实」的标签,只用于测试与控制流,
 * 任何真实注入都不使用它。
 */
object TimeInject {
  const val RULES_HEADER = "[时间规则]"
  const val FACT_HEADER = "[时间事实]"
  const val TEMP_HEADER = "[临时时间事实]"
  const val FUSE_HEADER = "[时段变更]"
  const val DIRECTOR_HEADER = "[导演时间事件]"
  const val META_HEADER = "[时间元信息"

  /** T3.1 规则块(内容恒定的常量文本)。 */
  fun rulesBlock(): InjectBlock = InjectBlock(InjectSegment.RULES, TimeRules.RULES_TEXT)

  /**
   * T3.4 / T6.4 临时时间事实:只在**查询轮**注入,带精确时间,用完即弃
   * (见 [TempTimeFactStore]:取一次就没了,不会残留到下一轮)。
   */
  fun tempFactBlock(state: ClockState, atRound: Int = 0): InjectBlock = InjectBlock(
    InjectSegment.TEMP_FACT,
    buildString {
      append(TEMP_HEADER).append('\n')
      append("现在:").append(TimeFallback.exactLabel(state)).append('\n')
      append("时段:").append(state.period.name).append('(').append(PeriodNouns.defaultNoun(state.period)).append(")\n")
      append("说明:仅供本轮回答与时间有关的提问,不要复述本段文字或标签。")
      if (atRound > 0) append("\n轮次:").append(atRound)
    },
  )

  /** T3.5 跨时段保险丝:只写「时段变了」这一件事,不写精确时刻(精确时间只给 UI/导演/日志)。 */
  fun fuseBlock(period: Period): InjectBlock = InjectBlock(
    InjectSegment.PERIOD_FUSE,
    "$FUSE_HEADER ${PeriodNouns.defaultNoun(period)}(${period.name})",
  )

  /** T4.5 导演轻量事件:格式由 [TimeTemplates.DIRECTOR_EVENT_TEMPLATE] 固定。 */
  fun directorEventBlock(patch: DirectorPatch): InjectBlock =
    InjectBlock(InjectSegment.DIRECTOR_EVENT, DirectorEvents.format(patch))

  /** T3.3 / T4.4 总结块:正文 + 末尾元数据(**元数据在末尾、不前置**)。 */
  fun summaryBlocks(summaryText: String, meta: SummaryMeta): List<InjectBlock> = listOf(
    InjectBlock(InjectSegment.SUMMARY, summaryText.trim()),
    InjectBlock(InjectSegment.SUMMARY_META, meta.format()),
  )

  /** 总结块 + 元数据拼在一起的文本(顺序固定:正文在前、元数据贴尾)。 */
  fun formatSummaryWithMetadata(summaryText: String, meta: SummaryMeta): String =
    summaryText.trim() + "\n" + meta.format()
}

/**
 * T3.4 —— 临时时间事实的「用后即焚」容器。
 *
 * 语义只有三条,每条都有单测:
 * 1. [issue] 放一份进去(通常由角色查询时间的动作触发);
 * 2. [consume] **取一次就清空** —— 第二次取是 null,所以它不可能残留到下一轮;
 * 3. 它**不进历史**:注入计划完成后文本不再被任何地方引用(计划对象本身也不进会话记录)。
 */
class TempTimeFactStore(private val logger: TimeLogger = TimeLogs.sink) {
  private var fact: String? = null
  private var issuedRound: Int = -1

  fun issue(text: String, atRound: Int = 0, at: Long = System.currentTimeMillis()) {
    fact = text
    issuedRound = atRound
    logger.log(
      TimeLogEntry(
        TimeLogCategory.INJECT,
        "登记临时时间事实",
        at,
        mapOf("round" to atRound.toString(), "length" to text.length.toString()),
      )
    )
  }

  /** 取走(并清空)。没有则 null。 */
  fun consume(): String? {
    val held = fact
    fact = null
    issuedRound = -1
    return held
  }

  /** 只看不取(调试/断言用)。 */
  fun peek(): String? = fact

  fun isEmpty(): Boolean = fact == null

  val roundOfHeld: Int get() = issuedRound

  fun clear() {
    fact = null
    issuedRound = -1
  }
}

/**
 * 一轮请求构造时需要的全部「时间材料」。**编排层**把它交给 [RoundOrchestrator],
 * 注入层只按顺序拼装 —— 中间没有第二个判断「该不该注入」的地方。
 */
data class RoundContext(
  val summaryBlock: String = "",
  val summaryMeta: SummaryMeta? = null,
  val tempFact: String? = null,
  val fuse: Period? = null,
  val directorPatch: DirectorPatch? = null,
)

/**
 * T1.5 / T3.2–T3.5 —— **请求编排层**。
 *
 * 轮次类型由调用方([plan] 的 [roundType] 参数)在构造请求时显式给出:**不查 AI、不从文本推断**,
 * 也不进 [ClockState]。四个分支硬编码在下面的 `when` 里,分支允许的注入分段由
 * [InjectionWhitelist] 钉住,并有单测逐个覆盖。
 *
 * 普通轮:只有规则块 —— 上下文里没有时间事实、没有时间标签(T3.2 验收)。
 */
object RoundOrchestrator {
  fun plan(
    roundType: RoundType,
    context: RoundContext = RoundContext(),
    tempFacts: TempTimeFactStore? = null,
    logger: TimeLogger = TimeLogs.sink,
    at: Long = System.currentTimeMillis(),
    atRound: Int = 0,
    clock: ClockState? = null,
  ): InjectionPlan {
    val blocks = when (roundType) {
      RoundType.NORMAL -> listOf(TimeInject.rulesBlock())

      RoundType.SUMMARY -> summaryBranch(context)

      RoundType.TEMP_QUERY -> {
        val fact = context.tempFact ?: tempFacts?.consume()
        val block = when {
          fact != null -> InjectBlock(InjectSegment.TEMP_FACT, fact)
          clock != null -> TimeInject.tempFactBlock(clock, atRound)
          else -> null
        }
        listOfNotNull(TimeInject.rulesBlock(), block)
      }

      RoundType.DIRECTOR_EVENT -> listOfNotNull(
        TimeInject.rulesBlock(),
        context.directorPatch?.let { TimeInject.directorEventBlock(it) },
      )
    }
    val plan = InjectionPlan(roundType, blocks)
    // 编排层自己的 bug 要立刻炸,不能静默把时间事实塞进普通轮
    plan.requireValid()
    logger.log(
      TimeLogEntry(
        TimeLogCategory.INJECT,
        "注入计划:${roundType.label}",
        at,
        mapOf(
          "round_type" to roundType.name,
          "segments" to plan.segments.joinToString(",") { it.name },
          "has_time_fact" to plan.hasTimeFact().toString(),
        ),
      )
    )
    return plan
  }

  /**
   * T3.3 / T3.5 总结分支:规则块 + (总结块 + 元数据) **或** 保险丝,**二者不并存**(总结优先去重)。
   *
   * 顺序固定:规则块 → 总结块 → 元数据;元数据贴在总结块末尾,不前置。
   */
  private fun summaryBranch(context: RoundContext): List<InjectBlock> {
    val rules = TimeInject.rulesBlock()
    val summary = context.summaryBlock.trim()
    if (summary.isNotEmpty()) {
      val meta = context.summaryMeta
      return listOfNotNull(
        rules,
        InjectBlock(InjectSegment.SUMMARY, summary),
        meta?.let { InjectBlock(InjectSegment.SUMMARY_META, it.format()) },
      )
    }
    val fuse = context.fuse
    return if (fuse != null) listOf(rules, TimeInject.fuseBlock(fuse)) else listOf(rules)
  }
}
