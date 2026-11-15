package com.reimei.intersect.data

import com.reimei.intersect.data.memory.MemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆库在请求体里的拼接契约(分段重构之后的版本)。
 *
 * 记忆不再单独成一条 user 消息,也不再充当「时间总结块」:它只作为**状态快照**的一部分出现,
 * 顺序固定为「元信息 → 主线大总结 → 近期微总结」。这里钉住的是记忆那一半
 * (元信息与整体结构另见 [ChatServiceTemplatePayloadTest] / [ChatServiceSegmentPayloadTest]):
 *
 * 1. 大总结与微总结都在状态快照里,且排在元信息块之后;
 * 2. 没有记忆时快照里只有元信息,不出现空的记忆小节;
 * 3. 快照是第二条消息,当前输入永远是最后一条,报文条数恒为 4(首轮为 3)。
 */
class ChatServiceMemoryPayloadTest {
  private val friend = User("friend_1", "小明", 0, role = "程序员", personality = "话少")
  private val items = listOf(Item("item_1", "药水", "回复 10 点体力"))

  private fun me(text: String) = Msg(User.Me, text)
  private fun other(text: String) = Msg(friend, text)

  /** 造 n 轮对话:第 i 轮是「第 i 句」/「回 i」。 */
  private fun rounds(n: Int): List<Msg> = buildList {
    for (i in 1..n) {
      add(me("第 $i 句"))
      add(other("回 $i"))
    }
  }

  private fun snapshot(node: String = "", micros: List<String> = emptyList()) =
    MemorySnapshot(nodeSummary = node, microSummaries = micros)

  @Test
  fun `第一条是 system 且带着记忆使用规则`() {
    val messages = ChatService.messages(friend, rounds(1) + me("现在呢"), items, snapshot())
    assertEquals("system", messages.first().role)
    assertTrue(messages.first().content.contains("名称:小明"))
    assertTrue(messages.first().content.contains(MemoryInject.RULES))
  }

  @Test
  fun `大总结与微总结进状态快照 且排在元信息块之后`() {
    val history = rounds(20) + me("新一轮")
    val messages = ChatService.messages(
      friend,
      history,
      items,
      snapshot(
        node = "两人从码头一路走到镇口,约定天亮前离开。",
        micros = listOf("第 41-60 轮的微总结", "第 21-40 轮的微总结", "第 1-20 轮的微总结"),
      ),
    )
    val snapshotMessage = messages[1]
    assertEquals("user", snapshotMessage.role)
    val content = snapshotMessage.content
    assertTrue(content.startsWith(ChatService.SNAPSHOT_HEADER))
    assertTrue(content.contains(ChatService.MEMORY_SECTION))
    assertTrue(content.contains(ChatService.NODE_SECTION))
    assertTrue(content.contains(ChatService.MICRO_SECTION))
    assertTrue(content.contains("两人从码头一路走到镇口"))
    assertTrue(content.contains("第 1-20 轮的微总结"))
    // 元信息在前、记忆在后
    assertTrue(content.indexOf(MetaInfo.HEADER) < content.indexOf(ChatService.MEMORY_SECTION))
    // 大总结在前、微总结在后
    assertTrue(content.indexOf(ChatService.NODE_SECTION) < content.indexOf(ChatService.MICRO_SECTION))
    // 当前输入仍然独立成最后一条,没有被并进快照
    assertEquals("新一轮", messages.last().content)
  }

  @Test
  fun `没有记忆时快照里只有元信息, 但 system 仍带规则`() {
    val messages = ChatService.messages(friend, rounds(2) + me("在吗"), items, MemorySnapshot())
    assertEquals(1, messages.count { it.content.startsWith(ChatService.SNAPSHOT_HEADER) })
    assertTrue(messages.count { it.content.contains(MetaInfo.HEADER) } >= 1)
    assertTrue(messages.none { it.content.contains(ChatService.MEMORY_SECTION) })
    assertTrue(messages.first().content.contains(MemoryInject.RULES))
  }

  @Test
  fun `只传递 null 也不影响报文结构`() {
    val messages = ChatService.messages(friend, rounds(2) + me("在吗"), items, null)
    assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.role })
    assertEquals("在吗", messages.last().content)
  }

  @Test
  fun `微总结最多注入三条且按时间顺序编号`() {
    // 生产路径给的是「新 → 旧」;装配时反过来按时间顺序编号,模型才不会把最新的当成最早的
    val micros = listOf(
      "第 61-80 轮的微总结",
      "第 41-60 轮的微总结",
      "第 21-40 轮的微总结",
      "第 1-20 轮的微总结",
    )
    val content = ChatService.snapshotBlock(MetaInfo.empty(friend.id), snapshot(micros = micros))
    assertEquals(3, Regex("轮的微总结").findAll(content).count())
    assertTrue(content.indexOf("第 21-40 轮的微总结") < content.indexOf("第 41-60 轮的微总结"))
    assertTrue(content.indexOf("第 41-60 轮的微总结") < content.indexOf("第 61-80 轮的微总结"))
    // 超出的最旧那条一个字都不进报文
    assertFalse(content.contains("第 1-20 轮的微总结"))
  }

  @Test
  fun `更早的原文一句都不注入`() {
    val history = rounds(3) + me("在吗")
    val messages = ChatService.messages(friend, history, items, snapshot(micros = listOf("一段总结")))
    assertEquals("system", messages.first().role)
    assertEquals("在吗", messages.last().content)
    assertEquals(1, messages.count { it.role == "assistant" })
    assertEquals("回 3", messages.first { it.role == "assistant" }.content)
    assertTrue(messages.none { it.content.contains("第 1 句") })
  }

  @Test
  fun `用户消息里的物品会被注入`() {
    val messages = ChatService.messages(friend, listOf(me("我要喝药水")), items, MemorySnapshot())
    assertTrue(messages.last().content.contains("[物品]"))
    assertTrue(messages.last().content.contains("药水"))
  }

  @Test
  fun `快照与当前输入是两条独立的消息`() {
    val messages = ChatService.messages(friend, rounds(1) + me("现在呢"), items, snapshot(micros = listOf("一段总结")))
    val snapshotMessage = messages[1]
    assertEquals("user", snapshotMessage.role)
    assertFalse("快照里不该有当前输入", snapshotMessage.content.contains("现在呢"))
    assertEquals("当前输入与快照必须是两条消息", 4, messages.size)
    assertEquals("现在呢", messages.last().content)
  }
}
