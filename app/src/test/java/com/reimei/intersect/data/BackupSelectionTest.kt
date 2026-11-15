package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 导出筛选的单元测试:勾了哪些就只导出哪些。
 *
 * 这一层的硬约束是「绝不顺带全量塞进去」,所以两个边界都钉住:
 * null 表示不筛选(记忆库 / 模型配置这类整类导出),空集合表示一条都不要 —— 不能等价成 null。
 */
class BackupSelectionTest {
  private val all = listOf("a", "b", "c")

  @Test
  fun `null 表示整套导出`() {
    val items = all.map { it to it }
    assertSame(items, filterSelectedBy(items, null) { it.first })
  }

  @Test
  fun `只留下勾中的那些`() {
    val picked = filterSelectedBy(all, setOf("a", "c")) { it }
    assertEquals(listOf("a", "c"), picked)
  }

  @Test
  fun `空集合不会退化成全量`() {
    assertEquals(emptyList<String>(), filterSelectedBy(all, emptySet()) { it })
  }

  @Test
  fun `勾了不存在的 id 时结果为空`() {
    assertEquals(emptyList<String>(), filterSelectedBy(all, setOf("zzz")) { it })
  }

  @Test
  fun `保持原有顺序`() {
    // 结果顺序跟的是原列表(而 Selection 的迭代顺序 —— 它是 Set,顺序不可依赖)
    assertEquals(listOf("a", "c"), filterSelectedBy(all, setOf("c", "a")) { it })
  }
}
