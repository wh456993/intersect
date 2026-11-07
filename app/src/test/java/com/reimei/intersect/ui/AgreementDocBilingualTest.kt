package com.reimei.intersect.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中英双语协议正文的一致性测试。
 *
 * 协议是两份独立的硬编码文本,靠人肉维护对齐,最容易出的问题不是翻译好不好,
 * 而是**改了一边忘了另一边**:中文加了一条、英文还是旧的,或者英文里漏了几节。
 * 这里用结构比对把「两边节数、每节段数完全一致」钉死,再加一条「英文版不含中文」。
 */
class AgreementDocBilingualTest {

  private val zh = agreementDocFor(english = false)
  private val en = agreementDocFor(english = true)

  private val cjk = Regex("[\\u4e00-\\u9fff]")

  @Test
  fun `中英两版节数与顺序一致`() {
    assertEquals(zh.blocks.size, en.blocks.size)
    zh.blocks.forEachIndexed { index, block ->
      assertEquals(
        "第 $index 节的标题级别不一致(顺序对不上)",
        block.level,
        en.blocks[index].level
      )
    }
  }

  @Test
  fun `每一节的段落数一致`() {
    zh.blocks.forEachIndexed { index, block ->
      assertEquals(
        "第 $index 节「${block.heading}」的段落数中英不一致",
        block.paragraphs.size,
        en.blocks[index].paragraphs.size
      )
    }
  }

  @Test
  fun `元信息行数一致`() {
    assertEquals(zh.meta.size, en.meta.size)
  }

  @Test
  fun `英文版正文不含中文`() {
    // 逐行报错,比一句「整体含中文」好定位漏翻的那一行
    en.plainText().lineSequence().forEach { line ->
      assertFalse("英文版里混进了中文:$line", cjk.containsMatchIn(line))
    }
  }

  @Test
  fun `中文版正文确实是中文`() {
    assertTrue(cjk.containsMatchIn(zh.plainText()))
  }

  @Test
  fun `两版都以效力声明开头且声明双向可查`() {
    assertTrue(zh.plainText().startsWith(zh.disclaimer))
    assertTrue(en.plainText().startsWith(en.disclaimer))
    // 中文版声明必须写清「以中文为准」,英文版必须写清 prevails
    assertTrue(zh.disclaimer.contains("以中文"))
    assertTrue(en.disclaimer.contains("Chinese version"))
    assertTrue(en.disclaimer.contains("prevail"))
  }

  @Test
  fun `关键外链在两版里都存在`() {
    listOf(zh, en).forEach { doc ->
      val text = doc.plainText()
      assertTrue(text.contains("https://github.com/wh456993/intersect"))
      assertTrue(text.contains("wba456456@qq.com"))
      assertTrue(text.contains("http://www.apache.org/licenses/LICENSE-2.0"))
    }
  }

  @Test
  fun `重点标记在两版里成对出现`() {
    // **...** 必须是偶数个,否则粗体渲染会把后半段整篇吞进去
    listOf(zh, en).forEach { doc ->
      doc.plainText().lineSequence().forEach { line ->
        val stars = line.windowed(2).count { it == "**" }
        assertTrue("这行的 ** 不成对:$line", stars % 2 == 0)
      }
    }
  }
}
