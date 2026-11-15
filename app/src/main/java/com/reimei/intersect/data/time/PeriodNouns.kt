package com.reimei.intersect.data.time

/**
 * T1.2 —— 时段 → 默认名词对照表。
 *
 * 每个 [Period] 都必须有默认名词(单测逐个枚举钉住)。名词是**给模型看的文本**,
 * 不进任何数值;`noun_hint` 只是提示,不能替代结构化时段(验收清单:
 * 「名词提示不替代结构化时段」)。
 *
 * 与枚举的职责分工:
 * - 枚举是**结构化 ID**(存盘、契约、日志里都用它,永不变);
 * - 名词是**人读的措辞**(可以换词、可以起别名,但要改就得改这张表,别在业务代码里硬写)。
 */
object PeriodNouns {
  /** 冻结对照表:枚举 → 默认名词。 */
  val DEFAULT: Map<Period, String> = mapOf(
    Period.LATE_NIGHT to "深夜",
    Period.DAWN to "清晨",
    Period.MORNING to "上午",
    Period.NOON to "正午",
    Period.AFTERNOON to "下午",
    Period.DUSK to "黄昏",
    Period.EVENING to "入夜",
    Period.MIDNIGHT to "午夜",
  )

  /** 这个时段的默认名词;表里缺项时返回空串(由 [TimeFallback] 降级到枚举名)。 */
  fun defaultNoun(period: Period): String = DEFAULT[period].orEmpty()

  /** 反查:名词 → 时段(只认默认名词)。 */
  fun periodOfNoun(noun: String?): Period? {
    val trimmed = noun?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return DEFAULT.entries.firstOrNull { it.value == trimmed }?.key
  }
}
