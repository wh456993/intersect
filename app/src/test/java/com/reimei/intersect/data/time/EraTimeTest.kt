package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非公元纪年与「基准时间」的验收。
 *
 * 三件事必须钉住:
 * 1. **纪年只是显示**:`第三纪 +302.5.6 8:22` 与 `302.5.6 8:22` 解析出同一个日期锚点,
 *    推进 / 跨日照旧按天序号走,不会因为换了个纪年名就跑偏;
 * 2. **正负号**:年份可正可负,`+` 只在显式给时才显示,负数自带 `-`;
 * 3. **基准时间是程序设定**:它作为账本初始状态落盘,不产生事务 —— 事务日志必须从空开始。
 */
class EraTimeTest {

  @Test
  fun `带纪年名与正号的时间可以解析回同一锚点`() {
    val era = requireNotNull(SceneCalendar.parse("第三纪 +302.5.6 8:22"))
    val plain = requireNotNull(SceneCalendar.parse("302.5.6 8:22"))
    assertEquals(plain.dateAnchor, era.dateAnchor)
    assertEquals(8 * 60 + 22, era.minuteOfDay)
    assertEquals("第三纪", era.eraName)
    assertTrue(era.plusSign)
    assertFalse(plain.plusSign)
    assertEquals("", plain.eraName)
  }

  @Test
  fun `纪年时间可以格式化回去 往返相等`() {
    val texts = listOf(
      "第三纪 +302.5.6 8:22",
      "罗历 302.5.6 8:22",
      "-302.5.6 8:22",
      "公元 2026.5.6 0:05",
    )
    texts.forEach { text ->
      val parsed = requireNotNull(SceneCalendar.parse(text)) { "解析失败:$text" }
      assertEquals(text, SceneCalendar.label(parsed.dateAnchor, parsed.minuteOfDay, parsed.eraName, parsed.plusSign))
    }
  }

  @Test
  fun `负年份不会叠两个减号`() {
    val parsed = requireNotNull(SceneCalendar.parse("-302.5.6 8:22"))
    assertFalse(parsed.plusSign)
    assertEquals("-302.5.6 8:22", parsed.label)
  }

  @Test
  fun `纪年名不能含数字`() {
    // 带数字就分不清哪一位开始是年份,按 R6 一律拒绝(不猜)
    assertNull(SceneCalendar.parse("第3纪 +302.5.6 8:22"))
  }

  @Test
  fun `纪年名过长或年份越界一律拒绝`() {
    assertNull(SceneCalendar.parse("纪".repeat(SceneConfig.ERA_NAME_MAX_CHARS + 1) + "302.5.6 8:22"))
    assertNull(SceneCalendar.parse("第三纪 +99999.5.6 8:22"))
    assertNull(SceneCalendar.parse("第三纪 +302.13.6 8:22"))   // 月份越界
    assertNull(SceneCalendar.parse("第三纪 +302.5.6 25:00"))   // 小时越界
    assertNull(SceneCalendar.parse("第三纪 +302.5.6"))         // 没有时刻
    assertNull(SceneCalendar.parse("第三纪 302.5.6 8:22 傍晚"))  // 多了一段
  }

  @Test
  fun `换了纪年推进与跨日仍按天序号走`() {
    val state = ClockState(exactTime = 23 * 60 + 30, dateAnchor = 0, eraName = "第三纪", eraPlusSign = true)
    val advanced = SceneClock.advance(state, 60)
    assertEquals(30, advanced.exactTime)
    assertEquals(1, advanced.dateAnchor)      // 跨了一个午夜
    assertEquals("第三纪", advanced.eraName)  // 纪年名跟着状态走,不会丢
    assertTrue(advanced.eraPlusSign)
    assertEquals("第三纪 +${SceneConfig.EPOCH_YEAR}.1.2 0:30", SceneCalendar.label(advanced))
  }

  @Test
  fun `纪年字段随账本序列化 往返相等`() {
    val state = ClockState(exactTime = 8 * 60, dateAnchor = 5, place = "北境", eraName = "第三纪", eraPlusSign = true)
    val decoded = requireNotNull(ClockState.fromJson(state.toJson()))
    assertEquals(state, decoded)
    assertEquals("第三纪", decoded.eraName)
    assertTrue(decoded.eraPlusSign)
  }

  @Test
  fun `契约接受纪年字段 且 clock 仍不允许 round_type`() {
    val state = ClockState(eraName = "第三纪", eraPlusSign = true)
    val problems = TimeContracts.validateClock(state.toJson())
    assertTrue(problems.toString(), problems.isEmpty())

    val withRoundType = state.toJson().let { obj ->
      JsonValue.Obj(LinkedHashMap(obj.fields).apply { put("round_type", jstr("NORMAL")) })
    }
    assertTrue(TimeContracts.validateClock(withRoundType).any { it.contains("round_type") })
  }

