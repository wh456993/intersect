package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 即时记忆层的规则单元测试(存储与网络在别处,这里只管纯逻辑)。
 *
 * 钉住的几条:token 估算与截断、「一轮 = 用户一句 + 角色一句」的分组、
 * 注入块的固定格式与「只放最近 3 条」、以及轮数 / 覆盖轮次的推进 ——
 * 这几条一旦走样,表现是「总结后原文又漏进请求体」或者「记忆被当成用户说的话」,
 * 界面上完全看不出来。
 */
class ImmediateMemoryTest {

  private fun memory(
    id: String,
    from: Int,
    to: Int,
    text: String = "第 $from-$to 轮的总结",
    createdAt: Long = 1_700_000_000L + from,
  ) = ImmediateMemory(
    id = id,
    fromRound = from,
    toRound = to,
    createdAt = createdAt,
    targetTokens = ImmediateMemoryState.TARGET_DEFAULT,
    actualTokens = TokenEstimator.estimate(text),
    text = text,
  )

  private val friend = User("friend_1", "小明", 0)

  private fun me(text: String) = Msg(User.Me, text)

  private fun other(text: String) = Msg(friend, text)

  @Test
  fun `中文按字估算, 英文按四字符估算`() {
    // 四个汉字 ≈ 4 token;四个英文字母 ≈ 1 token
    assertEquals(4, TokenEstimator.estimate("一二三四"))
    assertEquals(1, TokenEstimator.estimate("abcd"))
    assertEquals(0, TokenEstimator.estimate(""))
  }

  @Test
  fun `截断后不会超过上限`() {
    val long = "这是一段很长的记忆总结。".repeat(100)
    val cut = TokenEstimator.truncate(long, 50)
    assertTrue(cut.length < long.length)
    assertTrue(TokenEstimator.estimate(cut) <= 50)
  }

  @Test
  fun `不超过上限时原样返回`() {
    val text = "很短的一段。"
    assertEquals(text, TokenEstimator.truncate(text, 200))
  }

  @Test
  fun `一轮是用户一句加角色一句`() {
    val rounds = MemoryRounds.group(listOf(me("在吗"), other("在"), me("吃了吗"), other("吃了")))
    assertEquals(2, rounds.size)
    assertEquals(2, rounds[0].size)
    assertEquals(2, rounds[1].size)
    assertEquals("在吗", rounds[0][0].text)
    assertEquals("在", rounds[0][1].text)
  }

  @Test
  fun `还没回复的那一句自成一 轮`() {
    val rounds = MemoryRounds.group(listOf(me("在吗"), other("在"), me("吃了吗")))
    assertEquals(2, rounds.size)
    assertEquals(1, rounds[1].size)
  }

  @Test
  fun `总结原文带轮次序号`() {
    val rounds = MemoryRounds.group(listOf(me("在吗"), other("在")))
    val text = MemoryRounds.texts(rounds, firstNumber = 20).single()
    assertTrue(text.startsWith("第 20 轮"))
    assertTrue(text.contains("用户:在吗"))
    assertTrue(text.contains("角色:在"))
  }

  @Test
  fun `没有总结时注入块为空`() {
    assertEquals("", MemoryInject.block(emptyList()))
  }

  @Test
  fun `注入块只放最近三条, 并带首尾标记`() {
    val state = ImmediateMemoryState(
      friendId = friend.id,
      summaries = listOf(
        memory("m4", 61, 80),
        memory("m3", 41, 60),
        memory("m2", 21, 40),
        memory("m1", 1, 20),
      ),
    )
    val block = state.injectBlock
    assertTrue(block.startsWith(MemoryInject.HEADER))
    assertTrue(block.endsWith(MemoryInject.FOOTER))
    assertTrue(block.contains("最近 3 条"))
    // 最新那条在第 1 位,最老的一条(第 1-20 轮)不该出现
    assertTrue(block.indexOf("[总结1") < block.indexOf("[总结2"))
    assertTrue(block.contains("第 61-80 轮"))
    assertFalse(block.contains("第 1-20 轮"))
    assertEquals(3, state.latest.size)
  }

