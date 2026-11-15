package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.6 验收 —— 阈值集中定义、总结优先去重、pending patch 消费规则、解析模板冻结。
 *
 * 阈值「初值由产品给定」,这里**按初值钉住**:以后要调阈值,必须同时改这张表和单测,
 * 不能让某处偷偷漂移。
 */
class TimeConfigTest {

  @Test
  fun `阈值初值被冻结`() {
    assertEquals(20, TimeThresholds.SUMMARY_INTERVAL_ROUNDS)
    assertEquals(3000, TimeThresholds.SUMMARY_INTERVAL_TOKENS)
    assertEquals(30, TimeThresholds.FUSE_THRESHOLD_ROUNDS)
    assertEquals(
      mapOf(
        "summary_interval_rounds" to 20,
        "summary_interval_tokens" to 3000,
        "fuse_threshold_rounds" to 30,
      ),
      TimeThresholds.FROZEN,
    )
  }

  @Test
  fun `总结优先去重 两者不并存`() {
    val both = TimeDedupe.resolve(summaryDue = true, periodChanged = true, roundsSinceSummary = 99)
    assertTrue(both.summary)
    assertFalse("总结优先,保险丝必须让位", both.fuse)
    assertEquals(1, both.count)
  }

  @Test
  fun `没总结时 跨时段且间隔过长才发保险丝`() {
    val fuse = TimeDedupe.resolve(
      summaryDue = false,
      periodChanged = true,
      roundsSinceSummary = TimeThresholds.FUSE_THRESHOLD_ROUNDS,
    )
    assertFalse(fuse.summary)
    assertTrue(fuse.fuse)

    val tooEarly = TimeDedupe.resolve(
      summaryDue = false,
      periodChanged = true,
      roundsSinceSummary = TimeThresholds.FUSE_THRESHOLD_ROUNDS - 1,
    )
    assertFalse(tooEarly.summary)
    assertFalse("间隔还不够长,不发保险丝", tooEarly.fuse)

    val noCross = TimeDedupe.resolve(summaryDue = false, periodChanged = false, roundsSinceSummary = 99)
    assertFalse(noCross.fuse)
    assertEquals(0, noCross.count)
  }

  @Test
  fun `pending 补丁只被消费一次`() {
    val patch = DirectorPatch(period = Period.DUSK, noun = "暮色", op = ClockOp.ALIGN)
    val state = ClockState(pendingDirectorPatch = patch)

    val consumed = PendingPatchRule.consume(state)
    requireNotNull(consumed)
    assertEquals(patch, consumed.patch)
    assertNull("消费后必须清空", consumed.consumed.pendingDirectorPatch)

    // 再消费一次拿不到东西 —— 同一个补丁不会反复出现在后续总结里
    assertNull(PendingPatchRule.consume(consumed.consumed))
    // 本来就没有补丁时也是 null
    assertNull(PendingPatchRule.consume(ClockState()))
  }

  @Test
  fun `时段词表覆盖全部默认名词`() {
    Period.entries.forEach { period ->
      val noun = PeriodNouns.defaultNoun(period)
      assertEquals(
        "别名表必须认默认名词「$noun」",
        period,
        TimeTemplates.PERIOD_ALIASES[noun],
      )
      assertEquals("别名表必须认枚举名", period, Period.fromName(period.name))
    }
  }

  @Test
  fun `解析模板与时长单位被冻结`() {
    assertTrue(TimeTemplates.ALIGN_TEMPLATES.isNotEmpty())
    TimeTemplates.ALIGN_TEMPLATES.forEach { template ->
      assertTrue("对齐模板必须带 {noun}:$template", template.contains("{noun}"))
    }
    TimeTemplates.ADVANCE_TEMPLATES.forEach { template ->
      assertTrue("推进模板必须带 {amount} 与 {unit}:$template", template.contains("{amount}") && template.contains("{unit}"))
    }
    assertEquals(1, TimeTemplates.DURATION_UNITS["分钟"])
    assertEquals(15, TimeTemplates.DURATION_UNITS["刻"])
    assertEquals(60, TimeTemplates.DURATION_UNITS["小时"])
    // 导演时间事件模板:三个占位符都在,顺序固定
    assertEquals(
      "[导演时间事件]\n操作:{op}\n时段:{period}\n名词:{noun}",
      TimeTemplates.DIRECTOR_EVENT_TEMPLATE,
    )
  }
}
