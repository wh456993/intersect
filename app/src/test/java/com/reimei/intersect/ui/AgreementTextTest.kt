package com.reimei.intersect.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议正文富文本的单元测试。
 *
 * 锁死两件事:哪些片段被认成链接(网址 / 邮箱)、以及 `**重点**` 标记不出现在成品文本里。
 * 这些结论在界面上肉眼很难确认(字色变了没有、点得动不动),只能靠注解与文本内容断言。
 */
class AgreementTextTest {

  private val linkColor = Color(0xFF2563EB)
  private val emphasisColor = Color(0xFF111827)

  private fun build(text: String) = buildAgreementText(text, linkColor, emphasisColor)

  /** 取出全部链接注解目标(去重保序)。 */
  private fun AnnotatedString.links(): List<String> =
    getStringAnnotations(TAG_URL, 0, length).map { it.item }.distinct()

  @Test
  fun `裸网址被认成链接且去掉中文逗号`() {
    val text = build("源码托管于 https://github.com/wh456993/intersect,以仓库为准")
    assertEquals(listOf("https://github.com/wh456993/intersect"), text.links())
  }

  @Test
  fun `邮箱补上 mailto 前缀且去掉尾部句号`() {
    val text = build("开发者邮箱:wba456456@qq.com。")
    assertEquals(listOf("mailto:wba456456@qq.com"), text.links())
  }

  @Test
  fun `普通文字没有链接注解`() {
    val text = build("本应用不收集任何数据")
    assertTrue(text.links().isEmpty())
    assertEquals("本应用不收集任何数据", text.text)
  }

  @Test
  fun `粗体标记渲染成粗体并且星号不留在文本里`() {
    val text = build("秉持「**纯本地存储**」的原则")
    assertEquals("秉持「纯本地存储」的原则", text.text)
    val bold = text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
    assertEquals(1, bold.size)
    // 粗体范围正好覆盖「纯本地存储」这五个字
    assertEquals("纯本地存储", text.text.substring(bold.single().start, bold.single().end))
  }

  @Test
  fun `同一段里的链接与粗体互不干扰`() {
    val text = build("**仓库**地址:https://github.com/wh456993/intersect")
    assertEquals("仓库地址:https://github.com/wh456993/intersect", text.text)
    assertEquals(listOf("https://github.com/wh456993/intersect"), text.links())
    assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
  }

  @Test
  fun `网址里的冒号与问号属于链接`() {
    val text = build("见 https://example.com:8443/v1?x=1 这一条")
    assertEquals(listOf("https://example.com:8443/v1?x=1"), text.links())
  }
}
