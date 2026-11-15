package com.reimei.intersect.data

import com.reimei.intersect.data.memory.MemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体模板的接线契约:模板里每个占位符都要从对应的数据源取到值,且位置固定。
 *
 * 对应关系(需求给的模板 ↔ 本工程的数据源):
 *
 * - `{{UI_角色身份_*}}` / `{{UI_世界观_*}}` / `{{UI_背景_来历与处境}}` → [User] 的字段,进 system
 *   的静态设定卡([ChatService.characterCard]);
 * - `{{元信息块}}` → [MetaInfo.block](已套好时钟),进第二条 user 的[状态快照][ChatService.snapshotBlock];
 * - `{{记忆库_过往剧情摘要}}` → [com.reimei.intersect.data.memory.MemorySnapshot](大总结 + 最近 3 条微总结),
 *   同样进状态快照;
 * - `{{历史对话_最近一轮AI回复}}` → 会话里当前输入之前最后一条角色消息,单独成一条 assistant;
 * - `{{用户当前输入}}` → 会话最后一条用户消息,永远是最后一条;
 * - `{{物品管理器_当前物品列表及简介}}` → 维持旧做法:不进快照,扫到名称才拼到用户话尾。
 *
 * 这些错了界面上一律看不出来,只有模型会莫名其妙地失忆或把状态当台词说,所以逐条钉住。
 */
class ChatServiceTemplatePayloadTest {
  private val friend = User("friend_1", "小明", 0, role = "程序员", personality = "话少")

  private val fullWorldview = Worldview(
    laws = "灵气为王",
    factions = "两派对立",
    economy = "以灵石结算",
    history = "百年前的封山",
  )

  private fun me(text: String) = Msg(User.Me, text)
  private fun other(text: String) = Msg(friend, text)

  /** 造 n 轮对话:第 i 轮是「第 i 句」/「回 i」。 */
  private fun rounds(n: Int): List<Msg> = buildList {
    for (i in 1..n) {
      add(me("第 $i 句"))
      add(other("回 $i"))
    }
  }

  private fun meta() = MetaInfo(
    friendId = friend.id,
    time = "黄昏",
    relationship = "点头之交",
    location = "镇口酒馆",
    weather = "小雨",
  )

  @Test
  fun `system 是静态设定卡 含着角色身份的每一项`() {
    val messages = ChatService.messages(friend, rounds(1) + me("现在呢"), meta = meta())
    val system = messages.first()
    assertEquals("system", system.role)
    assertTrue(system.content.startsWith(ChatService.CARD_INTRO))
    assertTrue(system.content.contains("【角色身份】"))
    assertTrue(system.content.contains("名称:小明"))
    assertTrue(system.content.contains("性格:话少"))
    assertTrue(system.content.contains("身份:程序员"))
    // 元信息规则与记忆规则都在 system 里,且措辞固定
    assertTrue(system.content.contains(MetaInfo.RULES))
    assertTrue(system.content.contains(MemoryInject.RULES))
  }

  @Test
  fun `世界观四项各自成行 进 system 且在背景之前`() {
    val withWorld = friend.copy(worldview = fullWorldview, background = "从北境流落到此")
    val system = ChatService.messages(withWorld, listOf(me("你好"))).first().content
    assertTrue(system.contains("【世界观】"))
    Worldview.LABELS.forEach { label ->
      assertTrue("世界观缺了「$label」", system.contains("$label:"))
    }
    assertTrue(system.contains("灵气为王"))
    assertTrue(system.contains("百年前的封山"))
    assertTrue(system.indexOf("【世界观】") < system.indexOf("【背景】"))
    assertTrue(system.contains("来历与处境:从北境流落到此"))
  }

  @Test
  fun `没填世界观与背景时整段省略`() {
    // 本体代入的角色这两项恒为 null
    val system = ChatService.messages(friend, listOf(me("你好"))).first().content
    assertFalse(system.contains("【世界观】"))
    assertFalse(system.contains("【背景】"))
    assertFalse(system.contains("法则体系"))
    // 角色身份与规则仍在
    assertTrue(system.contains("名称:小明"))
    assertTrue(system.contains(MetaInfo.RULES))
  }

  @Test
  fun `世界观只填一项时只出那一行`() {
    val withOne = friend.copy(worldview = Worldview(laws = "灵气为王"))
    val system = ChatService.messages(withOne, listOf(me("你好"))).first().content
    assertTrue(system.contains("法则体系:灵气为王"))
    assertFalse(system.contains("势力分布"))
    assertFalse(system.contains("经济架构"))
    assertFalse(system.contains("历史与重大事件"))
  }

