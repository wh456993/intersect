package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 角色唯一 ID 的分配规则单元测试(不含存储:那部分在 androidTest 里)。
 *
 * 规则一旦走样(把 0 / 1 发给角色、恢复备份后又发同一个号),记忆索引就对不上人了,
 * 所以把「intersect = 0 / 用户 = 1 / 角色从 2 起 / 不倒退」这几条钉死在这里。
 */
class UidStoreTest {
  @Test
  fun `intersect 固定 0 号, 用户自己固定 1 号`() {
    assertEquals(0L, UidStore.INTERSECT_UID)
    assertEquals(1L, UidStore.ME_UID)
    assertEquals(0L, DawnAssistant.assistant().uid)
    assertEquals(UidStore.ME_UID, User.Me.uid)
  }

  @Test
  fun `第一个发给角色的号是 2`() {
    assertEquals(2L, UidStore.FIRST_FREE_UID)
    assertEquals(2L, UidStore.nextFree(UidStore.FIRST_FREE_UID))
  }

  @Test
  fun `计数器正常推进时原样给出下一个号`() {
    // 号不回收:已经发到 7 就接着给 7,不会因为中间删过角色而回退
    assertEquals(7L, UidStore.nextFree(7L))
    assertEquals(1024L, UidStore.nextFree(1024L))
  }

  @Test
  fun `计数器的脏值被拉回起点, 永远不会发出 0 与 1`() {
    assertEquals(2L, UidStore.nextFree(0L))
    assertEquals(2L, UidStore.nextFree(1L))
    assertEquals(2L, UidStore.nextFree(-5L))
  }

  @Test
  fun `登记外部带来的号之后, 计数器至少跳到它后面`() {
    // 恢复备份回来的角色带着号 9:之后的计数器至少是 10,新角色不会拿到 9
    assertEquals(10L, UidStore.counterAfter(2L, 9L))
    // 比当前计数器小的号不会把计数器拉回去
    assertEquals(20L, UidStore.counterAfter(20L, 9L))
    // intersect(0)与我在(1)的号不会把计数器拉到起点之前
    assertEquals(2L, UidStore.counterAfter(2L, UidStore.INTERSECT_UID))
    assertEquals(2L, UidStore.counterAfter(2L, UidStore.ME_UID))
  }
}
