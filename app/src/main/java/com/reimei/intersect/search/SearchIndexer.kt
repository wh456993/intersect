package com.reimei.intersect.search

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.reimei.intersect.data.ChatStore
import com.reimei.intersect.data.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后台索引回填管线:把本地语料(聊天消息 + 即时记忆总结)灌进 vectors 表。
 *
 * 职责三件事:
 * 1. **快照**([snapshot]):从 [ChatStore]/[MemoryStore] 收出当前全部可检索文档;
 * 2. **回填**([backfill]):增量向量化入库 —— 内容没变且已有向量的行跳过,变了的重算,
 *    快照里已消失的行删除;
 * 3. **迁移**([ensureModelVersion]):启动时比对 [EmbeddingEngine.MODEL_VERSION],
 *    不符就清空重算 —— 旧向量与新模型不在同一向量空间,留着只会污染排序。
 *
 * 降级红线:ONNX 引擎不可用时文档**照样入库(空向量)** —— 字面检索(精确/模糊)读的是
 * 行里的 content,不受向量缺失影响;向量路按维度自动跳过空向量([HybridSearchEngine])。
 *
 * 线程:一切 IO/计算都在 [Dispatchers.IO](本文件的 scope 与 backfill 内部),
 * embed 的调度见 [EmbeddingEngine]。
 */
object SearchIndexer {

  private const val TAG = "SearchIndexer"

  /** 周期回填的唯一任务名(KEEP 语义:重复调用不叠加)。 */
  private const val UNIQUE_PERIODIC = "hybrid_search_backfill"

  /** 全量重算的唯一任务名:模型版本迁移后立即补跑一次。 */
  private const val UNIQUE_ONCE = "hybrid_search_backfill_now"

  /** 分页读既有行的页大小:判「行有没有变」只需要 content/是否带向量,BLOB 不必全量驻留内存。 */
  private const val PAGE = 100

  /** 批量写库的批大小:攒一批 upsert,减少事务次数。 */
  private const val BATCH = 32

  /** 启动检查用的常驻 scope:只跑一次版本比对 + 排程,轻量短命任务。 */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** 一条待索引文档:id/原文/来源类型/元数据标签(直接写进 metadata_tags JSON)。 */
  private data class IndexDoc(
    val id: String,
    val content: String,
    val sourceType: String,
    val tags: JSONObject,
  )

  /** 既有行的判变摘要:内容 + 模型版本 + 是否已带向量。 */
  private data class ExistingRow(val content: String, val modelVersion: Int, val hasEmbedding: Boolean)

  // ---------- 启动入口 ----------

  /**
   * App 启动入口(由 MainActivity.onCreate 调用):
   * 先做模型版本迁移检查([ensureModelVersion]),再把回填任务排进 [WorkManager]。
   * 全程 try/catch —— 检索是旁路功能,任何失败都不能影响 App 启动。
   */
  fun onAppStart(context: Context) {
    val app = context.applicationContext
    scope.launch {
      val cleared = runCatching { ensureModelVersion(app) }
        .onFailure { Log.w(TAG, "model version check failed", it) }
        .getOrDefault(false)
      runCatching {
        // 迁移清空过 → 先补一次全量;周期任务两种情况都排(KEEP,不叠加)
        if (cleared) scheduleOnce(app)
        schedulePeriodic(app)
      }.onFailure { Log.w(TAG, "schedule failed", it) }
    }
  }

  /**
   * 模型版本迁移:盘上向量的最大版本与当前 [EmbeddingEngine.MODEL_VERSION] 不一致
   * (含「换模型后第一次启动」)时 **清空全表**,返回 true 表示需要全量重算。
   *
   * 为什么要清空:向量的相似度只在同一模型的向量空间里有意义,新旧模型产出的 384 维
   * 向量「长得很像」但不可比 —— 混在一起排序会静默出错,比搜不到更糟。
   * 空表(null)是正常首启:不清空,直接等回填。
   */
  suspend fun ensureModelVersion(context: Context): Boolean {
    val dao = VectorDb.getInstance(context.applicationContext).vectors()
    val onDisk = dao.maxModelVersion()
    if (onDisk != null && onDisk != EmbeddingEngine.MODEL_VERSION) {
      dao.clearAll()
      Log.i(TAG, "model version changed: onDisk=$onDisk current=${EmbeddingEngine.MODEL_VERSION} → cleared")
      return true
    }
    return false
  }

  // ---------- WorkManager 排程 ----------

