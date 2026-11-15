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
    // 轮数、长度上限都由常量拼进来,这里填的是它们实际会拼出的值
    val samples = listOf(
      "正在总结前 20 轮对话...",
      "上次总结失败:timed out",
      "总结失败:HTTP 500",
      "总结完成 · 实际 391 token",
      "上限 400 token · 实际 391 token",
      "最大 400 token",
      "每 20 轮对话自动总结一次;这是长度上限,实际长度在 250 token 到该值之间按内容浮动。",
      "每聊完 20 轮会自动总结一次,总结之后这些轮的原文不再发给模型,只发总结。",
      "占位符:{target} 长度上限、{min} 长度下限(固定 250)。写成空文本会退回内置模板。",
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

  @Test
  fun `导演模式面板与会话回显的文案会翻译`() {
    // 面板里的静态标签
    listOf("导演模式", "时间", "地点", "天气", "未设定").forEach { assertTranslated(it) }
    // 拒绝原因与回显(由常量拼出来,只能靠规则反查)
    listOf(
      "时间要写成 2026.5.6 8:22 或 第三纪 +302.5.6 8:22,这次没有写时钟",
      "地点最多 40 个字,这次没有写时钟",
      "场景没有变化,没有写时钟",
      "没有改场景:AI 无权写入时钟:只能给建议,执行必须由导演发起",
      "已更新场景:\n<2026.1.1 8:22变更2026.1.2 9:40>",
    ).forEach { assertTranslated(it) }
  }

  @Test
  fun `世界观与背景相关文案会翻译`() {
    listOf(
      "世界观",
      "背景",
      "法则体系",
      "势力分布",
      "经济架构",
      "历史与重大事件",
      // 角色信息页「世界观」那一行的两种形态(由 Worldview 拼出来,只能靠整句表与规则表反查)
      "还没填写 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件",
      "已填 2/4 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件",
      "填写这个角色的来历与处境(可留空)",
      "创建后可用",
      "扮演模式",
      "点选后进入该模式的功能页,完成后按最后进入的模式创建",
      // 创建流程里草稿页的标题带模式名
      "角色信息 · 角色代入",
      "角色信息 · 本体代入",
      // 填写页的占位文案
      "可留空,比如:这个世界没有神,一切力量都要以记忆为代价",
      "可留空,比如:北境军府与商会联盟对峙,教团在两方之间卖消息",
      "可留空,比如:以盐为硬通货,长途贸易全走河运,税按船算",
      "可留空,比如:十七年前的断河之战之后,旧王族再没出现过",
      "可留空,比如:出身铁匠铺,十六岁进城当学徒,如今欠着一笔还不上的债",
      // 两页底部的纪律说明
      "这四项只在本机记录,不会发给模型;你的每一次对话仍只带名称、身份与性格。",
      "只在本机记录,不会发给模型。",
    ).forEach { assertTranslated(it) }
  }

  @Test
  fun `基准时间与纪年相关文案会翻译`() {
    // 角色信息页的时间管理器、它打开的那一页、编辑器提示与错误
    listOf(
      "基准时间(程序设定的)",
      "基准时间",
      "格式不对",
      "看不懂这个时间:年份可带正负号,纪年名不能含数字",
      "正负号 + 纪年 + 年.月.日 时:分,如 第三纪 +302.5.6 8:22",
      "基准时间是程序设定的开局时刻;改它只改当前的时刻,以前的时间记录不会被动。清空就是回到出厂基准 2026.1.1 8:00。",
    ).forEach { assertTranslated(it) }
    // 导演面板的格式提示已换成带纪年的版本
    assertFalse(EnStrings.exact.containsKey("时间要写成 2026.5.6 8:22"))
  }

  @Test
  fun `备份页的带变量文案都会翻译`() {
    // 四张卡片的统计行、删除确认、导入标题、恢复结果与导出完成提示都是拼出来的,只能靠规则反查
    val samples = listOf(
      "当前共有 2 条聊天记录(共 20 条消息)。",
      "当前共有 4 个角色卡。",
      "当前共有 31 条记忆(4 个角色)。",
      "当前共有 1 个模型配置。",
      "已清空 2 个会话的全部聊天记录。",
      "本机的 2 个会话及其中的 20 条消息会全部删除,无法恢复。\n\n角色卡、记忆库与模型配置不受影响。",
      "导入聊天记录",
      "导入角色卡",
      "导入记忆库",
      "导入模型配置",
      "应用版本 1.2.3",
      "应用版本 未知",
      // 备份规模概览:一份文件只装一类内容,所以每次只会出现其中一句
      "2 个角色",
      "1 个会话(2 条消息)",
      "31 条记忆",
      "1 个模型配置",
      // 恢复结果
      "角色 +1 / 更新 0",
      "会话 +1 / 补充 2(3 条消息)",
      "记忆 +2",
      "模型配置 +1 / 更新 0",
      "已保存到你选择的位置:\nintersect-chats-20240101-120000.json",
    )
    samples.forEach { assertTranslated(it) }
  }

  @Test
  fun `备份不再出现加密与口令相关文案`() {
    // 备份已改成明文 .json,这些句子留在表里只会让人以为还有加密功能
    listOf(
      "口令加密",
      "用 6 位数字口令加密整份备份",
      "未加密",
      "备份口令",
      "备份口令必须是 6 位数字",
    ).forEach { assertFalse("翻译表里还留着「$it」", EnStrings.exact.containsKey(it)) }
  }

  @Test
  fun `基准时间带具体时刻时只翻前缀`() {
    // 「第三纪」是用户起的纪年名,属于数据不是文案,翻完必须原样保留
    assertEquals("Base time · 第三纪 +302.5.6 8:22", translate("基准时间 · 第三纪 +302.5.6 8:22"))
    // 没起纪年名(公元)时整行没有中文残留
    assertEquals("Base time · -302.5.6 8:22", assertTranslated("基准时间 · -302.5.6 8:22"))
  }
}
