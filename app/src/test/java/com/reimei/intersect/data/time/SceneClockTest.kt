package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.2–T2.5 验收 —— 时钟纯函数核心。
 *
 * 覆盖:每个边界分钟的时段映射、暂停与倍率、跨日推进、导演对齐的三种日期锚点规则、
 * 设置时刻、暂停 / 恢复 / 倍率的写入口径(写操作本身在 [TimeAuthorityTest] 里再验事务与快照)。
 */
class SceneClockTest {

  private fun state(
    minute: Int,
    anchor: Int = 0,
    rate: Double = 1.0,
    paused: Boolean = false,
  ) = ClockState(exactTime = minute, dateAnchor = anchor, rate = rate, paused = paused)

  @Test
  fun `时段映射逐分钟与枚举一致`() {
    for (minute in 0 until Period.MINUTES_PER_DAY) {
      assertEquals(Period.fromMinute(minute), SceneClock.periodFromTime(minute))
    }
    // 每个边界分钟单独验一遍
    Period.entries.forEach { period ->
      assertEquals(period, SceneClock.periodFromTime(period.startMinute))
      assertEquals(period, SceneClock.periodFromTime(period.endMinuteExclusive - 1))
    }
  }

  @Test
  fun `普通推进按倍率生效`() {
    assertEquals(60 + 30, SceneClock.advance(state(60, rate = 1.0), 30).exactTime)
    assertEquals(60 + 60, SceneClock.advance(state(60, rate = 2.0), 30).exactTime)
    // 0.5 倍 × 3 分钟 = 1.5 → 取整 2(避免长期偏少)
    assertEquals(60 + 2, SceneClock.advance(state(60, rate = 0.5), 3).exactTime)
  }

  @Test
  fun `暂停时不推进`() {
    val paused = state(600, paused = true)
    val next = SceneClock.advance(paused, 120)
    assertEquals(paused, next)
    assertEquals(600, next.exactTime)
  }

  @Test
  fun `跨过午夜时日期锚点加一`() {
    val before = state(23 * 60, anchor = 2)
    val after = SceneClock.advance(before, 120)
    assertEquals(1 * 60, after.exactTime)
    assertEquals(3, after.dateAnchor)
    assertEquals(Period.LATE_NIGHT, after.period)

    // 一次推过两天
    val twoDays = SceneClock.advance(state(12 * 60, anchor = 0), 60 * 48)
    assertEquals(12 * 60, twoDays.exactTime)
    assertEquals(2, twoDays.dateAnchor)
  }

  @Test
  fun `对齐到同一时段保留分钟`() {
    val morning = state(10 * 60 + 20) // 上午
    val aligned = SceneClock.align(morning, Period.MORNING)
    assertEquals(morning.exactTime, aligned.exactTime)
    assertEquals(0, aligned.dateAnchor)
  }

  @Test
  fun `对齐到别的时段取区间起点`() {
    val morning = state(10 * 60 + 20)
    val aligned = SceneClock.align(morning, Period.DUSK)
    assertEquals(Period.DUSK.startMinute, aligned.exactTime)
    assertEquals(Period.DUSK, aligned.period)
    // 目标在当前时间之后 ⇒ 锚点不变
    assertEquals(0, aligned.dateAnchor)
  }

  @Test
  fun `目标时段在当前时间之前时锚点加一日`() {
    val lateNight = state(23 * 60 + 30, anchor = 1)
    val aligned = SceneClock.align(lateNight, Period.DAWN)
    assertEquals(Period.DAWN.startMinute, aligned.exactTime)
    assertEquals("目标时段在之前 ⇒ 第二天清晨", 2, aligned.dateAnchor)
  }

  @Test
  fun `导演显式指定日期时以导演为准`() {
    val state = state(23 * 60, anchor = 1)
    val aligned = SceneClock.align(state, Period.DAWN, explicitDateAnchor = 7)
    assertEquals(7, aligned.dateAnchor)
    assertEquals(Period.DAWN.startMinute, aligned.exactTime)

    val set = SceneClock.setTime(state, 6 * 60, explicitDateAnchor = 9)
    assertEquals(9, set.dateAnchor)
    assertEquals(6 * 60, set.exactTime)
  }

  @Test
  fun `对齐只改时段相关字段 其它状态原样保留`() {
    val original = ClockState(
      exactTime = 10 * 60,
      dateAnchor = 3,
      rate = 2.0,
      paused = true,
      lastSummaryPeriod = Period.MORNING,
    )
    val aligned = SceneClock.align(original, Period.EVENING, noun = "入夜初", atRound = 5)
    assertEquals(2.0, aligned.rate, 0.0001)
    assertTrue(aligned.paused)
    assertEquals(Period.MORNING, aligned.lastSummaryPeriod)
    // 生成 pending 补丁(名词是导演给的)
    val patch = requireNotNull(aligned.pendingDirectorPatch)
    assertEquals(Period.EVENING, patch.period)
    assertEquals("入夜初", patch.noun)
    assertEquals(ClockOp.ALIGN, patch.op)
    assertEquals(5, patch.atRound)
  }

  @Test
  fun `设置时刻不影响日期锚点`() {
    val set = SceneClock.setTime(state(10 * 60, anchor = 4), 22 * 60 + 15)
    assertEquals(22 * 60 + 15, set.exactTime)
    assertEquals(4, set.dateAnchor)
    assertEquals(ClockOp.SET, requireNotNull(set.pendingDirectorPatch).op)
  }

  @Test
  fun `24点设成0点`() {
    val set = SceneClock.setTime(state(600), 24 * 60)
    assertEquals(0, set.exactTime)
    assertEquals(Period.LATE_NIGHT, set.period)
  }

  @Test
  fun `暂停 恢复 倍率`() {
    assertTrue(SceneClock.pause(state(600)).paused)
    assertFalse(SceneClock.pause(state(600, paused = true), paused = false).paused)
    assertEquals(3.0, SceneClock.setRate(state(600), 3.0).rate, 0.0001)
    assertThrows(IllegalArgumentException::class.java) { SceneClock.setRate(state(600), 0.0) }
    assertThrows(IllegalArgumentException::class.java) { SceneClock.setRate(state(600), -1.0) }
  }

  @Test
  fun `导演推进保留暂停标记并留补丁`() {
    val before = state(600, rate = 1.0, paused = true)
    val after = SceneClock.directorAdvance(before, 90, atRound = 2)
    // 暂停状态本身不该被推进;显式推进指令按导演意愿执行,但暂停标记保留
    assertEquals(600 + 90, after.exactTime)
    assertTrue(after.paused)
    assertEquals(ClockOp.ADVANCE, requireNotNull(after.pendingDirectorPatch).op)
    assertEquals(2, requireNotNull(after.pendingDirectorPatch).atRound)
  }

  @Test
  fun `总结落账推进上次总结时段并清掉补丁`() {
    val state = ClockState(
      exactTime = Period.DUSK.startMinute,
      lastSummaryPeriod = Period.NOON,
      pendingDirectorPatch = DirectorPatch(period = Period.DUSK, noun = "暮色"),
    )
    val after = SceneClock.markSummary(state)
    assertEquals(Period.DUSK, after.lastSummaryPeriod)
    assertEquals("补丁被总结吸收后必须清空", null, after.pendingDirectorPatch)
    assertEquals(state.exactTime, after.exactTime)
  }
}
