package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.2 验收 —— 每个枚举都必须有默认名词,并且能反查回同一个枚举。 */
class PeriodNounsTest {

  @Test
  fun `每个时段都有非空默认名词`() {
    assertEquals(Period.entries.size, PeriodNouns.DEFAULT.size)
    Period.entries.forEach { period ->
      val noun = PeriodNouns.defaultNoun(period)
      assertTrue("${period.name} 缺少默认名词", noun.isNotBlank())
      assertEquals("默认名词不能带空白", noun.trim(), noun)
    }
  }

  @Test
  fun `默认名词互不重复`() {
    val nouns = Period.entries.map { PeriodNouns.defaultNoun(it) }
    assertEquals(nouns.size, nouns.distinct().size)
  }

  @Test
  fun `名词可以反查回时段`() {
    Period.entries.forEach { period ->
      assertEquals(period, PeriodNouns.periodOfNoun(PeriodNouns.defaultNoun(period)))
    }
    assertNull(PeriodNouns.periodOfNoun("不存在的词"))
    assertNull(PeriodNouns.periodOfNoun(null))
    assertNull(PeriodNouns.periodOfNoun(""))
  }
}
