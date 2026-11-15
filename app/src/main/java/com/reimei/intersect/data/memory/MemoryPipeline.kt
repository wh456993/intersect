package com.reimei.intersect.data.memory

import android.content.Context
import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.ApiUsage
import com.reimei.intersect.data.BugLog
import com.reimei.intersect.data.FriendsStore
import com.reimei.intersect.data.MemoryRounds
import com.reimei.intersect.data.MemoryStore
import com.reimei.intersect.data.MetaInfo
import com.reimei.intersect.data.MetaInfoStore
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.SceneTime
import com.reimei.intersect.data.UsageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 总结流水线:20 轮 → 微总结,3 条微总结 → 主线大总结。
 *
 * 三条设计约束(与任务板一致,别再往上加机制):
 * 1. **单飞** —— 同一个角色同时只允许一次总结任务在跑;已经在跑就跳过这一轮触发。
 * 2. **不随页面消失** —— 任务挂在自己的 [scope] 上,发完消息返回上一页也照常跑完。
 * 3. **不做重试/断点续传/租约**:失败了就静默作废,等下一次轮数到 20 的倍数再试。
 *
 * 两条入口共用同一段落库逻辑:
 * - [maybeLaunch] 自动触发(每 20 轮,由发消息链路调用);
 * - [launchManual] 手动触发(记忆库页的「手动触发总结」),不受 20 轮整除限制,补跑下一段。
 *
 * 防脏数据用的是**轻量快照比对**:启动前记下当前会话的消息条数 [startMessageCount],
 * AI 回来之后再看一次;条数变少说明用户中途删了消息或回滚过,这一次的结果直接丢弃 ——
 * 不引入 epoch / 内容哈希 / CAS 那一套。判定落在纯函数 [decideCommit] 里,单测直接钉。
 */
object MemoryPipeline {

  /** 每多少轮触发一次微总结。 */
  const val MICRO_EVERY_ROUNDS = 20

  /** 几条微总结合成一条主线大总结。 */
  const val MICRO_PER_NODE = 3

  /** 总结任务不随任何页面销毁:独立作用域,主线程上跑(网络与数据库各自切线程)。 */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /** 正在跑的角色集合(只在主线程读写,加锁只是为了防御性的可见性)。 */
  private val running = mutableSetOf<String>()

  fun isRunning(friendId: String): Boolean = synchronized(running) { running.contains(friendId) }

  /**
   * 该不该现在总结:轮数攒够 [MICRO_EVERY_ROUNDS] 的整数倍就总结一次。
   *
   * 纯函数(不碰数据库、不起协程),单测直接钉它。
   */
  fun isDue(rounds: Int): Boolean = rounds > 0 && rounds % MICRO_EVERY_ROUNDS == 0

  /**
   * AI 回来之后要不要落库 —— 轻量防脏数据就看这两个条件(纯函数,单测直接钉):
   *
   * - [currentCount] < [snapshotCount]:请求在飞的时候用户删了消息 / 回滚过,这一批对话已经不完整了,
   *   结果直接丢弃;
   * - [latestToRound] >= [toRound]:这段轮次已经总结过了(手动补跑撞上自动触发),不重复写。
   */
  fun decideCommit(
    snapshotCount: Int,
    currentCount: Int,
    latestToRound: Int,
    toRound: Int,
  ): CommitDecision = when {
    currentCount < snapshotCount -> CommitDecision.DROP_SHRUNK
    latestToRound >= toRound -> CommitDecision.DROP_DUPLICATE
    else -> CommitDecision.COMMIT
  }

  /** 落库决策的结果。 */
  enum class CommitDecision { COMMIT, DROP_SHRUNK, DROP_DUPLICATE }

  /** 一次总结要覆盖的那一段:[fromRound] 从 1 开始(轮次是人类编号),最多 [MICRO_EVERY_ROUNDS] 轮。 */
  data class RoundRange(val fromRound: Int, val rounds: List<List<Msg>>) {
    val toRound: Int get() = fromRound + rounds.size - 1
  }

  /**
   * 选出这次要总结的那一段:从水位([covered],最新微总结的结束轮次)往后接,最多 20 轮。
   *
   * 为什么不是「取最后 20 轮」:某一次总结失败(网络/解析)时,取最后 20 轮会让中间那一段
   * 永久不被总结;按水位往后接则下一次自动接着补,任何一轮都不会被跳过。
   *
   * 水位已经到头(没有新轮次)时返回 null —— 调用方据此判断「没有可总结的内容」。
   */
  fun nextRange(covered: Int, rounds: List<List<Msg>>): RoundRange? {
    if (rounds.isEmpty()) return null
    val start = covered.coerceIn(0, rounds.size)
    val targets = rounds.drop(start).take(MICRO_EVERY_ROUNDS)
    if (targets.isEmpty()) return null
    return RoundRange(fromRound = start + 1, rounds = targets)
  }

