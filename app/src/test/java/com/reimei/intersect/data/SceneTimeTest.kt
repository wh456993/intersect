package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景时间(唯一特殊的元信息字段)的单元测试。
 *
 * 钉住三条:本体代入必须走真实时钟且算法**不生效**、角色代入走算法且算法缺失时回落到手填、
 * 时间行只出文本不出数值。
 */
class SceneTimeTest {

  /** 固定时刻,避免测试跟着真实时钟飘。 */
  private val fixedMillis = 1_735_000_000_000L

  /** 测试用剧情时钟:角色代入下给出一个虚构时间。 */
  private val fictionClock = FictionClock { _, _ -> "第三天黄昏" }

  // ---- 本体代入:真实时钟,算法不得干预 ----

  @Test
  fun `本体代入输出年月日具体时间`() {
    val text = SceneTime.of(RoleplayMode.SELF_INSERT, "f1", nowMillis = fixedMillis)
    assertTrue("应是 yyyy-MM-dd HH:mm 格式:$text", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(text))
  }

  @Test
  fun `本体代入时剧情时钟不生效`() {
    // 就算传了算法,本体代入也必须给真实时间 —— 这是硬保证,不给算法留后门
    val text = SceneTime.of(
      mode = RoleplayMode.SELF_INSERT,
      friendId = "f1",
      fallback = "手填的时间",
      nowMillis = fixedMillis,
      fiction = fictionClock,
    )
    assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(text))
    assertFalse(text.contains("第三天黄昏"))
  }

  @Test
  fun `本体代入忽略手填值`() {
    val text = SceneTime.of(RoleplayMode.SELF_INSERT, "f1", fallback = "黄昏", nowMillis = fixedMillis)
    assertFalse("手填值不该覆盖真实时钟", text == "黄昏")
  }

  // ---- 角色代入:算法优先,没有算法就回落到手填 ----

  @Test
  fun `角色代入时剧情时钟生效`() {
    assertEquals(
      "第三天黄昏",
      SceneTime.of(RoleplayMode.AVATAR_BASED, "f1", fallback = "手填的时间", nowMillis = fixedMillis, fiction = fictionClock),
    )
  }

  @Test
  fun `角色代入默认时钟未实现时回落到手填值`() {
    // DefaultFictionClock 恒返回 null = 算法尚未实现,此时表现为「用手填的时间」
    assertEquals(
      "黄昏",
      SceneTime.of(RoleplayMode.AVATAR_BASED, "f1", fallback = "黄昏", nowMillis = fixedMillis),
    )
  }

  @Test
  fun `角色代入没有手填值时返回空串`() {
    // 空串表示「没有时间」,调用方据此整行省略
    assertEquals("", SceneTime.of(RoleplayMode.AVATAR_BASED, "f1", nowMillis = fixedMillis))
  }

  @Test
  fun `算法明确返回空串时不回落到手填`() {
    // 空串是算法给出的「确定没有时间」(失忆/梦境),不该被手填值顶掉
    val blank = FictionClock { _, _ -> "" }
    assertEquals(
      "",
      SceneTime.of(RoleplayMode.AVATAR_BASED, "f1", fallback = "黄昏", nowMillis = fixedMillis, fiction = blank),
    )
  }

  @Test
  fun `手填值两端空白会被去掉`() {
    assertEquals(
      "黄昏",
      SceneTime.of(RoleplayMode.AVATAR_BASED, "f1", fallback = "  黄昏  ", nowMillis = fixedMillis),
    )
  }

  // ---- 套回元信息 ----

  @Test
  fun `本体代入用真实时间覆盖手填并进块`() {
    val meta = MetaInfo(friendId = "f1", time = "黄昏")
    val applied = SceneTime.applyTo(meta, RoleplayMode.SELF_INSERT, fixedMillis)
    assertTrue(
      "块里应是真实时间:${applied.block()}",
      Regex("""时间:\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(applied.block()),
    )
  }

  @Test
  fun `角色代入在算法未实现时保留手填时间`() {
    val meta = MetaInfo(friendId = "f1", time = "黄昏")
    val applied = SceneTime.applyTo(meta, RoleplayMode.AVATAR_BASED, fixedMillis)
    assertTrue(applied.block().contains("时间:黄昏"))
  }

  @Test
  fun `角色代入没有时间时块里省略时间行`() {
    val applied = SceneTime.applyTo(MetaInfo(friendId = "f1"), RoleplayMode.AVATAR_BASED, fixedMillis)
    assertFalse(applied.block().contains("\n时间:"))
  }

  @Test
  fun `时间行不带数字以外的数值污染`() {
    // 角色代入的算法时间是纯文本,块里不该因此冒出好感/生命那种数字
    val applied = SceneTime.applyTo(
      MetaInfo(friendId = "f1"),
      RoleplayMode.AVATAR_BASED,
      fixedMillis,
    )
    assertFalse(applied.block().any { it.isDigit() })
  }
}
