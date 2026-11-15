package com.reimei.intersect.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reimei.intersect.data.memory.MemorySnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK-02 的验收:看**最终请求 JSON** 本身(而不是只看 [ChatService.messages] 的中间结果)。
 *
 * 为什么放在 androidTest:报文里的 JSON 用平台的 `org.json` 生成,而 JVM 单测里那是空实现
 * (调用即抛),只有真机运行时才能真的拼出并解析一份 JSON。
 *
 * 这里钉三件事:
 * 1. `messages` 顺序恒为 `[system, user(状态快照), assistant(最近回复), user(当前输入)]`;
 * 2. `system` 下**没有**任何动态数据 —— 换一段历史、换一套元信息,system 逐字节不变;
 * 3. 状态快照首行是固定标注,记忆(大总结 + 微总结)只出现在快照里,不再单独成条。
 *
 * 测试会把最终 JSON 打到 logcat(前缀 `PAYLOAD_JSON`),需要人工核对时用
 * `logcat_read filter=PAYLOAD_JSON` 取。
 */
@RunWith(AndroidJUnit4::class)
class ChatServicePayloadJsonTest {
  private val friend = User("friend_json_test", "小明", 0, role = "程序员", personality = "话少")
  private val items = listOf(Item("item_json_test", "药水", "回复 10 点体力"))

  private val memory = MemorySnapshot(
    nodeSummary = "两人一路走到镇口,约好天亮前离开。",
    microSummaries = listOf("最近一条微总结"),
  )

  private fun me(text: String, time: String? = null) = Msg(User.Me, text, time = time)
  private fun other(text: String) = Msg(friend, text)

  /** 造一段历史:epoch 轮 + 最后一条当前输入。 */
  private fun history(rounds: Int, current: Msg): List<Msg> = buildList {
    for (i in 1..rounds) {
      add(me("第 $i 句"))
      add(other("回 $i"))
    }
    add(current)
  }

  private fun payloadOf(rounds: Int, meta: MetaInfo): String =
    ChatService.payload("test-model", friend, history(rounds, me("我要喝药水", time = "2025-01-15 14:05")), items, memory, meta = meta)

  @Test
  fun 最终请求体的消息顺序与静态前缀() {
    val meta = MetaInfo(friendId = friend.id, time = "黄昏", location = "镇口酒馆", favorability = 65)
    val json = payloadOf(3, meta)
    println("PAYLOAD_JSON $json")
    println("PAYLOAD_JSON_PRETTY ${JSONObject(json).toString(2)}")

    val root = JSONObject(json)
    assertEquals("test-model", root.getString("model"))
    assertEquals(ChatService.TEMPERATURE.toDouble(), root.getDouble("temperature"), 0.0001)

    val messages = root.getJSONArray("messages")
    assertEquals(4, messages.length())
    assertEquals("system", messages.getJSONObject(0).getString("role"))
    assertEquals("user", messages.getJSONObject(1).getString("role"))
    assertEquals("assistant", messages.getJSONObject(2).getString("role"))
    assertEquals("user", messages.getJSONObject(3).getString("role"))

    // system:静态前缀,一个动态值都不许有
    val system = messages.getJSONObject(0).getString("content")
    assertTrue(system.contains(ChatService.CARD_INTRO))
    assertTrue(system.contains(MetaInfo.RULES))
    assertFalse("system 里不该有状态快照标注", system.contains(ChatService.SNAPSHOT_HEADER))
    assertFalse("system 里不该有元信息", system.contains("黄昏"))
    assertFalse("system 里不该有地点", system.contains("镇口酒馆"))
    assertFalse("system 里不该有记忆正文", system.contains("两人一路走到镇口"))
    assertFalse("system 里不该有当前输入", system.contains("我要喝药水"))
    // 时间戳 / token 数一类会变的数字一律不能出现在静态前缀里
    assertFalse(system, Regex("(?<![0-9])\\d{4,}").containsMatchIn(system))

    // 状态快照:首行固定标注,记忆只在这里出现
    val snapshot = messages.getJSONObject(1).getString("content")
    assertTrue(snapshot.startsWith(ChatService.SNAPSHOT_HEADER))
    assertTrue(snapshot.contains(MetaInfo.HEADER))
    assertTrue(snapshot.contains(ChatService.NODE_SECTION))
    assertTrue(snapshot.contains(ChatService.MICRO_SECTION))
    assertTrue(snapshot.contains("两人一路走到镇口"))
    assertTrue(snapshot.contains("最近一条微总结"))
    assertFalse(snapshot.contains("我要喝药水"))

    // 历史只留最近一轮回复
    assertEquals("回 3", messages.getJSONObject(2).getString("content"))

    // 当前输入永远是最后一条,带时间前缀与物品信息
    val current = messages.getJSONObject(3).getString("content")
    assertTrue(current.startsWith("[2025-01-15 14:05] 我要喝药水"))
    assertTrue(current.contains("[物品]"))
  }

  @Test
  fun 换历史换元信息时静态前缀逐字节一致() {
    val early = JSONObject(payloadOf(1, MetaInfo(friendId = friend.id, time = "清晨", location = "客栈")))
    val late = JSONObject(
      payloadOf(9, MetaInfo(friendId = friend.id, time = "深夜", location = "后山", favorability = 12))
    )
    val earlySystem = early.getJSONArray("messages").getJSONObject(0).getString("content")
    val lateSystem = late.getJSONArray("messages").getJSONObject(0).getString("content")
    assertEquals("静态前缀必须逐字节一致(前缀缓存的前提)", earlySystem, lateSystem)
  }

  @Test
  fun 首轮对话没有历史回复时只有三条消息() {
    val json = ChatService.payload(
      "test-model",
      friend,
      listOf(me("你好")),
      emptyList(),
      null,
      meta = MetaInfo.empty(friend.id),
    )
    val messages = JSONObject(json).getJSONArray("messages")
    assertEquals(3, messages.length())
    assertEquals("system", messages.getJSONObject(0).getString("role"))
    assertEquals("user", messages.getJSONObject(1).getString("role"))
    assertEquals("user", messages.getJSONObject(2).getString("role"))
  }
}
