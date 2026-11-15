package com.reimei.intersect.data

import com.reimei.intersect.data.memory.MemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段报文的结构契约(TASK-02 的验收):
 *
 * 1. `messages` 数组顺序恒为 `[system, user(状态快照), assistant(历史回复), user(当前输入)]`
 *    (首轮没有角色回复时为 3 条);
 * 2. `system` 里**没有**任何动态数据 —— 同一段历史连问两次,字面量逐字节一致;
 * 3. 状态快照首行是固定标注;
 * 4. 当前输入永远是最后一条,且带 `[时间]` 前缀与物品信息。
 */
class ChatServiceSegmentPayloadTest {
  private val friend = User("friend_1", "小明", 0, role = "程序员", personality = "话少")
  private val items = listOf(Item("item_1", "药水", "回复 10 点体力"))

  private fun me(text: String, time: String? = null) = Msg(User.Me, text, time = time)
  private fun other(text: String) = Msg(friend, text)

  private fun rounds(n: Int): List<Msg> = buildList {
    for (i in 1..n) {
      add(me("第 $i 句"))
      add(other("回 $i"))
    }
  }

  private val memory = MemorySnapshot(
    nodeSummary = "两人一路走到镇口。",
    microSummaries = listOf("第 1-20 轮的微总结", "第 21-40 轮的微总结"),
  )

  @Test
  fun `消息顺序是 system 快照 回复 当前输入`() {
    val messages = ChatService.messages(friend, rounds(3) + me("现在呢"), items, memory)
    assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.role })
    assertEquals(1, messages.indexOfFirst { it.content.startsWith(ChatService.SNAPSHOT_HEADER) })
    assertEquals("回 3", messages.first { it.role == "assistant" }.content)
    assertEquals("现在呢", messages.last().content)
  }

  @Test
  fun `首轮只有三条消息`() {
    val messages = ChatService.messages(friend, listOf(me("你好")), items, null)
    assertEquals(listOf("system", "user", "user"), messages.map { it.role })
  }

  @Test
  fun `system 里没有任何动态数据`() {
    val meta = MetaInfo(friendId = friend.id, time = "黄昏", location = "镇口酒馆")
    val first = ChatService.messages(friend, rounds(2) + me("在吗"), items, memory, meta = meta)
    val later = ChatService.messages(friend, rounds(9) + me("在吗"), items, memory, meta = meta)

    val system = first.first().content
    assertEquals("静态前缀必须逐字节一致", system, later.first().content)
    // 状态、记忆、当前输入一律不进 system
    assertFalse(system.contains(ChatService.SNAPSHOT_HEADER))
    assertFalse(system.contains("黄昏"))
    assertFalse(system.contains("镇口酒馆"))
    assertFalse(system.contains("两人一路走到镇口"))
    assertFalse(system.contains("在吗"))
    // 也不许出现随轮次变化的动态数字(时间戳 / token 数 / 轮次号):
    // 规则块里的小节编号(「1.」「2.」)是常量,不算动态数据,所以按「行首编号」排除
    val dynamicNumbers = Regex("(?m)(?<![0-9])\\d{4,}").findAll(system).toList()
    assertTrue("system 里出现了疑似时间戳/token 数:$dynamicNumbers", dynamicNumbers.isEmpty())
  }

  @Test
  fun `状态快照的首行是固定标注`() {
    val content = ChatService.snapshotBlock(MetaInfo.empty(friend.id), memory)
    assertTrue(content.startsWith(ChatService.SNAPSHOT_HEADER))
    assertTrue(content.indexOf(ChatService.SNAPSHOT_HEADER) < content.indexOf(MetaInfo.HEADER))
  }

  @Test
  fun `当前输入带时间前缀与物品信息`() {
    val messages = ChatService.messages(
      friend,
      rounds(1) + me("我要喝药水", time = "2025-01-15 14:05"),
      items,
      memory,
    )
    val last = messages.last()
    assertEquals("user", last.role)
    assertTrue(last.content.startsWith("[2025-01-15 14:05] 我要喝药水"))
    assertTrue(last.content.contains("[物品]"))
  }
}
