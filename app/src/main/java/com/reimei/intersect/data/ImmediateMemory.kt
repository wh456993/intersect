package com.reimei.intersect.data

/**
 * 一条即时记忆总结。
 *
 * 「即时记忆层」每 [ImmediateMemoryState.SUMMARY_EVERY] 轮对话自动压缩一次:把这一批轮次的
 * 原文交给模型,换回一段几百 token 的总结。之后这些轮次的原文不再进请求体,只注入总结
 * (见 [MemoryInject.block])。
 *
 * [fromRound] / [toRound] 是覆盖的轮次区间(从 1 开始,「用户一句 + 角色一句」算一轮);
 * [createdAt] 是生成时的本地秒级时间戳(epoch second),显示时再按本地时区格式化;
 * [targetTokens] 是当时滑块定下的目标长度,[actualTokens] 是生成后用 [TokenEstimator] 估出来的实际长度。
 */
data class ImmediateMemory(
  val id: String,
  val fromRound: Int,
  val toRound: Int,
  val createdAt: Long,
  val targetTokens: Int,
  val actualTokens: Int,
  val text: String,
) {
  /** 列表里那一行「第 1-20 轮」。 */
  fun roundLabel(): String =
    if (fromRound == toRound) "第 $fromRound 轮" else "第 $fromRound-$toRound 轮"

  /** 生成时间,精确到分钟。 */
  fun timeLabel(): String = TimeManager.formatMinute(createdAt)

  companion object {
    fun newId(): String = "immediate_${System.currentTimeMillis()}_${(1000..9999).random()}"
  }
}

/**
 * 某个角色的即时记忆层状态:滑块值、轮数计数、已总结到第几轮、全部总结与错误日志。
 *
 * 这些都是**跟着角色走并持久化**的(见 [MemoryStore]):换页、重启进程都不会丢,
 * 否则「每 20 轮总结一次」这件事就无从判断。
 */
