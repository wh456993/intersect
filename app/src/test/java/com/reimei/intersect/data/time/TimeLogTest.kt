package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.1 验收 —— 四类日志都要有,且都带时间戳与上下文。
 *
 * 这里用真实链路产日志(事务 / 总结 / 注入 / 降级各跑一遍),而不是手工 `log()` 塞一条 ——
 * 那样只能证明日志类能存东西,证明不了「该记的地方真的记了」。
 */
class TimeLogTest {

  @Test
  fun `四类日志都能由真实链路产生 并带时间戳与上下文`() {
    val logger = InMemoryTimeLog()
    val authority = TimeAuthority(logger = logger, clock = { 4_242L })

    // 事务日志
    authority.apply(DirectorCommand.Align(Period.DUSK), atRound = 1)
    // 总结日志
    authority.markSummary(atRound = 20)
    // 注入日志
    RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), logger = logger, at = 4_243L)
    // 降级日志
    SummaryChain.next(
      previous = null,
      patch = null,
      span = "第 1-20 轮",
      endPeriod = Period.EVENING,
      elapsedMinutes = 100,
      logger = logger,
      at = 4_244L,
    )

    TimeLogCategory.entries.forEach { category ->
      val entries = logger.of(category)
      assertTrue("${category.label} 日志缺失", entries.isNotEmpty())
      entries.forEach { entry ->
        assertTrue("${category.label} 日志必须带时间戳", entry.at > 0)
        assertTrue("${category.label} 日志必须带上下文", entry.context.isNotEmpty())
        assertTrue(entry.line().startsWith("["))
      }
    }

    val tx = logger.of(TimeLogCategory.TRANSACTION).first()
    assertEquals(4_242L, tx.at)
    assertEquals(TxOperator.DIRECTOR.name, tx.context["operator"])
    assertTrue(tx.context.containsKey("snapshot"))
  }

  @Test
  fun `日志有上限 新的挤掉旧的`() {
    val logger = InMemoryTimeLog(limit = 3)
    repeat(5) { index -> logger.log(TimeLogEntry(TimeLogCategory.INJECT, "第 $index 条", index.toLong())) }
    assertEquals(3, logger.entries.size)
    assertEquals("第 4 条", logger.last()!!.message)
  }

  @Test
  fun `日志行包含上下文键值`() {
    val entry = TimeLogEntry(
      category = TimeLogCategory.INJECT,
      message = "注入计划",
      at = 1L,
      context = mapOf("round_type" to "NORMAL"),
    )
    assertTrue(entry.line().contains("[注入]"))
    assertTrue(entry.line().contains("round_type=NORMAL"))
  }
}
