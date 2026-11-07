package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「当前连续 / 最长连续天数」的单元测试:统计口径错了用户是看不出来的,这里逐条钉死。 */
class UsageDaysTest {
  @Test
  fun `同一天的时间戳得到同一个天序号`() {
    val zone = java.util.TimeZone.getTimeZone("GMT+8")
    val morning = 1_700_000_000_000L
    val evening = morning + 6 * 60 * 60 * 1000L
    assertEquals(UsageDays.dayIndex(morning, zone), UsageDays.dayIndex(evening, zone))
  }

  @Test
  fun `相邻两天的天序号差一`() {
    val zone = java.util.TimeZone.getTimeZone("GMT+8")
    val day = 1_700_000_000_000L
    val next = day + 24 * 60 * 60 * 1000L
    assertEquals(UsageDays.dayIndex(day, zone) + 1, UsageDays.dayIndex(next, zone))
  }

  @Test
  fun `没有任何活跃记录时连续天数为零`() {
    assertEquals(UsageStreak(0, 0), UsageDays.streakOf(emptyList(), today = 100))
  }

  @Test
  fun `今天有活跃则当前连续从今天往前数`() {
    // 100(今天)、99、98 连续三天
    assertEquals(UsageStreak(3, 3), UsageDays.streakOf(listOf(100L, 99L, 98L), today = 100))
  }

  @Test
  fun `今天还没用但昨天有活跃,连续天数不清零`() {
    assertEquals(UsageStreak(2, 2), UsageDays.streakOf(listOf(99L, 98L), today = 100))
  }

  @Test
  fun `最近一次活跃在前天则当前连续归零,最长连续仍保留`() {
    assertEquals(UsageStreak(0, 2), UsageDays.streakOf(listOf(98L, 97L), today = 100))
  }

  @Test
  fun `最长连续取历史最长的那一段,不取最近的那一段`() {
    // 100 今天;90..94 是更早的五连
    val days = listOf(100L, 94L, 93L, 92L, 91L, 90L, 80L)
    assertEquals(UsageStreak(1, 5), UsageDays.streakOf(days, today = 100))
  }

  @Test
  fun `前台时长按采样数乘十分钟`() {
    assertEquals(10 * 60_000L * 3, UsageQueries.foregroundDurationMs(3))
  }
}
