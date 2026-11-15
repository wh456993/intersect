package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.4 验收 —— 生产验收清单里标注「自动」的项目逐条自动化。
 *
 * 每一条都跑一段**真实链路**(不是断言常量),失败的条目会连编号与原文一起报出来,
 * 方便对着清单逐条核对。清单里标注「自动 + 人工」的两条(第 11 条)这里覆盖自动部分,
 * 人工核对部分见 [MANUAL_ITEMS] 的说明(T7.5)。
 */
class TimeAcceptanceTest {

  private class Item(val id: String, val text: String, val check: () -> Boolean)

  private val logger = InMemoryTimeLog()

  private fun item(id: String, text: String, check: () -> Boolean) = Item(id, text, check)

  /** 生产验收清单(对应需求第 8 节的 16 条)。 */
  private fun checklist(): List<Item> = listOf(
    item("1", "普通轮次上下文无时间事实,仅规则块") {
      val plan = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), logger = logger)
      plan.segments == listOf(InjectSegment.RULES) && !plan.hasTimeFact()
    },
    item("2", "总结块末尾含跨度、结束时段、名词提示、fallback") {
      val meta = SummaryChain.next(null, null, "第 1-20 轮", Period.DUSK, 480, logger = logger)
      val text = meta.format()
      text.contains("跨度:") && text.contains("结束时段:") && text.contains("名词提示:") && text.contains("fallback:")
    },
    item("3", "总结轮 = 规则块 + 总结块 + 元数据,顺序固定") {
      val meta = SummaryChain.next(null, null, "第 1-20 轮", Period.DUSK, 480, logger = logger)
      val plan = RoundOrchestrator.plan(
        RoundType.SUMMARY,
        RoundContext(summaryBlock = "正文", summaryMeta = meta),
        logger = logger,
      )
      plan.segments == listOf(InjectSegment.RULES, InjectSegment.SUMMARY, InjectSegment.SUMMARY_META) &&
        plan.text().endsWith(meta.format())
    },
    item("4", "跨时段自动触发总结或保险丝注入") {
      val crossTriggers = SummaryTriggers.decide(SummaryTriggerInput(0, 0, periodChanged = true)) ==
        SummaryTrigger.PERIOD_CROSS
      val fuseInstead = TimeDedupe.resolve(
        summaryDue = false,
        periodChanged = true,
        roundsSinceSummary = TimeThresholds.FUSE_THRESHOLD_ROUNDS,
      ).fuse
      crossTriggers && fuseInstead
    },
    item("5", "导演对齐保留分钟,跨日按 date_anchor 规则,支持快照回滚") {
      val inside = SceneClock.align(ClockState(exactTime = 10 * 60 + 25), Period.MORNING)
      val keepMinute = inside.exactTime == 10 * 60 + 25
      val crossDay = SceneClock.align(ClockState(exactTime = 23 * 60, dateAnchor = 1), Period.DAWN)
      val anchorRule = crossDay.dateAnchor == 2
      val clock = TimeAuthority(logger = logger, clock = { 1L })
      val origin = clock.state
      val tx = clock.apply(DirectorCommand.Align(Period.DUSK))
      val rolledBack = clock.rollback(tx.txId) != null && clock.state == origin
      keepMinute && anchorRule && rolledBack
    },
    item("6", "round_type 由编排层确定,不进入 ClockState,不交给 AI") {
      val noFieldInState = !ClockState().toJson().keys().contains("round_type")
      val orchestratorDecides = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), logger = logger).roundType ==
        RoundType.NORMAL
      noFieldInState && orchestratorDecides
    },
    item("7", "阈值集中定义在 config/time_thresholds,测试锁定") {
      TimeThresholds.FROZEN == mapOf(
        "summary_interval_rounds" to 20,
        "summary_interval_tokens" to 3000,
        "fuse_threshold_rounds" to 30,
      )
    },
    item("8", "名词提示不替代结构化时段") {
      val meta = SummaryChain.next(null, DirectorPatch(Period.DUSK, "暮色四合"), "第 1-20 轮", Period.DUSK, 480, logger = logger)
      // 名词是「暮色四合」,结构化时段仍然是 DUSK —— 两者同时出现
      meta.nounHint.text == "暮色四合" && meta.toJson().strField("end_period") == "DUSK"
    },
    item("9", "精确时间默认只给 UI/导演/日志") {
      val normal = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), logger = logger)
      val summary = RoundOrchestrator.plan(
        RoundType.SUMMARY,
        RoundContext(summaryBlock = "正文", summaryMeta = SummaryChain.next(null, null, "第 1-20 轮", Period.NOON, 60, logger = logger)),
        logger = logger,
      )
      val clockLabel = Period.clockLabel(Period.NOON.startMinute) // "11:00"
      !normal.text().contains(clockLabel) && !summary.text().contains(clockLabel) &&
        TimeFallback.exactLabel(ClockState(exactTime = Period.NOON.startMinute)).contains(clockLabel)
    },
    item("10", "fallback 有标记、有日志") {
      val logger = InMemoryTimeLog()
      val meta = SummaryChain.next(null, null, "第 1-20 轮", Period.EVENING, 60, logger = logger, at = 5L)
      meta.fallback == FallbackLevel.DEFAULT_NOUN && meta.toJson().strField("fallback") == "DEFAULT_NOUN" &&
        logger.of(TimeLogCategory.FALLBACK).isNotEmpty()
    },
    item("11", "AI 输出不复述标签、不自行报时(自动部分)") {
      val cleaned = OutputGuard.sanitize("[时段变更] 现在是下午三点。她把门关上。")
      !cleaned.text.contains("[时段变更]") && !cleaned.text.contains("三点") && cleaned.text.contains("她把门关上")
    },
    item("12", "角色查询时间用后即焚") {
      val store = TempTimeFactStore(logger)
      CharacterTimeQuery.issue(ClockState(exactTime = 12 * 60), store, logger = logger)
      val plan = RoundOrchestrator.plan(RoundType.TEMP_QUERY, RoundContext(), tempFacts = store, logger = logger)
      plan.contains(InjectSegment.TEMP_FACT) && store.isEmpty()
    },
    item("13", "冲突优先级明确且有测试") {
      TxOperator.winner(TxOperator.DIRECTOR, TxOperator.PROGRAM) == TxOperator.DIRECTOR &&
        TxOperator.winner(TxOperator.PROGRAM, TxOperator.AI) == TxOperator.PROGRAM
    },
    item("14", "ClockState 写方法仅暴露给 T5.3,其他调用抛 PermissionError") {
      TimeAuthority(logger = logger) // 注册执行器
      val denied = try {
        ClockState().write("ai") { it }
        false
      } catch (e: ClockPermissionError) {
        true
      }
      val allowed = ClockState().write(ClockWrites.EXECUTOR_OWNER) { it.copy(paused = true) }.paused
      denied && allowed
    },
    item("15", "导演时间事件模板由 T4.5 定义,注入层只按 round_type=director_event 插入") {
      val patch = DirectorPatch(Period.DAWN, "天刚亮", ClockOp.ALIGN)
      val asEvent = RoundOrchestrator.plan(RoundType.DIRECTOR_EVENT, RoundContext(directorPatch = patch), logger = logger)
      val asNormal = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(directorPatch = patch), logger = logger)
      asEvent.contains(InjectSegment.DIRECTOR_EVENT) &&
        DirectorEvents.format(patch) == TimeTemplates.DIRECTOR_EVENT_TEMPLATE
          .replace("{op}", "ALIGN").replace("{period}", "DAWN").replace("{noun}", "天刚亮") &&
        !asNormal.contains(InjectSegment.DIRECTOR_EVENT)
    },
    item("16", "自然语言解析受限模板来自 T1.6,模板外低置信不写时钟") {
      val clock = TimeAuthority(logger = logger, clock = { 1L })
      val before = clock.state
      val inside = DirectorParser.parse("时间来到黄昏")
      val outside = DirectorParser.parse("时间好像变慢了")
      inside is DirectorParse.Ok && inside.confidence == ParseConfidence.HIGH &&
        outside is DirectorParse.Low && clock.state == before
    },
  )

  @Test
  fun `生产验收清单自动项逐条通过`() {
    val items = checklist()
    assertEquals("自动项数量必须与清单一致", 16, items.size)
    val failures = items.filterNot { runCatching { it.check() }.getOrDefault(false) }
    assertTrue(
      "未通过的验收项:" + failures.joinToString(";") { "${it.id} ${it.text}" },
      failures.isEmpty(),
    )
  }

  @Test
  fun `清单编号连续且不重复`() {
    val ids = checklist().map { it.id }
    assertEquals((1..16).map { it.toString() }, ids)
  }

  companion object {
    /** T7.5 人工核对项:需要人看模型输出样本,自动化只覆盖到清洗与标记层。 */
    val MANUAL_ITEMS: List<String> = listOf(
      "AI 输出样本里是否真的不再自行报时 / 复述标签(需人读回复)",
      "元数据虽已贴尾,模型是否仍把它当台词引用(需人读回复)",
    )
  }
}
