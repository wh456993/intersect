package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导演模式面板的**写入与注入**验收。
 *
 * 覆盖四件事(每条都是这个功能的验收项):
 * 1. 保存走唯一写入口 [TimeAuthority.applyScene]:留快照与事务,可按 `tx_id` 回滚;
 * 2. **没改就不写**:差异为空 ⇒ 没有事务、没有补丁、下一轮也不会重注入;
 * 3. 注入行是 `<旧值变更新值>` 形态,且**只注入一次**([TimeAuthority.consumeSceneChanges]);
 * 4. 契约与序列化同步(place / weather / changes 都在,旧账本读回来不炸)。
 */
class SceneEditTest {

  private val logger = InMemoryTimeLog()
  private var now = 1_000L

  private fun authority(state: ClockState = ClockState()): TimeAuthority =
    TimeAuthority(ClockLedger(state = state), logger) { now }

  private fun edit(
    time: String = "2026.1.2 9:40",
    place: String = "上海",
    weather: String = "小雨",
  ): SceneEdit {
    val parsed = requireNotNull(SceneCalendar.parse(time))
    return SceneEdit(parsed.dateAnchor, parsed.minuteOfDay, place, weather)
  }

  @Test
  fun `一次保存同时写时间 地点与天气`() {
    val authority = authority(ClockState(exactTime = 8 * 60 + 22, dateAnchor = 0, place = "北京", weather = "晴"))
    val tx = requireNotNull(authority.applyScene(edit(), atRound = 3))

    assertEquals(ClockOp.SCENE, tx.op)
    assertEquals(TxOperator.DIRECTOR, tx.operator)
    assertEquals(1, tx.after.dateAnchor)
    assertEquals(9 * 60 + 40, tx.after.exactTime)
    assertEquals("上海", tx.after.place)
    assertEquals("小雨", tx.after.weather)
    // 补丁带着变更明细,等下一轮注入
    val patch = requireNotNull(tx.after.pendingDirectorPatch)
    assertEquals(ClockOp.SCENE, patch.op)
    assertEquals(listOf(SceneField.TIME, SceneField.PLACE, SceneField.WEATHER), patch.changes.map { it.field })
    assertEquals(3, patch.atRound)
  }

  @Test
  fun `没改就不产生事务与补丁`() {
    val state = ClockState(exactTime = 8 * 60, dateAnchor = 0, place = "北京", weather = "晴")
    val authority = authority(state)
    val tx = authority.applyScene(edit(time = "2026.1.1 8:00", place = "北京", weather = "晴"))
    assertNull(tx)
    assertEquals(state, authority.state)
    assertTrue(authority.transactions().isEmpty())
    assertTrue(authority.snapshots().isEmpty())
  }

  @Test
  fun `注入行是旧值变更新值 且只在注入一次之后就清掉`() {
    val authority = authority(ClockState(exactTime = 8 * 60 + 22, dateAnchor = 0, place = "北京", weather = "晴"))
    val tx = requireNotNull(authority.applyScene(edit()))

    // 这一轮的注入计划:导演事件轮,正文就是那三行
    val plan = TimeRounds.plan(
      event = TimeRounds.RoundEvent.DIRECTOR_EVENT,
      directorPatch = authority.state.pendingDirectorPatch,
      logger = logger,
    )
    assertEquals(RoundType.DIRECTOR_EVENT, plan.roundType)
    val text = plan.blocks.single { it.segment == InjectSegment.DIRECTOR_EVENT }.text
    assertTrue(text, text.contains("<2026.1.1 8:22变更2026.1.2 9:40>"))
    assertTrue(text, text.contains("<北京变更上海>"))
    assertTrue(text, text.contains("<晴变更小雨>"))
    // 这一轮只允许规则块 + 导演事件两段(白名单没被这一幕绕过)
    assertEquals(listOf(InjectSegment.RULES, InjectSegment.DIRECTOR_EVENT), plan.segments)

    // 注入过之后清掉明细:补丁还在(等总结吸收),但变更行不再重复
    val consumed = requireNotNull(authority.consumeSceneChanges(atRound = 4))
    assertEquals(ClockOp.CONSUME, consumed.op)
    assertTrue(consumed.after.pendingDirectorPatch?.changes.isNullOrEmpty())
    assertNotEquals(null, consumed.after.pendingDirectorPatch)
    val again = TimeRounds.plan(
      event = TimeRounds.RoundEvent.DIRECTOR_EVENT,
      directorPatch = authority.state.pendingDirectorPatch,
      logger = logger,
    )
    assertFalse(again.text().contains("变更2026"))
    // 再清一次什么都没有:不该凭空留事务
    assertNull(authority.consumeSceneChanges(atRound = 5))
    assertEquals(2, authority.transactions().size)
    assertEquals(tx.txId, authority.transactions().last().txId)
  }

