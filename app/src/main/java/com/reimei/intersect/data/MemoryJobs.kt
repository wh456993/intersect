package com.reimei.intersect.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 总结任务的调度:同一个角色同时只允许一次总结在跑。
 *
 * 为什么单独拿出来:
 * 1. **单飞** —— 自动触发(每 20 轮,在 [com.reimei.intersect.MainViewModel] 里)与手动触发
 *    (记忆库的开发者面板)是两条入口,用户在总结进行中反复点「手动总结」、或者刚攒够轮数就
 *    切进记忆库点一下,都不能发出两个并行的请求:那会拿同一批轮次生成两条重复记忆,
 *    覆盖轮次还会互相打架。
 * 2. **不随页面消失** —— 任务挂在自己的 [scope] 上而不是记忆库页的 rememberCoroutineScope:
 *    用户点完手动总结就返回,请求不该被静默取消(既可能已经计费,又什么都没留下)。
 *
 * [running] 是 Compose 状态:记忆库页读它来把按钮置灰并显示「总结中」。
 */
object MemoryJobs {
  private val running = mutableStateListOf<String>()

  /** 总结任务不随任何页面销毁:用一个独立的作用域,主线程上跑(只有网络那一段在 IO)。 */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  fun isRunning(friendId: String): Boolean = running.contains(friendId)

  /**
   * 启动一次总结,不等待结果。
   *
   * [rounds] 是这个角色的全部轮次(见 [MemoryRounds.group]):这里自己切出「还没总结的那一段」
   * 里**最早的** [ImmediateMemoryState.SUMMARY_EVERY] 轮 —— 从最早的开始补,任何一轮都不会被跳过;
   * 注入侧则永远保留最近的未总结原文(见 [ChatService.payload]),两头各管一头。
   *
   * [onFinish] 在主线程回调(成功 / 失败 / 被丢弃都有结果,已在跑时不会回调)。
   * 返回 false 表示这次没有启动(已经在跑,或者根本没有可总结的轮次)。
   */
  fun launch(
    context: Context,
    friendId: String,
    apiKey: ApiKey?,
    state: ImmediateMemoryState,
    rounds: List<List<Msg>>,
    onFinish: (MemorySummaryResult?) -> Unit = {},
  ): Boolean {
    if (isRunning(friendId)) return false
    val aligned = state.aligned(rounds.size)
    val start = aligned.coveredRounds
    val targets = rounds.drop(start).take(ImmediateMemoryState.SUMMARY_EVERY)
    if (targets.isEmpty()) return false
    // 任务可能比调用方的页面活得久:只拿 application context,别把 Activity 带着一起引用
    val appContext = context.applicationContext
    running.add(friendId)
    scope.launch {
      val result = try {
        run(appContext, friendId, apiKey, aligned, start, targets)
      } finally {
        running.remove(friendId)
      }
      onFinish(result)
    }
    return true
  }

  /** 真正发请求、落盘的那一段。 */
  private suspend fun run(
    context: Context,
    friendId: String,
    apiKey: ApiKey?,
    state: ImmediateMemoryState,
    start: Int,
    targets: List<List<Msg>>,
  ): MemorySummaryResult {
    val firstNumber = start + 1
    val result = MemorySummarizer.summarize(
      apiKey = apiKey,
      rounds = MemoryRounds.texts(targets, firstNumber),
      targetTokens = state.targetTokens,
      // 总结规则:开发者模式下可以在记忆库页改成自己的版本
      template = MemoryPromptStore.template(),
    )
    // 总结也是要花钱的一次请求:和其他调用一样落一条消耗流水。
    // 放在纪元判断**之前**:无论结果最终是否被采用,这次请求都已经计费了,
    // 统计里看不到这笔钱比「结果作废」更糟。
    val ok = result as? MemorySummaryResult.Ok
    val key = apiKey
    if (ok != null && key != null) {
      withContext(Dispatchers.IO) {
        UsageLog.record(
          context = context.applicationContext,
          keyGroupId = key.id,
          model = key.model,
          chatWindowId = friendId,
          usage = ok.usage,
        )
      }
    }
    // 请求期间用户可能清空过记忆、也可能删了消息或回滚过(reconcile 同样刷新纪元):
    // 这之后回来的**成功**结果不能把盘上的状态盖回去。
    // 真失败不在此列 —— 它的原因比「作废」更值得记下来,走下面的分支照常入库。
    val epochChanged = MemoryStore.load(context, friendId).clearedAt != state.clearedAt
    if (epochChanged && result is MemorySummaryResult.Ok) {
      // 结果作废要说出来:否则界面报「总结完成」而盘上什么都没写,用户会以为攒下的记忆丢了。
      // 走 Fail 这条既有通道,面板上能看到原因,也能一键重试。
      val message = "本轮总结期间删过消息或回滚过,结果已作废"
      MemoryStore.recordError(context, friendId, message)
      return MemorySummaryResult.Fail(message)
    }
    when (result) {
      is MemorySummaryResult.Ok -> {
        MemoryStore.addSummary(
          context,
          friendId,
          ImmediateMemory(
            id = ImmediateMemory.newId(),
            fromRound = firstNumber,
            toRound = firstNumber + targets.size - 1,
            createdAt = TimeManager.nowSecond(),
            targetTokens = state.targetTokens,
            actualTokens = result.actualTokens,
            text = result.text,
          ),
        )
      }

      is MemorySummaryResult.Fail -> MemoryStore.recordError(context, friendId, result.message)
    }
    return result
  }
}
