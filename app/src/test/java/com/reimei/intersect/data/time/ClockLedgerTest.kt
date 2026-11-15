package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.6 / T5.4 验收 —— 快照存储、事务日志、回滚。
 *
 * 三件事必须同时成立,否则「回滚」就是假的:
 * 1. 每次写都留一份 **before 快照** + 一条事务日志(带 tx_id / 操作者 / 前后状态);
 * 2. `rollback(tx_id)` 后状态与那份快照**完全一致**;
 * 3. 整份账本可序列化(落盘一个键),读回来还能继续回滚。
 */
class ClockLedgerTest {

  private fun authority(at: Long = 1_000L): TimeAuthority =
    TimeAuthority(logger = InMemoryTimeLog(), clock = { at })

  @Test
  fun `每次写操作都产生事务与快照`() {
    val clock = authority()
    val tx1 = clock.apply(DirectorCommand.Align(Period.DUSK))
    val tx2 = clock.apply(DirectorCommand.SetTime(9 * 60))
    val tx3 = clock.advanceProgram(30)!!
    val tx4 = clock.markSummary()

    assertEquals(4, clock.transactions().size)
    assertEquals(4, clock.snapshots().size)

    val all = listOf(tx1, tx2, tx3, tx4)
    assertEquals(all.size, all.map { it.txId }.distinct().size)
    all.forEach { tx ->
      assertNotNull("事务必须能找回快照:${tx.txId}", clock.snapshotOf(tx.snapshotRef))
      assertEquals(TxOperator.DIRECTOR, tx1.operator)
    }
    // 事务日志里的前后状态就是当时的真实状态
    assertEquals(tx1.after, tx2.before)
    assertEquals(tx2.after, tx3.before)
    assertEquals(TimeFallback.exactLabel(tx4.before).isNotEmpty(), true)
  }

  @Test
  fun `回滚后状态与快照一致`() {
    val clock = authority()
    val origin = clock.state
    val tx = clock.apply(DirectorCommand.Align(Period.DUSK, "暮色"))
    assertNotEquals(origin, clock.state)

    val rollback = clock.rollback(tx.txId)
    requireNotNull(rollback)
    assertEquals(ClockOp.ROLLBACK, rollback.op)
    assertEquals("回滚后必须与写入前的快照一致", origin, clock.state)
    assertNotNull(rollback.snapshotRef)

    // 回滚本身也是一条事务,可以再回滚(回到「回滚前」)
    val again = clock.rollback(rollback.txId)
    requireNotNull(again)
    assertEquals(tx.after, clock.state)
  }

  @Test
  fun `回滚不存在的事务返回null`() {
    val clock = authority()
    assertNull(clock.rollback("tx_不存在"))
  }

  @Test
  fun `账本可序列化 读回来还能继续回滚`() {
    val clock = authority()
    val tx = clock.apply(DirectorCommand.SetTime(3 * 60, explicitDateAnchor = 2))
    val encoded = clock.ledger().encode()

    val restoredLedger = ClockLedger.decode(encoded)
    assertEquals(clock.state, restoredLedger.state)
    assertEquals(clock.transactions().size, restoredLedger.transactions.size)
    assertEquals(clock.snapshots().size, restoredLedger.snapshots.size)
    assertEquals(clock.ledger().nextSeq, restoredLedger.nextSeq)

    // 用读回来的账本继续回滚
    val resumed = TimeAuthority(restoredLedger, InMemoryTimeLog()) { 2_000L }
    val rollback = resumed.rollback(tx.txId)
    requireNotNull(rollback)
    assertEquals(ClockState(), resumed.state)
  }

  @Test
  fun `坏数据退回空账本`() {
    val empty = ClockLedger()
    assertEquals(empty, ClockLedger.decode(null))
    assertEquals(empty, ClockLedger.decode(""))
    assertEquals(empty, ClockLedger.decode("不是 JSON"))
    assertEquals(empty, ClockLedger.decode("{\"state\":{\"exact_time\":\"x\"}}"))
  }

  @Test
  fun `快照与事务日志按上限裁剪 旧的先掉`() {
    var ledger = ClockLedger()
    val total = ClockLedger.TX_KEEP + 10
    for (seq in 1..total) {
      val tx = ClockTxLog(
        txId = "tx_$seq",
        op = ClockOp.ADVANCE,
        operator = TxOperator.PROGRAM,
        before = ClockState(exactTime = seq),
        after = ClockState(exactTime = seq + 1),
        snapshotRef = "snap_$seq",
        at = seq.toLong(),
        pending = false,
        atRound = 0,
      )
      ledger = ledger.record(tx, seq.toLong())
    }
    assertEquals(ClockLedger.SNAPSHOT_KEEP, ledger.snapshots.size)
    assertEquals(ClockLedger.TX_KEEP, ledger.transactions.size)
    // 最新的一定在
    assertNotNull(ledger.transactionOf("tx_$total"))
    assertNull(ledger.transactionOf("tx_1"))
    assertTrue(ledger.snapshots.last().id == "snap_$total")
  }
}
