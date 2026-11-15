package com.reimei.intersect.data.time

import kotlin.math.abs

/**
 * 极简 JSON 值模型 —— 时间子系统专用的**纯 Kotlin** 序列化层。
 *
 * 为什么不用 `org.json`:本工程的单测跑在 JVM 上,`android.jar` 里的 `org.json` 是空实现
 * (方法体只有 `throw new RuntimeException("Stub!")`),用它写的序列化逻辑一行也测不到(T1.4 的
 * 契约校验、T2.1 的状态往返、T2.6 的快照回滚都必须有单测)。所以这里自带一套最小实现。
 *
 * 只支持时间子系统用到的 JSON 子集:对象、数组、字符串、数字、布尔、null。没有注释、
 * 没有尾逗号、不支持单引号。
 *
 * 数字用 [JsonValue.Num.raw] 保存**原始文本**:`2` 与 `2.0` 不会被悄悄格式化,
 * 倍率(`rate`)这类小数的往返序列化才可能逐字节相等。
 */
sealed class JsonValue {
  abstract fun writeTo(out: StringBuilder)

  /** 序列化成一行 JSON 文本。 */
  fun encode(): String {
    val out = StringBuilder()
    writeTo(out)
    return out.toString()
  }

  data class Str(val value: String) : JsonValue() {
    override fun writeTo(out: StringBuilder) = quote(value, out)
  }

  data class Num(val raw: String) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
      out.append(raw)
    }

    fun asLong(): Long = raw.toLongOrNull() ?: (raw.toDoubleOrNull()?.toLong() ?: 0L)

    fun asInt(): Int = asLong().toInt()

    fun asDouble(): Double = raw.toDoubleOrNull() ?: 0.0

    companion object {
      fun of(value: Long): Num = Num(value.toString())

      fun of(value: Int): Num = Num(value.toString())

      /**
       * 小数保留一位 `.0` 的写法(整数倍率也写成 `2.0`):这样 [encode] 后重新解析回来
       * 与原值完全相等,单测可以直接比对象。
       */
      fun of(value: Double): Num =
        if (value % 1.0 == 0.0 && !value.isInfinite() && abs(value) < 1e15) Num("${value.toLong()}.0")
        else Num(value.toString())
    }
  }

  data class Bool(val value: Boolean) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
      out.append(if (value) "true" else "false")
    }
  }

  data object Null : JsonValue() {
    override fun writeTo(out: StringBuilder) {
      out.append("null")
    }
  }

  data class Obj(val fields: Map<String, JsonValue>) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
      out.append('{')
      var first = true
      fields.forEach { (key, value) ->
        if (!first) out.append(',')
        first = false
        quote(key, out)
        out.append(':')
        value.writeTo(out)
      }
      out.append('}')
    }

    operator fun get(name: String): JsonValue? = fields[name]

    fun keys(): Set<String> = fields.keys
  }

  data class Arr(val items: List<JsonValue>) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
      out.append('[')
      items.forEachIndexed { index, item ->
        if (index > 0) out.append(',')
        item.writeTo(out)
      }
      out.append(']')
    }
  }
}

/** 构造函数:`jobj("a" to jstr("b"))`;值为 null 的键**整体省略**(契约里可选字段就是这么写的)。 */
fun jobj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj {
  val map = LinkedHashMap<String, JsonValue>()
  pairs.forEach { (key, value) -> if (value != null) map[key] = value }
  return JsonValue.Obj(map)
}

fun jarr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

fun jarrOf(vararg items: JsonValue): JsonValue.Arr = JsonValue.Arr(items.toList())

fun jstr(value: String): JsonValue.Str = JsonValue.Str(value)

fun jnum(value: Int): JsonValue.Num = JsonValue.Num.of(value)

fun jnum(value: Long): JsonValue.Num = JsonValue.Num.of(value)

fun jnum(value: Double): JsonValue.Num = JsonValue.Num.of(value)

fun jbool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

fun JsonValue?.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue?.asArr(): JsonValue.Arr? = this as? JsonValue.Arr

