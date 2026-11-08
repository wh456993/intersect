package com.reimei.intersect.data

/**
 * 本地 token 估算。
 *
 * 本工程不引分词器(各家模型的 BPE 词表都不一样,拿一份去估另一样只会更偏),
 * 这里用一条粗但稳定的规则估:中日韩文字按一字 ≈ 1 token、其余字符按 4 字符 ≈ 1 token、
 * 空白按 6 个 ≈ 1 token。用来判断「总结是不是超了目标长度」足够了 —— 判的是数量级,
 * 真正的账以服务商回的 usage 为准(那部分记在 Token 统计里)。
 */
object TokenEstimator {
  /** 估算一段文本有多少 token(结果为向上取整的整数)。 */
  fun estimate(text: String): Int {
    if (text.isEmpty()) return 0
    var units = 0.0
    for (ch in text) units += costOf(ch)
    val whole = units.toInt()
    return if (units > whole) whole + 1 else whole
  }

  /**
   * 把文本截到 [limit] token 以内。
   *
   * 优先收在一个句子的边界上(截断处若落在靠后的句末标点之后就切在那里),
   * 找不到合适的边界就按估算位置硬截 —— 宁可句子不完整,也不让总结把上下撑爆。
   */
  fun truncate(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (estimate(text) <= limit) return text
    val builder = StringBuilder(text.length)
    var used = 0.0
    for (ch in text) {
      val cost = costOf(ch)
      if (used + cost > limit) break
      used += cost
      builder.append(ch)
    }
    val cut = builder.lastIndexOfAny(SENTENCE_ENDS)
    val body = if (cut >= builder.length * 6 / 10) builder.substring(0, cut + 1) else builder.toString()
    return body.trim()
  }

  /** 句末标点:截断时尽量落在这些字符之后。 */
  private val SENTENCE_ENDS = charArrayOf('。', '!', '?', ';', '\n', '、', '.', '!', '?')

  /** 单个字符折多少 token。 */
  private fun costOf(ch: Char): Double = when {
    isCjk(ch) -> 1.0
    ch.isWhitespace() -> 1.0 / 6.0
    else -> 1.0 / 4.0
  }

  private fun isCjk(ch: Char): Boolean =
    ch.code in 0x4E00..0x9FFF || // 汉字
      ch.code in 0x3400..0x4DBF || // 扩展 A
      ch.code in 0x3000..0x303F || // 中文标点
      ch.code in 0xFF00..0xFFEF || // 全角字符
      ch.code in 0x3040..0x30FF // 日文假名
}
