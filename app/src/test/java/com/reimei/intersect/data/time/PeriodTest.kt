package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.1 验收 —— 时段枚举与区间表。
 *
 * 钉住四件事:
 * 1. 覆盖 `00:00–24:00` **无缺口、无重叠**(逐分钟走一遍,每天 1440 个点各归一个时段);
 * 2. **半开区间**:边界分钟归右边,`endMinuteExclusive` 自己不属于本时段;
 * 3. **24:00 归一为 00:00**;
 * 4. 表里没有任何时段跨越午夜(所以「跨日」只体现在日期锚点上,规则由 [SceneClock] 实现,
 *    那里有单独的对齐测试)。
 */
class PeriodTest {

  @Test
  fun `表的每个时段都非空且不跨越午夜`() {
    Period.entries.forEach { period ->
      assertTrue("${period.name} 的长度必须为正", period.minutes > 0)
      assertTrue(
        "${period.name} 必须 start < endExclusive(表不跨午夜,跨日靠日期锚点)",
        period.startMinute < period.endMinuteExclusive,
      )
      assertTrue(period.endMinuteExclusive <= Period.MINUTES_PER_DAY)
    }
  }

  @Test
  fun `逐分钟覆盖整天且无缺口无重叠`() {
    assertEquals(1440, Period.entries.sumOf { it.minutes })
    var previousEnd = 0
    Period.entries.forEach { period ->
      assertEquals("${period.name} 的起点必须紧接上一个时段的终点", previousEnd, period.startMinute)
      previousEnd = period.endMinuteExclusive
    }
    assertEquals(Period.MINUTES_PER_DAY, previousEnd)

    // 逐分钟:每个点都恰好落在一个时段里
    for (minute in 0 until Period.MINUTES_PER_DAY) {
      val hits = Period.entries.count { minute >= it.startMinute && minute < it.endMinuteExclusive }
      assertEquals("第 $minute 分钟应当恰好属于一个时段", 1, hits)
      val mapped = Period.fromMinute(minute)
      assertTrue("$minute 映射到 ${mapped.name} 后必须真的在它的区间里", mapped.contains(minute))
    }
  }

  @Test
  fun `半开区间边界分钟归右边时段`() {
    Period.entries.forEach { period ->
      val start = period.startMinute
      assertTrue("${period.name} 的起点 $start 属于自己", period.contains(start))
      if (period.endMinuteExclusive < Period.MINUTES_PER_DAY) {
        assertEquals(
          "终点 ${period.endMinuteExclusive} 不属于 ${period.name}",
          false,
          period.contains(period.endMinuteExclusive),
        )
        assertTrue(
          "${period.endMinuteExclusive} 必须属于下一个时段",
          period.endMinuteExclusive >= Period.entries[period.ordinal + 1].startMinute,
        )
      }
    }
    // 具体锚点:05:00 是清晨不是深夜;08:00 是上午;22:00 是午夜
    assertEquals(Period.DAWN, Period.fromMinute(5 * 60))
    assertEquals(Period.LATE_NIGHT, Period.fromMinute(5 * 60 - 1))
    assertEquals(Period.MORNING, Period.fromMinute(8 * 60))
    assertEquals(Period.MIDNIGHT, Period.fromMinute(22 * 60))
    assertEquals(Period.EVENING, Period.fromMinute(22 * 60 - 1))
  }

  @Test
  fun `24点归一到0点`() {
    assertEquals(0, Period.normalize(24 * 60))
    assertEquals(0, Period.parseClock("24:00")!!)
    assertEquals(Period.LATE_NIGHT, Period.fromMinute(24 * 60))
    assertEquals(Period.LATE_NIGHT, Period.of(24, 0))
    assertEquals("00:00", Period.clockLabel(24 * 60))
    // 一天之内的取模:25:00 与 01:00 同一时刻,负数也兜住
    assertEquals(1 * 60, Period.normalize(25 * 60))
    assertEquals(Period.MIDNIGHT, Period.fromMinute(-1))
  }

  @Test
  fun `时刻解析对非法输入返回null`() {
    assertNull(Period.parseClock("25:00"))
    assertNull(Period.parseClock("24:01"))
    assertNull(Period.parseClock("12:60"))
    assertNull(Period.parseClock("12"))
    assertNull(Period.parseClock("ab:cd"))
    assertNull(Period.parseClock(""))
    assertEquals(14 * 60 + 30, Period.parseClock("14:30")!!)
    assertEquals("14:30", Period.clockLabel(14 * 60 + 30))
  }

  @Test
  fun `枚举名可以被不区分大小写地还原`() {
    Period.entries.forEach { assertEquals(it, Period.fromName(it.name.lowercase())) }
    assertNull(Period.fromName(""))
    assertNull(Period.fromName("傍晚"))
  }
}
