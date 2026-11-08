package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 即时记忆层的本地持久化,按角色分开存。
 *
 * 存几条关键的东西:滑块值(目标总结长度)、轮数计数、已总结到第几轮、全部总结 ——
 * 少了任何一样,「每 20 轮自动总结一次」在重启后就会重新计数或重复总结。
 * 开发者面板要看的错误日志、最近一次注入的记忆块也一起存着,换页回来还能看到。
 *
 * 和 [ItemStore] 同一套做法:一个角色一个 JSON 串,坏了解析失败就当「还没有记忆」,
 * 不让一次读盘失败把整页挡住。
 */
object MemoryStore {
  private const val PREFS = "immediate_memory"
  private const val KEY_PREFIX = "state_"

  /** 读某个角色的即时记忆状态;从没存过时返回默认值(滑块 400、零轮、没有总结)。 */
  fun load(context: Context, friendId: String): ImmediateMemoryState {
    val raw = prefsOf(context).getString(keyOf(friendId), null)
      ?: return ImmediateMemoryState.empty(friendId)
    return try {
      fromJson(friendId, JSONObject(raw))
    } catch (e: Exception) {
      ImmediateMemoryState.empty(friendId)
    }
  }

  fun save(context: Context, state: ImmediateMemoryState) {
    prefsOf(context).edit().putString(keyOf(state.friendId), toJson(state).toString()).apply()
  }

  /**
   * 一轮对话收尾:轮数按会话实际轮次校正、记下这一轮注入的记忆块、攒够就交给调用方去总结。
   *
   * 读完再写只走一遍(一次 load + 一次 save):发消息是高频路径,不该为记忆在两三份 JSON 上来回解析。
   */
  fun afterRound(
    context: Context,
    friendId: String,
    roundsCount: Int,
    injectedBlock: String,
  ): ImmediateMemoryState {
    val next = load(context, friendId)
      .aligned(roundsCount)
      .withInjected(injectedBlock)
    save(context, next)
    return next
  }

  /** 滑块改了:写盘并返回新状态。 */
  fun setTarget(context: Context, friendId: String, target: Int): ImmediateMemoryState =
    load(context, friendId).withTarget(target).also { save(context, it) }

  /** 总结成功:落一条总结,并把「已总结到第几轮」推到它的覆盖轮次。 */
  fun addSummary(context: Context, friendId: String, memory: ImmediateMemory): ImmediateMemoryState =
    load(context, friendId).withSummary(memory).also { save(context, it) }

  /** 总结失败:记一条错误日志(开发者面板里能看到,列表上方也能看到最近一次失败)。 */
  fun recordError(context: Context, friendId: String, message: String): ImmediateMemoryState =
    load(context, friendId).withError(message).also { save(context, it) }

  /**
   * 清空即时记忆:总结、覆盖轮次、错误日志一起清掉,滑块值保留。
   *
   * [roundsCount] 是当前会话已有的轮数:清空之后这些轮次算「已归档」——
   * 它们既不再进请求体,也不会被重新总结一遍(否则清空完立刻会为一段旧对话补总结)。
   * 新聊的轮次从头开始攒,攒够 20 轮再总结。
   */
  fun clear(context: Context, friendId: String, roundsCount: Int): ImmediateMemoryState {
    val kept = load(context, friendId)
    val cleared = ImmediateMemoryState(
      friendId = friendId,
      targetTokens = kept.targetTokens,
      rounds = roundsCount.coerceAtLeast(0),
      coveredRounds = roundsCount.coerceAtLeast(0),
      // 在飞的总结回来时靠它判断「用户已经清过了」,不把结果盖回来
      clearedAt = nextEpoch(kept.clearedAt),
    )
    save(context, cleared)
    return cleared
  }

  /**
   * 按会话当前的轮数校正记忆状态:删消息 / 回滚之后调用。
   *
   * 三件事一起做,因为它们必须同时成立:
   * 1. `rounds` 改成实际轮数,`coveredRounds` 夹进范围 —— 否则面板上的「距下次总结」永远是 0;
   * 2. 丢掉覆盖区间已经不存在了的总结(`fromRound` 超过现在的轮数)—— 否则模型还会收到
   *    已经被删掉的那段对话的记忆;
   * 3. 刷新 [ImmediateMemoryState.clearedAt] 这个「状态纪元」—— 正在飞的总结拿的是删消息之前的
   *    快照,回来时靠它对不上而丢弃,不会把截断后的水位又推回去。
   */
  fun reconcile(context: Context, friendId: String, roundsCount: Int): ImmediateMemoryState {
    val current = load(context, friendId)
    val limit = roundsCount.coerceAtLeast(0)
    val next = current.copy(
      rounds = limit,
      coveredRounds = current.coveredRounds.coerceIn(0, limit),
      summaries = current.summaries.filter { it.fromRound <= limit },
      // 与清空同一个字段:它表示「盘上的状态被外部重置过」,在飞的结果据此作废
      clearedAt = nextEpoch(current.clearedAt),
    )
    // 无条件写盘:纪元每次都变,内容比较永远是「不相等」,留着 `if (next != current)`
    // 只会让人误以为存在「无事可写」的情况。
    save(context, next)
    return next
  }

