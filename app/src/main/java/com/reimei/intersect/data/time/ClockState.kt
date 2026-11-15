package com.reimei.intersect.data.time

/**
 * T2.1 —— 程序时钟的唯一权威状态。
 *
 * 字段与 T1.4 的 [TimeContracts.CLOCK] 契约一一对应:
 * - [exactTime] `exact_time`:一天内的分钟数(0–1439)。**精确时间只给 UI / 导演 / 日志**,
 *   不作为注入给模型的措辞(验收清单);
 * - [dateAnchor] `date_anchor`:剧情日期锚点,0 = 第 1 天;跨日规则见 [SceneClock];
 * - [rate] / [paused]:倍率与暂停,**暂停时不推进**,倍率对推进量生效;
 * - [lastSummaryPeriod] `last_summary_period`:上次总结落在哪个时段(去重与保险丝判定用);
 * - [pendingDirectorPatch] `pending_director_patch`:导演改过、还没被总结吸收的补丁;
 * - [place] / [weather]:导演模式可改的场景字段(空串 = 未设定,按原样落盘,不猜默认值);
 * - [eraName] / [eraPlusSign]:纪年名(空串 = 公元)与年份是否显式带 `+`。
 *   两者**只影响人读文本**,不参与时段 / 推进 / 跨日任何算法 —— 所以支持
 *   「第三纪 +302.5.6 8:22」这种非公元纪年,而时钟本身仍然走纯天序号。
 *
 * **没有 `round_type`**(T1.5 修订 / T2.1 验收):轮次类型由编排层在构造请求时确定,
 * 时钟只知道「时间」。`ClockStateTest` 直接断言序列化结果里不含这个键。
 *
 * **没有写方法**:改状态必须走 [SceneClock] 的纯函数 + [TimeAuthority] 这个唯一写入口;
 * 想在别处写,只能调 [write] 并会撞上 [ClockWrites] 的权限检查(T5.5)。
 */
data class ClockState(
  val exactTime: Int = DEFAULT_START_MINUTE,
  val dateAnchor: Int = 0,
  val rate: Double = 1.0,
  val paused: Boolean = false,
  val lastSummaryPeriod: Period? = null,
  val pendingDirectorPatch: DirectorPatch? = null,
  /** 地点(导演面板可改,空串表示未设定)。 */
  val place: String = "",
  /** 天气(导演面板可改,空串表示未设定)。 */
  val weather: String = "",
  /** 纪年名(空串 = 公元)。只影响人读文本,不参与推进算法。 */
  val eraName: String = "",
  /** 年份是否显式带 `+`(负数自带 `-`)。 */
  val eraPlusSign: Boolean = false,
) {
  /** 当前时段(由分钟数确定性映射,不是独立字段,也不许单独改)。 */
  val period: Period get() = Period.fromMinute(exactTime)

  /** 契约序列化:显式写出 null 字段,便于日志审计与 Schema 检查。 */
  fun toJson(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
    "exact_time" to jnum(exactTime),
    "period" to jstr(period.name),
    "date_anchor" to jnum(dateAnchor),
    "rate" to jnum(rate),
    "paused" to jbool(paused),
    "last_summary_period" to (lastSummaryPeriod?.let { jstr(it.name) } ?: JsonValue.Null),
    "pending_director_patch" to (pendingDirectorPatch?.toJson() ?: JsonValue.Null),
    "place" to jstr(place),
    "weather" to jstr(weather),
    "era_name" to jstr(eraName),
    "era_plus_sign" to jbool(eraPlusSign),
  ))

  /**
   * 唯一的写路径:**必须**带调用方身份,且只有事务执行器([ClockWrites.EXECUTOR_OWNER])
   * 且已注册时才能通过。其他模块调用会抛 [ClockPermissionError]。
   */
  fun write(owner: String, patch: (ClockState) -> ClockState): ClockState {
    ClockWrites.check(owner)
    return patch(this)
  }

  fun isSamePeriodAs(other: ClockState): Boolean = period == other.period

  companion object {
    /** 初始时刻:第 1 天 08:00(上午),一个不会撞上「深夜/午夜」的中间值。 */
    const val DEFAULT_START_MINUTE = 8 * 60

    /** 反序列化。坏数据一律返回 null,由调用方决定要不要回落到默认状态。 */
    fun fromJson(value: JsonValue?): ClockState? {
      val obj = value as? JsonValue.Obj ?: return null
      val exact = obj.intField("exact_time") ?: return null
      val rate = obj.doubleField("rate") ?: 1.0
      // 契约里的 period 字段是精确时间的函数:两者不一致说明数据被手改过,一律以分钟数为准
      return ClockState(
        exactTime = Period.normalize(exact),
        dateAnchor = obj.intField("date_anchor") ?: 0,
        // 倍率必须为正:0 或负数会让时钟停止/倒退,是坏数据
        rate = if (rate > 0.0) rate else 1.0,
        paused = obj.boolField("paused") ?: false,
        lastSummaryPeriod = Period.fromName(obj.strField("last_summary_period")),
        pendingDirectorPatch = DirectorPatch.fromJson(obj["pending_director_patch"]),
        place = obj.strField("place").orEmpty(),
        weather = obj.strField("weather").orEmpty(),
        eraName = obj.strField("era_name").orEmpty(),
        eraPlusSign = obj.boolField("era_plus_sign") ?: false,
      )
    }
  }
}