data class ImmediateMemoryState(
  val friendId: String,
  /** 总结目标长度(token),由记忆库页的滑块调节。 */
  val targetTokens: Int = TARGET_DEFAULT,
  /** 这个角色累计完成过多少轮对话(每完成一轮 +1)。 */
  val rounds: Int = 0,
  /** 已经总结到第几轮:请求体里这一轮之前的原文不再注入。 */
  val coveredRounds: Int = 0,
  /** 上次总结生成的时间(epoch 秒);0 表示还没总结过。 */
  val lastSummaryAt: Long = 0L,
  /** 最近一次真正发出去的请求里,记忆部分的原文(开发者面板里可以查看)。 */
  val lastInjectedBlock: String = "",
  /** 上次注入记忆块的时间(epoch 秒);0 表示还没发过请求 —— 与「发过但没注入」区分开。 */
  val lastInjectedAt: Long = 0L,
  /**
   * 状态纪元:盘上的记忆被外部重置(清空 / 删消息 / 回滚)时递增的标记。
   *
   * 通常接近当前秒,但**不保证等于真实时间** —— 同一秒内连续重置会依次 +1、+2
   * (见 [MemoryStore.nextEpoch]),它只用来做相等比较,不要当时间戳显示。
   *
   * 总结请求在飞的时候用户可能清空了记忆、或者回滚了消息:结果回来时比对这个值,
   * 不一致就丢弃 —— 否则清空之后几秒那条总结又会冒出来、覆盖轮次也跟着跳回去。
   */
  val clearedAt: Long = 0L,
  /** 最近一次总结失败的原因;空串表示没有失败。 */
  val lastError: String = "",
  /** 总结失败日志,新的在前,最多 [ERROR_LOG_LIMIT] 条。 */
  val errors: List<String> = emptyList(),
  /** 全部总结,新的在前;最多留 [SUMMARY_KEEP] 条(注入只用最近几条)。 */
  val summaries: List<ImmediateMemory> = emptyList(),
) {
  /** 攒着还没总结的轮数。 */
  val pendingRounds: Int get() = (rounds - coveredRounds).coerceAtLeast(0)

  /** 还差几轮触发下一次总结。 */
  val roundsUntilNext: Int get() = (SUMMARY_EVERY - pendingRounds).coerceAtLeast(0)

  /** 该不该现在总结:攒够 [SUMMARY_EVERY] 轮就该。 */
  val needsSummary: Boolean get() = pendingRounds >= SUMMARY_EVERY

  /** 实际注入请求体的那几条:最近 [INJECT_COUNT] 条总结。 */
  val latest: List<ImmediateMemory> get() = summaries.take(INJECT_COUNT)

  /** 这一轮请求里会注入的记忆块(还没有总结时为空串,整块省略)。 */
  val injectBlock: String get() = MemoryInject.block(latest)

  /** 注入块的 token 估算。 */
  val injectTokens: Int get() = TokenEstimator.estimate(injectBlock)

  fun withRound(): ImmediateMemoryState = copy(rounds = rounds + 1)

  /**
   * 用会话里**实际的轮次数**校正两个计数。
   *
   * [rounds] 这个计数器不能只靠自己 +1:删消息、恢复备份、跨设备合并之后,消息条数与计数器
   * 必然对不上;一旦覆盖轮次超过实际轮数,请求体里那段「未总结原文」会整段消失。
   * 所以每次用到它之前都用消息分组校正一遍,以消息为准(自愈),再把覆盖轮次夹进范围。
   */
  fun aligned(roundsCount: Int): ImmediateMemoryState = copy(
    rounds = roundsCount.coerceAtLeast(0),
    coveredRounds = coveredRounds.coerceIn(0, roundsCount.coerceAtLeast(0)),
  )

  fun withTarget(target: Int): ImmediateMemoryState =
    copy(targetTokens = target.coerceIn(TARGET_MIN, TARGET_MAX))

  /** 记下一条总结:覆盖轮次往后推、刷新上次总结时间、清掉上一次的错误。 */
  fun withSummary(memory: ImmediateMemory): ImmediateMemoryState = copy(
    coveredRounds = maxOf(coveredRounds, memory.toRound),
    lastSummaryAt = memory.createdAt,
    lastError = "",
    // 只留最近几条:注入只用 3 条,面板也只展示摘要,没必要无限涨
    summaries = (listOf(memory) + summaries).take(SUMMARY_KEEP),
  )

  fun withInjected(block: String, at: Long = TimeManager.nowSecond()): ImmediateMemoryState =
    copy(lastInjectedBlock = block, lastInjectedAt = at)

  fun withError(message: String, at: Long = System.currentTimeMillis() / 1000L): ImmediateMemoryState =
    copy(
      lastError = message,
      errors = (listOf("${TimeManager.formatSecond(at)}  $message") + errors).take(ERROR_LOG_LIMIT),
    )

  companion object {
    /** 每多少轮自动总结一次。 */
    const val SUMMARY_EVERY = 20

    /** 请求体里注入最近几条总结。 */
    const val INJECT_COUNT = 3

    const val TARGET_MIN = 200
    const val TARGET_MAX = 600
    const val TARGET_DEFAULT = 400

    /** 滑块的步长:200~600 之间每 20 token 一档,共 21 档。 */
    const val TARGET_STEP = 20

    const val ERROR_LOG_LIMIT = 20

    /** 本地保留多少条总结(注入只用最近 3 条,留这么多是为了面板回看与不被无限增长的 JSON 拖慢)。 */
    const val SUMMARY_KEEP = 60

    fun empty(friendId: String): ImmediateMemoryState = ImmediateMemoryState(friendId)
  }
}

/**
 * 把会话消息按「一轮」分组:用户一句 + 角色一句算一轮。
 *
 * 分组只看发送方,不看内容:以用户消息开头起一轮,紧随其后的角色回复归进这一轮。
 * 用户说了还没等到回复(或回复失败)时,这一轮里就只有一条 —— 请求体拼接时它会被单列成
 * 「当前输入」,而 [ImmediateMemoryState.rounds] 是由完整的消息列表算出来的,
 * 所以两者用同一把尺子量([ImmediateMemoryState.aligned]),不会因为某轮没回复而错位。
 *
 * 注:轮次下标是**当前消息列表里的位置**(从 0 开始)。删掉消息后位置会前移,所以
 * 覆盖轮次的落盘值在用之前都要经过 [ImmediateMemoryState.aligned] 夹到合法范围。
 */
