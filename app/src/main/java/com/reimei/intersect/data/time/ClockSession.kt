package com.reimei.intersect.data.time

/** 导演指令的执行结果:一条给人看的回显 + 是否真的改了时钟。 */
data class DirectorOutcome(
  val echo: String,
  val applied: Boolean,
  val tx: ClockTxLog? = null,
  val rejection: String? = null,
)

/**
 * T5.3 / T5.4 —— 一次导演会话:把「解析 → 执行 → 快照/日志 → 回滚」串起来,**不碰 Android**。
 *
 * 为什么要这一层:落盘([ClockStore])与链句柄(Context)在应用侧,但「收到一句话 → 时钟变成什么样
 * → 回显什么 → 能不能按 tx_id 回滚」这段逻辑必须能在 JVM 单测里跑。所以这层只认
 * [ClockLedger] 与 [TimeAuthority],外部把 load/save 交给调用方。
 *
 * 三条纪律:
 * 1. 写了时钟就把新账本交出去([ledger]),调用方负责落盘 —— 这里不隐式存盘;
 * 2. 低置信 / 解析失败**一律不动时钟**(T5.2),回显说明原因;
 * 3. 回滚按 `tx_id` 走,[TimeAuthority.rollback] 会自己再记一条 ROLLBACK 事务。
 */
class ClockSession(
  ledger: ClockLedger = ClockLedger(),
  private val logger: TimeLogger = TimeLogs.sink,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val authority = TimeAuthority(initial = ledger, logger = logger, clock = clock)

  val state: ClockState get() = authority.state

  fun ledger(): ClockLedger = authority.ledger()

  fun transactions(): List<ClockTxLog> = authority.transactions()

  /**
   * 执行一句导演话术(结构化 `/time ...` 或受限模板的自然语言)。
   *
   * @param atRound 当前轮次(写进事务日志,便于对照会话)。
   * @param operator 默认导演;AI 侧传 [TxOperator.AI] 会被拒绝(T5.5)。
   */
  fun command(
    text: String,
    atRound: Int = 0,
    operator: TxOperator = TxOperator.DIRECTOR,
  ): DirectorOutcome {
    val parse = DirectorParser.parse(text)
    if (parse is DirectorParse.Low) {
      logger.log(
        TimeLogEntry(
          TimeLogCategory.TRANSACTION,
          "导演指令未生效:${parse.reason}",
          clock(),
          mapOf("input" to text, "at_round" to atRound.toString()),
        )
      )
      return DirectorOutcome(echo = parse.echo, applied = false, rejection = parse.reason)
    }
    val ok = parse as DirectorParse.Ok
    return try {
      val tx = authority.apply(ok.command, operator = operator, atRound = atRound)
      DirectorOutcome(echo = "${ok.echo}\n${tx.summary()}", applied = true, tx = tx)
    } catch (e: ClockPermissionError) {
      // AI / 未授权调用方:返回拒绝结果而不是崩(界面要能显示「无权改时间」)
      DirectorOutcome(echo = "没有改时钟:${e.message}", applied = false, rejection = e.message)
    } catch (e: IllegalArgumentException) {
      DirectorOutcome(echo = "没有改时钟:${e.message}", applied = false, rejection = e.message)
    }
  }

  /** 按 `tx_id` 回滚。返回回滚后的事务;找不到或已回滚过则 null。 */
  fun rollback(txId: String, atRound: Int = 0): ClockTxLog? =
    try {
      authority.rollback(txId, atRound = atRound)
    } catch (e: IllegalArgumentException) {
      logger.log(TimeLogEntry(TimeLogCategory.TRANSACTION, "回滚失败:${e.message}", clock(), mapOf("tx_id" to txId)))
      null
    }

  /**
   * 导演面板保存(时间 / 地点 / 天气)。与 [command] 同一套纪律:
   * 解析不过就一句原因、不动时钟;改成了才落账并回显变更行。
   *
   * 「没改」也是一种什么都不做的结果:返回 `applied=false`、`rejection=null`,
   * 回显说明没有变化 —— 这样界面不会误以为出了错,也不会平白注入一次。
   */
  fun editScene(
    draft: SceneDraft,
    atRound: Int = 0,
    operator: TxOperator = TxOperator.DIRECTOR,
  ): DirectorOutcome {
    val parse = SceneEditor.parse(draft)
    if (parse is SceneEditResult.Reject) {
      logger.log(
        TimeLogEntry(
          TimeLogCategory.TRANSACTION,
          "导演面板未生效:${parse.reason}",
          clock(),
          mapOf("at_round" to atRound.toString()),
        )
      )
      return DirectorOutcome(echo = parse.echo, applied = false, rejection = parse.reason)
    }
    val edit = (parse as SceneEditResult.Ok).edit
    return try {
      val tx = authority.applyScene(edit, operator = operator, atRound = atRound)
        ?: return DirectorOutcome(echo = "场景没有变化,没有写时钟", applied = false)
      DirectorOutcome(
        echo = "已更新场景:\n" + tx.after.pendingDirectorPatch?.changes.orEmpty()
          .joinToString("\n") { it.line() },
        applied = true,
        tx = tx,
      )
    } catch (e: ClockPermissionError) {
      DirectorOutcome(echo = "没有改场景:${e.message}", applied = false, rejection = e.message)
    } catch (e: IllegalArgumentException) {
      DirectorOutcome(echo = "没有改场景:${e.message}", applied = false, rejection = e.message)
    }
  }

  /**
   * 场景变更已经注入过一次:清掉补丁里的明细并返回这次事务。
   *
   * 调用时机固定为「这一轮的请求真的发出去了」之后(与 [markSummary] 同一类时机):
   * 请求失败时不清,下一轮还能再带上这份变更。
   */
  fun consumeSceneChanges(atRound: Int = 0): ClockTxLog? = authority.consumeSceneChanges(atRound = atRound)

  /**
   * 一段总结已经注入 / 生成:推进 `last_summary_period`,并**消费掉 pending 导演补丁**
   * (T1.6 的消费规则:只消费一次),返回这次记下的事务。
   *
   * 没有总结可生成时(模型失败)不应该调它 —— 补丁还要留给下一次总结吸收。
   */
  fun markSummary(atRound: Int = 0): ClockTxLog = authority.markSummary(atRound = atRound)

  /**
   * 这一轮如果注入了总结块,就用它拼段落元数据(T4.2 / T4.3)。
   *
   * [previous] 传上一段的元数据(第一段时 null):本段的 `start_period` 继承它的 `end_period`,
   * 这样时间轴是连续的;pending 补丁在这里被吸收进 `key_events`,并在 [markSummary] 时清掉。
   */
  fun summaryMeta(
    previous: SummaryMeta? = null,
    span: String,
    elapsedMinutes: Int = 0,
    keyEvents: List<String> = emptyList(),
  ): SummaryMeta = SummaryChain.next(
    previous = previous,
    patch = state.pendingDirectorPatch,
    span = span,
    endPeriod = state.period,
    elapsedMinutes = elapsedMinutes,
    keyEvents = keyEvents,
    logger = logger,
    at = clock(),
  )
}
