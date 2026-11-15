package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.1–T4.5 验收 —— 总结触发、段落元数据、继承合并、注入位置、导演轻量事件。
 */
class SummaryMetaTest {

  @Test
  fun `四类触发都能命中`() {
    val base = SummaryTriggerInput(roundsSinceSummary = 0, tokensSinceSummary = 0)
    assertEquals(SummaryTrigger.DIRECTOR_MANUAL, SummaryTriggers.decide(base.copy(directorManual = true)))
    assertEquals(SummaryTrigger.PERIOD_CROSS, SummaryTriggers.decide(base.copy(periodChanged = true)))
    assertEquals(SummaryTrigger.SCENE_SWITCH, SummaryTriggers.decide(base.copy(sceneSwitched = true)))
    assertEquals(
      SummaryTrigger.ROUNDS,
      SummaryTriggers.decide(base.copy(roundsSinceSummary = TimeThresholds.SUMMARY_INTERVAL_ROUNDS)),
    )
    assertEquals(
      SummaryTrigger.TOKENS,
      SummaryTriggers.decide(base.copy(tokensSinceSummary = TimeThresholds.SUMMARY_INTERVAL_TOKENS)),
    )
    assertNull("都没到就不总结", SummaryTriggers.decide(base))
  }

  @Test
  fun `阈值来自 config_time_thresholds`() {
    val justBelow = SummaryTriggerInput(
      roundsSinceSummary = TimeThresholds.SUMMARY_INTERVAL_ROUNDS - 1,
      tokensSinceSummary = TimeThresholds.SUMMARY_INTERVAL_TOKENS - 1,
    )
    assertNull(SummaryTriggers.decide(justBelow))
    assertTrue(SummaryTriggerInput(roundsSinceSummary = TimeThresholds.SUMMARY_INTERVAL_ROUNDS, tokensSinceSummary = 0)
      .let { SummaryTriggers.decide(it) != null })
  }

  @Test
  fun `正在总结时不再触发 单飞`() {
    val busy = SummaryTriggerInput(
      roundsSinceSummary = 99,
      tokensSinceSummary = 99_999,
      periodChanged = true,
      directorManual = true,
      summaryInFlight = true,
    )
    assertNull(SummaryTriggers.decide(busy))
  }

  @Test
  fun `元数据字段完整 fallback 有标记`() {
    val meta = SummaryChain.next(
      previous = null,
      patch = null,
      span = "第 1-20 轮",
      endPeriod = Period.DUSK,
      elapsedMinutes = 480,
      keyEvents = listOf("下雨"),
      logger = InMemoryTimeLog(),
    )
    val json = meta.toJson()
    listOf("span", "start_period", "end_period", "noun_hint", "elapsed", "key_events", "fallback").forEach {
      assertTrue("元数据缺字段 $it", json.fields.containsKey(it))
    }
    assertEquals("DUSK", json.strField("end_period"))
    assertEquals("黄昏", json.strField("noun_hint"))
    assertEquals(480, json.intField("elapsed"))
    assertEquals(FallbackLevel.DEFAULT_NOUN.name, json.strField("fallback"))
    assertEquals(FallbackLevel.DEFAULT_NOUN, meta.fallback)
    assertEquals(emptyList<String>(), TimeContracts.validate(TimeContracts.SUMMARY_META, json))
    // 名词提示不替代结构化时段:两者同时出现
    val text = meta.format()
    assertTrue(text.contains("DUSK"))
    assertTrue(text.contains("黄昏"))
  }

  @Test
  fun `继承 上一段的结束时段就是本段的起始时段`() {
    val first = SummaryChain.next(
      previous = null,
      patch = null,
      span = "第 1-20 轮",
      endPeriod = Period.MORNING,
      elapsedMinutes = 200,
      logger = InMemoryTimeLog(),
    )
    val second = SummaryChain.next(
      previous = first,
      patch = null,
      span = "第 21-40 轮",
      endPeriod = Period.DUSK,
      elapsedMinutes = 480,
      logger = InMemoryTimeLog(),
    )
    assertEquals(Period.MORNING, second.startPeriod)
    assertEquals(Period.DUSK, second.endPeriod)
  }

  @Test
  fun `合并 pending 补丁 跨度跳变写进关键事件`() {
    val patch = DirectorPatch(period = Period.DAWN, noun = "天刚亮", op = ClockOp.ALIGN, atRound = 33)
    val meta = SummaryChain.next(
      previous = null,
      patch = patch,
      span = "第 21-40 轮",
      endPeriod = Period.DAWN,
      elapsedMinutes = 60,
      logger = InMemoryTimeLog(),
    )
    assertEquals("导演名词优先", "天刚亮", meta.nounHint.text)
    assertEquals(FallbackLevel.DIRECTOR, meta.fallback)
    assertTrue(
      "跨度跳变必须在总结里体现:${meta.keyEvents}",
      meta.keyEvents.any { it.contains("导演时间调整") && it.contains("天刚亮") },
    )
  }

  @Test
  fun `降级会被记日志`() {
    val logger = InMemoryTimeLog()
    SummaryChain.next(
      previous = null,
      patch = null,
      span = "第 1-20 轮",
      endPeriod = Period.EVENING,
      elapsedMinutes = 100,
      logger = logger,
      at = 7_000L,
    )
    val entry = logger.of(TimeLogCategory.FALLBACK).single()
    assertEquals(7_000L, entry.at)
    assertEquals("EVENING", entry.context["period"])
    assertEquals(PeriodNouns.defaultNoun(Period.EVENING), entry.context["text"])
    assertTrue(entry.message.contains("DEFAULT_NOUN"))
  }

  @Test
  fun `元数据低显著性 且贴总结块末尾`() {
    val meta = SummaryChain.next(
      previous = null,
      patch = null,
      span = "第 1-20 轮",
      endPeriod = Period.NOON,
      elapsedMinutes = 120,
      logger = InMemoryTimeLog(),
    )
    val metaText = meta.format()
    assertTrue("必须标明不是台词", metaText.contains("非台词"))
    assertFalse("元数据不能是祈使句", metaText.contains("请"))
    val combined = TimeInject.formatSummaryWithMetadata("总结正文。", meta)
    assertTrue(combined.startsWith("总结正文。"))
    assertTrue(combined.endsWith(metaText))
    assertEquals(combined, "总结正文。" + "\n" + metaText)
  }

  @Test
  fun `导演轻量事件格式固定 不触发总结`() {
    val patch = DirectorPatch(period = Period.MIDNIGHT, noun = null, op = ClockOp.ADVANCE)
    val text = DirectorEvents.format(patch)
    assertTrue(text.startsWith(TimeInject.DIRECTOR_HEADER))
    assertTrue(text.contains("ADVANCE"))
    assertTrue(text.contains("MIDNIGHT"))
    assertTrue("没给名词就用默认名词", text.contains(PeriodNouns.defaultNoun(Period.MIDNIGHT)))
    assertFalse(DirectorEvents.triggersSummary())
  }
}
