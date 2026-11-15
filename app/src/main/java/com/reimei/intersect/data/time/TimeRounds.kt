package com.reimei.intersect.data.time

/**
 * T3.2 / T1.5 —— **编排层与请求体之间的桥**。
 *
 * 分工(接手时不要挪):
 * - 轮次类型由**调用方**在构造请求时给出,本文件只做「事件 → [RoundType]」的固定映射,
 *   不查 AI、不看用户文本 —— 模型无权决定自己这一轮是什么轮;
 * - 注入内容由 [RoundOrchestrator] 决定(白名单与顺序在那边),这里只负责把它拆成
 *   「进 system 的规则块」与「单独成条的动态块」两种形态,交给 `ChatService` 拼请求体。
 *
 * 规则块进 system 的理由:它是常量([TimeRules.RULES_TEXT]),放在前缀里不动,
 * 服务商的前缀缓存才有机会命中;动态时间事实(临时事实 / 导演事件 / 总结元数据)
 * 一律单独成条,与 system 隔开。
 */
object TimeRounds {

  /** 规则块的文本(system 末尾那段,常量)。 */
  fun rulesText(): String = TimeRules.RULES_TEXT

  /**
   * 一轮请求的**事件**:调用方只报「这一轮发生了什么」,由这里翻成轮次类型。
   *
   * 映射是 1:1 的固定表(见 [of]),没有第二种解释。
   */
  enum class RoundEvent(val roundType: RoundType) {
    /** 这一轮就是普通对话。 */
    NONE(RoundType.NORMAL),

    /** 这一轮要产出 / 注入一段总结(T4.1–T4.3)。 */
    SUMMARY_READY(RoundType.SUMMARY),

    /** 用户 / 角色明确问了时间(T6.4)。 */
    TIME_QUERY(RoundType.TEMP_QUERY),

    /** 导演刚改过时间,这一轮播一条轻量事件(T4.5)。 */
    DIRECTOR_EVENT(RoundType.DIRECTOR_EVENT),
  }

  /**
   * 这一轮算哪一类。
   *
   * 优先级(同时成立时取前面的)与先前的规则一一对应,没有新增时机:
   * 1. **临时查询**最优先 —— 用户问到时间,这一轮必须答,别的事件都不能把它挤掉;
   * 2. **总结**次之 —— 导演补丁的归宿是「下一次总结」([PendingPatchRule]),所以有总结就轮不到轻量事件;
   * 3. **导演事件**(有 pending 补丁)再次之;
   * 4. 都没有 ⇒ 普通轮(只允许稳定规则块)。
   */
  fun of(
    summaryReady: Boolean = false,
    timeQuery: Boolean = false,
    directorPatchPending: Boolean = false,
  ): RoundEvent = when {
    timeQuery -> RoundEvent.TIME_QUERY
    summaryReady -> RoundEvent.SUMMARY_READY
    directorPatchPending -> RoundEvent.DIRECTOR_EVENT
    else -> RoundEvent.NONE
  }

  /**
   * 按事件拼注入计划。所有「该不该注入」的判断都在 [RoundOrchestrator] 里,
   * 这里只把材料(总结块 / 补丁 / 保险丝)按事件装进 [RoundContext]。
   */
  fun plan(
    event: RoundEvent,
    summaryBlock: String = "",
    summaryMeta: SummaryMeta? = null,
    fuse: Period? = null,
    directorPatch: DirectorPatch? = null,
    clock: ClockState? = null,
    tempFacts: TempTimeFactStore? = null,
    logger: TimeLogger = TimeLogs.sink,
    at: Long = System.currentTimeMillis(),
    atRound: Int = 0,
  ): InjectionPlan {
    val context = when (event.roundType) {
      RoundType.NORMAL -> RoundContext()
      RoundType.SUMMARY -> RoundContext(summaryBlock = summaryBlock, summaryMeta = summaryMeta, fuse = fuse)
      RoundType.TEMP_QUERY -> RoundContext()
      RoundType.DIRECTOR_EVENT -> RoundContext(directorPatch = directorPatch)
    }
    return RoundOrchestrator.plan(
      roundType = event.roundType,
      context = context,
      tempFacts = tempFacts,
      logger = logger,
      at = at,
      atRound = atRound,
      clock = clock,
    )
  }

  /**
   * 主聊天一轮的注入计划:时钟里有 pending 补丁就走导演事件轮,否则普通轮。
   *
   * 普通轮**不注入任何时间事实** —— 调用方拿到计划后不要往 system 之外另加时间文本。
   */
  fun planForChat(
    clock: ClockState?,
    logger: TimeLogger = TimeLogs.sink,
    at: Long = System.currentTimeMillis(),
    atRound: Int = 0,
  ): InjectionPlan {
    val patch = clock?.pendingDirectorPatch
    val event = of(directorPatchPending = patch != null)
    return plan(
      event = event,
      directorPatch = patch,
      clock = clock,
      logger = logger,
      at = at,
      atRound = atRound,
    )
  }

  /**
   * 除规则块之外、需要**单独成条**注入的块(顺序即注入顺序)。
   *
   * 规则块不在这里:它已经进了 system 末尾,再单独发一条就是重复注入。
   * 这个是**未合并**的视图(分段边界与 [InjectionPlan.blocks] 一致),给日志与断言用;
   * 真正拼请求体时用 [messageBlocks]。
   */
  fun dynamicBlocks(plan: InjectionPlan): List<InjectBlock> =
    plan.blocks.filter { it.segment != InjectSegment.RULES && !it.isEmpty }

  /**
   * 拼请求体时要成条的内容:在 [dynamicBlocks] 基础上把**总结元数据并回总结块末尾**(T4.4)。
   *
   * 为什么不拆成两条:元数据是总结块的状态尾巴,拆成独立一条 user 消息后,模型更容易把它
   * 当成「用户刚说的一句话」去回应(而规则块第 4 条明确禁止引用它)。分段信息仍保留在
   * [InjectionPlan.blocks] 里,日志与白名单校验看的是那一份。
   */
  fun messageBlocks(plan: InjectionPlan): List<InjectBlock> {
    val out = mutableListOf<InjectBlock>()
    dynamicBlocks(plan).forEach { block ->
      val last = out.lastOrNull()
      if (block.segment == InjectSegment.SUMMARY_META && last?.segment == InjectSegment.SUMMARY) {
        out[out.lastIndex] = InjectBlock(InjectSegment.SUMMARY, "${last.text}\n${block.text}")
      } else {
        out.add(block)
      }
    }
    return out
  }
}
