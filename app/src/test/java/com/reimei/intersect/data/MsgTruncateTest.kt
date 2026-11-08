package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「回滚」截断逻辑的单元测试。
 *
 * 被钉住的是两条容易被改坏的性质(注释里写了,但只有测试才拦得住):
 * 1. 按**对象身份**匹配,不用 `equals` —— 内容相同的两条消息各自独立;
 * 2. 目标不在列表里时一个字都不删(返回 -1),调用方据此早退。
 *
 * 这些用例是纯逻辑,不碰 SharedPreferences,所以能在 JVM 单测里跑。
 */
class MsgTruncateTest {

  private val me = User.Me
  private val friend = User("char-1", "角色", 0)

  private fun msg(text: String, from: User = me) = Msg(from, text)

  @Test
  fun `从目标起截断 只留它之前的部分`() {
    val a = msg("一")
    val b = msg("二", friend)
    val c = msg("三")
    val list = mutableListOf(a, b, c)

    assertEquals(2, truncateAt(list, b))
    assertEquals(listOf<Msg>(a), list)
  }

  @Test
  fun `内容相同的两条消息不会串`() {
    val first = msg("同样的话")
    val second = msg("同样的话")
    // 内容完全一样,但不是同一个对象:回滚第二条不该把第一条一起带走
    val list = mutableListOf(first, second)

    assertEquals(1, truncateAt(list, second))
    assertEquals(listOf<Msg>(first), list)
    assertTrue(list.single() === first)
  }

  @Test
  fun `目标不在列表里时一条都不删`() {
    val a = msg("一")
    val b = msg("二")
    val list = mutableListOf(a, b)
    val outsider = msg("一")

    assertEquals(-1, truncateAt(list, outsider))
    assertEquals(2, list.size)
  }

  @Test
  fun `回滚最后一条只删它自己`() {
    val a = msg("一")
    val b = msg("二")
    val list = mutableListOf(a, b)

    assertEquals(1, truncateAt(list, b))
    assertEquals(listOf<Msg>(a), list)
  }

  @Test
  fun `空列表上是安全的空操作`() {
    assertEquals(-1, truncateAt(mutableListOf(), msg("一")))
  }

  @Test
  fun `回滚到第一条等于清空`() {
    val a = msg("一")
    val list = mutableListOf(a, msg("二"), msg("三"))

    assertEquals(3, truncateAt(list, a))
    assertTrue(list.isEmpty())
  }
}
