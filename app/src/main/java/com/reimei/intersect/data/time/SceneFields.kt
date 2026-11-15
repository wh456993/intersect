package com.reimei.intersect.data.time

/**
 * 导演模式能改的三个场景字段。
 *
 * **本任务冻结:只有这三项**(R6:不自行扩展)。要加第四项属于新任务,得先改这张表与
 * [SceneEditor] 的解析、注入行与单测。
 */
enum class SceneField(val label: String) {
  TIME("时间"),
  PLACE("地点"),
  WEATHER("天气");

  companion object {
    fun fromName(name: String?): SceneField? =
      entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
  }
}

/**
 * 一次场景字段变更:**旧值 → 新值**。
 *
 * 注入文本严格按 [TimeTemplates.DIRECTOR_CHANGE_LINE] 生成:
 * `<2026.5.6 8:22变更2026.5.7 9:40>`。空值显示成「未设定」,避免出现 `<>变更北京` 这种半截。
 */
data class SceneChange(val field: SceneField, val before: String, val after: String) {
  fun line(): String = TimeTemplates.DIRECTOR_CHANGE_LINE
    .replace("{before}", before)
    .replace("{after}", after)

  fun toJson(): JsonValue.Obj = jobj(
    "field" to jstr(field.name),
    "before" to jstr(before),
    "after" to jstr(after),
  )

  companion object {
    const val UNSET = "未设定"

    /** 空字段在变更行里的写法。 */
    fun displayOf(value: String?): String = value?.trim().orEmpty().ifBlank { UNSET }

    fun fromJson(value: JsonValue?): SceneChange? {
      val obj = value as? JsonValue.Obj ?: return null
      val field = SceneField.fromName(obj.strField("field")) ?: return null
      return SceneChange(
        field = field,
        before = obj.strField("before").orEmpty(),
        after = obj.strField("after").orEmpty(),
      )
    }

    fun listToJson(changes: List<SceneChange>): JsonValue = jarr(changes.map { it.toJson() })

    fun listFromJson(value: JsonValue?): List<SceneChange> =
      value.arrItems().mapNotNull { fromJson(it) }
  }
}

/**
 * 导演面板的三个输入框(**界面状态**,不落盘)。
 *
 * 落盘的是解析之后的结果([SceneEdit] → [ClockState]);草稿只在面板开着时存在,
 * 关掉面板就丢掉,不会以「半截输入」的形态进请求体。
 */
data class SceneDraft(
  val time: String = "",
  val place: String = "",
  val weather: String = "",
)

/**
 * 解析后的场景时刻:日期锚点 + 一天内的分钟数,外加可选的**纪年名与显式正负号**。
 *
 * 为什么纪年单独存:[ClockState.dateAnchor] 是纯天序号,年份本身来自公历换算;
 * 扮演非公元纪年(如「第三纪 302 年」)时,年份要按导演写的数值展示与解析,
 * 纪年名(`第三纪` / `罗历` ......)则纯粹是文本前缀。两者都不参与推进算法。
 */
data class SceneTime(
  val dateAnchor: Int,
  val minuteOfDay: Int,
  /** 纪年名(空串 = 公元,显示时不加前缀)。 */
  val eraName: String = "",
  /** 是否显式带 `+`(只影响显示;负数自带 `-`)。 */
  val plusSign: Boolean = false,
) {
  val period: Period get() = Period.fromMinute(minuteOfDay)

  /** 人读文本,与面板输入框一致:`2026.5.6 8:22` / `第三纪 +302.5.6 8:22`。 */
  val label: String get() = SceneCalendar.label(dateAnchor, minuteOfDay, eraName, plusSign)
}

/** 面板保存后交给 [TimeAuthority] 的编辑内容(**已解析、已校验**)。 */
data class SceneEdit(
  val dateAnchor: Int,
  val minuteOfDay: Int,
  val place: String,
  val weather: String,
  /** 纪年名(空串 = 公元)。 */
  val eraName: String = "",
  /** 是否显式带 `+`。 */
  val plusSign: Boolean = false,
) {
  val timeLabel: String get() = SceneCalendar.label(dateAnchor, minuteOfDay, eraName, plusSign)

  val period: Period get() = Period.fromMinute(minuteOfDay)
}

