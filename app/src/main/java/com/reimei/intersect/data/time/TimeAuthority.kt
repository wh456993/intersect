package com.reimei.intersect.data.time

/**
 * T5.3 —— **唯一的时钟写入口**。
 *
 * 只有这里能改 [ClockState]:它做四件事,别处一个都不许做 ——
 * 1. **校验**:指令参数(推进量为正、倍率 > 0、时刻合法)不合法直接抛,不动状态;
 * 2. **更新**:调用 [SceneClock] 的纯函数算出新状态,并通过 [ClockWrites] 的权限检查真正写入;
 * 3. **快照 + 事务日志**:每次写都留一份 before 快照与一条 [ClockTxLog](T5.4),可按 `tx_id` 回滚;
 * 4. **pending 标记**:导演改过时间就留 [DirectorPatch],等下一次总结吸收(T4.3)。
 *
 * 权限(T5.5):
 * - 导演 / 程序基线走 [apply] / [advanceProgram] / [markSummary];
 * - **AI 一律拒绝**([TxOperator.AI] 会抛 [ClockPermissionError]):AI 只能给建议文本
 *   (见 [DirectorCommand.toCommandText])。
 *
 * 冲突优先级([TxOperator.priority]):导演 > 程序基线 > AI。
 */
class TimeAuthority(
  initial: ClockLedger = ClockLedger(),
  private val logger: TimeLogger = TimeLogs.sink,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private var current: ClockLedger = initial

  init {
    // 注册一次即可(重复注册幂等):时钟写权限只授予事务执行器
    ClockWrites.register(ClockWrites.EXECUTOR_OWNER)
  }

  val state: ClockState get() = current.state

  fun ledger(): ClockLedger = current

  fun transactions(): List<ClockTxLog> = current.transactions

  fun snapshots(): List<ClockSnapshot> = current.snapshots

  fun transactionOf(txId: String): ClockTxLog? = current.transactionOf(txId)

  fun snapshotOf(id: String): ClockSnapshot? = current.snapshotOf(id)

  /** 从盘上加载好的账本接管(不产生事务)。 */
  fun restore(ledger: ClockLedger) {
    current = ledger
  }

  /**
   * 执行一条导演指令(T5.3)。
   *
   * @param operator 执行者。传 [TxOperator.AI] 会抛 [ClockPermissionError]。
   */
  fun apply(
    command: DirectorCommand,
    operator: TxOperator = TxOperator.DIRECTOR,
    atRound: Int = 0,
    note: String = "",
  ): ClockTxLog {
    requireOperator(operator)
    validate(command)
    val before = current.state
    val at = clock()
    val afterRaw = when (command) {
      is DirectorCommand.Align ->
        SceneClock.align(before, command.period, command.noun, command.explicitDateAnchor, atRound, at)

      is DirectorCommand.Advance -> SceneClock.directorAdvance(before, command.minutes, atRound, at)
      is DirectorCommand.SetTime ->
        SceneClock.setTime(before, command.minuteOfDay, command.explicitDateAnchor, atRound, at)

      is DirectorCommand.Pause -> SceneClock.pause(before, command.paused)
      is DirectorCommand.Rate -> SceneClock.setRate(before, command.rate)
    }
    return commit(command.op, operator, before, afterRaw, atRound, note.ifBlank { command.describe() }, at)
  }

  /**
   * 导演面板保存:时间 + 地点 + 天气一次落账(T5.3 的唯一写入口,面板也走这里)。
   *
   * 三条纪律:
   * 1. **没改就不写**:差异清单为空时返回 null —— 没有事务、没有补丁、下一轮也不会重注入;
   * 2. **变更明细进补丁**:注入行 `<旧值变更新值>` 由补丁里的 [SceneChange] 决定
   *    (见 [DirectorEvents.format]),由 [consumeSceneChanges] 保证只注入一次;
   * 3. 与别的写一样留快照与事务日志,可按 `tx_id` 回滚 —— 回滚会把地点/天气一起放回去。
   */
  fun applyScene(
    edit: SceneEdit,
    operator: TxOperator = TxOperator.DIRECTOR,
    atRound: Int = 0,
  ): ClockTxLog? {
    requireOperator(operator)
    val before = current.state
    val changes = SceneEditor.changesOf(before, edit)
    if (changes.isEmpty()) return null
    val at = clock()
    val afterRaw = SceneClock.sceneEdit(before, edit, changes, atRound, at)
    return commit(
      ClockOp.SCENE,
      operator,
      before,
      afterRaw,
      atRound,
      "场景变更:" + changes.joinToString("、") { "${it.field.label}${it.line()}" },
      at,
    )
  }

  /**
   * 场景变更已经**注入过一次**:清掉补丁里的明细,补丁本身留着等下一次总结吸收(T4.3)。
   *
   * 返回 null 表示没有可清的东西(没有补丁 / 明细本来就空)—— 那就不该凭空留一条事务。
   */
  fun consumeSceneChanges(atRound: Int = 0): ClockTxLog? {
    val before = current.state
    val patch = before.pendingDirectorPatch ?: return null
    if (patch.changes.isEmpty()) return null
    val after = SceneClock.consumeSceneChanges(before)
    return commit(
      ClockOp.CONSUME,
      TxOperator.PROGRAM,
      before,
      after,
      atRound,
      "场景变更已注入,清掉变更明细",
      clock(),
    )
  }

  /**
   * T2.3 普通推进(程序基线):按倍率推进,暂停时不推进。
   *
   * 返回 null 表示**没有产生事务**(暂停中 / 推进量为 0 / 倍率把它吃成 0 分钟)——
   * 这种情况不该留事务日志,否则日志里全是「什么都没变」。
   */
  fun advanceProgram(deltaMinutes: Int, atRound: Int = 0): ClockTxLog? {
    if (deltaMinutes <= 0) return null
    val before = current.state
    if (before.paused) {
      logger.log(
        TimeLogEntry(
          TimeLogCategory.TRANSACTION,
          "暂停中,未推进",
          clock(),
          mapOf("delta" to deltaMinutes.toString(), "round" to atRound.toString()),
        )
      )
      return null
    }
    val after = SceneClock.advance(before, deltaMinutes)
    if (after == before) return null
    return commit(ClockOp.ADVANCE, TxOperator.PROGRAM, before, after, atRound, "普通推进 $deltaMinutes 分钟", clock())
  }

  /**
   * 总结已生成(T4.1 / T4.3):推进 `last_summary_period` 并清掉已被吸收的 pending 补丁。
   *
   * 为什么要留事务:总结改变了「上次总结落在哪个时段」这个会影响后续注入判定的状态,
   * 它和别的写一样要能回滚。
   */
  fun markSummary(atRound: Int = 0): ClockTxLog {
    val before = current.state
    val after = SceneClock.markSummary(before)
    val tx = commit(ClockOp.SUMMARY, TxOperator.PROGRAM, before, after, atRound, "总结落账", clock())
    logger.log(
      TimeLogEntry(
        TimeLogCategory.SUMMARY,
        "总结落账:时段 ${before.period.name}",
        tx.at,
        mapOf("round" to atRound.toString(), "tx" to tx.txId, "period" to before.period.name),
      )
    )
    return tx
  }

  /**
   * T2.6 / T5.4 回滚:按 `tx_id` 找到它写入前的那份快照并放回去。
   *
   * 回滚本身也是一次写:记成一条 [ClockOp.ROLLBACK] 事务(有自己的快照),所以回滚还能再回滚。
   * 找不到事务时返回 null(不抛):那是「没有这一笔」,不是错误。
   */
  fun rollback(
    txId: String,
    operator: TxOperator = TxOperator.DIRECTOR,
    atRound: Int = 0,
  ): ClockTxLog? {
    requireOperator(operator)
    val target = current.transactionOf(txId) ?: return null
    val snapshot = current.snapshotOf(target.snapshotRef) ?: return null
    val before = current.state
    return commit(
      ClockOp.ROLLBACK,
      operator,
      before,
      snapshot.state,
      atRound,
      "回滚 ${target.txId}(${target.op})",
      clock(),
    )
  }

  private fun requireOperator(operator: TxOperator) {
    if (operator == TxOperator.AI) {
      throw ClockPermissionError("AI 无权写入时钟:只能给建议,执行必须由导演发起")
    }
  }

  private fun validate(command: DirectorCommand) {
    when (command) {
      is DirectorCommand.Advance -> require(command.minutes > 0) { "推进量必须为正" }
      is DirectorCommand.Rate -> require(command.rate > 0.0) { "倍率必须大于 0" }
      is DirectorCommand.SetTime -> require(command.minuteOfDay in 0 until Period.MINUTES_PER_DAY) {
        "时刻必须是 0..1439 的分钟数"
      }

      is DirectorCommand.Align -> require(command.explicitDateAnchor == null || command.explicitDateAnchor >= 0) {
        "日期锚点不能为负"
      }

      is DirectorCommand.Pause -> Unit
    }
  }

  /** 落地一次写:过权限检查 → 记快照与事务 → 写日志。 */
  private fun commit(
    op: ClockOp,
    operator: TxOperator,
    before: ClockState,
    afterRaw: ClockState,
    atRound: Int,
    note: String,
    at: Long,
  ): ClockTxLog {
    val after = before.write(ClockWrites.EXECUTOR_OWNER) { afterRaw }
    val tx = ClockTxLog(
      txId = current.nextTxId(),
      op = op,
      operator = operator,
      before = before,
      after = after,
      snapshotRef = current.nextSnapshotId(),
      at = at,
      pending = after.pendingDirectorPatch != null,
      atRound = atRound,
      note = note,
    )
    current = current.record(tx, at)
    logger.log(
      TimeLogEntry(
        TimeLogCategory.TRANSACTION,
        tx.summary(),
        at,
        mapOf(
          "tx" to tx.txId,
          "op" to op.name,
          "operator" to operator.name,
          "round" to atRound.toString(),
          "snapshot" to tx.snapshotRef,
          "pending" to tx.pending.toString(),
        ),
      )
    )
    return tx
  }
}
