package com.reimei.intersect.search

import com.reimei.intersect.data.SublimeFuzzy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 混合搜索引擎:精确 + 模糊 + 向量三路并发,RFC 融合(Reciprocal Rank Fusion)排序。
 *
 * 架构定位:底层统一检索引擎,上层分离两个用例(UI 搜索、AI 上下文构建),
 * 两个用例都只依赖 [SearchEngine] 接口与本实现。
 *
 * 降级链路:向量路依赖 [EmbeddingEngine],它失败(未初始化/推理异常)时 embed() 返回 null,
 * 向量路退化为空列表,精确 + 模糊两路照常融合 —— 检索永远可用,只是少了语义召回。
 *
 * 并发与线程:候选行一次加载(IO),三路打分在 coroutineScope 里 async 并发(Default),
 * 向量路内部的 embed() 自行调度(分词/推理/池化见 [EmbeddingEngine])。
 */
class HybridSearchEngine(
  private val dao: VectorDao,
) : SearchEngine {

  /** 三路各自的候选上限:RRF 只看名次(rank),每路截断到前 N 不损失头部排序质量。 */
  private val perPathTopK = 30

  /** 三路各自排名的内部载体;融合后转成对外的 [SearchResult]。 */
  private data class RankedHit(
    val id: String,
    val content: String,
    val sourceType: String,
    val spans: List<MatchSpan>?, // 向量命中为 null(语义相似无字符级对应)
    val rank: Int, // 该路内的名次,从 1 起
  )

  /** 候选行 + 解析好的元数据:JSON 每行只解析一次,三路共享。 */
  private data class Candidate(
    val row: VectorEntity,
    val tags: JSONObject,
    val sourceType: String,
  )

  override suspend fun search(
    query: String,
    metadataFilters: Map<String, String>?,
  ): List<SearchResult> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    return coroutineScope {
      // 候选行:一次加载 + 过滤(IO);行数量级几千,BLOB 只反序列化一次
      val candidates = withContext(Dispatchers.IO) {
        dao.loadAll()
          .filter {
            // modelVersion 不一致的向量来自另一个向量空间,点积无意义,必须排除
            it.modelVersion == EmbeddingEngine.MODEL_VERSION
          }
          .mapNotNull { toCandidate(it) }
          .filter { matchesFilters(it, metadataFilters) }
      }
      if (candidates.isEmpty()) return@coroutineScope emptyList()

      // 三路并发:字面两路纯 CPU,向量路含推理(embed 内部自行调度),互不阻塞
      val exactRanked = async(Dispatchers.Default) { exactPath(q, candidates) }
      val fuzzyRanked = async(Dispatchers.Default) { fuzzyPath(q, candidates) }
      val vectorRanked = async(Dispatchers.Default) { vectorPath(q, candidates) }

      rrfMerge(exactRanked.await(), fuzzyRanked.await(), vectorRanked.await())
    }
  }

  // ---------- 三路 ----------

  /** 精确路:整串子串命中;名次按命中次数降序(多次出现视为更相关),同次数按 id 定序保证稳定。 */
  private fun exactPath(q: String, candidates: List<Candidate>): List<RankedHit> =
    candidates.mapNotNull { c ->
      val spans = LiteralMatchers.exactSpans(q, c.row.content) ?: return@mapNotNull null
      Triple(c, spans, spans.size)
    }
      .sortedWith(compareByDescending<Triple<Candidate, List<MatchSpan>, Int>> { it.third }.thenBy { it.first.row.id })
      .take(perPathTopK)
      .mapIndexed { i, (c, spans, _) ->
        RankedHit(c.row.id, c.row.content, c.sourceType, spans, rank = i + 1)
      }

  /** 模糊路:SublimeFuzzy 打分判定命中,名次按分数降序;高亮位置另取(fuzzySpans)。 */
  private fun fuzzyPath(q: String, candidates: List<Candidate>): List<RankedHit> =
    candidates.mapNotNull { c ->
      val s = fuzzyScoreOrNull(q, c.row.content) ?: return@mapNotNull null
      c to s
    }
      .sortedWith(compareByDescending<Pair<Candidate, Int>> { it.second }.thenBy { it.first.row.id })
      .take(perPathTopK)
      .mapIndexed { i, (c, _) ->
        RankedHit(
          c.row.id, c.row.content, c.sourceType,
          LiteralMatchers.fuzzySpans(q, c.row.content) ?: emptyList(),
          rank = i + 1,
        )
      }

  /**
   * 向量路:query 向量与每行向量的点积排序。
   *
   * 相似度数学:所有向量(行内 embedding 与 query)都已 L2 归一化(‖v‖=1,见 [EmbeddingEngine]),
   * 点积即余弦相似度:
   *
   *   cos(a, b) = (a·b) / (‖a‖·‖b‖) = Σ_j a_j·b_j      (‖a‖=‖b‖=1)
   *
   * 取 cos 降序前 perPathTopK。embed() 返回 null(引擎不可用/推理失败)时本路返回空列表,
   * 由 RRF 自动退化为纯字面融合。
   */
  private suspend fun vectorPath(q: String, candidates: List<Candidate>): List<RankedHit> {
    val queryVec = EmbeddingEngine.embed(q) ?: return emptyList()
    val scored = candidates.mapNotNull { c ->
      val v = runCatching { c.row.embedding.toFloatsLE() }.getOrNull() ?: return@mapNotNull null
      if (v.size != queryVec.size) return@mapNotNull null // 维度不匹配(数据损坏/换模型),跳过
      var dot = 0.0
      for (j in v.indices) dot += queryVec[j].toDouble() * v[j]
      c to dot
    }
      .sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.row.id })
      .take(perPathTopK)
    // 向量命中无字符级对应,spans 恒为 null
    return scored.mapIndexed { i, (c, _) ->
      RankedHit(c.row.id, c.row.content, c.sourceType, spans = null, rank = i + 1)
    }
  }

  // ---------- RRF 融合 ----------

  /**
   * Reciprocal Rank Fusion:
   *
   *   score(d) = Σ_r  1 / (k + rank_r(d))       (k = 60,rank 从 1 起)
   *
   * 只用名次不用原始分:精确的命中次数、模糊的 Sublime 分、向量的余弦值三者量纲不同,
   * 直接加权很难调;RRF 把每路都折算成 1/(k+rank) 的统一贡献,谁进过前排谁得分高。
   * k=60 是原论文推荐值:压平头部名次之间的差距,避免单路第一名独裁。
   * 同一条命中在多路出现时得分累加 —— 字面与语义双路都认,置信度自然更高。
   */
  private fun rrfMerge(vararg rankings: List<RankedHit>): List<SearchResult> {
    val k = 60
    val scores = HashMap<String, Double>()
    val best = HashMap<String, RankedHit>()
    for (list in rankings) {
      for (hit in list) {
        scores[hit.id] = (scores[hit.id] ?: 0.0) + 1.0 / (k + hit.rank)
        val prev = best[hit.id]
        // 展示信息优先取带高亮的字面命中;同为字面则取先入者
        if (prev == null || (prev.spans == null && hit.spans != null)) best[hit.id] = hit
      }
    }
    return scores.entries
      .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
      .mapNotNull { (id, score) ->
        val hit = best[id] ?: return@mapNotNull null
        SearchResult(
          id = hit.id,
          content = hit.content,
          sourceType = hit.sourceType,
          rrfScore = score,
          matchSpans = hit.spans,
        )
      }
  }

  /**
   * SublimeFuzzy 的打分封装:达到 [SublimeFuzzy.MATCH_THRESHOLD] 才算命中,
   * 否则返回 null(未命中或分数太松)。
   */
  private fun fuzzyScoreOrNull(query: String, text: String): Int? {
    val s = SublimeFuzzy.score(query, text) ?: return null
    return if (s >= SublimeFuzzy.MATCH_THRESHOLD) s else null
  }

  // ---------- 元数据 ----------

  /** 解析 metadata_tags JSON(损坏时按空对象处理,不能因脏数据崩溃)。 */
  private fun toCandidate(row: VectorEntity): Candidate? {
    val tags = try {
      JSONObject(row.metadataTags)
    } catch (_: Throwable) {
      JSONObject()
    }
    return Candidate(row, tags, tags.optString("source", "memory"))
  }

  /** 过滤语义:每个 key 的值必须全等于给定值(AND);任一 key 缺失或不等即排除。 */
  private fun matchesFilters(c: Candidate, filters: Map<String, String>?): Boolean {
    if (filters.isNullOrEmpty()) return true
    for ((key, value) in filters) {
      if (!c.tags.has(key) || c.tags.optString(key) != value) return false
    }
    return true
  }
}