  @Test
  fun `攒够二十轮就该总结, 总结后覆盖轮次往后推`() {
    var state = ImmediateMemoryState(friendId = friend.id)
    repeat(ImmediateMemoryState.SUMMARY_EVERY) { state = state.withRound() }
    assertEquals(0, state.coveredRounds)
    assertTrue(state.needsSummary)
    assertEquals(0, state.roundsUntilNext)

    state = state.withSummary(memory("m1", 1, ImmediateMemoryState.SUMMARY_EVERY))
    assertEquals(ImmediateMemoryState.SUMMARY_EVERY, state.coveredRounds)
    assertFalse(state.needsSummary)
    assertEquals(ImmediateMemoryState.SUMMARY_EVERY, state.roundsUntilNext)
    assertEquals(1, state.summaries.size)
  }

  @Test
  fun `滑块目标值被夹在 200 到 600 之间`() {
    val state = ImmediateMemoryState(friendId = friend.id)
    assertEquals(ImmediateMemoryState.TARGET_MIN, state.withTarget(10).targetTokens)
    assertEquals(ImmediateMemoryState.TARGET_MAX, state.withTarget(9999).targetTokens)
    assertEquals(ImmediateMemoryState.TARGET_DEFAULT, state.targetTokens)
  }

  @Test
  fun `总结失败会记下错误, 下一次成功时清掉`() {
    var state = ImmediateMemoryState(friendId = friend.id).withError("HTTP 500: 服务器错误")
    assertEquals("HTTP 500: 服务器错误", state.lastError)
    assertEquals(1, state.errors.size)

    state = state.withSummary(memory("m1", 1, 20))
    assertEquals("", state.lastError)
    // 历史错误日志保留:开发者面板要能看到踩过哪些坑
    assertEquals(1, state.errors.size)
  }

  @Test
  fun `轮数按会话实际轮次校正, 覆盖轮次被夹在范围内`() {
    // 删过消息 / 恢复过备份:落盘值比现在剩下的轮数还大
    val stale = ImmediateMemoryState(friendId = friend.id, rounds = 40, coveredRounds = 40)
    val fixed = stale.aligned(12)
    assertEquals(12, fixed.rounds)
    assertEquals(12, fixed.coveredRounds)
    assertEquals(0, fixed.pendingRounds)
    assertFalse(fixed.needsSummary)
  }

  @Test
  fun `覆盖轮次不会低于最后一条总结的覆盖范围`() {
    val state = ImmediateMemoryState(friendId = friend.id, rounds = 20, coveredRounds = 0)
      .withSummary(memory("m1", 1, 20))
    assertEquals(ImmediateMemoryState.SUMMARY_EVERY, state.coveredRounds)
    // 再校正一次也不会把它推到总结范围以下
    assertEquals(ImmediateMemoryState.SUMMARY_EVERY, state.aligned(20).coveredRounds)
  }

  @Test
  fun `本地最多保留固定条数的总结`() {
    var state = ImmediateMemoryState(friendId = friend.id)
    repeat(ImmediateMemoryState.SUMMARY_KEEP + 10) { index ->
      val from = index * ImmediateMemoryState.SUMMARY_EVERY + 1
      state = state.withSummary(memory("m$index", from, from + ImmediateMemoryState.SUMMARY_EVERY - 1))
    }
    assertEquals(ImmediateMemoryState.SUMMARY_KEEP, state.summaries.size)
    // 新的在前
    assertTrue(state.summaries.first().toRound > state.summaries.last().toRound)
  }

  @Test
  fun `注入时间戳能区分发过请求与没发过`() {
    val state = ImmediateMemoryState(friendId = friend.id).withInjected("")
    assertTrue(state.lastInjectedAt > 0L)
    assertEquals("", state.lastInjectedBlock)
  }
}
