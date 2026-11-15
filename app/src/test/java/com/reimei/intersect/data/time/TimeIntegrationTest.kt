package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.3 验收 —— 四条主链路的集成测试。
 *
 * 每条链路都走**真的**编排层 + 事务执行器(不是单独测某个函数),因为要验的正是
 * 「几个阶段拼起来之后会不会漏」:
 * 1. 普通对话零注入;
 * 2. 跨时段自动总结(含保险丝补位);
 * 3. 导演跳转(结构化指令 → 轻量事件);
 * 4. 回滚(状态与快照一致,且落盘读回来还能继续用)。
 */
class TimeIntegrationTest {

  /** 一次会话的脚手架:时钟 + 日志 + 临时事实容器,模拟编排层的调用顺序。 */
  private class Session(logger: InMemoryTimeLog) {
    val clock = TimeAuthority(logger = logger, clock = { 9_000L })
    val tempFacts = TempTimeFactStore(logger)
    val log = logger
    var rounds = 0
    var roundsSinceSummary = 0
    var tokensSinceSummary = 0

    fun runRound(roundType: RoundType, context: RoundContext = RoundContext(), logger: TimeLogger = log): InjectionPlan {
      val plan = RoundOrchestrator.plan(
        roundType = roundType,
        context = context,
        tempFacts = tempFacts,
        logger = logger,
        atRound = rounds,
        clock = clock.state,
      )
      rounds += 1
      return plan
    }

    /** 一轮结束后按程序基线推进时间,并返回是否跨了时段。 */
    fun passTime(minutes: Int): Boolean {
      val before = clock.state.period
      clock.advanceProgram(minutes, atRound = rounds)
      return clock.state.period != before
    }
  }

  private fun summaryOf(session: Session, spa: String = "第 1-20 轮", elapsed: Int = 480): SummaryMeta =
    SummaryChain.next(
      previous = null,
      patch = session.clock.state.pendingDirectorPatch,
      span = spa,
      endPeriod = session.clock.state.period,
      elapsedMinutes = elapsed,
      keyEvents = listOf("进城"),
      logger = session.log,
    )

  @Test
  fun `链路一 普通对话零注入`() {
    val session = Session(InMemoryTimeLog())
    repeat(5) {
      session.passTime(12)
      val plan = session.runRound(RoundType.NORMAL)
      assertEquals(listOf(InjectSegment.RULES), plan.segments)
      assertFalse(plan.hasTimeFact())
      assertFalse(plan.text().contains(TimeInject.FACT_HEADER))
      assertFalse(plan.text().contains(TimeInject.TEMP_HEADER))
      assertFalse(plan.text().contains("08:"))
      assertTrue(plan.text().contains(TimeInject.RULES_HEADER))
    }
  }

  @Test
  fun `链路二 跨时段自动总结 元数据贴尾 并把补丁吸收掉`() {
    val session = Session(InMemoryTimeLog())
    session.clock.apply(DirectorCommand.SetTime(16 * 60 + 30, explicitDateAnchor = 0))

    // 推进 90 分钟:16:30 下午 → 18:00 黄昏
    val crossed = session.passTime(90)
    assertTrue("必须真的跨了时段", crossed)

    val trigger = SummaryTriggers.decide(
      SummaryTriggerInput(
        roundsSinceSummary = session.roundsSinceSummary,
        tokensSinceSummary = session.tokensSinceSummary,
        periodChanged = crossed,
      ),
    )
    assertEquals(SummaryTrigger.PERIOD_CROSS, trigger)

    val meta = summaryOf(session)
    assertEquals(Period.DUSK, meta.endPeriod)
    val plan = session.runRound(
      RoundType.SUMMARY,
      RoundContext(summaryBlock = "这段时间你们走了很远。", summaryMeta = meta),
    )
    assertEquals(
      listOf(InjectSegment.RULES, InjectSegment.SUMMARY, InjectSegment.SUMMARY_META),
      plan.segments,
    )
    assertTrue(plan.text().endsWith(meta.format()))
    assertTrue(meta.format().contains("跨度:第 1-20 轮"))
    assertTrue(meta.format().contains("黄昏"))

    // 总结落账:上次总结时段推进、pending 补丁被消费
    session.clock.markSummary(atRound = 20)
    assertEquals(Period.DUSK, session.clock.state.lastSummaryPeriod)
    assertNull("补丁只能被消费一次", session.clock.state.pendingDirectorPatch)
  }

