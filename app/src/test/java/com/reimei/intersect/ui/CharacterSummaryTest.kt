package com.reimei.intersect.ui

import com.reimei.intersect.data.Worldview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色信息页那几行副标题的单元测试。
 *
 * 这些副标题是**拼出来的**字符串:计数、四项名、首行摘要都由代码组合。拼错不会编译报错,
 * 界面上只是显示一行怪话(或者英文模式下漏翻)。这里把三种形态钉死。
 */
class CharacterSummaryTest {

  @Test
  fun `世界观没填时说还没填写`() {
    assertEquals(
      "还没填写 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件",
      worldviewSummary(null)
    )
    assertEquals(
      "还没填写 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件",
      worldviewSummary(Worldview())
    )
  }

  @Test
  fun `世界观部分填写时计数排在前面`() {
    // 计数必须在最前:这一行只有一行宽,超出会被省略,尾巴上的计数会被截掉
    val summary = worldviewSummary(Worldview(laws = "记忆即代价", history = "断河之战"))
    assertTrue(summary, summary.startsWith("已填 2/4 · "))
    assertEquals("已填 2/4 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件", summary)
    assertEquals(
      "已填 4/4 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件",
      worldviewSummary(Worldview("a", "b", "c", "d"))
    )
  }

  @Test
  fun `背景没填时给出提示`() {
    assertEquals("填写这个角色的来历与处境(可留空)", backgroundSummary(null))
    assertEquals("填写这个角色的来历与处境(可留空)", backgroundSummary("   "))
  }

  @Test
  fun `背景填了以后取首行`() {
    assertEquals("出身铁匠铺", backgroundSummary("  出身铁匠铺\n十六岁进城当学徒  "))
    assertEquals("出身铁匠铺", backgroundSummary("出身铁匠铺"))
  }
}
