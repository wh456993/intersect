package com.reimei.intersect.data.time

/**
 * T5.1 / T5.2 —— 导演指令的两种来源:**结构化指令**与**受限模板的自然语言旁白**。
 *
 * 一个 [DirectorCommand] 只是「想做什么」,真正改时钟要交给 [TimeAuthority](T5.3 唯一写入口)。
 * 解析器不碰状态,所以两种来源可以用同一批单测覆盖。
 */
sealed class DirectorCommand {
  abstract val op: ClockOp

  /** `/time align 黄昏`。[explicitDateAnchor] 非空表示导演显式指定了日期。 */
  data class Align(
    val period: Period,
    val noun: String? = null,
    val explicitDateAnchor: Int? = null,
  ) : DirectorCommand() {
    override val op: ClockOp get() = ClockOp.ALIGN
  }

  /** `/time advance 30`(分钟)或「过了半小时」这类受限模板。 */
  data class Advance(val minutes: Int) : DirectorCommand() {
    override val op: ClockOp get() = ClockOp.ADVANCE
  }

  /** `/time set 14:30 [day 3]`。 */
  data class SetTime(val minuteOfDay: Int, val explicitDateAnchor: Int? = null) : DirectorCommand() {
    override val op: ClockOp get() = ClockOp.SET
  }

  /** `/time pause` / `/time resume`。 */
  data class Pause(val paused: Boolean) : DirectorCommand() {
    override val op: ClockOp get() = if (paused) ClockOp.PAUSE else ClockOp.RESUME
  }

  /** `/time rate 2`。 */
  data class Rate(val rate: Double) : DirectorCommand() {
    override val op: ClockOp get() = ClockOp.RATE
  }

  /** 回显确认里用的「人话」,也顺便给 AI 侧当作**建议文本**(AI 只能建议,不能执行)。 */
  fun describe(): String = when (this) {
    is Align -> buildString {
      append("时间对齐到「")
      append(PeriodNouns.defaultNoun(period).ifBlank { period.name })
      append('」')
      explicitDateAnchor?.let { append(",日期锚点设为第 ").append(it + 1).append(" 天") }
    }

    is Advance -> "时间推进 $minutes 分钟"
    is SetTime -> buildString {
      append("时间设为 ")
      append(Period.clockLabel(minuteOfDay))
      explicitDateAnchor?.let { append(",日期锚点设为第 ").append(it + 1).append(" 天") }
    }

    is Pause -> if (paused) "时间暂停" else "时间恢复"
    is Rate -> "时间倍率设为 ${formatRate(rate)}"
  }

  /** 等价的 `/time` 指令文本(写进日志、或给用户复制)。 */
  fun toCommandText(): String = when (this) {
    is Align -> "/time align ${PeriodNouns.defaultNoun(period).ifBlank { period.name }}" +
      (explicitDateAnchor?.let { " day ${it + 1}" } ?: "")

    is Advance -> "/time advance $minutes"
    is SetTime -> "/time set ${Period.clockLabel(minuteOfDay)}" +
      (explicitDateAnchor?.let { " day ${it + 1}" } ?: "")

    is Pause -> if (paused) "/time pause" else "/time resume"
    is Rate -> "/time rate ${formatRate(rate)}"
  }

  companion object {
    fun formatRate(rate: Double): String =
      if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()
  }
}

/** 解析置信度。模板外输入一律 [LOW],低置信不写时钟(T5.2 验收)。 */
enum class ParseConfidence { HIGH, LOW }

sealed class DirectorParse {
  /** 解析成功。结构化指令恒为 [ParseConfidence.HIGH],自然语言命中受限模板才算 [ParseConfidence.HIGH]。 */
  data class Ok(
    val command: DirectorCommand,
    val confidence: ParseConfidence,
    val echo: String,
  ) : DirectorParse()

  /** 解析失败或低置信:**不写时钟**,只回一句为什么。 */
  data class Low(val reason: String, val echo: String) : DirectorParse()
}

/**
 * T5.1 结构化指令 + T5.2 自然语言(受限模板)。
 *
 * 结构化指令:`/time align|advance|set|pause|resume|rate ...`
 *
 * 自然语言**只认** [TimeTemplates] 里那几张模板 + 词表:
 * - 时段:`时间来到黄昏` / `到了黄昏` / `现在是黄昏` ...
 * - 推进:`过了30分钟` / `三小时后` / `又过了一刻钟` ...
 *
 * 模板之外(比如「时间好像变慢了」「天快亮了吧」)一律 [DirectorParse.Low] —— 宁可不动,
 * 也不猜(R6:不得自行增加降级路径)。
 */
