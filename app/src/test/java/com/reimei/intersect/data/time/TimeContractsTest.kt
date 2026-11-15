package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.4 验收 —— 五份数据契约([TimeContracts])。
 *
 * 每份契约都要:1能用真实对象序列化出来;2Schema 校验通过;3少字段/多字段/类型错/枚举错
 * 都能被逮住。`clock` 还要额外保证**不含 round_type**(T1.5 修订)。
 */
class TimeContractsTest {

  private fun validClock(): JsonValue.Obj = ClockState(
    exactTime = 17 * 60,
    dateAnchor = 1,
    rate = 1.5,
    paused = false,
    lastSummaryPeriod = Period.NOON,
    pendingDirectorPatch = DirectorPatch(period = Period.DUSK, noun = "暮色", op = ClockOp.ALIGN, atRound = 7, at = 100L),
  ).toJson()

  @Test
  fun `五份契约都存在`() {
    listOf(
      TimeContracts.CLOCK,
      TimeContracts.SUMMARY_META,
      TimeContracts.TIME_EVENT,
      TimeContracts.DIRECTOR_TRANSACTION,
      TimeContracts.ROUND_TYPE,
    ).forEach { name -> assertNotNull("缺契约:$name", TimeContracts.schemaOf(name)) }
    assertEquals(5, TimeContracts.allSchemas().size)
  }

  @Test
  fun `clock 契约通过校验且不含 round_type`() {
    val clock = validClock()
    assertEquals(emptyList<String>(), TimeContracts.validateClock(clock))
    assertEquals(emptyList<String>(), TimeContracts.validate(TimeContracts.CLOCK, clock))
    assertTrue("clock 里不许出现 round_type", clock.fields.keys.none { it == "round_type" })
    // 字段齐:契约要求的三项特殊字段都在
    assertTrue(clock.fields.containsKey("pending_director_patch"))
    assertEquals("NOON", clock.strField("last_summary_period"))
  }

  @Test
  fun `clock 缺字段 多字段 类型错都能报出来`() {
    val base = validClock()

    val missing = JsonValue.Obj(base.fields - "exact_time")
    assertTrue(TimeContracts.validate(TimeContracts.CLOCK, missing).any { it.contains("缺字段 exact_time") })

    val extra = JsonValue.Obj(base.fields + ("round_type" to jstr("NORMAL")))
    assertTrue(TimeContracts.validateClock(extra).any { it.contains("round_type") })

    val wrongType = JsonValue.Obj(base.fields + ("exact_time" to jstr("17:00")))
    assertTrue(TimeContracts.validate(TimeContracts.CLOCK, wrongType).any { it.contains("类型应为 INT") })

    val badEnum = JsonValue.Obj(base.fields + ("period" to jstr("DAWNISH")))
    assertTrue(TimeContracts.validate(TimeContracts.CLOCK, badEnum).any { it.contains("不在枚举内") })

    val notObject: JsonValue = jstr("不是对象")
    assertTrue(TimeContracts.validate(TimeContracts.CLOCK, notObject).isNotEmpty())
  }

  @Test
  fun `内嵌的导演补丁也要过 Schema`() {
    val patch = DirectorPatch(period = Period.DAWN, noun = null, op = ClockOp.SET, atRound = 3, at = 9L)
    assertEquals(emptyList<String>(), TimeContracts.validatePatch(patch.toJson()))
    val broken = JsonValue.Obj(patch.toJson().fields + ("period" to jstr("NOPE")))
    assertTrue(TimeContracts.validatePatch(broken).any { it.contains("不在枚举内") })
  }

  @Test
  fun `summary_meta 契约覆盖 跨度 结束时段 名词提示 经过 关键事件 fallback`() {
    val meta = SummaryMeta(
      span = "第 1-20 轮",
      startPeriod = Period.MORNING,
      endPeriod = Period.DUSK,
      nounHint = FallbackResult("黄昏", FallbackLevel.DEFAULT_NOUN),
      elapsedMinutes = 480,
      keyEvents = listOf("下雨", "进城"),
    )
    val json = meta.toJson()
    assertEquals(emptyList<String>(), TimeContracts.validate(TimeContracts.SUMMARY_META, json))
    listOf("span", "end_period", "noun_hint", "elapsed", "key_events", "fallback").forEach {
      assertTrue("summary_meta 必须包含 $it", json.fields.containsKey(it))
    }
  }

  @Test
  fun `time_event 契约字段齐全`() {
    val event = jobj(
      "kind" to jstr(TimeEventKind.FUSE.name),
      "period" to jstr(Period.DUSK.name),
      "noun" to jstr(PeriodNouns.defaultNoun(Period.DUSK)),
      "exact_time" to jnum(17 * 60),
      "date_anchor" to jnum(1),
      "at_round" to jnum(40),
      "fallback" to jstr(FallbackLevel.DEFAULT_NOUN.name),
      "round_type" to jstr(RoundType.SUMMARY.name),
    )
    assertEquals(emptyList<String>(), TimeContracts.validate(TimeContracts.TIME_EVENT, event))
    assertTrue(
      TimeContracts.validate(TimeContracts.TIME_EVENT, jobj("kind" to jstr("NOPE"), "at_round" to jnum(1)))
        .any { it.contains("不在枚举内") },
    )
  }

  @Test
  fun `director_transaction 契约由真实事务日志填充`() {
    val authority = TimeAuthority(logger = InMemoryTimeLog(), clock = { 1_000L })
    val tx = authority.apply(DirectorCommand.Align(Period.DUSK, "暮色"))
    assertEquals(emptyList<String>(), TimeContracts.validate(TimeContracts.DIRECTOR_TRANSACTION, tx.toJson()))
    val json = tx.toJson()
    listOf("tx_id", "operator", "before", "after", "snapshot_ref", "at", "pending", "at_round").forEach {
      assertTrue("director_transaction 必须包含 $it", json.fields.containsKey(it))
    }
  }

  @Test
  fun `round_type 契约只接受四种轮次`() {
    RoundType.entries.forEach { roundType ->
      val json = jobj(
        "round_type" to jstr(roundType.name),
        "segments" to jarr(InjectionWhitelist.BY_ROUND.getValue(roundType).map { jstr(it.name) }),
      )
      assertEquals(emptyList<String>(), TimeContracts.validate(TimeContracts.ROUND_TYPE, json))
    }
    val bad = jobj("round_type" to jstr("SOMETHING_ELSE"), "segments" to jarr(emptyList()))
    assertTrue(TimeContracts.validate(TimeContracts.ROUND_TYPE, bad).any { it.contains("不在枚举内") })
  }
}