object MemoryRounds {
  fun group(msgs: List<Msg>): List<List<Msg>> {
    val rounds = mutableListOf<MutableList<Msg>>()
    msgs.forEach { msg ->
      if (msg.from.id == User.Me.id || rounds.isEmpty()) {
        rounds.add(mutableListOf(msg))
      } else {
        val last = rounds.last()
        if (last.last().from.id == User.Me.id && last.size < 2) last.add(msg)
        else rounds.add(mutableListOf(msg))
      }
    }
    return rounds
  }

  /**
   * 一轮的原文,给总结请求的 user 消息用。
   *
   * [number] 是这一轮在整段对话里的序号(从 1 开始),总结出来的记忆里要能对上「第几轮到第几轮」。
   */
  fun textOf(round: List<Msg>, number: Int): String = buildString {
    append("第 ")
    append(number)
    append(" 轮")
    round.forEach { msg ->
      append('\n')
      append(if (msg.from.id == User.Me.id) "用户:" else "角色:")
      msg.time?.let { time ->
        append('[')
        append(time)
        append(']')
      }
      append(msg.text)
    }
  }

  /** 一串轮次的原文,序号从 [firstNumber] 开始。 */
  fun texts(rounds: List<List<Msg>>, firstNumber: Int = 1): List<String> =
    rounds.mapIndexed { index, round -> textOf(round, firstNumber + index) }
}

/**
 * 记忆上下文在请求体里的格式。
 *
 * 记忆块单独占一条 user 消息(绝不和「当前用户输入」合并),前后带固定的首尾行,
 * 让模型一眼看出这段是**角色过去的经历**而不是用户此刻说的话。
 * [RULES] 拼在 system 里,每轮一字不改 —— 内容稳定,服务商的前缀缓存才有用。
 */
object MemoryInject {
  const val HEADER = "【记忆上下文|角色过去经历,非用户发言】"
  const val FOOTER = "【记忆上下文结束】"
  const val SECTION = "[即时记忆层·最近 {n} 条总结]"

  /** system 里的记忆使用规则。措辞固定,避免每轮变化破坏前缀缓存。 */
  val RULES: String = buildString {
    append("记忆使用规则:\n")
    append("1. 后面的「记忆上下文」是角色过去的经历,不是用户此刻的发言。\n")
    append("2. 记忆与用户当前输入冲突时,一律以当前输入为准。\n")
    append("3. 不得把记忆内容当作「用户说过的话」引用或复述,也不要提及「即时记忆层」「总结」这类标签。\n")
    append("4. 记忆只用来保持角色连贯,不需要主动说明自己记得什么。")
  }

  /**
   * 拼出记忆块;没有总结时返回空串(调用方据此整块省略)。
   *
   * 格式:
   * ```
   * 【记忆上下文|角色过去经历,非用户发言】
   * [即时记忆层·最近 2 条总结]
   * [总结1|2025-01-15 14:05|第 1-20 轮]
   * 正文......
   * 【记忆上下文结束】
   * ```
   */
  fun block(summaries: List<ImmediateMemory>): String {
    if (summaries.isEmpty()) return ""
    val picked = summaries.take(ImmediateMemoryState.INJECT_COUNT)
    return buildString {
      append(HEADER)
      append('\n')
      append(SECTION.replace("{n}", picked.size.toString()))
      picked.forEachIndexed { index, memory ->
        append("\n[总结")
        append(index + 1)
        append('|')
        append(memory.timeLabel())
        append('|')
        append(memory.roundLabel())
        append("]\n")
        append(memory.text.trim())
      }
      append('\n')
      append(FOOTER)
    }
  }
}
