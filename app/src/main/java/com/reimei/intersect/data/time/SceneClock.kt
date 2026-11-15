package com.reimei.intersect.data.time

/** 时钟操作。事务日志与契约([TimeContracts.DIRECTOR_TRANSACTION])共用这一套词。 */
enum class ClockOp {
  ADVANCE,
  ALIGN,
  SET,
  PAUSE,
  RESUME,
  RATE,
  SUMMARY,
  ROLLBACK,

  /** 导演面板整体改场景(时间 + 地点 + 天气)。 */
  SCENE,

  /** 场景变更已注入、把补丁里的变更明细清掉(不改时间,只让注入只发生一次)。 */
  CONSUME;

  companion object {
    fun fromName(name: String?): ClockOp? =
      entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
  }
}

/**
 * T2.2–T2.5 —— 时钟的**纯函数**核心:每个函数拿旧状态算新状态,不写盘、不发日志。
 *
 * 分层原因(接手时不要推翻):
 * - 纯函数好测:边界分钟、跨日、暂停/倍率都能在单测里穷举;
 * - 「写」这件事只发生在 [TimeAuthority] 一处(T5.3 唯一写入口):快照、事务日志、
 *   权限校验、pending 标记都在那一层,这里只负责算法。
 *
 * 日期锚点([ClockState.dateAnchor])规则集中在这里:
 * - **推进**时分钟数取模回绕 → 锚点加一(跨日);
 * - **对齐**时目标时段落在当前时间**之后** → 锚点不变;落在**之前** → 锚点加一(第二天);
 * - 导演**显式给日期** → 以导演为准(覆盖上面两条)。
 */
object SceneClock {

  /** T2.2 时段映射:分钟数 → 时段。表全覆盖,任何输入都有结果。 */
  fun periodFromTime(minuteOfDay: Int): Period = Period.fromMinute(minuteOfDay)

  /**
   * T2.3 普通推进。
   *
   * - **暂停时不推进**(原样返回,连时间戳都不动);
   * - 先进倍率再取整(`Math.round`):0.5 倍时推进 3 分钟 = 1.5 → 2 分钟,避免长期偏少;
   * - 分钟数回绕时日期锚点加一(或减一,若 delta 为负 —— 本工程不用,但算法保持闭合)。
   */
  fun advance(state: ClockState, deltaMinutes: Int): ClockState {
    if (state.paused) return state
    if (deltaMinutes == 0) return state
    val scaled = Math.round(deltaMinutes * state.rate).toInt()
    if (scaled == 0) return state
    return wrap(state, state.exactTime + scaled)
  }

  /**
   * T2.4 导演对齐。
   *
   * - **只改时段**:当前分钟若已经在目标时段里就**保留分钟**,否则取目标时段起点;
   * - 锚点规则见类注释(之后不变 / 之前加一 / 显式日期优先);
   * - 顺带记一条 [DirectorPatch](名词可选),供下一次总结吸收(T4.3)。
   */
  fun align(
    state: ClockState,
    target: Period,
    noun: String? = null,
    explicitDateAnchor: Int? = null,
    atRound: Int = 0,
    at: Long = 0L,
  ): ClockState {
    val targetMinute = if (target.contains(state.exactTime)) state.exactTime else target.startMinute
    val anchor = explicitDateAnchor
      ?: if (targetMinute < state.exactTime) state.dateAnchor + 1 else state.dateAnchor
    return state.copy(
      exactTime = targetMinute,
      dateAnchor = anchor,
      pendingDirectorPatch = DirectorPatch(
        period = target,
        noun = noun?.trim()?.takeIf { it.isNotEmpty() },
        op = ClockOp.ALIGN,
        atRound = atRound,
        at = at,
      ),
    )
  }