  /**
   * 触发一次总结(不等待结果)。
   *
   * [rounds] 是这个会话的全部轮次([MemoryRounds.group] 的结果);[messageCount] 返回
   * 「当前会话的消息条数」,流水线在启动时与 AI 回来之后各读一次做快照比对。
   * [onFinish] 在主线程回调,true 表示这次真的写进了新数据。
   * 返回 false 表示这次没有启动(轮数没到 / 已经在跑 / 没有可总结的轮次)。
   */
  fun maybeLaunch(
    context: Context,
    friendId: String,
    apiKey: ApiKey?,
    rounds: List<List<Msg>>,
    messageCount: () -> Int,
    onFinish: (Boolean) -> Unit = {},
  ): Boolean = launch(context, friendId, apiKey, rounds, messageCount, forced = false, onFinish = onFinish)

  /**
   * 手动触发(记忆库页的「手动触发总结」):与自动触发同一段落库逻辑,只是**不要求轮数整除 20**。
   *
   * 手动入口是「补跑」用的:从最新微总结的水位往后接着总结一段,不足 20 轮也照做 ——
   * 用户既然点了,就说明他想要现在这一段被记住。
   */
  fun launchManual(
    context: Context,
    friendId: String,
    apiKey: ApiKey?,
    rounds: List<List<Msg>>,
    messageCount: () -> Int,
    onFinish: (Boolean) -> Unit = {},
  ): Boolean = launch(context, friendId, apiKey, rounds, messageCount, forced = true, onFinish = onFinish)

  /**
   * 两条入口的共同前半段:判条件 → 占单飞标记 → 起协程。
   *
   * [forced] 为 true 时跳过「轮数整除 20」的判断(手动触发)。
   */
  private fun launch(
    context: Context,
    friendId: String,
    apiKey: ApiKey?,
    rounds: List<List<Msg>>,
    messageCount: () -> Int,
    forced: Boolean,
    onFinish: (Boolean) -> Unit,
  ): Boolean {
    if (!forced && !isDue(rounds.size)) return false
    if (apiKey == null) return false
    if (rounds.isEmpty()) return false
    if (!markRunning(friendId)) {
      BugLog.log("总结", "跳过:已有总结在跑 friend=$friendId")
      return false
    }
    BugLog.log(
      "总结",
      if (forced) "强制触发 · friend=$friendId · 轮数 ${rounds.size} · 模型 ${apiKey.model}"
      else "20轮触发 · friend=$friendId · 轮数 ${rounds.size} · 模型 ${apiKey.model}"
    )
    val appContext = context.applicationContext
    val snapshotCount = messageCount()
    scope.launch {
      var ok = false
      try {
        ok = run(appContext, friendId, apiKey, rounds, snapshotCount, messageCount)
      } catch (e: CancellationException) {
        // 取消不是失败:照常往上抛,不要被下面那层静默吃掉
        throw e
      } catch (e: Exception) {
        // 网络失败 / 解析失败一律静默:等下一次轮数到 20 的倍数再试,不弹窗不重试
        BugLog.log("总结", e)
        ok = false
      } finally {
        clearRunning(friendId)
      }
      BugLog.log("总结", "结束 friend=$friendId · 是否写入新数据=$ok")
      onFinish(ok)
    }
    return true
  }

