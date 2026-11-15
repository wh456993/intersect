package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.1 验收 —— [ClockState]。
 *
 * 1. 可序列化 / 反序列化(往返相等);
 * 2. **不含 round_type**;
 * 3. 写方法只暴露给事务执行器:别的身份来写会抛 [ClockPermissionError](T5.5 的一半也在这一层)。
 */
class ClockStateTest {

  @Test
  fun `状态往返相等`() {
    val state = ClockState(
      exactTime = 13 * 60 + 25,
      dateAnchor = 4,
      rate = 0.5,
      paused = true,
      lastSummaryPeriod = Period.NOON,
      pendingDirectorPatch = DirectorPatch(
        period = Period.AFTERNOON,
        noun = "午后",
        op = ClockOp.ALIGN,
        atRound = 12,
        at = 1_700_000_000L,
      ),
    )
    val restored = ClockState.fromJson(Json.parse(state.toJson().encode()))
    assertEquals(state, restored)
  }

  @Test
  fun `没有补丁时也能往返 空字段写成 null`() {
    val state = ClockState(exactTime = 60, dateAnchor = 0)
    val json = state.toJson()
    assertEquals(JsonValue.Null, json["last_summary_period"])
    assertEquals(JsonValue.Null, json["pending_director_patch"])
    assertEquals(state, ClockState.fromJson(Json.parse(json.encode())))
  }

  @Test
  fun `时段由分钟数确定性推出`() {
    assertEquals(Period.LATE_NIGHT, ClockState(exactTime = 0).period)
    assertEquals(Period.DAWN, ClockState(exactTime = 5 * 60).period)
    assertEquals(Period.MIDNIGHT, ClockState(exactTime = 23 * 60 + 59).period)
  }

  @Test
  fun `坏数据反序列化返回null或者是安全的默认值`() {
    assertNull(ClockState.fromJson(null))
    assertNull(ClockState.fromJson(jstr("x")))
    assertNull(ClockState.fromJson(jobj("period" to jstr("DUSK"))))
    // 分钟数超界:归一,不抛
    val wrapped = ClockState.fromJson(jobj("exact_time" to jnum(1440 + 60), "rate" to jnum(0.0), "date_anchor" to jnum(0)))
    requireNotNull(wrapped)
    assertEquals(60, wrapped.exactTime)
    assertEquals("倍率为 0 是坏数据,回落 1.0", 1.0, wrapped.rate, 0.0001)
  }

  @Test
  fun `写权限只给事务执行器`() {
    ClockWrites.resetForTest()
    val state = ClockState()

    // 还没注册:连执行器都写不了
    assertThrows(ClockPermissionError::class.java) {
      state.write(ClockWrites.EXECUTOR_OWNER) { it.copy(paused = true) }
    }

    // 执行器构造时注册
    TimeAuthority(logger = InMemoryTimeLog())
    assertTrue(ClockWrites.isRegistered())

    // 其他身份:拒绝
    val denied = assertThrows(ClockPermissionError::class.java) {
      state.write("ai-assistant") { it.copy(paused = true) }
    }
    assertTrue(denied.message!!.contains("只允许事务执行器"))

    // 执行器:通过
    val written = state.write(ClockWrites.EXECUTOR_OWNER) { it.copy(paused = true) }
    assertTrue(written.paused)
  }
}
