package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.3 验收 —— fallback 四级降级。
 *
 * 优先级:**导演名词 > 默认名词 > 枚举 > 精确时间**;每一级都要能被单独触发,
 * 并且「不是导演给的措辞」必须被标记([FallbackResult.degraded]),
 * 这样 [TimeLogCategory.FALLBACK] 才有东西可记。
 */
class TimeFallbackTest {

  @Test
  fun `第一级 导演名词优先`() {
    val result = TimeFallback.resolve(directorNoun = "太阳快落山了", period = Period.DUSK, exactMinute = 17 * 60)
    assertEquals("太阳快落山了", result.text)
    assertEquals(FallbackLevel.DIRECTOR, result.level)
    assertFalse("导演名词不算降级", result.degraded)
  }

  @Test
  fun `第二级 没有导演名词时用默认名词`() {
    val result = TimeFallback.resolve(directorNoun = null, period = Period.DUSK, exactMinute = 17 * 60)
    assertEquals(PeriodNouns.defaultNoun(Period.DUSK), result.text)
    assertEquals(FallbackLevel.DEFAULT_NOUN, result.level)
    assertTrue("发生了降级必须被标记", result.degraded)
  }

  @Test
  fun `空白导演名词视为没有`() {
    val result = TimeFallback.resolve(directorNoun = "   ", period = Period.NOON)
    assertEquals(FallbackLevel.DEFAULT_NOUN, result.level)
  }

  @Test
  fun `第三级 名词表缺项时退回枚举名`() {
    // 生产表的默认名词是全的,所以「枚举级」要靠注入一个空名词表才触发 —— 这正是
    // resolve 把名词表抽成参数的原因(nounOf 默认就是 PeriodNouns)。
    val emptyCatalog: (Period) -> String = { "" }
    val result = TimeFallback.resolve(
      directorNoun = null,
      period = Period.EVENING,
      exactMinute = 20 * 60,
      nounOf = emptyCatalog,
    )
    assertEquals("evening", result.text)
    assertEquals(FallbackLevel.PERIOD_ENUM, result.level)
    assertTrue("枚举级也是降级", result.degraded)
  }

  @Test
  fun `四级顺序完整 从导演名词一路降到精确时间`() {
    val emptyCatalog: (Period) -> String = { "" }
    assertEquals(FallbackLevel.DIRECTOR, TimeFallback.resolve("暮色", Period.DUSK, 17 * 60).level)
    assertEquals(FallbackLevel.DEFAULT_NOUN, TimeFallback.resolve(null, Period.DUSK, 17 * 60).level)
    assertEquals(FallbackLevel.PERIOD_ENUM, TimeFallback.resolve(null, Period.DUSK, 17 * 60, emptyCatalog).level)
    assertEquals(FallbackLevel.EXACT_TIME, TimeFallback.resolve(null, null, 17 * 60).level)
  }

  @Test
  fun `第四级 连时段都没有时退回精确时间`() {
    val result = TimeFallback.resolve(directorNoun = null, period = null, exactMinute = 14 * 60 + 5)
    assertEquals("14:05", result.text)
    assertEquals(FallbackLevel.EXACT_TIME, result.level)
    assertTrue(result.degraded)
  }

  @Test
  fun `什么都没有时返回空文本且不算降级`() {
    val result = TimeFallback.resolve(directorNoun = null, period = null, exactMinute = null)
    assertEquals("", result.text)
    assertEquals(FallbackLevel.NONE, result.level)
    assertFalse(result.degraded)
  }

  @Test
  fun `时钟状态解析会带上未消费的导演名词`() {
    val state = ClockState(
      exactTime = Period.DUSK.startMinute,
      pendingDirectorPatch = DirectorPatch(period = Period.DUSK, noun = "暮色四合"),
    )
    val result = TimeFallback.resolve(state)
    assertEquals("暮色四合", result.text)
    assertEquals(FallbackLevel.DIRECTOR, result.level)

    val plain = TimeFallback.resolve(ClockState(exactTime = Period.DUSK.startMinute))
    assertEquals(PeriodNouns.defaultNoun(Period.DUSK), plain.text)
    assertEquals(FallbackLevel.DEFAULT_NOUN, plain.level)
  }

  @Test
  fun `精确时间带日期锚点的展示文本`() {
    val state = ClockState(exactTime = 14 * 60 + 5, dateAnchor = 2)
    assertEquals("第 3 天 14:05", TimeFallback.exactLabel(state))
  }
}
