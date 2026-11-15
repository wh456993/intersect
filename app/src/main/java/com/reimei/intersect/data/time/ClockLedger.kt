package com.reimei.intersect.data.time

/**
 * 事务操作者。冲突优先级:**导演 > 程序基线 > AI**(T5.3)。
 *
 * [priority] 就是这条规则的唯一实现,别的模块不要自己再写一遍比较逻辑。
 */
enum class TxOperator(val label: String, val priority: Int) {
  DIRECTOR("导演", 3),
  PROGRAM("程序基线", 2),
  AI("AI", 1);

  companion object {
    fun fromName(name: String?): TxOperator? =
      entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

    /** 两个操作者冲突时谁说了算。 */
    fun winner(a: TxOperator, b: TxOperator): TxOperator = if (a.priority >= b.priority) a else b
  }
}

/** 一次快照:写入**之前**的状态存下来,回滚就是把它放回去(T2.6)。 */
data class ClockSnapshot(val id: String, val state: ClockState, val at: Long) {
  fun toJson(): JsonValue.Obj = jobj(
    "id" to jstr(id),
    "state" to state.toJson(),
    "at" to jnum(at),
  )

  companion object {
    fun fromJson(value: JsonValue?): ClockSnapshot? {
      val obj = value as? JsonValue.Obj ?: return null
      val id = obj.strField("id") ?: return null
      val state = ClockState.fromJson(obj["state"]) ?: return null
      return ClockSnapshot(id, state, obj.longField("at") ?: 0L)
    }
  }
}

/**
 * T5.4 —— 事务日志:事务 ID、操作、操作者、**改前与改后状态**、快照引用、轮次、备注。
 *
 * 契约是 [TimeContracts.DIRECTOR_TRANSACTION],字段名与这里一一对应。
 */
data class ClockTxLog(
  val txId: String,
  val op: ClockOp,
  val operator: TxOperator,
  val before: ClockState,
  val after: ClockState,
  val snapshotRef: String,
  val at: Long,
  val pending: Boolean,
  val atRound: Int,
  val note: String = "",
) {
  fun toJson(): JsonValue.Obj = jobj(
    "tx_id" to jstr(txId),
    "op" to jstr(op.name),
    "operator" to jstr(operator.name),
    "before" to before.toJson(),
    "after" to after.toJson(),
    "snapshot_ref" to jstr(snapshotRef),
    "at" to jnum(at),
    "pending" to jbool(pending),
    "at_round" to jnum(atRound),
    "note" to jstr(note),
  )

  /** 给人看的摘要(调试面板/日志)。 */
  fun summary(): String =
    "$op ${TimeFallback.exactLabel(before)} → ${TimeFallback.exactLabel(after)}(${operator.label})"

  companion object {
    fun fromJson(value: JsonValue?): ClockTxLog? {
      val obj = value as? JsonValue.Obj ?: return null
      val txId = obj.strField("tx_id") ?: return null
      val op = ClockOp.fromName(obj.strField("op")) ?: return null
      val operator = TxOperator.fromName(obj.strField("operator")) ?: return null
      val before = ClockState.fromJson(obj["before"]) ?: return null
      val after = ClockState.fromJson(obj["after"]) ?: return null
      return ClockTxLog(
        txId = txId,
        op = op,
        operator = operator,
        before = before,
        after = after,
        snapshotRef = obj.strField("snapshot_ref").orEmpty(),
        at = obj.longField("at") ?: 0L,
        pending = obj.boolField("pending") ?: false,
        atRound = obj.intField("at_round") ?: 0,
        note = obj.strField("note").orEmpty(),
      )
    }
  }
}

/**
 * T2.6 —— 时钟账本:当前状态 + 快照栈 + 事务日志,整份可序列化(落盘只有一个键)。
 *
 * 为什么把三样放一起:回滚要能按 `tx_id` 找回快照,而「状态被改过」与「日志里有一条对不上的事务」
 * 是不能容忍的破口 —— 一个 JSON 文档保证三者同生共死。
 */
data class ClockLedger(
  val state: ClockState = ClockState(),
  val snapshots: List<ClockSnapshot> = emptyList(),
  val transactions: List<ClockTxLog> = emptyList(),
  /** 事务序号,决定 tx_id / snapshot id;落盘保留,重启后不会重号。 */
  val nextSeq: Long = 1L,
) {
  fun encode(): String = toJson().encode()

  fun toJson(): JsonValue.Obj = jobj(
    "state" to state.toJson(),
    "next_seq" to jnum(nextSeq),
    "snapshots" to jarr(snapshots.map { it.toJson() }),
    "transactions" to jarr(transactions.map { it.toJson() }),
  )

  fun snapshotOf(id: String): ClockSnapshot? = snapshots.firstOrNull { it.id == id }

  fun transactionOf(txId: String): ClockTxLog? = transactions.firstOrNull { it.txId == txId }

  /** 记一笔事务:把 before 状态存成快照,日志与快照都按上限裁掉最旧的。 */
  fun record(tx: ClockTxLog, snapshotAt: Long): ClockLedger {
    val snapshot = ClockSnapshot(tx.snapshotRef, tx.before, snapshotAt)
    return copy(
      state = tx.after,
      snapshots = (snapshots + snapshot).takeLast(SNAPSHOT_KEEP),
      transactions = (listOf(tx) + transactions).take(TX_KEEP),
      nextSeq = nextSeq + 1,
    )
  }

  fun nextTxId(): String = "tx_$nextSeq"

  fun nextSnapshotId(): String = "snap_$nextSeq"

  companion object {
    const val SNAPSHOT_KEEP = 50
    const val TX_KEEP = 100

    /** 反序列化。任何一处坏了都退回空账本 —— 时钟读不出来时宁可从头开始,也不要半份状态。 */
    fun decode(raw: String?): ClockLedger {
      if (raw.isNullOrBlank()) return ClockLedger()
      return try {
        val obj = Json.parse(raw).asObj() ?: return ClockLedger()
        val state = ClockState.fromJson(obj["state"]) ?: return ClockLedger()
        val snapshots = obj["snapshots"].arrItems().mapNotNull { ClockSnapshot.fromJson(it) }
        val transactions = obj["transactions"].arrItems().mapNotNull { ClockTxLog.fromJson(it) }
        ClockLedger(
          state = state,
          snapshots = snapshots,
          transactions = transactions,
          nextSeq = obj.longField("next_seq") ?: ((transactions.maxOfOrNull { it.at } ?: 0L) + 1L),
        )
      } catch (e: Exception) {
        ClockLedger()
      }
    }
  }
}