  /** 真正发请求、比对快照、落库的那一段。返回 true 表示写进了新数据。 */
  private suspend fun run(
    context: Context,
    friendId: String,
    apiKey: ApiKey,
    rounds: List<List<Msg>>,
    snapshotCount: Int,
    messageCount: () -> Int,
  ): Boolean {
    val repo = MemoryRepository.get(context)
    // 覆盖水位 = 最新一条微总结的结束轮次(取不到就当没总结过)
    val covered = runCatching { repo.recentMicro(friendId, 1).firstOrNull()?.toRound ?: 0 }
      .getOrDefault(0)
    val range = nextRange(covered, rounds) ?: return false
    BugLog.log(
      "总结",
      "段落 friend=$friendId · 水位 $covered · 本次第 ${range.fromRound}-${range.toRound} 轮"
    )
    // 元信息:总结让模型知道这批对话开始时角色是什么状态(纯名词,不含数值)。
    // 播种来源必须与发消息那条路一致(设置页的值 + 时钟),否则会把默认值永久钉进 rolling_meta。
    val meta = runCatching { repo.loadMeta(friendId, liveMeta(context, friendId)) }.getOrNull()
    val result = MicroSummarizer.summarize(
      apiKey = apiKey,
      rounds = MemoryRounds.texts(range.rounds, range.fromRound),
      meta = meta,
      // 长度上限沿用记忆库页的滑块(旧设置项仍然生效),夹在 250~600
      maxTokens = targetTokens(context, friendId),
    )
    // 总结也是要花钱的一次请求:无论结果最终是否被采用,都记一条消耗流水
    (result as? MicroSummaryResult.Ok)?.let { recordUsage(context, apiKey, friendId, it.usage) }
    val ok = result as? MicroSummaryResult.Ok ?: return false
    if (!commitMicro(context, friendId, range, ok, snapshotCount, messageCount())) return false

    // 大总结是「锦上添花」:它失败不该把已经落库的微总结判成失败(上层据此记账)
    runCatching { summarizeNodeIfReady(context, friendId, apiKey, snapshotCount, messageCount) }
    return true
  }

  /**
   * 微总结的落库入口:先过防脏数据判定,再写 micro_summary + 合并 rolling_meta。
   *
   * 抽成独立函数是为了让「删消息之后该丢弃」这条验收能在真机上直接跑(不需要网络):
   * 测试拿来一份假的 [MicroSummaryResult.Ok] 调它,看数据库里到底多了没有一行。
   * 返回 true 表示真的写进去了。
   */
  internal suspend fun commitMicro(
    context: Context,
    friendId: String,
    range: RoundRange,
    ok: MicroSummaryResult.Ok,
    snapshotCount: Int,
    currentCount: Int,
  ): Boolean {
    val repo = MemoryRepository.get(context)
    val covered = runCatching { repo.recentMicro(friendId, 1).firstOrNull()?.toRound ?: 0 }
      .getOrDefault(0)
    // 防脏数据:请求在飞的时候用户删了消息 / 回滚过 ⇒ 这次结果直接丢弃
    when (decideCommit(snapshotCount, currentCount, covered, range.toRound)) {
      CommitDecision.DROP_SHRUNK -> {
        BugLog.log(
          "总结",
          "丢弃:消息被删过(快照 $snapshotCount → 现在 $currentCount)· " +
            "第 ${range.fromRound}-${range.toRound} 轮"
        )
        return false
      }
      CommitDecision.DROP_DUPLICATE -> {
        BugLog.log("总结", "丢弃:第 ${range.fromRound}-${range.toRound} 轮已总结过(水位 $covered)")
        return false
      }
      CommitDecision.COMMIT -> Unit
    }
    repo.insertMicro(
      friendId = friendId,
      fromRound = range.fromRound,
      toRound = range.toRound,
      text = ok.text,
      score = ok.score,
    )
    repo.applyMetaUpdate(friendId, ok.update)
    BugLog.log(
      "总结",
      "写入DB成功 friend=$friendId · 微总结第 ${range.fromRound}-${range.toRound} 轮 · " +
        "评分 ${ok.score}"
    )
    return true
  }

  /** 总结正文的长度上限:记忆库页滑块的值(读不到就用 600 的默认上限)。 */
  private fun targetTokens(context: Context, friendId: String): Int =
    runCatching { MemoryStore.load(context, friendId).targetTokens }
      .getOrDefault(MicroSummarizer.MAX_TOKENS)
      .coerceIn(MicroSummarizer.MIN_TOKENS, MicroSummarizer.MAX_TOKENS)

  /**
   * 攒够 [MICRO_PER_NODE] 条**还没并过**的连续微总结就压一条主线大总结。
   *
   * 水位来自最新一条大总结的 `to_round`:只压水位之后的微总结,所以同一段内容不会被
   * 反复压进新的大总结(否则每 20 轮都会发一次含 2/3 重复内容的付费请求)。
   * 连续 = 后一条的起始轮次紧接前一条的结束轮次;中间断档(用户删过消息)就等下一批,不硬凑。
   */
  private suspend fun summarizeNodeIfReady(
    context: Context,
    friendId: String,
    apiKey: ApiKey,
    snapshotCount: Int,
    messageCount: () -> Int,
  ) {
    val repo = MemoryRepository.get(context)
    val watermark = repo.latestNode(friendId)?.toRound ?: 0
    val plan = nodePlan(watermark, repo.pendingMicro(friendId, watermark)) ?: return
    if (repo.hasNode(friendId, plan.fromRound, plan.toRound)) return

    val result = NodeSummarizer.summarize(apiKey, plan.texts)
    (result as? NodeSummaryResult.Ok)?.let { recordUsage(context, apiKey, friendId, it.usage) }
    val ok = result as? NodeSummaryResult.Ok ?: return
    // 与微总结同一把尺子:这段期间消息被删过就不写
    if (messageCount() < snapshotCount) return
    commitNode(context, friendId, plan, ok.text)
  }

