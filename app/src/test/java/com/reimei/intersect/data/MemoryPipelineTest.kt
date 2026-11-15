package com.reimei.intersect.data

import com.reimei.intersect.data.memory.MemoryPipeline
import com.reimei.intersect.data.memory.MicroSummaryEntity
import com.reimei.intersect.data.memory.MicroSummarizer
import com.reimei.intersect.data.memory.NodeSummarizer
import com.reimei.intersect.data.memory.RollingMetaUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 总结流水线的纯逻辑契约(TASK-03):触发条件、3 条连续的判定、AI 返回值的解析。
 *
 * 数据库读写与网络请求不在这里(那两部分要 Context / 真机),这里只钉「不需要 Android 就能错」
 * 的那一层 —— 触发阈值、连续性、标签解析。
 */
class MemoryPipelineTest {

  private fun micro(id: Long, from: Int, to: Int, text: String = "微总结 $from-$to") =
    MicroSummaryEntity(
      id = id,
      friendId = "friend_1",
      fromRound = from,
      toRound = to,
      text = text,
      score = 60,
      createdAt = 1_700_000_000L,
    )

  @Test
  fun `每 20 轮触发一次且第 20 轮也算`() {
    listOf(1, 19, 20, 21, 39, 40, 60).forEach { rounds ->
      assertEquals("轮数 $rounds 是否该总结", rounds % 20 == 0, MemoryPipeline.isDue(rounds))
    }
    assertFalse("0 轮不该触发", MemoryPipeline.isDue(0))
  }

  @Test
  fun `三条首尾衔接的微总结算连续`() {
    val micros = listOf(micro(3, 41, 60), micro(2, 21, 40), micro(1, 1, 20))
    assertTrue(MemoryPipeline.isConsecutive(micros))
  }

  @Test
  fun `中间断档不算连续`() {
    // 用户删过消息:1-20 之后直接跳到 25-44
    val micros = listOf(micro(3, 45, 64), micro(2, 25, 44), micro(1, 1, 20))
    assertFalse(MemoryPipeline.isConsecutive(micros))
  }

  @Test
  fun `解析标准三段输出`() {
    val raw = """
      [正文]
      用户与角色在码头碰头,约定天亮前离开镇子。
      [评分]
      78
      [状态]
      好感:65
      生命:80
      饥饿:42
      时间:黄昏
      地点:镇口码头
    """.trimIndent()
    val parsed = MicroSummarizer.parse(raw)
    assertEquals("用户与角色在码头碰头,约定天亮前离开镇子。", parsed.text)
    assertEquals(78, parsed.score)
    assertEquals(65, parsed.update.favorability)
    assertEquals(80, parsed.update.health)
    assertEquals(42, parsed.update.hunger)
    assertEquals("黄昏", parsed.update.timeText)
    assertEquals("镇口码头", parsed.update.location)
  }

  @Test
  fun `格式跑偏时退化成整段正文`() {
    val parsed = MicroSummarizer.parse("他们聊了很久,没有别的信息。")
    assertEquals("他们聊了很久,没有别的信息。", parsed.text)
    assertEquals(MicroSummarizer.SCORE_DEFAULT, parsed.score)
    assertTrue(parsed.update.isEmpty)
  }

  @Test
  fun `评分越界会被夹回来`() {
    val parsed = MicroSummarizer.parse("[正文]\n正文\n[评分]\n250\n[状态]\n好感:130")
    assertEquals(100, parsed.score)
    assertEquals(100, parsed.update.favorability)
  }

  @Test
  fun `代码块围栏会被剥掉`() {
    val raw = "```\n[正文]\n正文内容\n[评分]\n55\n```"
    val parsed = MicroSummarizer.parse(raw)
    assertEquals("正文内容", parsed.text)
    assertEquals(55, parsed.score)
  }

  @Test
  fun `状态块里拿不准的项整行省略`() {
    val update = MicroSummarizer.parseState("好感:\n生命:70\n地点:\n")
    assertNull(update.favorability)
    assertEquals(70, update.health)
    assertNull(update.location)
    assertFalse(update.isEmpty)
  }