  @Test
  fun `导演面板改纪年会进变更行`() {
    val current = ClockState(exactTime = 8 * 60, dateAnchor = 0)
    val parsed = requireNotNull(SceneCalendar.parse("第三纪 +302.5.6 8:22"))
    val edit = SceneEdit(
      dateAnchor = parsed.dateAnchor,
      minuteOfDay = parsed.minuteOfDay,
      place = "",
      weather = "",
      eraName = parsed.eraName,
      plusSign = parsed.plusSign,
    )
    val change = SceneEditor.changesOf(current, edit).single()
    assertEquals(SceneField.TIME, change.field)
    assertEquals("<2026.1.1 8:00变更第三纪 +302.5.6 8:22>", change.line())
  }

  @Test
  fun `基准时间解析出初始状态 且不产生事务`() {
    val state = requireNotNull(BaseTime.parse("第三纪 +302.5.6 8:22"))
    assertEquals("第三纪", state.eraName)
    assertTrue(state.eraPlusSign)
    val ledger = BaseTime.seedLedger(state)
    assertEquals(state, ledger.state)
    assertTrue(ledger.transactions.isEmpty())
    assertTrue(ledger.snapshots.isEmpty())
    // 基准落盘 → 读回来还是同一个时刻
    assertEquals(state, requireNotNull(ClockState.fromJson(ledger.toJson()["state"])))
  }

  @Test
  fun `空白基准用出厂值 格式不对返回 null`() {
    assertEquals(BaseTime.DEFAULT, BaseTime.parseOrEmpty(""))
    assertEquals(BaseTime.DEFAULT, BaseTime.parseOrEmpty("   "))
    assertEquals(BaseTime.DEFAULT, BaseTime.parseOrEmpty(null))
    assertNull(BaseTime.parseOrEmpty("第三纪 +302.5.6"))
    // 出厂值的文本就是纪元当天 8:00
    assertEquals("2026.1.1 8:00", BaseTime.format(BaseTime.DEFAULT))
    // 空白 = 没改过:造出来的账本就是一份干净的初始状态(创建流程据此决定「未改不写账本」)
    val seeded = BaseTime.seedLedger(requireNotNull(BaseTime.parseOrEmpty("")))
    assertTrue(seeded.transactions.isEmpty())
    assertTrue(seeded.snapshots.isEmpty())
  }

  @Test
  fun `基准时间写的纪年在后续导演面板里继续可见`() {
    val seeded = BaseTime.seedLedger(requireNotNull(BaseTime.parse("罗历 +302.5.6 8:22")))
    val session = ClockSession(seeded)
    // 面板打开时的初始值就带纪年名,不会掉成公元写法
    assertEquals("罗历 +302.5.6 8:22", SceneEditor.draftOf(session.state).time)
    assertEquals("罗历 +302.5.6 8:22", SceneCalendar.label(session.state))
  }

  @Test
  fun `改已有角色的基准时间只换时间 其它字段全部保留`() {
    // 一份「导演已经改过场景、还有待注入补丁」的状态:地点、天气、倍率、暂停、补丁都在
    val patch = DirectorPatch(period = Period.MORNING, noun = "清晨", atRound = 3)
    val current = ClockState(
      exactTime = 9 * 60,
      dateAnchor = 12,
      rate = 2.0,
      paused = true,
      pendingDirectorPatch = patch,
      place = "北境军府",
      weather = "雪",
      eraName = "罗历",
      eraPlusSign = true,
    )
    val merged = requireNotNull(BaseTime.mergeInto(current, "第三纪 +302.5.6 8:22"))
    // 时间四项换掉了
    assertEquals("第三纪", merged.eraName)
    assertTrue(merged.eraPlusSign)
    assertEquals(8 * 60 + 22, merged.exactTime)
    assertEquals(
      requireNotNull(SceneCalendar.parse("302.5.6 8:22")).dateAnchor,
      merged.dateAnchor
    )
    // 其余字段必须原样:地点 / 天气被抹掉会静默清空下一轮注入的场景事实,
    // 待注入补丁没了那一轮的一次性注入就再也不会出现
    assertEquals("北境军府", merged.place)
    assertEquals("雪", merged.weather)
    assertEquals(2.0, merged.rate, 0.0)
    assertTrue(merged.paused)
    assertEquals(patch, merged.pendingDirectorPatch)
  }

  @Test
  fun `合并基准时间时看不懂就返回 null 空白用出厂值`() {
    val current = ClockState(place = "北境军府")
    assertNull(BaseTime.mergeInto(current, "第三纪 +302.5.6"))
    // 空白 = 用出厂值,但地点这些仍然留着
    val blank = requireNotNull(BaseTime.mergeInto(current, " "))
    assertEquals(BaseTime.DEFAULT.exactTime, blank.exactTime)
    assertEquals(BaseTime.DEFAULT.dateAnchor, blank.dateAnchor)
    assertEquals("北境军府", blank.place)
  }
}
