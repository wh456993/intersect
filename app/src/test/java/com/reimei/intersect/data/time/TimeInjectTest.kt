package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.1–T3.5 验收 —— 注入层。
 *
 * 核心断言:**普通轮上下文里没有任何时间事实**,只有稳定规则块;总结轮顺序固定为
 * 规则块 → 总结块 → 元数据;临时时间事实用后即焚;保险丝与总结不并存。
 */
class TimeInjectTest {

  private fun meta(end: Period = Period.DUSK, hint: String = "黄昏"): SummaryMeta =
    SummaryMeta(
      span = "第 1-20 轮",
      startPeriod = Period.MORNING,
      endPeriod = end,
      nounHint = FallbackResult(hint, FallbackLevel.DEFAULT_NOUN),
      elapsedMinutes = 480,
      keyEvents = listOf("下雨"),
    )

  @Test
  fun `规则块是常量 不含动态时间`() {
    val block = TimeInject.rulesBlock()
    assertEquals(InjectSegment.RULES, block.segment)
    assertEquals(TimeRules.RULES_TEXT, block.text)
    assertTrue("规则块必须以 [时间规则] 开头", block.text.startsWith(TimeInject.RULES_HEADER))
    // 不含动态时间:没有精确时刻、没有日期、没有「N 点」这类报时
    assertFalse("规则块不许含 HH:mm", Regex("[0-9]{1,2}\\s*[::]\\s*[0-9]{2}").containsMatchIn(block.text))
    assertFalse("规则块不许含日期", Regex("[0-9]{4}\\s*年").containsMatchIn(block.text))
    assertFalse("规则块不许含报时", Regex("[0-9]{1,2}\\s*[点時时]").containsMatchIn(block.text))
    // 没有时段名(时段是动态的)
    Period.entries.forEach { period ->
      assertFalse(
        "规则块不许含时段名词 ${PeriodNouns.defaultNoun(period)}",
        block.text.contains(PeriodNouns.defaultNoun(period)),
      )
    }
    // 不含任何系统标签字面量(避免模型学着复述)
    assertFalse(block.text.contains("[时间事实]"))
    assertFalse(block.text.contains("[临时时间事实]"))
    assertFalse(block.text.contains("[时段变更]"))
    // 同一份文本,每轮都一样(前缀缓存友好)
    assertEquals(TimeInject.rulesBlock().text, TimeInject.rulesBlock().text)
  }

