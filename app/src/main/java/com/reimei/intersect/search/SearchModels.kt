package com.reimei.intersect.search

/**
 * 混合检索的公共模型:上层两个用例(UI 搜索、AI 上下文构建)只依赖这些类型与 [SearchEngine] 接口。
 */

/** 命中高亮区间:content 的 [start, end) 半开区间,与 String.substring 语义一致。 */
data class MatchSpan(val start: Int, val end: Int)

/**
 * 一条检索结果。
 *
 * @param id 命中条目的稳定 id(即 [VectorEntity.id])
 * @param content 命中原文
 * @param sourceType 来源类型:取 metadata_tags JSON 里的 "source" 字段(如 "chat"/"memory"),
 *   缺省 "memory";由回填管线写入,检索层只透传
 * @param rrfScore RRF 融合得分(见 HybridSearchEngine.rrfMerge 的公式),越大越相关
 * @param matchSpans 字面命中(精确/模糊)的高亮区间列表;**向量命中为 null** ——
 *   语义相似没有字符级对应,UI 不应该高亮
 */
data class SearchResult(
  val id: String,
  val content: String,
  val sourceType: String,
  val rrfScore: Double,
  val matchSpans: List<MatchSpan>?,
)

/** 检索引擎接口:两个用例共用的统一入口。 */
interface SearchEngine {
  /**
   * 混合检索:精确 + 模糊 + 向量三路并发后 RRF 融合,按 rrfScore 降序返回。
   *
   * @param query 用户输入(空白串直接返回空结果)
   * @param metadataFilters 元数据过滤,如 {"favorability":"72"}:metadata_tags JSON 里
   *   对应 key 的值必须全等于给定值(AND 语义);null 表示不过滤
   */
  suspend fun search(query: String, metadataFilters: Map<String, String>? = null): List<SearchResult>
}