  /**
   * 回填约束:**充电中 + 息屏**。
   *
   * WorkManager 没有「屏幕熄灭」约束,业界标准近似是 `setRequiresDeviceIdle`(设备空闲,
   * 即熄屏静置后的 Doze idle 态,夜间充电时通常满足)。该约束仅 API 23+ 生效,
   * Lollipop 上退化为只约束充电。
   */
  private fun constraints(): Constraints =
    Constraints.Builder()
      .setRequiresCharging(true)
      .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setRequiresDeviceIdle(true) }
      .build()

  /** 周期回填:每天一次(充电 + 息屏时),保持向量索引跟上新增的聊天与记忆。 */
  fun schedulePeriodic(context: Context) {
    val request = PeriodicWorkRequestBuilder<IndexBackfillWorker>(24, TimeUnit.HOURS)
      .setConstraints(constraints())
      .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
      .build()
    WorkManager.getInstance(context.applicationContext)
      .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  /** 单次全量重算:模型版本迁移后立刻补跑(同样等充电 + 息屏,不抢前台资源)。 */
  fun scheduleOnce(context: Context) {
    val request = OneTimeWorkRequestBuilder<IndexBackfillWorker>()
      .setConstraints(constraints())
      .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
      .build()
    WorkManager.getInstance(context.applicationContext)
      .enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, request)
  }

  // ---------- 快照 ----------

  /**
   * 当前全部可检索文档:
   * - 每条**聊天消息**一条(id=`chat:{friendId}:{消息下标}`,来源 "chat");
   * - 每条**即时记忆总结**一条(id=`mem:{friendId}:{记忆id}`,来源 "memory")。
   *
   * id 用来源数据的稳定标识:总结自带 id;消息没有 id,只能用下标 —— 消息删除/回滚会让
   * 下标移位,靠 [backfill] 的「内容变了就重算 + 快照外的行删除」兜住,不会留下错位旧行。
   * metadata_tags 统一带 `source` 与 `friendId`,上层可按需过滤(如 {"source":"memory"})。
   */
  private fun snapshot(context: Context): List<IndexDoc> {
    val out = ArrayList<IndexDoc>()
    val chats = ChatStore.load(context) ?: return out
    for (chat in chats) {
      val friendId = chat.friend.id
      chat.msgs.forEachIndexed { index, msg ->
        val text = msg.text.trim()
        if (text.isEmpty()) return@forEachIndexed
        out.add(
          IndexDoc(
            id = "chat:$friendId:$index",
            content = text,
            sourceType = "chat",
            tags = JSONObject().put("source", "chat").put("friendId", friendId),
          )
        )
      }
      val summaries = MemoryStore.load(context, friendId).summaries
      for (memory in summaries) {
        val text = memory.text.trim()
        if (text.isEmpty()) continue
        out.add(
          IndexDoc(
            id = "mem:$friendId:${memory.id}",
            content = text,
            sourceType = "memory",
            tags = JSONObject().put("source", "memory").put("friendId", friendId),
          )
        )
      }
    }
    return out
  }

  // ---------- 回填 ----------

  /**
   * 增量回填一趟([IndexBackfillWorker] 的 doWork 主体):
   *
   * 1. 分页读既有行,记下「id → (内容, 版本, 是否有向量)」;
   * 2. 对快照里每条文档:内容没变、版本一致、且已有向量 → **跳过**(不重算,省内存省电);
   *    否则向量化后 REPLACE 写入;引擎不可用时写入**空向量**,内容照常入库;
   * 3. 快照里已不存在的行(消息被删/回滚/记忆清空)整批删除,防止搜出已消失的内容。
   *
   * 幂等:随时可重跑,重复执行收敛到同一状态。
   */
  suspend fun backfill(context: Context) = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val dao = VectorDb.getInstance(app).vectors()

    val existing = HashMap<String, ExistingRow>()
    var offset = 0
    while (true) {
      val page = dao.loadBatch(PAGE, offset)
      if (page.isEmpty()) break
      for (row in page) {
        existing[row.id] = ExistingRow(row.content, row.modelVersion, row.embedding.isNotEmpty())
      }
      offset += page.size
    }

    val docs = snapshot(app)
    val docIds = HashSet<String>(docs.size)
    for (doc in docs) docIds.add(doc.id)

    // 引擎初始化:失败只置 isAvailable=false(见 EmbeddingEngine),embed 返回 null 走降级
    EmbeddingEngine.init(app)

    var embedded = 0
    val batch = ArrayList<VectorEntity>(BATCH)
    for (doc in docs) {
      val prev = existing[doc.id]
      val unchanged = prev != null && prev.hasEmbedding &&
        prev.content == doc.content && prev.modelVersion == EmbeddingEngine.MODEL_VERSION
      if (unchanged) continue

      val vec = EmbeddingEngine.embed(doc.content)
      if (vec != null) embedded++
      batch.add(
        VectorEntity(
          id = doc.id,
          content = doc.content,
          // 降级:空向量。字面路读 content 不受影响;向量路按维度(0≠384)自动跳过
          embedding = vec?.toBytesLE() ?: ByteArray(0),
          modelVersion = EmbeddingEngine.MODEL_VERSION,
          metadataTags = doc.tags.toString(),
        )
      )
      if (batch.size >= BATCH) {
        dao.upsertAll(batch.toList())
        batch.clear()
      }
    }
    if (batch.isNotEmpty()) dao.upsertAll(batch.toList())

    // 陈旧行清理:源数据里已经没有的 id 一律删掉
    val stale = existing.keys.filter { it !in docIds }
    if (stale.isNotEmpty()) dao.deleteByIds(stale)

    Log.i(
      TAG,
      "backfill done: docs=${docs.size} embedded=$embedded skipped=${docs.size - embedded - stale.size} stale=${stale.size}"
    )
  }
}