  /** 一条待落库的主线大总结:覆盖轮次区间 + 参与压缩的那几段正文。 */
  data class NodePlan(val fromRound: Int, val toRound: Int, val texts: List<String>)

  /**
   * 该不该压一条大总结、压哪几条(纯函数,不碰数据库也不发请求)。
   *
   * 只取水位([watermark],最新大总结的 to_round)之后的微总结,所以同一段内容不会被
   * 反复压进新的大总结(否则每 20 轮都会发一次含 2/3 重复内容的付费请求);
   * 连续 = 后一条的起始轮次紧接前一条的结束轮次,中间断档(用户删过消息)就等下一批,不硬凑。
   */
  fun nodePlan(watermark: Int, pending: List<MicroSummaryEntity>): NodePlan? {
    val fresh = pending.filter { it.fromRound > watermark }.sortedBy { it.fromRound }
    if (fresh.size < MICRO_PER_NODE) return null
    val picked = fresh.take(MICRO_PER_NODE)
    if (!isConsecutive(picked)) return null
    return NodePlan(
      fromRound = picked.first().fromRound,
      toRound = picked.last().toRound,
      texts = picked.map { it.text },
    )
  }

  /**
   * 大总结落库。同样抽出来给真机测试用(TASK-03 的「60 轮之后 node_summary 多一条」)。
   *
   * 写库前再过一遍正文底线:不足 [NodeSummarizer.MIN_CJK_CHARS] 个汉字的内容一旦进了 node_summary,
   * 之后每一次请求体都会把它当「主线」带上,所以宁可这一条不写。返回 -1 表示被判掉。
   */
  internal suspend fun commitNode(context: Context, friendId: String, plan: NodePlan, text: String): Long {
    if (!NodeSummarizer.isBodyUsable(text)) {
      BugLog.log(
        "总结",
        "丢弃:大总结正文汉字不足 ${NodeSummarizer.MIN_CJK_CHARS} 个 · " +
          "第 ${plan.fromRound}-${plan.toRound} 轮 · 正文「${text.take(80)}」"
      )
      return -1L
    }
    return MemoryRepository.get(context).insertNode(
      friendId = friendId,
      fromRound = plan.fromRound,
      toRound = plan.toRound,
      text = text,
    ).also { id ->
      BugLog.log(
        "总结",
        "写入DB成功 friend=$friendId · 大总结第 ${plan.fromRound}-${plan.toRound} 轮 · id=$id"
      )
    }
  }

  /**
   * 总结要用的元信息:**套好时钟**的那一份(本体代入走真实时间,角色代入走剧情时钟 / 手填值)。
   *
   * 流水线手上只有 friendId,扮演模式得从角色记录里查;查不到按角色代入算。
   */
  private fun liveMeta(context: Context, friendId: String): MetaInfo {
    val mode = FriendsStore.load(context).firstOrNull { it.id == friendId }?.roleplayMode
      ?: RoleplayMode.AVATAR_BASED
    return SceneTime.applyTo(MetaInfoStore.load(context, friendId), mode)
  }

  /** [micros] 按「新 → 旧」给出,判断它们的轮次区间是否首尾衔接。 */
  fun isConsecutive(micros: List<MicroSummaryEntity>): Boolean {
    if (micros.size < 2) return true
    val ordered = micros.sortedBy { it.fromRound }
    return ordered.zipWithNext().all { (a, b) -> b.fromRound == a.toRound + 1 }
  }

  /** 消耗流水:失败的请求不计费,所以只在成功分支调用(与主聊天的口径一致)。 */
  private suspend fun recordUsage(context: Context, apiKey: ApiKey, friendId: String, usage: ApiUsage?) {
    withContext(Dispatchers.IO) {
      UsageLog.record(
        context = context.applicationContext,
        keyGroupId = apiKey.id,
        model = apiKey.model,
        chatWindowId = friendId,
        usage = usage,
      )
    }
  }

  private fun markRunning(friendId: String): Boolean = synchronized(running) {
    if (running.contains(friendId)) false else running.add(friendId)
  }

  private fun clearRunning(friendId: String) {
    synchronized(running) { running.remove(friendId) }
  }
}