object DirectorParser {
  /** 归一:去首尾空白、全角冒号转半角、砍掉句末标点。 */
  fun normalize(raw: String): String =
    raw.trim()
      .replace(':', ':')
      .trimEnd('。', '!', '!', '.', ',', ',', '~', '~', '\n', ' ')

  fun parse(raw: String): DirectorParse {
    val text = normalize(raw)
    if (text.isEmpty()) return DirectorParse.Low("空指令", ECHO_LOW)
    return if (text.startsWith(CMD_PREFIX)) parseStructured(text) else parseNatural(text)
  }

  /** T5.1 —— `/time ...`。语法固定,不认识就是 Low。 */
  private fun parseStructured(text: String): DirectorParse {
    val body = text.removePrefix(CMD_PREFIX).trim()
    if (body.isEmpty()) return DirectorParse.Low("指令缺少动作", ECHO_LOW)
    val parts = body.split(Regex("\\s+"))
    val action = parts[0].lowercase()
    val rest = parts.drop(1)
    return when (action) {
      "align" -> {
        val (args, day) = splitDay(rest)
        val word = args.joinToString("").trim()
        if (word.isEmpty()) return DirectorParse.Low("align 缺少时段", ECHO_LOW)
        val period = periodOf(word) ?: return DirectorParse.Low("不认识的时段:$word", ECHO_LOW)
        val command = DirectorCommand.Align(period = period, noun = word, explicitDateAnchor = day)
        DirectorParse.Ok(command, ParseConfidence.HIGH, echoOk(command))
      }

      "advance" -> {
        val minutes = parseDuration(rest.joinToString("").trim())
          ?: return DirectorParse.Low("advance 缺少时长", ECHO_LOW)
        if (minutes <= 0) return DirectorParse.Low("推进量必须为正", ECHO_LOW)
        val command = DirectorCommand.Advance(minutes)
        DirectorParse.Ok(command, ParseConfidence.HIGH, echoOk(command))
      }

      "set" -> {
        val (args, day) = splitDay(rest)
        val minute = Period.parseClock(args.joinToString("").trim())
          ?: return DirectorParse.Low("set 需要 HH:mm", ECHO_LOW)
        val command = DirectorCommand.SetTime(minute, day)
        DirectorParse.Ok(command, ParseConfidence.HIGH, echoOk(command))
      }

      "pause" -> DirectorParse.Ok(DirectorCommand.Pause(true), ParseConfidence.HIGH, echoOk(DirectorCommand.Pause(true)))
      "resume" -> DirectorParse.Ok(DirectorCommand.Pause(false), ParseConfidence.HIGH, echoOk(DirectorCommand.Pause(false)))
      "rate" -> {
        val rate = rest.joinToString("").trim().toDoubleOrNull()
          ?: return DirectorParse.Low("rate 需要数字", ECHO_LOW)
        if (rate <= 0.0) return DirectorParse.Low("倍率必须大于 0", ECHO_LOW)
        val command = DirectorCommand.Rate(rate)
        DirectorParse.Ok(command, ParseConfidence.HIGH, echoOk(command))
      }

      else -> DirectorParse.Low("不认识的指令:$action", ECHO_LOW)
    }
  }

  /** T5.2 —— 自然语言:只认冻结模板。 */
  private fun parseNatural(text: String): DirectorParse {
    matchAlign(text)?.let { (period, word) ->
      val command = DirectorCommand.Align(period = period, noun = word)
      return DirectorParse.Ok(command, ParseConfidence.HIGH, echoOk(command))
    }
    matchAdvance(text)?.let { minutes ->
      if (minutes <= 0) return DirectorParse.Low("推进量必须为正", ECHO_LOW)
      val command = DirectorCommand.Advance(minutes)
      return DirectorParse.Ok(command, ParseConfidence.HIGH, echoOk(command))
    }
    return DirectorParse.Low("模板外输入,时钟未改动", ECHO_LOW)
  }

