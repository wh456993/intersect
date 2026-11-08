package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义「总结记忆提示词」的单元测试(纯逻辑部分;落盘与开关在 androidTest 里)。
 *
 * 要钉住的是三件事:占位符会被替换成当前滑块值、模板为空时退回内置模板、
 * 自定义模板真的进了总结请求的 system —— 最后这条错了会表现成「改了提示词但总结没变」,
 * 界面上完全看不出来。
 */
class MemoryPromptTest {

  @Test
  fun `内置模板里的占位符会被替换成具体数字`() {
    val prompt = MemorySummarizer.systemPrompt(
      targetTokens = 400,
      template = "长度控制在 {min}~{target} token 之间。",
    )
    // 下限是目标的 70%
    assertEquals("长度控制在 280~400 token 之间。", prompt)
  }

  @Test
  fun `内置模板替换后不再含占位符`() {
    val prompt = MemorySummarizer.systemPrompt(ImmediateMemoryState.TARGET_DEFAULT)
    assertTrue(prompt.contains("280~400"))
    assertTrue(!prompt.contains(MemorySummarizer.TARGET_SLOT))
    assertTrue(!prompt.contains(MemorySummarizer.MIN_SLOT))
  }

  @Test
  fun `没有占位符的模板原样使用`() {
    val prompt = MemorySummarizer.systemPrompt(400, "只写事实,不要形容。")
    assertEquals("只写事实,不要形容。", prompt)
  }

  @Test
  fun `模板为空时退回内置模板`() {
    assertEquals(
      MemorySummarizer.systemPrompt(400, MemorySummarizer.DEFAULT_TEMPLATE),
      MemorySummarizer.systemPrompt(400, ""),
    )
  }

  @Test
  fun `自定义模板里的占位符同样会被替换`() {
    val prompt = MemorySummarizer.systemPrompt(600, "我的规则:不超过 {target} token,至少 {min}。")
    assertEquals("我的规则:不超过 600 token,至少 420。", prompt)
  }

  // ---- 三态:实际生效的到底是不是自定义模板 ----
  // 界面上的「当前:自定义 / 当前:内置模板」与发请求时用的模板必须同一把尺子,
  // 否则会出现「写着自定义、实际发的是内置模板」这种看不出来的错位。

  @Test
  fun `开关关着时即使写了模板也走内置模板`() {
    assertFalse(MemoryPromptStore.isCustom(enabled = false, prompt = "只写事实,不要形容。"))
  }

  @Test
  fun `开关开着但文本为空时仍走内置模板`() {
    assertFalse(MemoryPromptStore.isCustom(enabled = true, prompt = ""))
    assertFalse(MemoryPromptStore.isCustom(enabled = true, prompt = "   "))
  }

  @Test
  fun `开关开着且有内容时才算自定义`() {
    assertTrue(MemoryPromptStore.isCustom(enabled = true, prompt = "只写事实,不要形容。"))
  }

  @Test
  fun `模板长度上限不会把内置模板裁掉`() {
    // 上限只用在用户粘贴的文本上;内置模板自己必须短于上限,否则「恢复默认」会被裁
    assertTrue(MemorySummarizer.DEFAULT_TEMPLATE.length < MemoryPromptStore.MAX_LENGTH)
  }

  @Test
  fun `超长模板会被收到上限内`() {
    val long = "字".repeat(MemoryPromptStore.MAX_LENGTH + 500)
    assertEquals(MemoryPromptStore.MAX_LENGTH, MemoryPromptStore.cap(long).length)
  }

  @Test
  fun `没超限的模板原样返回`() {
    val text = "只写事实,不要形容。"
    assertEquals(text, MemoryPromptStore.cap(text))
  }

  @Test
  fun `裁剪不会把 emoji 切成半个 surrogate`() {
    // 前 3999 个字符 + 一个占两个 code unit 的 emoji:第 4000 位正好落在代理对中间
    val text = "字".repeat(MemoryPromptStore.MAX_LENGTH - 1) + "\uD83D\uDE00" + "尾巴"
    val capped = MemoryPromptStore.cap(text)
    assertTrue("裁剪后不该以孤立的高位代理结尾", !capped.last().isHighSurrogate())
    assertEquals(MemoryPromptStore.MAX_LENGTH - 1, capped.length)
  }
}