  @Test
  fun `元信息块跟在总结请求的 user 正文末尾`() {
    val meta = MetaInfo(friendId = "friend_1", time = "黄昏", location = "镇口酒馆")
    val content = MicroSummarizer.userContent(listOf("第 1 轮\n用户:在吗"), meta)
    assertTrue(content.contains("共 1 轮"))
    assertTrue(content.trimEnd().endsWith(meta.block()))
    // 请求体里依旧只有名词,没有数值
    assertFalse(Regex("好感:\\d").containsMatchIn(content))
  }

  @Test
  fun `大总结的 user 正文按顺序列出三段微总结`() {
    val content = NodeSummarizer.userContent(listOf("第一段", "第二段", "第三段"))
    assertTrue(content.contains("共 3 段"))
    assertTrue(content.indexOf("第一段") < content.indexOf("第二段"))
    assertTrue(content.indexOf("第二段") < content.indexOf("第三段"))
  }

  @Test
  fun `回流的状态更新只覆盖给出的项`() {
    val partial = RollingMetaUpdate(favorability = 70)
    assertFalse(partial.isEmpty)
    assertNull(partial.health)
    assertNull(partial.location)
  }

  // —— TASK-03 的防脏数据:落库前先过 decideCommit ——

  @Test
  fun `消息条数变少时丢弃这次总结`() {
    // 请求在飞时删了 5 条:200 → 195,这一段对话已经不完整
    assertEquals(
      MemoryPipeline.CommitDecision.DROP_SHRUNK,
      MemoryPipeline.decideCommit(snapshotCount = 200, currentCount = 195, latestToRound = 0, toRound = 20),
    )
  }

  @Test
  fun `消息条数没变或变多时正常落库`() {
    assertEquals(
      MemoryPipeline.CommitDecision.COMMIT,
      MemoryPipeline.decideCommit(snapshotCount = 200, currentCount = 200, latestToRound = 0, toRound = 20),
    )
    assertEquals(
      MemoryPipeline.CommitDecision.COMMIT,
      MemoryPipeline.decideCommit(snapshotCount = 200, currentCount = 206, latestToRound = 0, toRound = 20),
    )
  }

  @Test
  fun `已经总结过的轮次不重复写`() {
    assertEquals(
      MemoryPipeline.CommitDecision.DROP_DUPLICATE,
      MemoryPipeline.decideCommit(snapshotCount = 200, currentCount = 200, latestToRound = 20, toRound = 20),
    )
  }

  @Test
  fun `删消息优先于重复判定`() {
    // 两种都不该写:消息被删掉的证据更硬,先报它
    assertEquals(
      MemoryPipeline.CommitDecision.DROP_SHRUNK,
      MemoryPipeline.decideCommit(snapshotCount = 200, currentCount = 150, latestToRound = 20, toRound = 20),
    )
  }

  @Test
  fun `长度上限沿用滑块但夹回 250 到 600`() {
    // 滑块值直接当正文上限:请求的 max_tokens 还要给评分/状态标签留余量
    assertEquals(
      MicroSummarizer.REQUEST_MAX_TOKENS,
      MicroSummarizer.requestMaxTokens(MicroSummarizer.MAX_TOKENS),
    )
    assertTrue(MicroSummarizer.requestMaxTokens(300) < MicroSummarizer.REQUEST_MAX_TOKENS)
    // 越界值与默认值一样落到 600 的硬上限
    assertEquals(
      MicroSummarizer.requestMaxTokens(MicroSummarizer.MAX_TOKENS),
      MicroSummarizer.requestMaxTokens(9999),
    )
  }

  // —— TASK-03 的触发段:从水位往后接,最多 20 轮 ——

  private fun rounds(n: Int): List<List<Msg>> = List(n) { emptyList() }

  @Test
  fun `首段从第一轮开始且最多二十轮`() {
    val range = MemoryPipeline.nextRange(0, rounds(25))
    assertNotNull(range)
    assertEquals(1, range!!.fromRound)
    assertEquals(20, range.rounds.size)
    assertEquals(20, range.toRound)
  }

  @Test
  fun `水位之后接着总结下一段`() {
    // 已经总结到第 20 轮:这一段是 21-40
    val range = MemoryPipeline.nextRange(20, rounds(60))
    assertEquals(21, range!!.fromRound)
    assertEquals(40, range.toRound)

    // 已经到 40:这一段是 41-60
    val next = MemoryPipeline.nextRange(40, rounds(60))
    assertEquals(41, next!!.fromRound)
    assertEquals(60, next.toRound)
  }