/** 取对象字段;当前值不是对象时一律 null(读坏数据不该抛)。 */
fun JsonValue?.objField(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

fun JsonValue?.strField(name: String): String? = (objField(name) as? JsonValue.Str)?.value

fun JsonValue?.intField(name: String): Int? = (objField(name) as? JsonValue.Num)?.asInt()

fun JsonValue?.longField(name: String): Long? = (objField(name) as? JsonValue.Num)?.asLong()

fun JsonValue?.doubleField(name: String): Double? = (objField(name) as? JsonValue.Num)?.asDouble()

fun JsonValue?.boolField(name: String): Boolean? = (objField(name) as? JsonValue.Bool)?.value

fun JsonValue?.arrItems(): List<JsonValue> = (this as? JsonValue.Arr)?.items.orEmpty()

/** 解析入口。坏文本一律抛 [IllegalArgumentException](带出错位置),调用方自己决定降级方式。 */
object Json {
  fun parse(text: String): JsonValue = JsonParser(text).parse()
}

private fun quote(value: String, out: StringBuilder) {
  out.append('"')
  value.forEach { c ->
    when {
      c == '"' -> out.append("\\\"")
      c == '\\' -> out.append("\\\\")
      c == '\n' -> out.append("\\n")
      c == '\r' -> out.append("\\r")
      c == '\t' -> out.append("\\t")
      c == '\b' -> out.append("\\b")
      c == '\u000C' -> out.append("\\f")
      c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
      else -> out.append(c)
    }
  }
  out.append('"')
}

private class JsonParser(private val text: String) {
  private var pos = 0

  fun parse(): JsonValue {
    skipWhitespace()
    val value = readValue()
    skipWhitespace()
    if (pos != text.length) fail("多余的内容")
    return value
  }

  private fun readValue(): JsonValue {
    if (pos >= text.length) fail("内容意外结束")
    return when (val c = text[pos]) {
      '{' -> readObject()
      '[' -> readArray()
      '"' -> JsonValue.Str(readString())
      't' -> {
        expect("true")
        JsonValue.Bool(true)
      }

      'f' -> {
        expect("false")
        JsonValue.Bool(false)
      }

      'n' -> {
        expect("null")
        JsonValue.Null
      }

      else -> if (c == '-' || c.isDigit()) readNumber() else fail("非法字符 '$c'")
    }
  }

  private fun readObject(): JsonValue.Obj {
    pos++ // '{'
    val fields = LinkedHashMap<String, JsonValue>()
    skipWhitespace()
    if (peek() == '}') {
      pos++
      return JsonValue.Obj(fields)
    }
    while (true) {
      skipWhitespace()
      if (peek() != '"') fail("对象的键必须是字符串")
      val key = readString()
      skipWhitespace()
      if (peek() != ':') fail("缺少 ':'")
      pos++
      skipWhitespace()
      fields[key] = readValue()
      skipWhitespace()
      when (peek()) {
        ',' -> pos++
        '}' -> {
          pos++
          return JsonValue.Obj(fields)
        }

        else -> fail("对象里缺少 ',' 或 '}'")
      }
    }
  }

  private fun readArray(): JsonValue.Arr {
    pos++ // '['
    val items = mutableListOf<JsonValue>()
    skipWhitespace()
    if (peek() == ']') {
      pos++
      return JsonValue.Arr(items)
    }
    while (true) {
      skipWhitespace()
      items.add(readValue())
      skipWhitespace()
      when (peek()) {
        ',' -> pos++
        ']' -> {
          pos++
          return JsonValue.Arr(items)
        }

        else -> fail("数组里缺少 ',' 或 ']'")
      }
    }
  }

  private fun readString(): String {
    pos++ // '"'
    val sb = StringBuilder()
    while (pos < text.length) {
      when (val c = text[pos++]) {
        '"' -> return sb.toString()
        '\\' -> {
          if (pos >= text.length) fail("转义符后内容结束")
          when (val esc = text[pos++]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
              if (pos + 4 > text.length) fail("\\u 转义不完整")
              val hex = text.substring(pos, pos + 4)
              pos += 4
              val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制")
              sb.append(code.toChar())
            }

            else -> fail("不认识的转义 '\\$esc'")
          }
        }

        else -> sb.append(c)
      }
    }
    fail("字符串没有结束引号")
  }

  private fun readNumber(): JsonValue.Num {
    val start = pos
    if (peek() == '-') pos++
    while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) {
      // '-' 只允许出现在开头与指数部分,靠下面的 toDoubleOrNull 兜底
      if ((text[pos] == '-' || text[pos] == '+') && pos > start && text[pos - 1] != 'e' && text[pos - 1] != 'E') break
      pos++
    }
    val raw = text.substring(start, pos)
    if (raw.isEmpty() || raw.toDoubleOrNull() == null) fail("非法数字 '$raw'")
    return JsonValue.Num(raw)
  }

  private fun expect(literal: String) {
    if (!text.startsWith(literal, pos)) fail("期望 '$literal'")
    pos += literal.length
  }

  private fun peek(): Char? = if (pos < text.length) text[pos] else null

  private fun skipWhitespace() {
    while (pos < text.length && text[pos].isWhitespace()) pos++
  }

  private fun fail(message: String): Nothing =
    throw IllegalArgumentException("JSON 解析失败(位置 $pos):$message")
}