  /**
   * 下一个状态纪元。
   *
   * `clearedAt` 只被用作相等比较(见 [MemoryJobs]):同一秒内「删消息/回滚」再到「启动总结」时,
   * 拿 [TimeManager.nowSecond] 会得到同一个值,守卫就形同虚设,截断前的旧总结仍会把水位顶回去。
   * 所以取「当前秒」与「上一个纪元 + 1」中更大的那个 —— 保持单调,秒级语义不变。
   *
   * 提成 internal 并且允许注入 [now]:它是这条守卫唯一能被单测覆盖的部分(落盘那半边要 Context,
   * 属于 instrumented 测试)。
   */
  internal fun nextEpoch(previous: Long, now: Long = TimeManager.nowSecond()): Long =
    maxOf(now, previous + 1)

  /** 删角色时顺手清掉它的记忆,不留孤儿条目。 */
  fun remove(context: Context, friendId: String) {
    prefsOf(context).edit().remove(keyOf(friendId)).apply()
  }

  private fun keyOf(friendId: String) = "$KEY_PREFIX$friendId"

  private fun prefsOf(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun toJson(state: ImmediateMemoryState): JSONObject = JSONObject().apply {
    put("target", state.targetTokens)
    put("rounds", state.rounds)
    put("covered", state.coveredRounds)
    put("lastSummaryAt", state.lastSummaryAt)
    put("lastInjected", state.lastInjectedBlock)
    put("lastInjectedAt", state.lastInjectedAt)
    put("clearedAt", state.clearedAt)
    put("lastError", state.lastError)
    val logs = JSONArray()
    state.errors.forEach { logs.put(it) }
    put("errors", logs)
    val items = JSONArray()
    state.summaries.forEach { memory ->
      items.put(JSONObject().apply {
        put("id", memory.id)
        put("from", memory.fromRound)
        put("to", memory.toRound)
        put("createdAt", memory.createdAt)
        put("target", memory.targetTokens)
        put("actual", memory.actualTokens)
        put("text", memory.text)
      })
    }
    put("summaries", items)
  }

  private fun fromJson(friendId: String, obj: JSONObject): ImmediateMemoryState {
    val summaries = mutableListOf<ImmediateMemory>()
    obj.optJSONArray("summaries")?.let { array ->
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val text = item.optString("text")
        // 正文为空的总结没有意义(注入进去只是一段空标签),当它不存在
        if (text.isBlank()) continue
        summaries.add(
          ImmediateMemory(
            id = item.optString("id").ifBlank { ImmediateMemory.newId() },
            fromRound = item.optInt("from", 1),
            toRound = item.optInt("to", item.optInt("from", 1)),
            createdAt = item.optLong("createdAt", 0L),
            targetTokens = item.optInt("target", ImmediateMemoryState.TARGET_DEFAULT),
            actualTokens = item.optInt("actual", TokenEstimator.estimate(text)),
            text = text,
          )
        )
      }
    }
    val errors = mutableListOf<String>()
    obj.optJSONArray("errors")?.let { array ->
      for (index in 0 until array.length()) {
        array.optString(index).takeIf { it.isNotBlank() }?.let { errors.add(it) }
      }
    }
    val rounds = obj.optInt("rounds", 0).coerceAtLeast(0)
    // 覆盖轮次不能超过实际轮数(否则请求体会把未总结的原文整段丢掉,面板上的「距下次总结」
    // 也会永远显示 0),也不能小于「最后一条总结盖到的轮次」(否则已总结的原文又被注入一遍)。
    // 两个上界取小值:轮数更小时以轮数为准 —— 说明消息被删过,由 reconcile() 进一步收拾。
    val summarizedTo = summaries.maxOfOrNull { it.toRound } ?: 0
    val covered = obj.optInt("covered", 0)
      .coerceIn(0, rounds)
      .coerceAtLeast(minOf(summarizedTo, rounds))
    return ImmediateMemoryState(
      friendId = friendId,
      targetTokens = obj.optInt("target", ImmediateMemoryState.TARGET_DEFAULT)
        .coerceIn(ImmediateMemoryState.TARGET_MIN, ImmediateMemoryState.TARGET_MAX),
      rounds = rounds,
      coveredRounds = covered,
      lastSummaryAt = obj.optLong("lastSummaryAt", 0L),
      lastInjectedBlock = obj.optString("lastInjected"),
      lastInjectedAt = obj.optLong("lastInjectedAt", 0L),
      clearedAt = obj.optLong("clearedAt", 0L),
      lastError = obj.optString("lastError"),
      errors = errors.take(ImmediateMemoryState.ERROR_LOG_LIMIT),
      summaries = summaries,
    )
  }
}