  @Test
  fun `不足二十轮时把剩下的都总结掉`() {
    val range = MemoryPipeline.nextRange(55, rounds(60))
    assertEquals(56, range!!.fromRound)
    assertEquals(5, range.rounds.size)
    assertEquals(60, range.toRound)
  }

  @Test
  fun `水位到头时没有可总结的段`() {
    assertNull(MemoryPipeline.nextRange(60, rounds(60)))
    assertNull(MemoryPipeline.nextRange(999, rounds(60)))
    assertNull(MemoryPipeline.nextRange(0, rounds(0)))
  }

  @Test
  fun `某一段失败后下一次补的是最早那段而不是最后二十轮`() {
    // 第一次(第 20 轮)请求失败:水位还是 0。到第 40 轮时应该重补 1-20,
    // 而不是取最后 20 轮(21-40)—— 否则第 1-20 轮永远进不了记忆。
    val range = MemoryPipeline.nextRange(0, rounds(40))
    assertEquals(1, range!!.fromRound)
    assertEquals(20, range.toRound)
  }

  // —— 大总结的思考过程泄漏:提示词硬约束 + 后处理清洗 + 字数底线 ——

  @Test
  fun `提示词末尾带禁止输出思考过程的强制要求`() {
    val prompt = NodeSummarizer.SYSTEM_PROMPT
    // 约束必须在最后一行:模型对末尾的硬约束最敏感
    assertTrue(prompt.lines().last().startsWith("【强制要求】"))
    assertTrue(prompt.contains("禁止输出任何思考过程"))
  }

  @Test
  fun `泄漏的英文推理只保留最后一段中文正文`() {
    // 真机上抓到的原始返回(deepseek-flash):整段英文推理 + Maybe: 之后才是正文
    val raw =
      "We need answer Chinese only, main line 10-50 words, third person user/character, merge. " +
        "The memories: all test exchanges, no real RP. Need concise. 10-50 Chinese words. " +
        "Maybe: \"用户与角色仅完成六十轮测试对话,未建立关系或共同经历。"
    val body = NodeSummarizer.sanitize(raw)
    assertEquals("用户与角色仅完成六十轮测试对话,未建立关系或共同经历。", body)
    assertTrue(NodeSummarizer.isBodyUsable(body))
  }

  @Test
  fun `没有思考标记但以连排英文开头时从第一个汉字开始取`() {
    val raw = "Here is the merged main line for these three segments:\n用户与角色仍在镇口僵持,敌意未消。"
    assertFalse(NodeSummarizer.sanitize(raw).contains("Here is the merged"))
    assertTrue(NodeSummarizer.sanitize(raw).startsWith("用户与角色"))
  }

  @Test
  fun `中文正文后面跟着英文推理时截到最后一个汉字`() {
    val raw = "用户与角色在码头分手,约定三天后再见。\nLet me count the words: this is about twenty words in English."
    val body = NodeSummarizer.sanitize(raw)
    assertEquals("用户与角色在码头分手,约定三天后再见。", body)
  }

  @Test
  fun `整段都是英文推理时判为不可用`() {
    val raw = "We need to merge the three summaries into one main line of ten to fifty words."
    val body = NodeSummarizer.sanitize(raw)
    assertFalse("全是英文的结果不该被当成正文", NodeSummarizer.isBodyUsable(body))
  }

  @Test
  fun `清洗后不足十个汉字的主线判为不可用`() {
    val short = NodeSummarizer.sanitize("两人相识。")
    assertEquals(4, NodeSummarizer.cjkLength(short))
    assertFalse(NodeSummarizer.isBodyUsable(short))

    val long = NodeSummarizer.sanitize("两人在镇口相识,约定天亮前一起离开。")
    assertEquals(16, NodeSummarizer.cjkLength(long))
    assertTrue(NodeSummarizer.isBodyUsable(long))
  }

  @Test
  fun `正常中文主线不会被清洗破坏`() {
    val raw = "用户与角色在酒馆重逢,关系有所缓和;角色仍在挨饿,用户许诺去找吃的。"
    assertEquals(raw, NodeSummarizer.sanitize(raw))
  }
}