/**
 * 剧情日历:日期锚点 ↔ 人读日期。
 *
 * 为什么自己算:`ClockState.dateAnchor` 是从 0 起的天序号,面板与注入都要人读日期;
 * 而 minSdk 21 没有 `java.time`(本工程没开 desugaring),`java.util.Calendar` 又跟着系统时区走。
 * 于是这里用 Howard Hinnant 的 days_from_civil / civil_from_days —— 纯整数、无时区、无依赖,
 * 纪元固定在 [SceneConfig](配置,不是业务代码里的魔法数字)。
 *
 * **非公元纪年**(导演面板与基准时间共用):年份的写法是
 * `<纪年名><可选正负号><数值>`,例如 `第三纪 +302.5.6 8:22`、`-302.5.6 8:22`。
 * 数值本身就是公历年号(可以为负),所以「推进一天 / 跳一年」不需要额外换算;
 * 纪年名与 `+` 只决定**怎么显示**,不参与任何算法 —— 跨日 / 闰年 / 推进全部保持原样。
 */
object SceneCalendar {
  /** 纪元日(`date_anchor = 0` 对应的那一天)在「1970-01-01 起算的天序号」里的位置。 */
  private val EPOCH_DAYS: Int =
    daysFromCivil(SceneConfig.EPOCH_YEAR, SceneConfig.EPOCH_MONTH, SceneConfig.EPOCH_DAY)

  /**
   * 日期锚点 + 分钟数 → `2026.5.6 8:22`(月/日/时不补零,分钟补两位:`8:07`)。
   *
   * [eraName] 非空时拼在年份前面;[plusSign] 为真且年份非负时年份带 `+`
   * (负数自带 `-`,不再叠一个符号)。
   */
  fun label(
    dateAnchor: Int,
    minuteOfDay: Int,
    eraName: String = "",
    plusSign: Boolean = false,
  ): String {
    val (year, month, day) = civilOf(EPOCH_DAYS + dateAnchor)
    val minute = Period.normalize(minuteOfDay)
    val mm = minute % 60
    val yearText = if (plusSign && year >= 0) "+$year" else year.toString()
    // 纪年名与年份之间留一个空格:「第三纪 +302」而不是「第三纪+302」
    val era = eraName.trim()
    val head = if (era.isEmpty()) yearText else "$era $yearText"
    return "$head.$month.$day ${minute / 60}:${if (mm < 10) "0$mm" else mm.toString()}"
  }

  fun label(state: ClockState): String =
    label(state.dateAnchor, state.exactTime, state.eraName, state.eraPlusSign)

  /**
   * `2026.5.6 8:22` / `第三纪 +302.5.6 8:22` / `-302.5.6 8:22` → [SceneTime]。
   *
   * 认 `.` `/` `-` 三种日期分隔符与全角冒号;纪年名是年份前面的那一段文字(不能带数字,
   * 否则无法区分哪一位开始是年份)。格式不对**返回 null,不猜**(调用方据此拒绝,不写时钟)。
   */
  fun parse(text: String?): SceneTime? {
    // 中文输入法容易打出全角冒号 / 点 / 斜杠(用码点写,免得源码里两种字符看起来一样)
    val raw = text?.trim().orEmpty()
      .replace('\uFF1A', ':')
      .replace('\uFF0E', '.')
      .replace('\uFF0F', '/')
    if (raw.isEmpty()) return null
    val parts = raw.split(WHITESPACE)
    // 最后一段是时刻,前面全部拼回日期串:纪年名与年份之间可以有空格
    // (`第三纪 +302.5.6 8:22`),写成一串(`第三纪+302.5.6 8:22`)也认
    if (parts.size < 2) return null
    val timeToken = parts.last()
    val dateToken = parts.dropLast(1).joinToString("")
    if (dateToken.isEmpty()) return null
    // 年份的起点:第一个数字或正负号;它前面的都是纪年名
    val yearStart = dateToken.indexOfFirst { it.isDigit() || it == '+' || it == '-' }
    if (yearStart < 0) return null
    val eraName = dateToken.substring(0, yearStart).trim()
    if (eraName.length > SceneConfig.ERA_NAME_MAX_CHARS) return null
    if (eraName.any { it.isDigit() }) return null

    var rest = dateToken.substring(yearStart)
    var plusSign = false
    var negative = false
    if (rest.startsWith("+")) {
      plusSign = true
      rest = rest.substring(1)
    } else if (rest.startsWith("-")) {
      negative = true
      rest = rest.substring(1)
    }

    val dateParts = rest.split('.', '/', '-')
    if (dateParts.size != 3) return null
    val yearDigits = dateParts[0]
    if (yearDigits.isEmpty() || yearDigits.any { !it.isDigit() }) return null
    val yearValue = yearDigits.toIntOrNull() ?: return null
    val year = if (negative) -yearValue else yearValue
    val month = dateParts[1].toIntOrNull() ?: return null
    val day = dateParts[2].toIntOrNull() ?: return null
    if (year < SceneConfig.MIN_YEAR || year > SceneConfig.MAX_YEAR) return null
    if (month !in 1..12) return null
    if (day !in 1..daysInMonth(year, month)) return null

    val timeParts = timeToken.split(':')
    if (timeParts.size != 2) return null
    val hour = timeParts[0].toIntOrNull() ?: return null
    val minute = timeParts[1].toIntOrNull() ?: return null
    if (minute !in 0..59) return null
    val days = daysFromCivil(year, month, day) - EPOCH_DAYS
    // 24:00 归一到第二天 00:00(与 Period.normalize 同一口径)
    if (hour == 24 && minute == 0) return SceneTime(days + 1, 0, eraName, plusSign)
    if (hour !in 0..23) return null
    return SceneTime(days, hour * 60 + minute, eraName, plusSign)
  }

