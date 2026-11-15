package com.reimei.intersect.data.memory

import android.content.Context
import com.reimei.intersect.data.BugLog
import com.reimei.intersect.data.MetaInfo
import com.reimei.intersect.data.TimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 请求体状态快照里的「记忆」那一半:最新 1 条大总结 + 最近 3 条微总结。
 *
 * 只装从 memory.db 读出来的正文,不含元信息(元信息走 [MetaInfo],与时钟一起由调用方套好);
 * 也就是说它是一份**纯数据**,可以在 JVM 单测里直接构造,不需要数据库。
 *
 * [microSummaries] 按**新 → 旧**给出(与库里的排序一致);装配成报文时会反过来按时间顺序编号,
 * 否则模型会以为最新的一段是最早的。
 */
data class MemorySnapshot(
  val nodeSummary: String = "",
  val microSummaries: List<String> = emptyList(),
) {
  val isEmpty: Boolean get() = nodeSummary.isBlank() && microSummaries.isEmpty()
}

/**
 * 总结回流的状态更新:AI 在微总结里一并给出的数值与文本。
 *
 * 全是可空的「只改我给的这一项」语义 —— 模型没写某一项时不要把它清零。
 */
data class RollingMetaUpdate(
  val favorability: Int? = null,
  val health: Int? = null,
  val hunger: Int? = null,
  val timeText: String? = null,
  val location: String? = null,
) {
  val isEmpty: Boolean
    get() = favorability == null && health == null && hunger == null && timeText == null && location == null
}

/**
 * 记忆库仓库:把 [MemoryDao] 的读写包成 suspend 函数,并负责「滚动状态 ↔ 元信息」的映射。
 *
 * 调用方(发消息、总结流水线)只认这一层,不直接碰 DAO;所有数据库操作都在 IO 上跑。
 */
class MemoryRepository(private val dao: MemoryDao) {

  // —— 微总结 ——

  suspend fun insertMicro(
    friendId: String,
    fromRound: Int,
    toRound: Int,
    text: String,
    score: Int,
    createdAt: Long = TimeManager.nowSecond(),
  ): Long = dao.insertMicro(
    MicroSummaryEntity(
      friendId = friendId,
      fromRound = fromRound,
      toRound = toRound,
      text = text,
      score = score,
      createdAt = createdAt,
    )
  )

  suspend fun recentMicro(friendId: String, limit: Int = SNAPSHOT_MICRO_COUNT): List<MicroSummaryEntity> =
    dao.recentMicro(friendId, limit)

  /** 还没并进大总结的微总结(旧的在前,最多 [limit] 条)。 */
  suspend fun pendingMicro(friendId: String, nodedToRound: Int, limit: Int = NODE_MICRO_COUNT): List<MicroSummaryEntity> =
    dao.pendingMicro(friendId, nodedToRound, limit)

  suspend fun microCount(friendId: String): Int = dao.microCount(friendId)

  /** 全部微总结(旧的在前),给记忆库页展示用。 */
  suspend fun allMicro(friendId: String): List<MicroSummaryEntity> = dao.allMicro(friendId)

  // —— 大总结 ——

  suspend fun latestNode(friendId: String): NodeSummaryEntity? = dao.latestNode(friendId)

  /** 全部大总结(旧的在前),给记忆库页的「引导线索层」展示用。 */
  suspend fun allNode(friendId: String): List<NodeSummaryEntity> = dao.allNode(friendId)

  suspend fun hasNode(friendId: String, fromRound: Int, toRound: Int): Boolean =
    dao.nodeCount(friendId, fromRound, toRound) > 0

  suspend fun insertNode(
    friendId: String,
    fromRound: Int,
    toRound: Int,
    text: String,
    createdAt: Long = TimeManager.nowSecond(),
  ): Long = dao.insertNode(
    NodeSummaryEntity(
      friendId = friendId,
      fromRound = fromRound,
      toRound = toRound,
      text = text,
      createdAt = createdAt,
    )
  )

  // —— 清洗脏大总结(开发者面板用) ——

  /**
   * 扫一遍这个角色的大总结,找出把英文思考过程写进正文的脏行。**只读**,不动库。
   *
   * 判据与写入时同一套([NodeClean]),所以「扫描结果」就是「真按下去会变成什么样」。
   */
  suspend fun scanDirtyNodes(friendId: String): NodeCleanPlan = NodeClean.plan(dao.allNode(friendId))

