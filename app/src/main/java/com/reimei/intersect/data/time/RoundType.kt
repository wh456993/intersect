package com.reimei.intersect.data.time

/**
 * T1.5 —— 轮次分类。
 *
 * **`round_type` 由请求编排层([RoundOrchestrator])在构造请求时确定**:
 * - 它**不进** [ClockState](状态里没有这个字段,T2.1 有单测钉住);
 * - 它**不交给 AI 推断** —— 模型无权决定自己这一轮是普通轮还是总结轮。
 *
 * 四类:
 * - [NORMAL] 普通轮:只允许稳定规则块,禁止任何时间事实(R4 / T3.2);
 * - [SUMMARY] 总结轮:规则块 + 总结块 + 段落元数据(T3.3);
 * - [TEMP_QUERY] 临时查询轮:角色/用户问「现在几点」时的一次性时间事实,用后即焚(T3.4);
 * - [DIRECTOR_EVENT] 导演事件轮:导演改时间后的轻量事件播报,不触发完整总结(T4.5)。
 */
enum class RoundType(val label: String) {
  NORMAL("普通轮"),
  SUMMARY("总结轮"),
  TEMP_QUERY("临时查询轮"),
  DIRECTOR_EVENT("导演事件轮");

  companion object {
    fun fromName(name: String?): RoundType? =
      entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
  }
}

/**
 * 请求体里的一段注入内容。分段枚举是为了让「哪一类轮次允许哪几段」变成可断言的白名单,
 * 而不是散落在拼接代码里的 `if`。
 *
 * 前五个(规则 / 记忆 / 历史 / 当前输入 / 总结)是**常规上下文**,后四个是**时间相关注入**。
 */
enum class InjectSegment(val label: String) {
  /** 稳定规则块 `[时间规则]` —— 唯一允许出现在普通轮的段。 */
  RULES("时间规则"),

  /** 既有即时记忆块(与时间无关,列在这里只为把整个请求体描述完整)。 */
  MEMORY("记忆上下文"),

  /** 未总结的历史原文。 */
  HISTORY("历史原文"),

  /** 用户本轮输入。 */
  CURRENT_INPUT("当前输入"),

  /** 总结块正文。 */
  SUMMARY("总结块"),

  /** 总结块末尾的段落元数据。 */
  SUMMARY_META("总结元数据"),

  /** 临时时间事实(精确时间,用后即焚)。 */
  TEMP_FACT("临时时间事实"),

  /** 跨时段保险丝 `[时段变更]`。 */
  PERIOD_FUSE("时段变更"),

  /** 导演轻量事件 `[导演时间事件]`。 */
  DIRECTOR_EVENT("导演时间事件");

  /** 这一段是否含**动态时间事实**(普通轮一律不许出现)。 */
  val dynamicTimeFact: Boolean
    get() = this == TEMP_FACT || this == PERIOD_FUSE || this == DIRECTOR_EVENT
}

/**
 * T1.5 —— 普通轮白名单,编排层的**硬编码分支**(不查配置、不看 AI 意见)。
 *
 * 普通轮 = `RULES` + 既有上下文(MEMORY / HISTORY / CURRENT_INPUT),**没有**任何
 * `dynamicTimeFact`。其余三类轮次各自允许自己那一段,同样不含别类的时间注入。
 */
object InjectionWhitelist {
  val BY_ROUND: Map<RoundType, Set<InjectSegment>> = mapOf(
    RoundType.NORMAL to setOf(
      InjectSegment.RULES,
      InjectSegment.MEMORY,
      InjectSegment.HISTORY,
      InjectSegment.CURRENT_INPUT,
    ),
    RoundType.SUMMARY to setOf(
      InjectSegment.RULES,
      InjectSegment.SUMMARY,
      InjectSegment.SUMMARY_META,
      InjectSegment.PERIOD_FUSE,
      InjectSegment.HISTORY,
      InjectSegment.CURRENT_INPUT,
    ),
    RoundType.TEMP_QUERY to setOf(
      InjectSegment.RULES,
      InjectSegment.TEMP_FACT,
      InjectSegment.HISTORY,
      InjectSegment.CURRENT_INPUT,
    ),
    RoundType.DIRECTOR_EVENT to setOf(
      InjectSegment.RULES,
      InjectSegment.DIRECTOR_EVENT,
      InjectSegment.HISTORY,
      InjectSegment.CURRENT_INPUT,
    ),
  )

  fun allows(roundType: RoundType, segment: InjectSegment): Boolean =
    BY_ROUND[roundType]?.contains(segment) == true

  /** 这一段在该轮次里若出现就是违规。 */
  fun violates(roundType: RoundType, segment: InjectSegment): Boolean = !allows(roundType, segment)

  /** 普通轮里出现任何动态时间事实都算违规(白名单检查的显式版本,给验收清单用)。 */
  fun normalRoundHasNoTimeFact(segments: Collection<InjectSegment>): Boolean =
    segments.none { it.dynamicTimeFact }
}