  /** 公历 → 1970-01-01 起算的天序号。 */
  fun daysFromCivil(year: Int, month: Int, day: Int): Int {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
  }

  /** 天序号 → 公历(年, 月, 日)。 */
  fun civilOf(days: Int): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple(if (m <= 2) y + 1 else y, m, d)
  }

  fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

  fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> 0
  }

  private val WHITESPACE = Regex("\\s+")
}

/** [SceneEditor.parse] 的结果:要么可以写时钟,要么带原因拒绝(拒绝不产生任何写操作)。 */
sealed class SceneEditResult {
  data class Ok(val edit: SceneEdit) : SceneEditResult()

  data class Reject(val reason: String, val echo: String) : SceneEditResult()
}

/**
 * 导演面板的**解析层**:三个输入框 → [SceneEdit],或一句「为什么不行」。
 *
 * 与 [DirectorParser] 同一套纪律:解析不碰状态、格式不对就拒绝(不猜、不兜底),
 * 真正落账交给 [TimeAuthority](唯一写入口)。字段只认 [SceneField] 那三项。
 */
object SceneEditor {
  /** 输入提示:公元历与纪年两种写法都给一个例子(纪年名不能带数字)。 */
  const val TIME_HINT = "时间要写成 2026.5.6 8:22 或 第三纪 +302.5.6 8:22"

  fun parse(draft: SceneDraft): SceneEditResult {
    val time = SceneCalendar.parse(draft.time)
      ?: return SceneEditResult.Reject(REJECT_TIME, "$TIME_HINT,这次没有写时钟")
    val place = draft.place.trim()
    val weather = draft.weather.trim()
    if (place.length > SceneConfig.FIELD_MAX_CHARS) {
      return SceneEditResult.Reject(REJECT_PLACE, "地点最多 ${SceneConfig.FIELD_MAX_CHARS} 个字,这次没有写时钟")
    }
    if (weather.length > SceneConfig.FIELD_MAX_CHARS) {
      return SceneEditResult.Reject(REJECT_WEATHER, "天气最多 ${SceneConfig.FIELD_MAX_CHARS} 个字,这次没有写时钟")
    }
    return SceneEditResult.Ok(
      SceneEdit(
        dateAnchor = time.dateAnchor,
        minuteOfDay = time.minuteOfDay,
        place = place,
        weather = weather,
        eraName = time.eraName,
        plusSign = time.plusSign,
      )
    )
  }

  /**
   * 当前状态与目标编辑之间的**差异清单**(注入行就是按这个顺序拼的:时间 → 地点 → 天气)。
   *
   * 没有差异时返回空列表 —— 调用方据此**不写时钟、不产生注入**(「没改就不重注入」)。
   */
  fun changesOf(current: ClockState, edit: SceneEdit): List<SceneChange> {
    val out = mutableListOf<SceneChange>()
    val beforeTime = SceneCalendar.label(current)
    if (beforeTime != edit.timeLabel) {
      out += SceneChange(SceneField.TIME, beforeTime, edit.timeLabel)
    }
    if (current.place.trim() != edit.place.trim()) {
      out += SceneChange(
        SceneField.PLACE,
        SceneChange.displayOf(current.place),
        SceneChange.displayOf(edit.place),
      )
    }
    if (current.weather.trim() != edit.weather.trim()) {
      out += SceneChange(
        SceneField.WEATHER,
        SceneChange.displayOf(current.weather),
        SceneChange.displayOf(edit.weather),
      )
    }
    return out
  }

  /** 面板打开的初始值:当前时间 / 地点 / 天气(时间带纪年名与显式正负号)。 */
  fun draftOf(state: ClockState): SceneDraft =
    SceneDraft(time = SceneCalendar.label(state), place = state.place, weather = state.weather)

  const val REJECT_TIME = "时间格式看不懂"
  const val REJECT_PLACE = "地点太长"
  const val REJECT_WEATHER = "天气太长"
}
