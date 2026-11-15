package com.reimei.intersect.data

import com.reimei.intersect.data.memory.MemorySnapshot
import com.reimei.intersect.data.time.DirectorPatch
import com.reimei.intersect.data.time.InjectionPlan
import com.reimei.intersect.data.time.Period
import com.reimei.intersect.data.time.SummaryChain
import com.reimei.intersect.data.time.TimeRules
import com.reimei.intersect.data.time.InMemoryTimeLog
import com.reimei.intersect.data.time.TimeRounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间注入接进请求体之后的拼接契约单测(分段重构之后的版本)。
 *
 * 四条不能破的:
 * 1. 规则块在 **system 末尾**(常量,前缀稳定);
 * 2. 普通轮**不额外成条**(除规则块之外没有任何时间相关内容);
 * 3. 旁路注入(临时时间事实 / 导演事件)**并进状态快照那一条**,报文条数不变;
 * 4. **记忆总结块不再充当时间总结块**:总结轮不会多出一条带 `[时间元信息]` 的消息。
 */
class ChatServiceTimePayloadTest {
  private val friend = User("friend_1", "小明", 0, role = "程序员", personality = "话少")
  private val logger = InMemoryTimeLog()

  private fun me(text: String) = Msg(User.Me, text)
  private fun other(text: String) = Msg(friend, text)

  private fun history(n: Int): List<Msg> = buildList {
    for (i in 1..n) {
      add(me("第 $i 句"))
      add(other("回 $i"))
    }
    add(me("现在呢"))
  }

  private fun memory() = MemorySnapshot(microSummaries = listOf("第 1-20 轮的总结"))

  private fun normalPlan(): InjectionPlan =
    TimeRounds.plan(TimeRounds.RoundEvent.NONE, logger = logger)

  @Test
  fun `不带时间计划时 system 不含规则块`() {
    val messages = ChatService.messages(friend, history(1))
    assertEquals("system", messages.first().role)
    assertFalse(messages.first().content.contains(TimeRules.RULES_TEXT))
  }

  @Test
  fun `规则块进 system 末尾`() {
    val messages = ChatService.messages(friend, history(1), time = normalPlan())
    val system = messages.first()
    assertEquals("system", system.role)
    assertTrue(system.content.endsWith(TimeRules.RULES_TEXT))
    // 记忆使用规则仍在,且在规则块之前
    assertTrue(system.content.contains(MemoryInject.RULES))
    assertTrue(system.content.indexOf(MemoryInject.RULES) < system.content.indexOf(TimeRules.RULES_TEXT))
  }

  @Test
  fun `普通轮除 system 外没有任何时间块`() {
    val messages = ChatService.messages(friend, history(2), time = normalPlan())
    assertTrue(messages.drop(1).none { it.content.contains(TimeInjectText.RULES_HEADER) })
    assertTrue(messages.drop(1).none { it.content.contains(TimeInjectText.META_HEADER) })
    assertTrue(messages.drop(1).none { it.content.contains(TimeInjectText.DIRECTOR_HEADER) })
    assertTrue(messages.drop(1).none { it.content.contains(TimeInjectText.TEMP_HEADER) })
  }

  @Test
  fun `总结轮不再多出一条总结块`() {
    // 旧形态:总结块 + [时间元信息] 单独成一条 user。现在这两样都不该出现在报文里,
    // 记忆只走状态快照(见 ChatServiceMemoryPayloadTest)。
    val meta = SummaryChain.next(null, null, "第 1-20 轮", Period.DUSK, 480, logger = logger)
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.SUMMARY_READY,
      summaryBlock = "他们从码头走进巷子。",
      summaryMeta = meta,
      logger = logger,
    )
    val messages = ChatService.messages(friend, history(2), memory = memory(), time = plan)

    assertEquals("报文条数不变", listOf("system", "user", "assistant", "user"), messages.map { it.role })
    assertTrue(messages.none { it.content.startsWith("他们从码头走进巷子。") })
    assertTrue(messages.none { it.content.contains(TimeInjectText.META_HEADER) })
    assertEquals("现在呢", messages.last().content)
  }

  @Test
  fun `导演事件并进状态快照 不单独成条`() {
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.DIRECTOR_EVENT,
      directorPatch = DirectorPatch(Period.DAWN, "天刚亮"),
      logger = logger,
    )
    val messages = ChatService.messages(friend, history(1), memory = memory(), time = plan)
    val snapshot = messages[1]
    assertEquals("user", snapshot.role)
    assertTrue("快照里带记忆", snapshot.content.contains(ChatService.MEMORY_SECTION))
    assertTrue("导演事件在快照里", snapshot.content.contains(TimeInjectText.DIRECTOR_HEADER))
    assertTrue(
      "导演事件排在记忆之后",
      snapshot.content.indexOf(ChatService.MEMORY_SECTION) <
        snapshot.content.indexOf(TimeInjectText.DIRECTOR_HEADER),
    )
    assertEquals("报文条数不变", 4, messages.size)
    assertEquals("当前输入仍是最后一条", "现在呢", messages.last().content)
  }

  @Test
  fun `旁路注入不会把状态快照变成两条`() {
    val plan = TimeRounds.plan(
      TimeRounds.RoundEvent.DIRECTOR_EVENT,
      directorPatch = DirectorPatch(Period.DAWN, "天刚亮"),
      logger = logger,
    )
    val messages = ChatService.messages(friend, history(2), memory = memory(), time = plan)
    assertEquals(1, messages.count { it.role == "user" && it.content.startsWith(ChatService.SNAPSHOT_HEADER) })
    assertEquals(1, messages.count { it.role == "assistant" })
  }

  /** 只为了让断言读得懂:标签字面量集中在一处,避免测试里散落魔法字符串。 */
  private object TimeInjectText {
    const val RULES_HEADER = "[时间规则]"
    const val META_HEADER = "[时间元信息"
    const val DIRECTOR_HEADER = "[导演时间事件]"
    const val TEMP_HEADER = "[临时时间事实]"
  }
}