/**
 * 导演补丁:导演(用户)改过时间、还没被总结吸收的那一笔。
 *
 * [noun] 是导演显式给的措辞(可为空 ⇒ 用默认名词,见 [TimeFallback]);
 * [op] 记录它是哪种操作([ClockOp]),[atRound] / [at] 是写它时的轮次与时间戳。
 */
data class DirectorPatch(
  val period: Period,
  val noun: String? = null,
  val op: ClockOp = ClockOp.ALIGN,
  val atRound: Int = 0,
  val at: Long = 0L,
  /**
   * 导演面板改动的场景字段明细(旧值 → 新值)。
   *
   * 只有 [ClockOp.SCENE] 会带它;它决定下一轮 `[导演时间事件]` 里那几行
   * `<旧值变更新值>`,并且**注入一次就清**(见 `TimeAuthority.consumeSceneChanges`)。
   */
  val changes: List<SceneChange> = emptyList(),
) {
  fun toJson(): JsonValue.Obj = jobj(
    "period" to jstr(period.name),
    "noun" to (noun?.let { jstr(it) } ?: JsonValue.Null),
    "op" to jstr(op.name),
    "at_round" to jnum(atRound),
    "at" to jnum(at),
    "changes" to SceneChange.listToJson(changes),
  )

  companion object {
    fun fromJson(value: JsonValue?): DirectorPatch? {
      val obj = value as? JsonValue.Obj ?: return null
      val period = Period.fromName(obj.strField("period")) ?: return null
      val op = ClockOp.fromName(obj.strField("op")) ?: ClockOp.ALIGN
      return DirectorPatch(
        period = period,
        noun = obj.strField("noun")?.takeIf { it.isNotBlank() },
        op = op,
        atRound = obj.intField("at_round") ?: 0,
        at = obj.longField("at") ?: 0L,
        changes = SceneChange.listFromJson(obj["changes"]),
      )
    }
  }
}

/** 时钟写入被拒绝。T5.5 验收要求「其他模块调用抛 PermissionError 或返回拒绝结果」。 */
class ClockPermissionError(message: String) : IllegalStateException(message)

/**
 * T5.5 —— 写权限隔离。
 *
 * 时钟状态的写方法(`ClockState.write`)只接受**事务执行器**这一个身份,而且必须已注册:
 * 执行器在构造时([TimeAuthority.init])注册一次,其余模块拿着任意身份来写都会撞上
 * [ClockPermissionError]。AI 侧连入口都没有 —— 它只能提建议,由导演转成事务。
 */
object ClockWrites {
  /** 事务执行器(T5.3)的身份常量;没有第二个合法值。 */
  const val EXECUTOR_OWNER = "director_tx_executor"

  private var registered: Boolean = false

  internal fun register(owner: String) {
    if (owner != EXECUTOR_OWNER) {
      throw ClockPermissionError("只有事务执行器能注册时钟写权限,拒绝:$owner")
    }
    registered = true
  }

  /** 单测专用:把注册状态清掉,避免测试之间互相影响。 */
  internal fun resetForTest() {
    registered = false
  }

  internal fun isRegistered(): Boolean = registered

  internal fun check(owner: String) {
    if (!registered) {
      throw ClockPermissionError("时钟写权限尚未授予事务执行器,拒绝写入(调用方:$owner)")
    }
    if (owner != EXECUTOR_OWNER) {
      throw ClockPermissionError("时钟只允许事务执行器写入,拒绝调用方:$owner")
    }
  }
}