  /**
   * T2.5 直接设时刻(`HH:mm` 的分钟数)。锚点默认不动(设时刻 ≠ 换天),
   * 导演显式给日期时以导演为准。
   */
  fun setTime(
    state: ClockState,
    minuteOfDay: Int,
    explicitDateAnchor: Int? = null,
    atRound: Int = 0,
    at: Long = 0L,
  ): ClockState {
    val minute = Period.normalize(minuteOfDay)
    return state.copy(
      exactTime = minute,
      dateAnchor = explicitDateAnchor ?: state.dateAnchor,
      pendingDirectorPatch = DirectorPatch(
        period = Period.fromMinute(minute),
        noun = null,
        op = ClockOp.SET,
        atRound = atRound,
        at = at,
      ),
    )
  }

  /** 导演推进(与普通推进的区别:会留补丁,好让下一次总结体现这段跳变)。 */
  fun directorAdvance(
    state: ClockState,
    deltaMinutes: Int,
    atRound: Int = 0,
    at: Long = 0L,
  ): ClockState {
    val advanced = advance(state.copy(paused = false), deltaMinutes)
    return advanced.copy(
      paused = state.paused,
      pendingDirectorPatch = DirectorPatch(
        period = advanced.period,
        noun = null,
        op = ClockOp.ADVANCE,
        atRound = atRound,
        at = at,
      ),
    )
  }

  /** T2.5 暂停 / 恢复。 */
  fun pause(state: ClockState, paused: Boolean = true): ClockState = state.copy(paused = paused)

  /** T2.5 倍率。非正数视为非法(调用方应先校验,这里兜一层)。 */
  fun setRate(state: ClockState, rate: Double): ClockState {
    require(rate > 0.0) { "倍率必须大于 0" }
    return state.copy(rate = rate)
  }

  /**
   * 导演面板整体改场景:时间(锚点 + 分钟)、地点、天气一次写完。
   *
   * 与 [align] / [setTime] 的区别:**时间由导演显式给到日期**,不存在「取区间起点」的换算;
   * 补丁带上 [SceneChange] 明细,下一轮的 `[导演时间事件]` 才能写出 `<旧变更新>` 那几行。
   * [changes] 为空时不要在 [TimeAuthority] 那一层落账(没改就不重注入)。
   */
  fun sceneEdit(
    state: ClockState,
    edit: SceneEdit,
    changes: List<SceneChange>,
    atRound: Int = 0,
    at: Long = 0L,
  ): ClockState = state.copy(
    exactTime = Period.normalize(edit.minuteOfDay),
    dateAnchor = edit.dateAnchor,
    place = edit.place,
    weather = edit.weather,
    eraName = edit.eraName,
    eraPlusSign = edit.plusSign,
    pendingDirectorPatch = DirectorPatch(
      period = Period.fromMinute(edit.minuteOfDay),
      noun = null,
      op = ClockOp.SCENE,
      atRound = atRound,
      at = at,
      changes = changes,
    ),
  )

  /** 场景变更注入过一次之后:只清掉明细,补丁本身留着等总结吸收(T4.3)。 */
  fun consumeSceneChanges(state: ClockState): ClockState {
    val patch = state.pendingDirectorPatch ?: return state
    if (patch.changes.isEmpty()) return state
    return state.copy(pendingDirectorPatch = patch.copy(changes = emptyList()))
  }

  /**
   * 总结已生成:推进 `last_summary_period`,并把**已被吸收**的导演补丁清掉
   * (T1.6 的消费规则:只消费一次)。
   */
  fun markSummary(state: ClockState, period: Period = state.period): ClockState =
    state.copy(lastSummaryPeriod = period, pendingDirectorPatch = null)

  /** 消费 pending 补丁(不改其它字段),给「总结块已拼好」的路径用。 */
  fun consumePending(state: ClockState): ClockState = state.copy(pendingDirectorPatch = null)

  /** 把分钟数回绕成一天之内,并按「跨过了几个午夜」调整日期锚点。 */
  private fun wrap(state: ClockState, rawMinute: Int): ClockState {
    val days = Math.floorDiv(rawMinute, Period.MINUTES_PER_DAY)
    val minute = Math.floorMod(rawMinute, Period.MINUTES_PER_DAY)
    return state.copy(exactTime = minute, dateAnchor = state.dateAnchor + days)
  }
}
