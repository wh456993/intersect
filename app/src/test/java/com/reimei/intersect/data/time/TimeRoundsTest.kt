package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排层与请求体之间的桥(T3.2 / T1.5)单测。
 *
 * 钉住三件事:
 * 1. 「这一轮发生了什么」→ [RoundType] 的映射是固定的、有优先级的,不看 AI;
 * 2. 普通轮的计划里**没有**任何动态时间事实(验收清单第 1 条);
 * 3. 规则块单独取出进 system,动态块单独成条 —— 两者不重复。
 */
class TimeRoundsTest {
  private val logger = InMemoryTimeLog()

  @Test
  fun `事件到轮次类型的映射是固定表`() {
    assertEquals(RoundType.NORMAL, TimeRounds.of().roundType)
    assertEquals(RoundType.SUMMARY, TimeRounds.of(summaryReady = true).roundType)
    assertEquals(RoundType.TEMP_QUERY, TimeRounds.of(timeQuery = true).roundType)
    assertEquals(RoundType.DIRECTOR_EVENT, TimeRounds.of(directorPatchPending = true).roundType)
  }

  @Test
  fun `同时成立时 查询优先于总结 总结优先于导演事件`() {
    assertEquals(
      RoundType.TEMP_QUERY,
      TimeRounds.of(summaryReady = true, timeQuery = true, directorPatchPending = true).roundType,
    )
    assertEquals(
      RoundType.SUMMARY,
      TimeRounds.of(summaryReady = true, directorPatchPending = true).roundType,
    )
  }

  @Test
  fun `普通轮计划只有规则块 没有任何时间事实`() {
    val plan = TimeRounds.plan(TimeRounds.RoundEvent.NONE, logger = logger)
    assertEquals(listOf(InjectSegment.RULES), plan.segments)
    assertFalse(plan.hasTimeFact())
    assertTrue(InjectionWhitelist.normalRoundHasNoTimeFact(plan.segments))
  }

  @Test
  fun `普通轮即使传了补丁也不会注入导演事件`() {
    // 补丁只在 DIRECTORY_EVENT 轮出现;万一调用方误传,也不会漏进普通轮
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.NONE,
      directorPatch = DirectorPatch(Period.DUSK, "暮色四合"),
      logger = logger,
    )
    assertEquals(listOf(InjectSegment.RULES), plan.segments)
  }

  @Test
  fun `时钟里有 pending 补丁时 planForChat 走导演事件轮`() {
    val quiet = TimeRounds.planForChat(ClockState(), logger = logger)
    assertEquals(RoundType.NORMAL, quiet.roundType)

    val patched = ClockState(
      pendingDirectorPatch = DirectorPatch(Period.DAWN, "天刚亮", ClockOp.ALIGN),
    )
    val event = TimeRounds.planForChat(patched, logger = logger)
    assertEquals(RoundType.DIRECTOR_EVENT, event.roundType)
    assertTrue(event.contains(InjectSegment.DIRECTOR_EVENT))
    assertTrue(event.text().contains("天刚亮"))
  }

  @Test
  fun `时钟为 null 时按普通轮处理`() {
    val plan = TimeRounds.planForChat(null, logger = logger)
    assertEquals(RoundType.NORMAL, plan.roundType)
    assertFalse(plan.hasTimeFact())
  }

  @Test
  fun `规则块进 system 时不被 dynamicBlocks 重复取出`() {
    val plan = TimeRounds.plan(TimeRounds.RoundEvent.NONE, logger = logger)
    assertTrue(TimeRounds.dynamicBlocks(plan).isEmpty())
    assertEquals(TimeRules.RULES_TEXT, TimeRounds.rulesText())
  }

  @Test
  fun `总结轮的动态块顺序是总结正文在前 元数据在后`() {
    val meta = SummaryChain.next(null, null, "第 1-20 轮", Period.DUSK, 480, logger = logger)
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.SUMMARY_READY,
      summaryBlock = "他们从码头走进巷子。",
      summaryMeta = meta,
      logger = logger,
    )
    val dynamic = TimeRounds.dynamicBlocks(plan)
    assertEquals(
      listOf(InjectSegment.SUMMARY, InjectSegment.SUMMARY_META),
      dynamic.map { it.segment },
    )
    assertTrue(dynamic.last().text.startsWith(TimeInject.META_HEADER))
  }

  @Test
  fun `messageBlocks 把元数据并回总结块末尾`() {
    val meta = SummaryChain.next(null, null, "第 1-20 轮", Period.DUSK, 480, logger = logger)
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.SUMMARY_READY,
      summaryBlock = "他们从码头走进巷子。",
      summaryMeta = meta,
      logger = logger,
    )
    val messages = TimeRounds.messageBlocks(plan)
    assertEquals(1, messages.size)
    assertTrue(messages.first().text.startsWith("他们从码头走进巷子。"))
    assertTrue(messages.first().text.endsWith(meta.format()))
  }

  @Test
  fun `临时查询轮取出一次事实后 store 就空了`() {
    val store = TempTimeFactStore(logger)
    CharacterTimeQuery.issue(ClockState(exactTime = 9 * 60 + 30), store, logger = logger)

    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.TIME_QUERY,
      clock = ClockState(exactTime = 9 * 60 + 30),
      tempFacts = store,
      logger = logger,
    )
    assertTrue(plan.contains(InjectSegment.TEMP_FACT))
    assertTrue(store.isEmpty())

    // 下一轮(普通轮)不可能再拿到它
    val next = TimeRounds.plan(TimeRounds.RoundEvent.NONE, tempFacts = store, logger = logger)
    assertFalse(next.contains(InjectSegment.TEMP_FACT))
  }
}
