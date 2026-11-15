package com.reimei.intersect.data.time

/**
 * 基准时间:**程序设定的**开局时刻,在创建角色时定下来。
 *
 * 为什么单独一层:
 * - 它不是导演事务(创建角色那一刻还没有导演指令,也不该在账本里留一条「谁改的时间」),
 *   所以它直接作为账本的初始 [ClockState] 落盘 —— 事务日志从空开始;
 * - 它的写法与导演面板的时间框**完全同源**([SceneCalendar]):`2026.5.6 8:22`、
 *   `第三纪 +302.5.6 8:22`、`-302.5.6 8:22` 都认。支持非公元纪年是本层的唯一目的。
 *
 * 两条写路径:新建角色用 [parse] + [seedLedger](什么都是新的);改已有角色的基准时间用
 * [mergeInto](只换时间,其它字段原样保留)。
 *
 * 解析失败一律返回 null(调用方保留输入、显示错误,不写任何东西),与 [SceneEditor] 同一套纪律。
 */
object BaseTime {

  /** 输入框的提示语:写清「正负号 + 纪年 + 年.月.日 时:分」。 */
  const val HINT = "正负号 + 纪年 + 年.月.日 时:分,如 第三纪 +302.5.6 8:22"

  /** 没填时的默认基准:[ClockState] 的出厂值(2026.1.1 8:00)。 */
  val DEFAULT: ClockState get() = ClockState()

  /** 当前状态 → 输入框文本(带纪年名与显式正负号)。 */
  fun format(state: ClockState): String = SceneCalendar.label(state)

  /**
   * 输入框文本 → 基准状态。只带时间相关字段:地点 / 天气留给导演面板,基准时间不管它们。
   */
  fun parse(text: String?): ClockState? {
    val time = SceneCalendar.parse(text) ?: return null
    return ClockState(
      exactTime = Period.normalize(time.minuteOfDay),
      dateAnchor = time.dateAnchor,
      eraName = time.eraName,
      eraPlusSign = time.plusSign,
    )
  }

  /** 空白输入视为「用默认基准」;非空白但解析不了返回 null(调用方据此拦下「完成」)。 */
  fun parseOrEmpty(text: String?): ClockState? =
    if (text.isNullOrBlank()) DEFAULT else parse(text)

  /**
   * 只把**时间四项**写进已有状态,地点 / 天气 / 倍率 / 暂停 / 待注入补丁全部原样保留。
   *
   * 为什之不能直接用 [parse] 的结果:那是给**新角色**造初始状态用的,其余字段都落回默认值。
   * 改一个已存在角色的基准时间时直接整份覆盖会静默抹掉导演设过的地点与天气,还会把未注入的
   * 导演补丁([ClockState.pendingDirectorPatch])一起弄丢 —— 那一轮的一次性注入就再也不会出现了。
   *
   * 空白输入视为「用默认基准」,解析不了返回 null(与 [parseOrEmpty] 同一套纪律)。
   */
  fun mergeInto(current: ClockState, text: String?): ClockState? {
    val time = parseOrEmpty(text) ?: return null
    return current.copy(
      exactTime = time.exactTime,
      dateAnchor = time.dateAnchor,
      eraName = time.eraName,
      eraPlusSign = time.eraPlusSign,
    )
  }

  /** 一份只有基准时间的新账本(没有任何事务与快照 —— 基准是程序设定,不是一次写操作)。 */
  fun seedLedger(state: ClockState): ClockLedger = ClockLedger(state = state)
}
