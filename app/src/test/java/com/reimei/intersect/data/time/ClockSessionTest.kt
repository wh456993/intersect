package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导演会话(解析 → 执行 → 快照/日志 → 回滚)的端到端单测,以及它与编排层的接线。
 *
 * 这层不碰 Android:账本的 load/save 交给调用方,所以整条链路能在 JVM 里跑完。
 */
class ClockSessionTest {
  private val logger = InMemoryTimeLog()

  private fun session(
    ledger: ClockLedger = ClockLedger(),
    at: Long = 1_000L,
  ): ClockSession = ClockSession(ledger = ledger, logger = logger, clock = { at })

  @Test
  fun `结构化指令改时钟并留事务与快照`() {
    val s = session()
    val outcome = s.command("/time align 黄昏", atRound = 7)
    assertTrue(outcome.applied)
    assertEquals(Period.DUSK, s.state.period)
    val tx = outcome.tx
    assertNotNull(tx)
    assertEquals(ClockOp.ALIGN, tx!!.op)
    assertEquals(TxOperator.DIRECTOR, tx.operator)
    assertEquals(7, tx.atRound)
    assertNotNull(s.ledger().snapshotOf(tx.snapshotRef))
  }

  @Test
  fun `受限模板的自然语言也生效`() {
    val s = session()
    assertTrue(s.command("时间来到清晨").applied)
    assertEquals(Period.DAWN, s.state.period)
    assertTrue(s.command("过了三十分钟").applied)
    assertEquals(Period.DAWN.startMinute + 30, s.state.exactTime)
  }

  @Test
  fun `模板外说法不动时钟`() {
    val s = session()
    val before = s.state
    val outcome = s.command("时间好像变慢了")
    assertFalse(outcome.applied)
    assertNull(outcome.tx)
    assertEquals(before, s.state)
    assertTrue(s.transactions().isEmpty())
    assertEquals(1, logger.of(TimeLogCategory.TRANSACTION).size)
  }

  @Test
  fun `AI 作为执行者被拒绝 且不动时钟`() {
    val s = session()
    val before = s.state
    val outcome = s.command("/time align 午夜", operator = TxOperator.AI)
    assertFalse(outcome.applied)
    assertEquals(before, s.state)
    assertTrue(outcome.rejection!!.contains("AI 无权"))
  }

  @Test
  fun `非法参数被拒绝且不留事务`() {
    val s = session()
    val before = s.state
    // rate 0 在解析层就被拦下
    assertFalse(s.command("/time rate 0").applied)
    assertEquals(before, s.state)
    assertTrue(s.transactions().isEmpty())
  }

  @Test
  fun `按 tx_id 回滚回到写之前的状态`() {
    val s = session()
    val origin = s.state
    val outcome = s.command("/time align 黄昏")
    assertTrue(outcome.applied)
    assertFalse(s.state == origin)

    val rollback = s.rollback(outcome.tx!!.txId)
    assertNotNull(rollback)
    assertEquals(ClockOp.ROLLBACK, rollback!!.op)
    assertEquals(origin, s.state)
  }

  @Test
  fun `回滚不存在的事务返回 null 且不动账本`() {
    val s = session()
    val before = s.state
    assertNull(s.rollback("tx_404"))
    assertEquals(before, s.state)
  }

  @Test
  fun `总结落账推进 last_summary_period 并消费 pending 补丁`() {
    val s = session()
    s.command("/time align 黄昏")
    assertNotNull(s.state.pendingDirectorPatch)

    s.markSummary(atRound = 20)
    assertEquals(Period.DUSK, s.state.lastSummaryPeriod)
    assertNull("补丁只被消费一次", s.state.pendingDirectorPatch)
    // 事务日志新的在前([ClockLedger.record] 把新事务插在表头)
    assertEquals(ClockOp.SUMMARY, s.transactions().first().op)
  }

  @Test
  fun `总结元数据吸收 pending 补丁 并在关键事件里留下跳变记录`() {
    val s = session()
    s.command("/time align 黄昏")
    val meta = s.summaryMeta(previous = null, span = "第 1-20 轮", elapsedMinutes = 480)
    assertEquals(Period.DUSK, meta.endPeriod)
    assertTrue(meta.keyEvents.any { it.contains("导演时间调整") })
    // 名词来自导演给的那个词(ALIGN 时带的是「黄昏」)
    assertEquals(FallbackLevel.DIRECTOR, meta.fallback)
    // 元数据里同时有结构化时段与名词提示(名词不替代时段)
    assertEquals("DUSK", meta.toJson().strField("end_period"))
    assertTrue(meta.format().contains("名词提示:黄昏"))
  }

  @Test
  fun `账本可持久化后恢复 事务与快照都不丢`() {
    val s = session()
    s.command("/time align 黄昏")
    s.command("/time advance 30")
    val restored = ClockSession(ClockLedger.decode(s.ledger().encode()), logger = logger, clock = { 1_000L })
    assertEquals(s.state, restored.state)
    assertEquals(s.transactions().size, restored.transactions().size)
    // 新的在前:表头是最后一笔
    assertEquals(s.transactions().first().txId, restored.transactions().first().txId)
    assertEquals(ClockOp.ADVANCE, restored.transactions().first().op)
  }

  @Test
  fun `编排层把注入了记忆总结的那一轮判为总结轮`() {
    // 与 MainViewModel.chatTimePlan 同一套判定:有总结 ⇒ SUMMARY,否则看补丁
    assertEquals(
      RoundType.SUMMARY,
      TimeRounds.of(summaryReady = true, directorPatchPending = true).roundType,
    )
    assertEquals(
      RoundType.DIRECTOR_EVENT,
      TimeRounds.of(summaryReady = false, directorPatchPending = true).roundType,
    )
    assertEquals(
      RoundType.NORMAL,
      TimeRounds.of(summaryReady = false, directorPatchPending = false).roundType,
    )
  }

  @Test
  fun `总结轮的注入计划里 总结块与元数据都在 system 之外的动态块里`() {
    val s = session()
    s.command("/time align 黄昏")
    val meta = s.summaryMeta(span = "第 1-20 轮", elapsedMinutes = 480)
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.SUMMARY_READY,
      summaryBlock = "他们从码头走进巷子。",
      summaryMeta = meta,
      logger = logger,
    )
    assertEquals(
      listOf(InjectSegment.RULES, InjectSegment.SUMMARY, InjectSegment.SUMMARY_META),
      plan.segments,
    )
    val messages = TimeRounds.messageBlocks(plan)
    assertEquals(1, messages.size)
    assertTrue(messages.first().text.contains("结束时段:DUSK"))
    assertTrue(messages.first().text.contains("名词提示:黄昏"))
  }
}
