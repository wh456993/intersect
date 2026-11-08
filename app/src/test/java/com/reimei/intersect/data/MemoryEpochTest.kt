package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆状态纪元(`clearedAt`)的单元测试。
 *
 * 它是「删消息 / 回滚」与「正在跑的总结」之间的唯一一道守卫:总结请求带着启动时的快照,
 * 回来时若盘上的纪元已经变了就丢弃结果。守卫的核心是**同一秒内必须也能区分**——
 * 用户在总结刚触发时点一下回滚,两件事完全可能落在同一秒里;那时若纪元没变,
 * 旧总结会把已经被删掉的那段轮次重新写回去,覆盖轮次也就被顶回原处。
 *
 * 落盘那半边(`reconcile` / `clear` 调 SharedPreferences)属于 instrumented 测试,
 * 这里只钉住纯逻辑部分。
 */
class MemoryEpochTest {

  @Test
  fun `同一秒内连续取纪元也会递增`() {
    val now = 1_700_000_000L
    val first = MemoryStore.nextEpoch(previous = 0L, now = now)
    val second = MemoryStore.nextEpoch(previous = first, now = now)
    assertEquals(now, first)
    assertNotEquals("同一秒内的两个纪元必须不同,否则在飞总结的守卫失效", first, second)
    assertEquals(now + 1, second)
  }

  @Test
  fun `纪元始终大于上一个并且不落后于当前秒`() {
    val now = 1_700_000_000L
    // 盘上的纪元比当前时钟还新(改过系统时间 / 时钟回拨)时不能退回去
    val restored = MemoryStore.nextEpoch(previous = now + 120, now = now)
    assertTrue(restored > now + 120)
  }

  @Test
  fun `跨秒时跟随时钟而不是继续加一`() {
    val previous = 1_700_000_000L
    assertEquals(1_700_000_100L, MemoryStore.nextEpoch(previous = previous, now = 1_700_000_100L))
  }

  @Test
  fun `多次取纪元保持单调`() {
    val now = 1_700_000_000L
    var epoch = 0L
    val seen = mutableListOf<Long>()
    repeat(5) {
      epoch = MemoryStore.nextEpoch(previous = epoch, now = now)
      seen.add(epoch)
    }
    assertEquals(seen.sorted(), seen)
    assertEquals(seen.size, seen.distinct().size)
  }
}
