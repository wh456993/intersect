package com.reimei.intersect.data.time

/**
 * T1.3 —— fallback 优先级:**导演名词 > 默认名词 > 枚举 > 精确时间**。
 *
 * 为什么需要一个「降级」概念:同一个时段有多种措辞来源,精度与权威度都不同。
 * 导演(用户)显式指定的名词最权威;没有就退回程序侧的默认名词;连名词表都缺(理论上不会,
 * 但坏数据 / 未来改表都可能导致)就退回枚举名;再不行才把精确时间抛给人看。
 * 每一次降级都要被**标记**下来(验收清单:fallback 有标记、有日志),便于观测。
 *
 * 注意优先级最后一位是精确时间:按验收清单「精确时间默认只给 UI/导演/日志」,
 * 精确时间不作为给模型的措辞,只在 UI / 导演面板 / 日志里出现 —— 这里保留这个
 * 兜底级别只是为了「什么都不剩时不至于输出空串」,并不表示它可以随便进请求体。
 */
enum class FallbackLevel(val label: String) {
  /** 导演显式给的措辞 —— 最高优先级。 */
  DIRECTOR("导演名词"),

  /** 程序侧默认名词表([PeriodNouns])。 */
  DEFAULT_NOUN("默认名词"),

  /** 枚举名(名词表缺项时的兜底)。 */
  PERIOD_ENUM("枚举"),

  /** 精确时间(`HH:mm`),只给 UI / 导演 / 日志。 */
  EXACT_TIME("精确时间"),

  /** 什么都没有(空串)。 */
  NONE("无"),
}

/**
 * 一次解析的结果:[text] 是最终给人/模型看的措辞,[level] 是它来自哪一级。
 *
 * [degraded] 为 true 表示**不是**导演给的措辞(即发生了降级)—— 这就是「fallback 有标记」,
 * 日志侧据此记一条 [TimeLogCategory.FALLBACK]。
 */
data class FallbackResult(val text: String, val level: FallbackLevel) {
  val degraded: Boolean get() = level != FallbackLevel.DIRECTOR && level != FallbackLevel.NONE

  /** 契约字段 `fallback` 的值:直接写级别名,日志与契约用同一套词。 */
  fun toJson(): JsonValue = jobj(
    "text" to jstr(text),
    "level" to jstr(level.name),
    "degraded" to jbool(degraded),
  )
}

object TimeFallback {
  /**
   * 按优先级解析措辞。
   *
   * @param directorNoun 导演(用户)显式指定的名词,空/空白视为没有。
   * @param period 结构化的时段;为 null 表示当前连时段都不知道(坏数据)。
   * @param exactMinute 精确时间(一天内的分钟数),最后一级兜底。
   * @param nounOf 默认名词表。抽成参数是为了让「名词表缺项 ⇒ 降级到枚举名」这一级
   *   能被单测直接触发(生产代码一律用默认值 [PeriodNouns] → 默认名词)。
   */
  fun resolve(
    directorNoun: String?,
    period: Period?,
    exactMinute: Int? = null,
    nounOf: (Period) -> String = PeriodNouns::defaultNoun,
  ): FallbackResult {
    val director = directorNoun?.trim().orEmpty()
    if (director.isNotEmpty()) return FallbackResult(director, FallbackLevel.DIRECTOR)

    if (period != null) {
      val noun = nounOf(period)
      if (noun.isNotBlank()) return FallbackResult(noun, FallbackLevel.DEFAULT_NOUN)
      return FallbackResult(period.name.lowercase(), FallbackLevel.PERIOD_ENUM)
    }

    if (exactMinute != null) return FallbackResult(Period.clockLabel(exactMinute), FallbackLevel.EXACT_TIME)
    return FallbackResult("", FallbackLevel.NONE)
  }

  /** 同一个时段按状态解析:有 pending 补丁就用导演名词,否则退回默认名词。 */
  fun resolve(state: ClockState): FallbackResult =
    resolve(
      directorNoun = state.pendingDirectorPatch?.noun,
      period = state.period,
      exactMinute = state.exactTime,
    )

  /**
   * 精确时间的展示文本(带日期锚点):**只给 UI / 导演面板 / 日志 / 事务快照**用,
   * 不是注入给模型的措辞。日期锚点从 0 起算,显示时 +1 变成「第 N 天」。
   */
  fun exactLabel(state: ClockState): String =
    "第 ${state.dateAnchor + 1} 天 ${Period.clockLabel(state.exactTime)}"
}
