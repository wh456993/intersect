package com.reimei.intersect.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 总结请求接入元信息的契约。
 *
 * META_INFO_API.md 第 8 章留的那条待接入点:总结请求的 user 内容末尾要追加元信息块,
 * 总结规则模板要追加「元信息格式 + 由 AI 按剧情决定 buff 词条」的规则。
 *
 * 这里只测纯字符串那一层([MemorySummarizer.userContent] / [MemorySummarizer.systemPrompt]) ——
 * [MemorySummarizer.payload] 走 `org.json`,在 JVM 单测里是空实现,不能直接断言。
 */
class MemorySummaryMetaTest {
  private val meta = MetaInfo(
    friendId = "friend_1",
    favorability = 70,
    health = 60,
    hunger = 30,
    time = "黄昏",
    location = "镇口酒馆",
    buffs = listOf("失血"),
  )

  private val rounds = listOf("第 1 轮\n用户:我去码头", "第 2 轮\n角色:跟上来")

  @Test
  fun `没有元信息时正文与原来一字不差`() {
    val content = MemorySummarizer.userContent(rounds)
    assertTrue(content.startsWith("需要压缩的对话共 2 轮,原文如下:"))
    assertTrue(content.endsWith("第 2 轮\n角色:跟上来"))
    assertFalse(content.contains(MetaInfo.HEADER))
  }

  @Test
  fun `元信息块追加在原文末尾`() {
    val content = MemorySummarizer.userContent(rounds, meta)
    assertTrue(content.endsWith(meta.block()))
    assertTrue(content.startsWith("需要压缩的对话共 2 轮,原文如下:"))
    // 原文仍在,顺序是「原文 → 元信息」
    assertTrue(content.indexOf("角色:跟上来") < content.indexOf(MetaInfo.HEADER))
  }

  @Test
  fun `元信息块里只有名词,没有数值`() {
    val block = MemorySummarizer.userContent(rounds, meta).substringAfter(MetaInfo.HEADER)
    assertTrue(block.contains("好感:熟悉"))
    assertTrue(block.contains("生命:亚健康"))
    assertTrue(block.contains("饥饿:微饿"))
    assertTrue(block.contains("Buff:失血"))
    assertFalse("元信息块不许出现阿拉伯数字", Regex("[0-9]").containsMatchIn(block))
  }

  @Test
  fun `总结模板追加了元信息规则`() {
    val system = MemorySummarizer.systemPrompt(ImmediateMemoryState.TARGET_DEFAULT)
    assertTrue(system.contains(MetaInfo.SUMMARY_RULES))
    // 长度占位符仍被替换掉(既有契约不能被元信息规则破坏)
    assertFalse(system.contains(MemorySummarizer.TARGET_SLOT))
    assertFalse(system.contains(MemorySummarizer.MIN_SLOT))
  }

  @Test
  fun `元信息规则限定 buff 词表且禁止输出数值`() {
    val rules = MetaInfo.SUMMARY_RULES
    MetaInfo.BUFF_VOCAB.forEach { assertTrue("词表缺了 $it", rules.contains(it)) }
    assertTrue(rules.contains("不要编造新词条"))
    assertTrue(rules.contains("任何数值结算"))
  }

  @Test
  fun `聊天用的元信息规则与分档名词一致`() {
    // RULES 是对模型的表演指引,分档名词必须与 MetaPhrases 的实际输出一一对上,
    // 否则模型看到的对照表和请求体里的词不是同一套(对照表用的是全角冒号)。
    val rules = MetaInfo.RULES
    listOf(0, 30, 50, 70, 90).map { MetaPhrases.favor(it) }.distinct()
      .forEach { assertTrue("好感档「$it」没写进规则", rules.contains("$it:")) }
    listOf(0, 10, 40, 60, 80, 100).map { MetaPhrases.health(it) }.distinct()
      .forEach { assertTrue("生命档「$it」没写进规则", rules.contains("$it:")) }
    listOf(10, 30, 60, 80, 95).map { MetaPhrases.hunger(it) }.distinct()
      .forEach { assertTrue("饥饿档「$it」没写进规则", rules.contains("$it:")) }
  }

  @Test
  fun `聊天用的元信息规则逐字包含模板里的五条`() {
    val rules = MetaInfo.RULES
    (1..5).forEach { n ->
      assertTrue("缺了第 $n 条规则", Regex("^$n\\. ", RegexOption.MULTILINE).containsMatchIn(rules))
    }
    assertTrue(rules.contains("【系统元信息规则】"))
    assertTrue(rules.contains("【状态名词表演对照表】"))
    assertTrue(rules.contains("不要自行编造新词条"))
  }
}