  private fun matchAlign(text: String): Pair<Period, String>? {
    TimeTemplates.PERIOD_ALIASES.forEach { (word, period) ->
      TimeTemplates.ALIGN_TEMPLATES.forEach { template ->
        if (text == template.replace("{noun}", word)) return period to word
      }
    }
    return null
  }

  private fun matchAdvance(text: String): Int? {
    TimeTemplates.ADVANCE_TEMPLATES.forEach { template ->
      val match = advanceRegex(template).find(text) ?: return@forEach
      val amountText = match.groupValues[1]
      val unitText = match.groupValues[2]
      val amount = parseAmount(amountText) ?: return@forEach
      val unit = TimeTemplates.DURATION_UNITS[unitText] ?: return@forEach
      return amount * unit
    }
    return null
  }

  /**
   * 把模板编译成正则:`{amount}` / `{unit}` 换成分组,**其余字符逐个转义**(模板是中文,
   * 但里面可能有 `:` 这类字符,不能让它当正则元字符解释)。
   */
  private fun advanceRegex(template: String): Regex {
    val marker = template.replace("{amount}", AMOUNT_MARK.toString()).replace("{unit}", UNIT_MARK.toString())
    val sb = StringBuilder()
    marker.forEach { c ->
      when (c) {
        AMOUNT_MARK -> sb.append(AMOUNT_PATTERN)
        UNIT_MARK -> sb.append(unitPattern())
        else -> sb.append(Regex.escape(c.toString()))
      }
    }
    return Regex("^$sb$")
  }

  /** 时长单位的正则分组,长的优先(`刻钟` 要排在 `刻` 前面)。 */
  private fun unitPattern(): String =
    "(" + TimeTemplates.DURATION_UNITS.keys.sortedByDescending { it.length }.joinToString("|") + ")"

  private const val AMOUNT_MARK = '\u0001'
  private const val UNIT_MARK = '\u0002'
  private const val AMOUNT_PATTERN = "([0-9]{1,3}|[零一二三四五六七八九十百两]{1,4})"

  /** 数字:阿拉伯数字或中文数字。 */
  fun parseAmount(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    if (text.isEmpty()) return null
    var total = 0
    var current = 0
    text.forEach { c ->
      when (c) {
        '十' -> {
          total += (if (current == 0) 1 else current) * 10
          current = 0
        }

        '百' -> {
          total += (if (current == 0) 1 else current) * 100
          current = 0
        }

        else -> {
          val digit = TimeTemplates.CN_DIGITS[c] ?: return null
          current = digit
        }
      }
    }
    return total + current
  }

  /** 时长文本(`30分钟` / `三小时` / `一刻钟`)→ 分钟数。 */
  fun parseDuration(text: String): Int? {
    if (text.isEmpty()) return null
    text.toIntOrNull()?.let { return it }
    val units = TimeTemplates.DURATION_UNITS.keys.sortedByDescending { it.length }
    units.forEach { unit ->
      if (text.endsWith(unit)) {
        val amountText = text.removeSuffix(unit).trim()
        if (amountText.isEmpty()) return null
        val amount = parseAmount(amountText) ?: return null
        return amount * (TimeTemplates.DURATION_UNITS[unit] ?: 1)
      }
    }
    return null
  }

  /** 时段词:`黄昏` / `DUSK` / `傍晚` 都认;`name+day N` 由 [splitDay] 先摘掉。 */
  private fun periodOf(word: String): Period? =
    Period.fromName(word) ?: TimeTemplates.PERIOD_ALIASES[word] ?: PeriodNouns.periodOfNoun(word)

  /** 摘出 `day N`(N 是**人读的**第几天,转成 0 起的锚点)。 */
  private fun splitDay(tokens: List<String>): Pair<List<String>, Int?> {
    val index = tokens.indexOfFirst { it.equals("day", ignoreCase = true) }
    if (index < 0 || index + 1 >= tokens.size) return tokens to null
    val day = tokens[index + 1].toIntOrNull() ?: return tokens to null
    if (day < 1) return tokens to null
    return tokens.take(index) to (day - 1)
  }

  private fun echoOk(command: DirectorCommand): String = command.describe() + "(已确认)"

  const val CMD_PREFIX = "/time"

  const val ECHO_LOW = "没有改动时钟:请用 /time align|advance|set,或换一句模板内的说法"
}