  @Test
  fun `性格与身份没填时不出现这两行`() {
    val bare = User("friend_2", "阿岚", 0)
    val system = ChatService.messages(bare, listOf(me("你好"))).first().content
    assertTrue(system.contains("名称:阿岚"))
    assertFalse(system.contains("性格:"))
    assertFalse(system.contains("身份:"))
  }

  @Test
  fun `第二条是状态快照 带元信息块且不含任何数值`() {
    val messages = ChatService.messages(friend, rounds(1) + me("现在呢"), meta = meta())
    val snapshot = messages[1]
    assertEquals("user", snapshot.role)
    assertTrue(snapshot.content.startsWith(ChatService.SNAPSHOT_HEADER))
    assertTrue(snapshot.content.contains("时间:黄昏"))
    // 好感 / 生命 / 饥饿都是名词,请求体里一个阿拉伯数字都不许有
    assertTrue(snapshot.content.contains("好感:陌生"))
    assertTrue(snapshot.content.contains("生命:健康"))
    assertTrue(snapshot.content.contains("饥饿:正常吃饱"))
    assertFalse("状态快照里不许出现数字", Regex("[0-9]").containsMatchIn(snapshot.content))
  }

  @Test
  fun `记忆与元信息同处快照那一条消息`() {
    val memory = MemorySnapshot(
      nodeSummary = "两人从码头走进巷子,约定天亮前离开。",
      microSummaries = listOf("他们从码头走进巷子"),
    )
    val messages = ChatService.messages(friend, rounds(1) + me("现在呢"), memory = memory, meta = meta())
    val snapshot = messages[1]
    assertEquals("user", snapshot.role)
    // 元信息在前、记忆在后,同一条消息里
    assertTrue(snapshot.content.contains(MetaInfo.HEADER))
    assertTrue(snapshot.content.contains(ChatService.MEMORY_SECTION))
    assertTrue(snapshot.content.contains(ChatService.NODE_SECTION))
    assertTrue(snapshot.content.contains(ChatService.MICRO_SECTION))
    assertTrue(snapshot.content.indexOf(MetaInfo.HEADER) < snapshot.content.indexOf(ChatService.MEMORY_SECTION))
    assertTrue(snapshot.content.contains("他们从码头走进巷子"))
  }

  @Test
  fun `历史只留最近一轮角色回复`() {
    val messages = ChatService.messages(friend, rounds(3) + me("现在呢"), meta = meta())
    val replies = messages.filter { it.role == "assistant" }
    assertEquals("只该有一条角色回复", 1, replies.size)
    assertEquals("回 3", replies.first().content)
    // 更早的原文一句都不进请求体(由记忆库摘要负责)
    assertTrue(messages.none { it.content.contains("第 1 句") })
    assertTrue(messages.none { it.content.contains("回 1") })
  }

  @Test
  fun `首轮对话没有角色回复时整条省略`() {
    val messages = ChatService.messages(friend, listOf(me("你好")), meta = meta())
    assertEquals(listOf("system", "user", "user"), messages.map { it.role })
    assertEquals("你好", messages.last().content)
  }

  @Test
  fun `当前输入永远是最后一条 且带物品注入`() {
    val items = listOf(Item("item_1", "药水", "回复 10 点体力"))
    val messages = ChatService.messages(friend, rounds(2) + me("我要喝药水"), items, meta = meta())
    val last = messages.last()
    assertEquals("user", last.role)
    assertTrue(last.content.startsWith("我要喝药水"))
    assertTrue(last.content.contains("[物品]"))
    assertTrue(last.content.contains("药水"))
  }

  @Test
  fun `快照排在最近一轮回复之前`() {
    val messages = ChatService.messages(friend, rounds(2) + me("现在呢"), meta = meta())
    // 用快照首行定位:元信息块里的「[元信息]」在 system 的规则里也会出现一次
    val snapshotIndex = messages.indexOfFirst { it.content.startsWith(ChatService.SNAPSHOT_HEADER) }
    val replyIndex = messages.indexOfFirst { it.role == "assistant" }
    assertEquals("状态快照只能有一条,且不是 system", 1, snapshotIndex)
    assertTrue(replyIndex > snapshotIndex)
    assertEquals("现在呢", messages.last().content)
  }
}
