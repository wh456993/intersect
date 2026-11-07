package com.reimei.intersect.data

/**
 * Sublime Text 风格的模糊匹配:把 [pattern] 当作要在 [text] 里按顺序找到的一串字符,
 * 不要求它们紧挨着(子序列匹配),再按「是否紧邻、是否落在词首、是否跨驼峰」打分。
 *
 * 与 Sublime Text 选文件时用的算法同源:忽略大小写、字符按顺序命中即可,命中越紧实分越高。
 * 针对「在一条用户消息里找物品名称」这个场景做了两处调整:
 * 1. 不罚句首偏移 —— 物品名可以出现在消息的任何位置,不该因为前面有一大段话就被判低分;
 * 2. 显式扣「命中之间跳过的字符」分,并给跳过的字符总量设上限 —— 让「药 水」这种中间隔了
 *    空格/标点的写法仍然命中,而「药......水」这种散落在整句里的假命中被直接判为不命中。
 *
 * 取分时对每个可能的起点都试一遍([scoreFrom]),不只看第一个字符 —— 否则消息前半句先出现
 * 一个「药」、后半句才出现紧挨着的「药水」时,会因选错起点而漏掉真正紧实的那个命中。
 */
object SublimeFuzzy {

  /** 相邻命中(后一个命中的字符紧跟前一个命中)加分。 */
  private const val ADJACENCY_BONUS = 6

  /** 命中落在词首(开头或分隔符之后)加分。 */
  private const val SEPARATOR_BONUS = 4

  /** 命中跨驼峰边界(前一个小写、当前大写)加分。 */
  private const val CAMEL_BONUS = 6

  /** 两个命中之间每跳过 1 个字符的扣分。 */
  private const val GAP_PENALTY = -2

  /** 判定为「命中」的最低分:紧实命中得分非负,松散的命中会被扣到负分。 */
  const val MATCH_THRESHOLD = 0

  /**
   * [pattern] 在 [text] 中的最佳模糊匹配分;无法按顺序匹配完 [pattern] 的每个字符时返回 null。
   *
   * 分数越高代表命中越「紧实」:连续命中、词首命中、驼峰边界命中都会加分,
   * 命中之间被跳过的字符会扣分。
   */
  fun score(pattern: String, text: String): Int? {
    if (pattern.isEmpty()) return null
    if (pattern.length > text.length) return null

    // 跳过的字符总量不得超过这个数:名称多长就允许多松,再松就不是同一个东西了
    val gapBudget = pattern.length
    val first = pattern[0]
    var best: Int? = null
    for (start in text.indices) {
      if (!sameChar(text[start], first)) continue
      val s = scoreFrom(pattern, text, start, gapBudget) ?: continue
      if (best == null || s > best!!) best = s
    }
    return best
  }

  /** [pattern] 是否模糊命中 [text](得分不低于 [MATCH_THRESHOLD])。 */
  fun matches(pattern: String, text: String): Boolean {
    val s = score(pattern, text) ?: return false
    return s >= MATCH_THRESHOLD
  }

  /**
   * 从 [text] 的 [start] 位置起贪心匹配整段 [pattern] 的得分。
   *
   * 从固定起点出发,每一步都取最早能命中的位置 —— 这是 Sublime/fzf 同类做法里最稳的一步,
   * 越早命中留给后面的余量越大。跳过的字符超过 [gapBudget] 或匹配不完就返回 null。
   */
  private fun scoreFrom(pattern: String, text: String, start: Int, gapBudget: Int): Int? {
    var score = 0
    var prevMatch = -1
    var skipped = 0
    var pi = 0
    var ti = start
    while (ti < text.length) {
      if (pi >= pattern.length) break
      if (!sameChar(text[ti], pattern[pi])) {
        ti++
        continue
      }
      if (prevMatch >= 0) {
        val gap = ti - prevMatch - 1
        skipped += gap
        if (skipped > gapBudget) return null
        score += if (gap == 0) ADJACENCY_BONUS else gap * GAP_PENALTY
      }
      if (ti == 0 || isSeparator(text[ti - 1])) score += SEPARATOR_BONUS
      if (ti > 0 && text[ti - 1].isLowerCase() && text[ti].isUpperCase()) score += CAMEL_BONUS
      prevMatch = ti
      pi++
      ti++
    }
    if (pi < pattern.length) return null
    return score
  }

  /** 忽略大小写的单字符比较;中文等无大小写的字符退化为相等比较。 */
  private fun sameChar(a: Char, b: Char): Boolean =
    a == b || a.lowercaseChar() == b.lowercaseChar()

  /**
   * 这些字符之后出现的一个命中算「词首」:空格与中英文标点。
   *
   * 中文标点用 \u 转义写,不直接写字形 —— 全角标点和同形的 ASCII 标点在编辑器里难以分辨,
   * 转义能保证分隔符集合确实包含中文标点。
   */
  private fun isSeparator(c: Char): Boolean = when (c) {
    ' ', '\t', '\n', '_', '-', '/', '\\', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}',
    '\uFF0C', // ,
    '\u3002', // 。
    '\u3001', // 、
    '\uFF1B', // ;
    '\uFF1A', // :
    '\uFF01', // !
    '\uFF1F', // ?
    '\uFF08', // (
    '\uFF09', // )
    '\u3010', // 【
    '\u3011', // 】
    '\u300C', // 「
    '\u300D', // 」
    '\u300A', // 《
    '\u300B', // 》
    '\u2026', // ...
    '\u00B7' -> true // ·

    else -> false
  }
}