  @Test
  fun `回滚把地点与天气一起放回去`() {
    val before = ClockState(exactTime = 8 * 60 + 22, dateAnchor = 0, place = "北京", weather = "晴")
    val authority = authority(before)
    val tx = requireNotNull(authority.applyScene(edit()))
    assertEquals("上海", authority.state.place)

    val rollback = requireNotNull(authority.rollback(tx.txId, atRound = 5))
    assertEquals(ClockOp.ROLLBACK, rollback.op)
    assertEquals(before, authority.state)
    assertEquals("北京", authority.state.place)
    assertEquals("晴", authority.state.weather)
  }

  @Test
  fun `AI 无权改场景`() {
    val authority = authority()
    val error = assertThrows(ClockPermissionError::class.java) {
      authority.applyScene(edit(), operator = TxOperator.AI)
    }
    assertTrue(error.message.orEmpty().contains("AI"))
    assertTrue(authority.transactions().isEmpty())
  }

  @Test
  fun `ClockSession 面板入口 回显变更行 并区分格式问题`() {
    val session = ClockSession(ClockLedger(ClockState(exactTime = 8 * 60 + 22, dateAnchor = 0, place = "北京")), logger)
    val ok = session.editScene(SceneDraft(time = "2026.1.2 9:40", place = "上海", weather = "小雨"), atRound = 2)
    assertTrue(ok.applied)
    assertTrue(ok.echo.contains("<2026.1.1 8:22变更2026.1.2 9:40>"))
    assertEquals(SceneField.values().toList(), ok.tx!!.after.pendingDirectorPatch!!.changes.map { it.field })

    // 原样再保存一次:没改,不写时钟,但也不算「失败」
    val same = session.editScene(SceneDraft(time = "2026.1.2 9:40", place = "上海", weather = "小雨"), atRound = 3)
    assertFalse(same.applied)
    assertNull(same.rejection)

    // 格式看不懂:带原因拒绝,时钟不动
    val before = session.state
    val bad = session.editScene(SceneDraft(time = "傍晚"), atRound = 4)
    assertFalse(bad.applied)
    assertEquals(SceneEditor.REJECT_TIME, bad.rejection)
    assertEquals(before, session.state)
  }

  @Test
  fun `状态往返带上地点 天气与变更明细`() {
    val state = ClockState(
      exactTime = 9 * 60 + 40,
      dateAnchor = 1,
      place = "上海",
      weather = "小雨",
      pendingDirectorPatch = DirectorPatch(
        period = Period.MORNING,
        op = ClockOp.SCENE,
        changes = listOf(
          SceneChange(SceneField.TIME, "2026.1.1 8:22", "2026.1.2 9:40"),
          SceneChange(SceneField.PLACE, "北京", "上海"),
        ),
      ),
    )
    val restored = requireNotNull(ClockState.fromJson(Json.parse(state.toJson().encode())))
    assertEquals(state, restored)
    assertEquals(2, restored.pendingDirectorPatch!!.changes.size)
  }

  @Test
  fun `旧账本没有地点与天气字段也能读回来`() {
    val legacy = """
      {"exact_time":480,"period":"MORNING","date_anchor":0,"rate":1.0,"paused":false,
       "last_summary_period":null,"pending_director_patch":null}
    """.trimIndent()
    val state = requireNotNull(ClockState.fromJson(Json.parse(legacy)))
    assertEquals("", state.place)
    assertEquals("", state.weather)
    assertTrue(TimeContracts.validateClock(state.toJson()).isEmpty())
  }

  @Test
  fun `契约接受新字段 也仍然拒绝未声明字段`() {
    val state = ClockState(place = "北京", weather = "晴")
    assertTrue(TimeContracts.validateClock(state.toJson()).isEmpty())
    val patch = DirectorPatch(
      period = Period.MORNING,
      op = ClockOp.SCENE,
      changes = listOf(SceneChange(SceneField.PLACE, "北京", "上海")),
    )
    assertTrue(TimeContracts.validatePatch(patch.toJson()).isEmpty())

    val extra = state.toJson().let { obj ->
      JsonValue.Obj(LinkedHashMap(obj.fields).apply { put("mood", jstr("平静")) })
    }
    assertTrue(TimeContracts.validateClock(extra).isNotEmpty())
  }

  @Test
  fun `导演事件模板在有明细时不再输出 操作 时段 名词`() {
    val withChanges = DirectorPatch(
      period = Period.MORNING,
      op = ClockOp.SCENE,
      changes = listOf(SceneChange(SceneField.WEATHER, "晴", "小雨")),
    )
    val text = DirectorEvents.format(withChanges)
    assertTrue(text.startsWith(TimeInject.DIRECTOR_HEADER))
    assertTrue(text.contains("<晴变更小雨>"))
    assertFalse(text.contains("{op}"))
    assertFalse(text.contains("名词:"))

    // 没有明细的老路子(/time align 之类)照旧走固定模板
    val plain = DirectorEvents.format(DirectorPatch(Period.DUSK, "暮色"))
    assertTrue(plain.contains("操作:"))
    assertTrue(plain.contains("时段:DUSK"))
    assertFalse(DirectorEvents.triggersSummary())
  }
}
