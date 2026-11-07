package com.reimei.intersect.data

/**
 * 标准 Base64(RFC 4648,带 `=` 补位)的纯 Kotlin 实现。
 *
 * 备份文件是一段 JSON 文本,密文、盐、IV 都要塞进去,所以需要 Base64。
 * 这里不用 `android.util.Base64` 也不用 `java.util.Base64`:前者在 JVM 单元测试里是空壳,
 * 后者要求 API 26(本应用 minSdk 21)。自己写一份最小的实现,加解密那层就能被普通单测覆盖。
 */
object Base64Codec {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** 每个 ASCII 字符对应的值;-1 表示不是合法的 Base64 字符。 */
  private val VALUES = IntArray(128) { -1 }.apply {
    ALPHABET.forEachIndexed { value, ch -> this[ch.code] = value }
  }

  fun encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index < bytes.size) {
      val b0 = bytes[index].toInt() and 0xFF
      val hasB1 = index + 1 < bytes.size
      val hasB2 = index + 2 < bytes.size
      val b1 = if (hasB1) bytes[index + 1].toInt() and 0xFF else 0
      val b2 = if (hasB2) bytes[index + 2].toInt() and 0xFF else 0
      val triple = (b0 shl 16) or (b1 shl 8) or b2
      out.append(ALPHABET[triple ushr 18 and 0x3F])
      out.append(ALPHABET[triple ushr 12 and 0x3F])
      out.append(if (hasB1) ALPHABET[triple ushr 6 and 0x3F] else '=')
      out.append(if (hasB2) ALPHABET[triple and 0x3F] else '=')
      index += 3
    }
    return out.toString()
  }

  /**
   * 解码。[text] 里的换行与空格会被忽略,补位符可以缺省。
   *
   * @throws IllegalArgumentException 出现非法字符或长度不合法
   */
  fun decode(text: String): ByteArray {
    val clean = StringBuilder(text.length)
    for (ch in text) {
      when {
        ch == '=' || ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t' -> Unit
        else -> clean.append(ch)
      }
    }
    if (clean.length % 4 == 1) throw IllegalArgumentException("Base64 长度不合法")
    val out = ByteArray(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    var count = 0
    for (ch in clean) {
      val value = if (ch.code < VALUES.size) VALUES[ch.code] else -1
      if (value < 0) throw IllegalArgumentException("Base64 含非法字符: $ch")
      buffer = (buffer shl 6) or value
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out[count++] = ((buffer ushr bits) and 0xFF).toByte()
        // 只留还没用掉的低位,避免 buffer 无限左移后被高位污染
        buffer = buffer and ((1 shl bits) - 1)
      }
    }
    return if (count == out.size) out else out.copyOf(count)
  }
}
