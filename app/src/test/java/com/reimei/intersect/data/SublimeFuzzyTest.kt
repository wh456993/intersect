package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SublimeFuzzy 模糊匹配的单元测试。
 *
 * 把「物品名称能否模糊命中用户消息」这条规则钉死:连续出现、忽略大小写、中间隔
 * 空格/标点都算命中,散落在整句里的假命中不算,名称的字符顺序变了也不算。
 */
class SublimeFuzzyTest {

  @Test
  fun `完整连续出现必然命中`() {
    assertTrue(SublimeFuzzy.matches("药水", "我有药水"))
    assertTrue(SublimeFuzzy.matches("药水", "药水"))
    assertTrue(SublimeFuzzy.matches("药水", "这瓶药水在这里"))
  }

  @Test
  fun `单个字出现在句中就算命中`() {
    assertTrue(SublimeFuzzy.matches("剑", "我今天买了一把剑"))
    assertTrue(SublimeFuzzy.matches("剑", "剑"))
  }

  @Test
  fun `忽略大小写`() {
    assertTrue(SublimeFuzzy.matches("hp", "我的HP药水"))
    assertTrue(SublimeFuzzy.matches("HP", "hp药水"))
    assertTrue(SublimeFuzzy.matches("FooBar", "foobar"))
  }

  @Test
  fun `名称字符中间隔了空格或标点仍命中`() {
    assertTrue(SublimeFuzzy.matches("药水", "我有药 水"))
    assertTrue(SublimeFuzzy.matches("药水", "我有药-水"))
  }

  @Test
  fun `名称字符顺序乱了不算命中`() {
    assertFalse(SublimeFuzzy.matches("药水", "水药"))
    assertFalse(SublimeFuzzy.matches("铁剑", "剑铁"))
  }

  @Test
  fun `名称散落在整句里不算命中`() {
    assertFalse(SublimeFuzzy.matches("药水", "我把药放在桌上,水在杯子里"))
  }

  @Test
  fun `名称没有完整出现不算命中`() {
    assertFalse(SublimeFuzzy.matches("铁剑", "我拔出剑"))
    assertFalse(SublimeFuzzy.matches("健康药水", "来一瓶药水"))
  }

  @Test
  fun `空名称永不命中`() {
    assertFalse(SublimeFuzzy.matches("", "随便什么话"))
    assertFalse(SublimeFuzzy.matches("  ", "随便什么话"))
  }

  @Test
  fun `分数越高命中越紧实`() {
    val tight = SublimeFuzzy.score("药水", "药水")!!
    val withSpace = SublimeFuzzy.score("药水", "药 水")!!
    assertTrue(tight > withSpace)
  }

  @Test
  fun `无法按顺序匹配时得分为空`() {
    assertNull(SublimeFuzzy.score("药水", "水"))
    assertNull(SublimeFuzzy.score("abc", "aXcYbZ"))
  }

  @Test
  fun `驼峰边界命中加分`() {
    val camel = SublimeFuzzy.score("fb", "fooBar")!!
    val flat = SublimeFuzzy.score("fb", "foobar")!!
    assertTrue(camel > flat)
  }

  @Test
  fun `前半句先出现单个字时不会漏掉后半句紧实的命中`() {
    assertTrue(SublimeFuzzy.matches("药水", "药放在桌上,我要药水"))
    assertTrue(SublimeFuzzy.matches("药水", "药水"))
  }
}
