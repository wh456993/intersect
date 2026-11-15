package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 元信息「数值 → 名词」映射与名词块的单元测试(纯逻辑;落盘在 androidTest 里)。
 *
 * 钉住的是契约:请求体里**永远不出现具体数字** —— 分档边界一旦被改错,
 * 界面看不出来,只会静默地把「60 分的熟悉」发成「陌生」这类错位。
 */
class MetaInfoTest {

  // ---- 好感度 5 档:0-19 仇视 / 20-39 冷淡 / 40-59 陌生 / 60-79 熟悉 / 80-100 亲密 ----

  @Test
  fun `好感度分档边界`() {
    assertEquals("仇视", MetaPhrases.favor(0))
    assertEquals("仇视", MetaPhrases.favor(19))
    assertEquals("冷淡", MetaPhrases.favor(20))
    assertEquals("冷淡", MetaPhrases.favor(39))
    assertEquals("陌生", MetaPhrases.favor(40))
    assertEquals("陌生", MetaPhrases.favor(50)) // 50 居中 = 陌生人
    assertEquals("陌生", MetaPhrases.favor(59))
    assertEquals("熟悉", MetaPhrases.favor(60))
    assertEquals("熟悉", MetaPhrases.favor(79))
    assertEquals("亲密", MetaPhrases.favor(80))
    assertEquals("亲密", MetaPhrases.favor(100))
  }

  // ---- 生命值 6 档:0 死亡 / <30 重病 / <50 状态不佳 / <70 亚健康 / <90 健康 / 90+ 优质 ----

  @Test
  fun `生命值分档边界`() {
    assertEquals("死亡", MetaPhrases.health(0))
    assertEquals("重病", MetaPhrases.health(1))
    assertEquals("重病", MetaPhrases.health(29))
    assertEquals("状态不佳", MetaPhrases.health(30))
    assertEquals("状态不佳", MetaPhrases.health(49))
    assertEquals("亚健康", MetaPhrases.health(50))
    assertEquals("亚健康", MetaPhrases.health(69))
    assertEquals("健康", MetaPhrases.health(70))
    assertEquals("健康", MetaPhrases.health(90)) // 90 归健康(与「70~90=健康」一致)
    assertEquals("优质", MetaPhrases.health(91))
    assertEquals("优质", MetaPhrases.health(100))
  }

  // ---- 饥饿值:<20 饥饿 / <50 微饿 / <80 六分饱 / 80 正常吃饱 / >80 吃撑 ----

  @Test
  fun `饥饿值分档边界`() {
    assertEquals("饥饿", MetaPhrases.hunger(0))
    assertEquals("饥饿", MetaPhrases.hunger(19))
    assertEquals("微饿", MetaPhrases.hunger(20))
    assertEquals("微饿", MetaPhrases.hunger(49))
    assertEquals("六分饱", MetaPhrases.hunger(50)) // 50 = 六分饱
    assertEquals("六分饱", MetaPhrases.hunger(79))
    assertEquals("正常吃饱", MetaPhrases.hunger(80)) // 80 = 正常吃饱
    assertEquals("吃撑", MetaPhrases.hunger(81))
    assertEquals("吃撑", MetaPhrases.hunger(100))
  }

  @Test
  fun `越界数值夹回 0-100 再映射`() {
    assertEquals("仇视", MetaPhrases.favor(-5))
    assertEquals("亲密", MetaPhrases.favor(200))
    assertEquals("死亡", MetaPhrases.health(-1))
    assertEquals("优质", MetaPhrases.health(999))
    assertEquals("饥饿", MetaPhrases.hunger(-100))
    assertEquals("吃撑", MetaPhrases.hunger(1000))
  }

  // ---- 名词块:只出名词,不出数字 ----

  @Test
  fun `全字段元信息块的格式与名词`() {
    val info = MetaInfo(
      friendId = "f1",
      favorability = 65, // 熟悉
      health = 45, // 状态不佳
      hunger = 30, // 微饿
      time = "黄昏",
      relationship = "点头之交",
      location = "镇口酒馆",
      characters = "旅人、守卫",
      weather = "小雨",
      buffs = listOf("失血", "中毒"),
    )
    assertEquals(
      """
      [元信息]
      时间:黄昏
      好感:熟悉
      关系:点头之交
      地点:镇口酒馆
      角色:旅人、守卫
      天气:小雨
      生命:状态不佳
      Buff:失血、中毒
      饥饿:微饿
      """.trimIndent(),
      info.block(),
    )
  }

  @Test
  fun `文本字段全空时只剩三行数值名词`() {
    val info = MetaInfo(friendId = "f1")
    assertEquals(
      """
      [元信息]
      好感:陌生
      生命:健康
      饥饿:正常吃饱
      """.trimIndent(),
      info.block(),
    )
  }

  @Test
  fun `元信息块里不出现任何数字`() {
    val info = MetaInfo(
      friendId = "f1",
      favorability = 50,
      health = 69,
      hunger = 81,
      time = "周三下午",
      buffs = MetaInfo.BUFF_VOCAB,
    )
    val block = info.block()
    assertFalse("请求体里不该出现具体数值:$block", block.any { it.isDigit() })
    // 汉字数字(「三」「六」)不受限制:时间文本是用户写的自由文本
    assertTrue(block.contains("时间:周三下午"))
  }

  @Test
  fun `空白文本字段被整行省略`() {
    val info = MetaInfo(friendId = "f1", time = "  ", location = "酒馆")
    assertFalse(info.block().contains("\n时间:"))
    assertTrue(info.block().contains("\n地点:酒馆"))
  }

  @Test
  fun `空 buff 列表省略 Buff 行`() {
    val info = MetaInfo(friendId = "f1")
    assertFalse(info.block().contains("Buff:"))
  }

  @Test
  fun `默认值对应中立状态`() {
    assertEquals("陌生", MetaInfo.empty("f1").favorNoun())
    assertEquals("健康", MetaInfo.empty("f1").healthNoun())
    assertEquals("正常吃饱", MetaInfo.empty("f1").hungerNoun())
  }

  @Test
  fun `buff 词表只有确认过的四个词条`() {
    assertEquals(listOf("饥饿", "失血", "中毒", "重病"), MetaInfo.BUFF_VOCAB)
  }
}
