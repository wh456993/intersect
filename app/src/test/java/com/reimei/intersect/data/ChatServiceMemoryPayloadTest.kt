package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色扮演请求体的拼接契约单元测试。
 *
 * 这是「即时记忆层」最容易静默出错的地方:顺序错了、已总结的原文漏进请求、
 * 记忆块和当前输入被合并成一条 —— 界面上全都看不出来,只有模型会莫名其妙地把记忆
 * 当成用户刚说的话。所以把四段顺序钉死:
 * system → 未总结原文 → 独立的 user 记忆块 → 当前输入。
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

  private fun memory(covered: Int, summaries: List<ImmediateMemory> = emptyList()) =
    ImmediateMemoryState(friendId = friend.id, coveredRounds = covered, summaries = summaries)

  private fun summary(id: String, from: Int, to: Int, text: String = "第 $from-$to 轮的总结") =
    ImmediateMemory(
      id = id,
      fromRound = from,
      toRound = to,
      createdAt = 1_700_000_000L,
      targetTokens = ImmediateMemoryState.TARGET_DEFAULT,
      actualTokens = TokenEstimator.estimate(text),
      text = text,
    )

  @Test
  fun `第一条是 system 且带着记忆使用规则`() {
    val messages = ChatService.messages(friend, rounds(1) + me("现在呢"), items, memory(0))
    assertEquals("system", messages.first().role)
    assertTrue(messages.first().content.contains("你是小明"))
    assertTrue(messages.first().content.contains("身份:程序员"))
    assertTrue(messages.first().content.contains(MemoryInject.RULES))
  }

  @Test
  fun `已总结的轮次不再注入原文`() {
    val history = rounds(20) + me("新一轮")
    val messages = ChatService.messages(friend, history, items, memory(20, listOf(summary("m1", 1, 20))))
    // 前 20 轮的原文一句都不该出现
    assertTrue(messages.none { it.content.contains("第 1 句") })
    assertTrue(messages.none { it.content.contains("回 1") })
    // 当前输入还在,并且在最后
    assertEquals("新一轮", messages.last().content)
  }

  @Test
  fun `记忆块是独立的一条 user 消息, 且排在当前输入之前`() {
    val history = rounds(20) + me("新一轮")
    val messages = ChatService.messages(
      friend,
      history,
      items,
      memory(20, listOf(summary("m1", 1, 20), summary("m2", 21, 40))),
    )
    val memoryIndex = messages.indexOfFirst { it.content.startsWith(MemoryInject.HEADER) }
    val currentIndex = messages.lastIndex
    assertEquals("user", messages[memoryIndex].role)
    // 记忆块紧挨在最后一条之前:两者是各自独立的两条消息,没有被合并
    assertEquals(currentIndex - 1, memoryIndex)
    assertTrue(messages.last().content == "新一轮")
    assertTrue(messages[memoryIndex].content.contains("第 1-20 轮的总结"))
  }

  @Test
  fun `没有总结时不注入记忆块, 但 system 仍带规则`() {
    val messages = ChatService.messages(friend, rounds(2) + me("在吗"), items, memory(0))
    assertTrue(messages.none { it.content.contains(MemoryInject.HEADER) })
    assertTrue(messages.first().content.contains(MemoryInject.RULES))
  }

  @Test
  fun `未总结的轮次全部原样注入`() {
    val messages = ChatService.messages(friend, rounds(3) + me("在吗"), items, memory(0))
    // system + 3 轮×2 条 + 当前输入
    assertEquals(1 + 6 + 1, messages.size)
    assertEquals("第 1 句", messages[1].content)
    assertEquals("回 1", messages[2].content)
    assertEquals("user", messages[1].role)
    assertEquals("assistant", messages[2].role)
  }

  @Test
  fun `覆盖轮次超过实际轮数时不会把历史丢光`() {
    // 删过消息 / 恢复过备份:落盘的覆盖轮次比现在剩下的轮数还大
    val messages = ChatService.messages(friend, rounds(3) + me("在吗"), items, memory(99))
    // 夹到实际轮数(3 轮全算覆盖),所以只剩当前输入 —— 但绝不会变成空请求
    assertEquals(2, messages.size)
    assertEquals("在吗", messages.last().content)
  }

  @Test
  fun `未总结的轮次超过上限时只注入最近的二十轮`() {
    val history = rounds(30) + me("在吗")
    val messages = ChatService.messages(friend, history, items, memory(0))
    // system + 20 轮×2 + 当前输入
    assertEquals(1 + 40 + 1, messages.size)
    assertTrue(messages.none { it.content == "第 1 句" })
    assertEquals("第 11 句", messages[1].content)
  }

  @Test
  fun `用户消息里的物品会被注入`() {
    val messages = ChatService.messages(friend, listOf(me("我要喝药水")), items, memory(0))
    assertTrue(messages.last().content.contains("[物品]"))
    assertTrue(messages.last().content.contains("药水"))
  }
}
