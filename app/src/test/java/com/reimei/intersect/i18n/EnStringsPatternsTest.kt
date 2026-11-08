package com.reimei.intersect.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 带变量的文案规则的单元测试。
 *
 * 这里测的不是「翻得好不好」,而是**规则有没有匹配上**:这类句子的中文由 `${}` 拼出来,
 * 拼完就查不到整句,只能靠正则反查。正则里错一个字符(比如全角/半角逗号写岔)不会编译报错,
 * 界面上的表现只是英文模式下原样显示中文 —— 从别处很难发现。
 */
class EnStringsPatternsTest {

  private val cjk = Regex("[\\u4e00-\\u9fff]")

  /** 与 [t] 同一套顺序:先查整句表,再走规则表(这里不读语言开关,直接对两张表)。 */
  private fun translate(zh: String): String {
    EnStrings.exact[zh]?.let { return it }
    for (rule in EnStrings.patterns) {
      val match = rule.first.matchEntire(zh) ?: continue
      return rule.second(match)
    }
    return zh
  }

  /** 传进来的中文是界面真会拼出来的样子;翻完不该还是中文。 */
  private fun assertTranslated(zh: String): String {
    val en = translate(zh)
    assertNotEquals("「$zh」没有匹配到任何规则", zh, en)
    assertFalse("「$zh」翻完仍含中文:$en", cjk.containsMatchIn(en))
    return en
  }

  @Test
  fun `回滚确认正文会翻译成英文`() {
    assertEquals(
      "The 3 messages starting from this one will be deleted, returning to the state before it. " +
        "This cannot be undone.",
      assertTranslated("从这条起的 3 条都会被删除,回到它之前的状态。删除后无法恢复。")
    )
  }

  @Test
  fun `只删一条时的英文不再出现 the 1 message`() {
    // 回滚最后一条时 N==1:中文能说「从这条起的 1 条」,英文照抄会很别扭
    val en = assertTranslated("从这条起的 1 条都会被删除,回到它之前的状态。删除后无法恢复。")
    assertEquals(
      "This message will be deleted and you will be back to the state before it. This cannot be undone.",
      en
    )
  }

  @Test
  fun `删除确认正文会翻译成英文`() {
    assertEquals(
      "The 4 selected messages will be deleted and cannot be recovered.",
      assertTranslated("选中的 4 条消息将被删除,且无法恢复。")
    )
  }

  @Test
  fun `记忆库的带变量文案都会翻译`() {
    // 轮数、目标 token 都由常量拼进来,这里填的是它们实际会拼出的值
    val samples = listOf(
      "正在总结前 20 轮对话...",
      "上次总结失败:timed out",
      "总结失败:HTTP 500",
      "总结完成 · 实际 391 token",
      "目标 400 token · 实际 391 token",
      "每 20 轮对话自动总结一次;数值越大保留的细节越多,注入请求体时占的上下文也越多。",
      "每聊完 20 轮会自动总结一次,总结之后这些轮的原文不再发给模型,只发总结。",
      "占位符:{target} 目标 token、{min} 目标下限(约 70%)。写成空文本会退回内置模板。",
      "已选择 3 条",
      "名称、性格、身份 · 已填写 2 项",
      "已用 1200 / 4000 字符",
    )
    samples.forEach { assertTranslated(it) }
  }

  @Test
  fun `控制台的带变量文案会翻译`() {
    val samples = listOf(
      "已登记 3 件 · 对话提到名称时自动补充简介",
      "已复制 5 行到剪贴板",
      "保存失败:/data/local/tmp/x.txt",
      "已保存:/storage/emulated/0/backup.zip",
      "没有可用的 API KEY。请到「我 → API KEY 管理」里添加一条与 https://api.example.com 地址匹配的配置。",
    )
    samples.forEach { assertTranslated(it) }
  }

  @Test
  fun `回滚确认只翻半句的死条目已经删掉`() {
    // 上一版只登记了「这条消息以及它之后的」这半句:插值拼出来的整句永远查不到它,留着只是坑
    assertFalse(EnStrings.exact.containsKey("这条消息以及它之后的"))
  }

  @Test
  fun `本轮新增的两句提示会翻译`() {
    assertTranslated("这条消息已经不在了。")
    assertTranslated("本轮总结期间删过消息或回滚过,结果已作废")
  }
}
