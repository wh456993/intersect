package com.reimei.intersect.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * UI 搜索结果一行:[SearchResult] 的界面形态。
 *
 * 与 [SearchResult] 的差别只有一处:[highlights] 是**归一化后**的高亮区间
 * (已排序、已合并、已钳制到 content 范围内),UI 拿到直接渲染,不必再防御脏区间;
 * 向量命中为空列表 —— 语义相似没有字符级对应,不高亮。
 */
data class UiSearchHit(
  val id: String,
  val content: String,
  val sourceType: String,
  val rrfScore: Double,
  val highlights: List<MatchSpan>,
)

/**
 * UI 搜索用例:上层两个用例之一(另一个见 [BuildAIContextUseCase])。
 *
 * 职责:把统一检索([SearchEngine])的结果转成 UI 可直接消费的形态 ——
 * matchSpans → 归一化高亮区间。检索的调度(三路并发、RRF、降级)全在引擎里,
 * 这里只做纯转换,不做任何 IO。
 *
 * **防抖约定**:UI 层调用方(ViewModel)必须对输入做 `debounce(300)` 后再进本用例,
 * 严禁逐键触发 —— 每次调用都可能带一次 ONNX 推理。
 */
class SearchChatUseCase(
  private val engine: SearchEngine,
) {

  suspend operator fun invoke(
    query: String,
    metadataFilters: Map<String, String>? = null,
  ): List<UiSearchHit> =
    engine.search(query, metadataFilters).map { it.toUiHit() }

  private fun SearchResult.toUiHit(): UiSearchHit = UiSearchHit(
    id = id,
    content = content,
    sourceType = sourceType,
    rrfScore = rrfScore,
    highlights = normalizeSpans(matchSpans, content.length),
  )

  companion object {
    /**
     * 高亮区间归一化,三步:
     * 1. 钳制:端点夹进 [0, length],防止数据损坏/越界区间把渲染带崩;
     * 2. 过滤:end <= start 的空区间丢弃;
     * 3. 合并:按 start 排序后,重叠或相邻(start <= 上一段 end)的区间并为一段。
     *
     * 输出保证:互不相交、按 start 升序、全部落在 content 内 ——
     * UI 顺序扫描一遍就能渲染(fuzzy 命中天然是「孤岛」段,合并后更紧凑)。
     */
    fun normalizeSpans(spans: List<MatchSpan>?, length: Int): List<MatchSpan> {
      if (spans.isNullOrEmpty() || length <= 0) return emptyList()
      val cleaned = spans
        .map { MatchSpan(it.start.coerceIn(0, length), it.end.coerceIn(0, length)) }
        .filter { it.end > it.start }
        .sortedBy { it.start }
      val merged = ArrayList<MatchSpan>(cleaned.size)
      for (s in cleaned) {
        val last = merged.lastOrNull()
        if (last != null && s.start <= last.end) {
          merged[merged.size - 1] = MatchSpan(last.start, maxOf(last.end, s.end))
        } else {
          merged.add(s)
        }
      }
      return merged
    }
  }
}

/**
 * 渲染一行高亮文本(Compose)。[UiSearchHit.highlights] 的区间上底色,其余原文照抄;
 * 向量命中 highlights 为空 → 原样返回。
 */
fun UiSearchHit.toAnnotatedString(
  highlightStyle: SpanStyle = SpanStyle(background = Color(0x66FFC107)),
): AnnotatedString = buildAnnotatedString {
  var cursor = 0
  for (span in highlights) {
    append(content.substring(cursor, span.start))
    withStyle(highlightStyle) { append(content.substring(span.start, span.end)) }
    cursor = span.end
  }
  if (cursor < content.length) append(content.substring(cursor))
}