  /**
   * 执行清洗计划:能洗的就地重写正文,洗不出中文主线的整行删掉。
   *
   * 删掉的道理:那条正文进不了人眼也进不了模型,却会一直挡在请求体里;
   * 删掉之后 `latestNode` 退回到上一条(没有就是空),之后流水线会重新压一段干净的出来。
   */
  suspend fun applyNodeClean(plan: NodeCleanPlan) {
    if (plan.isEmpty) return
    plan.rewrites.forEach { dao.updateNodeText(it.row.id, it.after) }
    plan.deletes.forEach { item ->
      dao.deleteNodeById(item.row.id)
      // 删掉的是脏数据,但要留痕:以后看黑匣子能对上「这条大总结怎么没了」
      BugLog.log(
        "总结",
        "清洗大总结:删除 ${item.row.roundLabel()} 的脏正文(汉字 ${NodeSummarizer.cjkLength(NodeSummarizer.sanitize(item.row.text))} " +
          "< ${NodeSummarizer.MIN_CJK_CHARS})· 原文:${item.row.text.take(80)}"
      )
    }
    BugLog.log("总结", "清洗大总结:重写 ${plan.rewrites.size} 条,删除 ${plan.deletes.size} 条")
  }

  // —— 滚动状态 ——

  suspend fun rollingMeta(friendId: String): RollingMetaEntity? = dao.rollingMeta(friendId)

  /**
   * 这个角色当前的元信息。
   *
   * 库里有行就以行为准(总结会把 AI 给的新状态写进去);没有行时用 [fallback]
   * (调用方从旧设置页读到的值)播种一行,之后就以库为准了。
   */
  suspend fun loadMeta(friendId: String, fallback: MetaInfo): MetaInfo {
    val row = dao.rollingMeta(friendId)
    if (row != null) return RollingMetaCodec.toMetaInfo(row)
    dao.upsertRollingMeta(RollingMetaCodec.toEntity(fallback))
    return fallback
  }

  /** 把总结里带回来的状态改动并进当前行(没有行时不写:元信息总得先有个起点)。 */
  suspend fun applyMetaUpdate(friendId: String, update: RollingMetaUpdate) {
    if (update.isEmpty) return
    val row = dao.rollingMeta(friendId) ?: return
    dao.upsertRollingMeta(
      RollingMetaCodec.merge(
        row = row,
        favorability = update.favorability,
        health = update.health,
        hunger = update.hunger,
        timeText = update.timeText,
        location = update.location,
      )
    )
  }

  // —— 读给请求体用 ——

  /** 状态快照的记忆部分:最新 1 条大总结 + 最近 3 条微总结(新的在前)。 */
  suspend fun loadSnapshot(friendId: String): MemorySnapshot {
    val micros = dao.recentMicro(friendId, SNAPSHOT_MICRO_COUNT)
    return MemorySnapshot(
      nodeSummary = dao.latestNode(friendId)?.text.orEmpty(),
      microSummaries = micros.map { it.text },
    )
  }

  /** 这个角色的全部新库数据都清掉(删角色时用)。 */
  suspend fun clear(friendId: String) {
    dao.deleteMicro(friendId)
    dao.deleteNode(friendId)
    dao.deleteRollingMeta(friendId)
  }

  /**
   * 删消息 / 回滚之后收尾:把覆盖范围超出**当前轮数** [rounds] 的微总结与大总结清掉。
   *
   * 轻量架构不做内容哈希,所以只能粗粒度地按轮次区间裁剪 —— 但至少不会再把「已经删掉的
   * 那段剧情」继续当记忆注入,也不会让写水位卡在旧数值上、把之后的新总结全部吞掉。
   */
  suspend fun trimBeyond(friendId: String, rounds: Int) {
    val limit = rounds.coerceAtLeast(0)
    dao.deleteMicroBeyond(friendId, limit)
    dao.deleteNodeBeyond(friendId, limit)
  }

  companion object {
    /** 请求体里注入最近几条微总结。 */
    const val SNAPSHOT_MICRO_COUNT = 3

    /** 几条微总结合成一条主线大总结。 */
    const val NODE_MICRO_COUNT = 3

    @Volatile
    private var instance: MemoryRepository? = null

    /** 不随页面/ViewModel 销毁的后台作用域:删角色这类「必须落库」的清理走它。 */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context): MemoryRepository =
      instance ?: synchronized(this) {
        instance ?: MemoryRepository(MemoryDb.getInstance(context).memory()).also { instance = it }
      }

    /**
     * 后台清掉某个角色的新库数据(不等待结果)。
     *
     * 用它而不是 `viewModelScope`:用户删完角色可能立刻退出页面,那种清理不该被中途取消。
     */
    fun clearAsync(context: Context, friendId: String) {
      val appContext = context.applicationContext
      background.launch { runCatching { get(appContext).clear(friendId) } }
    }

    /** 后台裁剪超出当前轮数的总结(删消息 / 回滚之后)。 */
    fun trimBeyondAsync(context: Context, friendId: String, rounds: Int) {
      val appContext = context.applicationContext
      background.launch { runCatching { get(appContext).trimBeyond(friendId, rounds) } }
    }

    /** 单测/instrumented 测试用:自己给的 DAO(通常是内存库)。 */
    fun of(dao: MemoryDao): MemoryRepository = MemoryRepository(dao)
  }
}
