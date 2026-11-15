package com.reimei.intersect.data.time

/**
 * T1.1 —— 时段枚举与区间表(半开区间 `[startMinute, endMinuteExclusive)`)。
 *
 * **冻结表**,不要擅自加减枚举或改边界(R6):加时段属于新任务。
 *
 * 表把一天切成 8 段,**从 00:00 起、首尾相接、无缺口、无重叠**,覆盖 `00:00–24:00`:
 *
 * ```
 * 深夜 [00:00,05:00)  清晨 [05:00,08:00)  上午 [08:00,11:00)  正午 [11:00,13:00)
 * 下午 [13:00,17:00)  黄昏 [17:00,19:00)  入夜 [19:00,22:00)  午夜 [22:00,24:00)
 * ```
 *
 * 三条口径:
 *
 * 1. **半开区间** —— 边界分钟属于**右边**那个时段:`05:00` 是清晨不是深夜,
 *    `endMinuteExclusive` 自己不属于本时段。所以 24:00 不是任何时段的起点,
 *    它是最后一个时段的**上界**。
 * 2. **24:00 归一为 00:00**([normalize]):`24:00` 与 `00:00` 是同一时刻的两种写法,
 *    都落到「深夜 · 第 0 分钟」。负数与超过一天的值按 1440 取模兜住,不抛异常。
 * 3. **跨日时段有日期锚点规则** —— 本表以 00:00 为分界,所以**没有任何一个时段跨过午夜**;
 *    真正的「跨日」是分钟回落(`00:00` 之后)带来的**日期加一**,由
 *    [ClockState.dateAnchor] 记账(规则见 [SceneClock.align] / [SceneClock.advance]):
 *    分钟取模回绕时锚点 +1,导演对齐到「当前时间之前」的时段时锚点 +1,
 *    导演显式给日期时以导演为准。
 */
enum class Period(val startMinute: Int, val endMinuteExclusive: Int) {
  LATE_NIGHT(0, 300),
  DAWN(300, 480),
  MORNING(480, 660),
  NOON(660, 780),
  AFTERNOON(780, 1020),
  DUSK(1020, 1140),
  EVENING(1140, 1320),
  MIDNIGHT(1320, 1440);

  /** 区间标签,如 `05:00-08:00`;收尾边界按 24:00 显示(不是 00:00)。 */
  val intervalLabel: String
    get() = labelOf(startMinute, endMinuteExclusive)

  /** 这个分钟数(先按一天归一)落在本时段里吗。 */
  fun contains(minuteOfDay: Int): Boolean {
    val m = normalize(minuteOfDay)
    return m >= startMinute && m < endMinuteExclusive
  }

  /** 时段长度(分钟),恒 > 0。 */
  val minutes: Int get() = endMinuteExclusive - startMinute

  companion object {
    const val MINUTES_PER_DAY = 1440

    /** 冻结表的顺序视图(与枚举声明顺序一致,也是时段表顺序)。 */
    val TABLE: List<Period> = entries

    /** 分钟数归一:24:00 → 0,负数与超过一天的值按 1440 取模。 */
    fun normalize(minuteOfDay: Int): Int {
      val m = minuteOfDay % MINUTES_PER_DAY
      return if (m < 0) m + MINUTES_PER_DAY else m
    }

    /** 分钟数 → 时段。表是全覆盖的,任何输入都有结果(先归一)。 */
    fun fromMinute(minuteOfDay: Int): Period {
      val m = normalize(minuteOfDay)
      return entries.first { m >= it.startMinute && m < it.endMinuteExclusive }
    }

    /** 小时+分钟 → 时段;`24:00` 归一成 00:00。 */
    fun of(hour: Int, minute: Int): Period = fromMinute(hour * 60 + minute)

    /** 枚举名(不区分大小写)还原;不认识返回 null。存盘与契约里用枚举名。 */
    fun fromName(name: String?): Period? {
      val trimmed = name?.trim().orEmpty()
      if (trimmed.isEmpty()) return null
      return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }

    /**
     * `HH:mm` → 分钟数。`24:00` 归一为 0(见类注释第 2 条)。
     * 格式不对返回 null(调用方据此判「解析失败」,不要猜)。
     */
    fun parseClock(text: String?): Int? {
      val trimmed = text?.trim().orEmpty()
      val parts = trimmed.split(':')
      if (parts.size != 2) return null
      val hour = parts[0].toIntOrNull() ?: return null
      val minute = parts[1].toIntOrNull() ?: return null
      if (hour < 0 || hour > 24) return null
      if (minute < 0 || minute > 59) return null
      if (hour == 24 && minute != 0) return null
      return normalize(hour * 60 + minute)
    }

    /** 分钟数 → `HH:mm`(0 显示成 `00:00`)。 */
    fun clockLabel(minuteOfDay: Int): String {
      val m = normalize(minuteOfDay)
      return pad2(m / 60) + ":" + pad2(m % 60)
    }

    fun labelOf(startMinute: Int, endMinuteExclusive: Int): String =
      pad2(startMinute / 60) + ":" + pad2(startMinute % 60) + "-" +
        pad2(endMinuteExclusive / 60) + ":" + pad2(endMinuteExclusive % 60)

    /** 补零。不用 `String.format`:那玩意跟着默认 Locale 走,阿拉伯语环境会输出阿拉伯数字。 */
    private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()
  }
}
