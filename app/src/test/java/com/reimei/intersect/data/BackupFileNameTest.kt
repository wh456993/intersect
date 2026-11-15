package com.reimei.intersect.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出文件命名的单元测试。
 *
 * 文件名是用户在自己文件管理器里唯一能看到的线索:它要说清「这是谁、什么内容、什么时候」。
 * 角色名是用户随手写的,直接拼进文件名会产生空名字、带路径分隔符的名字、或者把 emoji 切成半个,
 * 所以这里的边界都在钉:非法字符被剔除、超长截断不切断代理对、刷干净后回退到只留类别名。
 *
 * 纯 JVM 单测:[BackupFiles.fileName] 只依赖 SimpleDateFormat,不碰 Context。
 */
class BackupFileNameTest {
  /** 时间戳带时区偏移,所以期望值也按同一个格式化器算,不硬编码。 */
  private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(NOW))

  private fun name(section: BackupSection, subject: String? = null) =
    BackupFiles.fileName(section, subject, NOW)

  @Test
  fun `聊天记录用角色名加类别加时间`() {
    assertEquals("小红-聊天记录-$stamp.json", name(BackupSection.Chats, "小红"))
  }

  @Test
  fun `角色卡用角色名加角色卡`() {
    assertEquals("小红-角色卡-$stamp.json", name(BackupSection.Characters, "小红"))
  }

  @Test
  fun `模型配置与整包用软件正式名`() {
    assertEquals("Intersect-API_Key-$stamp.json", name(BackupSection.ApiKeys))
    assertEquals("Intersect-软件备份-$stamp.json", name(BackupSection.Full))
    // 就算调用方传了名字也不该被拼进去:这两类本来就没有单一主体
    assertEquals("Intersect-软件备份-$stamp.json", name(BackupSection.Full, "小红"))
  }

  @Test
  fun `没有主体时只留类别名`() {
    assertEquals("聊天记录-$stamp.json", name(BackupSection.Chats))
    assertEquals("角色卡-$stamp.json", name(BackupSection.Characters))
  }

  @Test
  fun `路径分隔符与保留字符被剔掉`() {
    val result = name(BackupSection.Characters, "../a/b:c*d?e\"f<g>h|i")
    listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').forEach { bad ->
      assertFalse("名字里不该有 $bad:$result", result.contains(bad))
    }
    // 「..」被剔掉后不该还能拼出上级目录
    assertFalse("名字不该以点开头:$result", result.startsWith("."))
    assertTrue("名字该保留类别与时间:$result", result.endsWith("-$stamp.json"))
  }

  @Test
  fun `全是非法字符时回退到只留类别名`() {
    assertEquals("角色卡-$stamp.json", name(BackupSection.Characters, "///"))
    assertEquals("角色卡-$stamp.json", name(BackupSection.Characters, "   "))
    assertEquals("角色卡-$stamp.json", name(BackupSection.Characters, "..."))
  }

  @Test
  fun `超长名字被截断且不切断代理对`() {
    // 前缀 1 个字符 + 每个 emoji 占 2 个 char:第 40 个 char 正好落在半个 emoji 上
    val long = "a" + "😀".repeat(30)
    val subject = name(BackupSection.Chats, long).substringBefore("-聊天记录-")
    assertTrue("截断后不该超长:${subject.length}", subject.length <= 40)
    // 结尾不能是孤立的高位代理,否则文件管理器里显示成方块
    assertFalse("结尾是孤立高位代理", subject.last().isHighSurrogate())
    assertFalse("结尾是孤立低位代理", subject.last().isLowSurrogate())
  }

  @Test
  fun `时间戳格式固定`() {
    assertTrue(name(BackupSection.Characters, "小红").matches(Regex("小红-角色卡-\\d{8}-\\d{6}\\.json")))
  }

  private companion object {
    /** 2025-01-01 12:00:00 UTC。 */
    const val NOW = 1_735_732_800_000L
  }
}
