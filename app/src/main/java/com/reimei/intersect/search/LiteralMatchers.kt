package com.reimei.intersect.search

import com.reimei.intersect.data.SublimeFuzzy

/**
 * 两路字面匹配:精确(子串)与模糊(Sublime 风格子序列),产出 [MatchSpan] 供 UI 高亮。
 *
 * 精确匹配复用 String.indexOf(大小写不敏感);模糊匹配的判定复用 [SublimeFuzzy],
 * 本文件只补它没有暴露的「命中位置」——高亮只需要位置,不影响打分。
 */
internal object LiteralMatchers {

  /**
   * 精确命中:[query] 作为连续子串在 [text] 中的全部出现(大小写不敏感)。
   * 返回 null 表示未命中。出现区间互不重叠(每次从上一命中末尾继续找)。
   */
  fun exactSpans(query: String, text: String): List<MatchSpan>? {
    if (query.isEmpty()) return null
    val spans = ArrayList<MatchSpan>()
    var i = text.indexOf(query, 0, ignoreCase = true)
    while (i >= 0) {
      spans.add(MatchSpan(i, i + query.length))
      i = text.indexOf(query, i + query.length, ignoreCase = true)
    }
    return if (spans.isEmpty()) null else spans
  }

  /**
   * 模糊命中位置:[query] 的字符按顺序作为子序列命中 [text] 的位置,
   * 贪心取最早命中(与 [SublimeFuzzy.scoreFrom] 同策略),连续命中合并为一个 [MatchSpan]。
   *
   * 例:"药水" 在 "买了瓶药水" 命中 → [MatchSpan(3,5)]("药水" 连成一段);
   *     "药水" 在 "药 瓶 水" 命中 → [MatchSpan(0,1), MatchSpan(4,5)](两段孤立)。
   * 返回 null 表示无法按顺序命中。
   *
   * 注意:本函数只做定位,不做打分 —— 是否算「命中」由 [SublimeFuzzy.score]
   * 的阈值判定,两者解耦。
   */
  fun fuzzySpans(query: String, text: String): List<MatchSpan>? {
    if (query.isEmpty()) return null
    val positions = ArrayList<Int>(query.length)
    var cursor = 0
    for (ch in query) {
      var idx = cursor
      while (idx < text.length && !sameChar(text[idx], ch)) idx++
      if (idx >= text.length) return null
      positions.add(idx)
      cursor = idx + 1
    }
    // 连续位置合并为区间:[s,e) 每段 end 为最后命中位置 +1
    val spans = ArrayList<MatchSpan>()
    var start = positions[0]
    var end = start + 1
    for (k in 1 until positions.size) {
      if (positions[k] == end) {
        end++
      } else {
        spans.add(MatchSpan(start, end))
        start = positions[k]
        end = start + 1
      }
    }
    spans.add(MatchSpan(start, end))
    return spans
  }

  /** 与 SublimeFuzzy.sameChar 同语义:忽略大小写的字符比较。 */
  private fun sameChar(a: Char, b: Char): Boolean =
    a == b || a.lowercaseChar() == b.lowercaseChar()
}