  @Test
  fun `链路二之补充 总结做不了时用保险丝补位 且不与总结并存`() {
    val session = Session(InMemoryTimeLog())
    session.clock.apply(DirectorCommand.SetTime(21 * 60))
    val crossed = session.passTime(120) // 21:00 入夜 → 23:00 午夜
    assertTrue(crossed)

    val summaryDue = false // 假设这一轮总结因为单飞/失败做不了
    val roundsSinceSummary = TimeThresholds.FUSE_THRESHOLD_ROUNDS
    val events = TimeDedupe.resolve(summaryDue, crossed, roundsSinceSummary)
    assertTrue(events.fuse)
    assertFalse(events.summary)

    val fusePlan = session.runRound(
      RoundType.SUMMARY,
      RoundContext(fuse = session.clock.state.period),
    )
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.PERIOD_FUSE), fusePlan.segments)
    assertTrue(fusePlan.text().contains("[时段变更] 午夜"))
    assertFalse(fusePlan.contains(InjectSegment.SUMMARY))

    // 总结能做的同一轮:只有总结,没有保险丝
    val both = TimeDedupe.resolve(summaryDue = true, periodChanged = crossed, roundsSinceSummary = roundsSinceSummary)
    assertTrue(both.summary)
    assertFalse(both.fuse)
  }

  @Test
  fun `链路三 导演跳转 生成轻量事件而不触发总结`() {
    val session = Session(InMemoryTimeLog())
    val before = session.clock.state

    val parsed = DirectorParser.parse("/time align 清晨") as DirectorParse.Ok
    val tx = session.clock.apply(parsed.command, atRound = 7)
    assertTrue(tx.pending)
    assertEquals(Period.DAWN, session.clock.state.period)
    assertEquals("清晨在 08:00 之前 ⇒ 第二天", before.dateAnchor + 1, session.clock.state.dateAnchor)

    val patch = requireNotNull(session.clock.state.pendingDirectorPatch)
    val plan = session.runRound(RoundType.DIRECTOR_EVENT, RoundContext(directorPatch = patch))
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.DIRECTOR_EVENT), plan.segments)
    assertTrue(plan.text().contains("[导演时间事件]"))
    assertTrue(plan.text().contains("清晨"))
    assertFalse(plan.contains(InjectSegment.SUMMARY))
    assertFalse("轻量事件不触发总结", DirectorEvents.triggersSummary())
  }

  @Test
  fun `链路四 导演跳转可回滚 且落盘读回来还能继续`() {
    val session = Session(InMemoryTimeLog())
    val origin = session.clock.state
    val jump = session.clock.apply(DirectorCommand.Align(Period.MIDNIGHT))
    assertNotNull(session.clock.snapshotOf(jump.snapshotRef))

    // 落盘 → 读回 → 在新的执行器上回滚
    val reloaded = ClockLedger.decode(session.clock.ledger().encode())
    val resumed = TimeAuthority(reloaded, InMemoryTimeLog()) { 9_500L }
    assertEquals(session.clock.state, resumed.state)

    val rollback = resumed.rollback(jump.txId)
    requireNotNull(rollback)
    assertEquals("回滚后状态与快照一致", origin, resumed.state)

    // 回滚之后普通轮照旧零注入,时钟状态没有被回滚带坏
    val plan = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), logger = InMemoryTimeLog())
    assertEquals(listOf(InjectSegment.RULES), plan.segments)
    assertEquals(Period.MORNING, resumed.state.period)
  }
}