  @Test
  fun `普通轮只注入规则块`() {
    val logger = InMemoryTimeLog()
    val plan = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), logger = logger, at = 42L)
    assertEquals(listOf(InjectSegment.RULES), plan.segments)
    assertFalse("普通轮不许有时间事实", plan.hasTimeFact())
    assertEquals(emptyList<InjectSegment>(), plan.violations())
    // 文本里连时间标签都没有
    assertFalse(plan.text().contains(TimeInject.FACT_HEADER))
    assertFalse(plan.text().contains(TimeInject.TEMP_HEADER))
    assertFalse(plan.text().contains(TimeInject.FUSE_HEADER))
    // 注入日志(T7.1):带时间戳与上下文
    val entry = logger.of(TimeLogCategory.INJECT).single()
    assertEquals(42L, entry.at)
    assertEquals(RoundType.NORMAL.name, entry.context["round_type"])
    assertEquals("false", entry.context["has_time_fact"])
  }

  @Test
  fun `总结轮顺序固定 规则块在最前 元数据贴尾`() {
    val summary = "这段时间你们进了城。"
    val meta = meta()
    val plan = RoundOrchestrator.plan(
      RoundType.SUMMARY,
      RoundContext(summaryBlock = summary, summaryMeta = meta),
      logger = InMemoryTimeLog(),
    )
    assertEquals(
      listOf(InjectSegment.RULES, InjectSegment.SUMMARY, InjectSegment.SUMMARY_META),
      plan.segments,
    )
    val text = plan.text()
    val rulesAt = text.indexOf(TimeInject.RULES_HEADER)
    val summaryAt = text.indexOf(summary)
    val metaAt = text.indexOf(TimeInject.META_HEADER)
    assertTrue("规则块在最前", rulesAt == 0)
    assertTrue("总结正文在规则块之后", summaryAt > rulesAt)
    assertTrue("元数据在总结正文之后", metaAt > summaryAt)
    assertTrue("元数据必须贴在总结块末尾", text.endsWith(meta.format()))
    // 元数据不前置:它不是第一段
    assertFalse(text.startsWith(meta.format()))
  }

  @Test
  fun `总结优先 有总结时不插保险丝`() {
    val plan = RoundOrchestrator.plan(
      RoundType.SUMMARY,
      RoundContext(summaryBlock = "总结正文", summaryMeta = meta(), fuse = Period.DUSK),
      logger = InMemoryTimeLog(),
    )
    assertTrue(plan.contains(InjectSegment.SUMMARY))
    assertFalse("总结与保险丝不并存", plan.contains(InjectSegment.PERIOD_FUSE))
    assertFalse(plan.text().contains(TimeInject.FUSE_HEADER))
  }

  @Test
  fun `跨时段保险丝注入只在没有总结时出现`() {
    val plan = RoundOrchestrator.plan(
      RoundType.SUMMARY,
      RoundContext(fuse = Period.DUSK),
      logger = InMemoryTimeLog(),
    )
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.PERIOD_FUSE), plan.segments)
    assertTrue(plan.text().contains("[时段变更] 黄昏"))
    assertTrue(plan.hasTimeFact())
  }

  @Test
  fun `临时查询轮注入精确时间且用后即焚`() {
    val store = TempTimeFactStore(InMemoryTimeLog())
    val clock = ClockState(exactTime = 14 * 60 + 5, dateAnchor = 1)
    CharacterTimeQuery.issue(clock, store, atRound = 9, logger = InMemoryTimeLog())

    val plan = RoundOrchestrator.plan(
      RoundType.TEMP_QUERY,
      RoundContext(),
      tempFacts = store,
      logger = InMemoryTimeLog(),
    )
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.TEMP_FACT), plan.segments)
    assertTrue(plan.text().contains("第 2 天 14:05"))
    assertTrue("临时事实取走即清空", store.isEmpty())
    assertNull(store.consume())

    // 下一轮:普通轮,没有任何残留
    val next = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), tempFacts = store, logger = InMemoryTimeLog())
    assertEquals(listOf(InjectSegment.RULES), next.segments)
    assertFalse(next.text().contains("14:05"))
  }

  @Test
  fun `临时查询轮在没有事实时退回时钟快照`() {
    val plan = RoundOrchestrator.plan(
      RoundType.TEMP_QUERY,
      RoundContext(),
      tempFacts = TempTimeFactStore(InMemoryTimeLog()),
      logger = InMemoryTimeLog(),
      clock = ClockState(exactTime = 8 * 60, dateAnchor = 0),
      atRound = 3,
    )
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.TEMP_FACT), plan.segments)
    assertTrue(plan.text().contains("第 1 天 08:00"))
    // 连时钟都没有时:只有规则块,不编一个时间出来
    val bare = RoundOrchestrator.plan(
      RoundType.TEMP_QUERY,
      RoundContext(),
      tempFacts = TempTimeFactStore(InMemoryTimeLog()),
      logger = InMemoryTimeLog(),
    )
    assertEquals(listOf(InjectSegment.RULES), bare.segments)
  }

  @Test
  fun `导演事件轮只播报轻量事件 不触发总结`() {
    val patch = DirectorPatch(period = Period.DAWN, noun = "天刚亮", op = ClockOp.ALIGN)
    val plan = RoundOrchestrator.plan(
      RoundType.DIRECTOR_EVENT,
      RoundContext(directorPatch = patch),
      logger = InMemoryTimeLog(),
    )
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.DIRECTOR_EVENT), plan.segments)
    assertTrue(plan.text().contains(TimeInject.DIRECTOR_HEADER))
    assertTrue(plan.text().contains("天刚亮"))
    assertFalse(plan.contains(InjectSegment.SUMMARY))
    assertFalse(DirectorEvents.triggersSummary())
  }

  @Test
  fun `白名单违规会立刻抛出`() {
    val illegal = InjectionPlan(
      RoundType.NORMAL,
      listOf(TimeInject.rulesBlock(), TimeInject.tempFactBlock(ClockState())),
    )
    assertEquals(listOf(InjectSegment.TEMP_FACT), illegal.violations())
    assertThrows(IllegalStateException::class.java) { illegal.requireValid() }
  }
}
