package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.5 验收 —— 轮次分类与普通轮白名单。
 *
 * 两条硬要求:
 * 1. `round_type` **不进** [ClockState](由编排层在构造请求时确定);
 * 2. 普通轮白名单是编排层的硬编码分支:**普通轮上下文只有规则块**,不允许任何时间事实。
 */
class RoundTypeTest {

  @Test
  fun `四类轮次都在`() {
    assertEquals(4, RoundType.entries.size)
    listOf("普通轮", "总结轮", "临时查询轮", "导演事件轮").forEach { label ->
      assertTrue(RoundType.entries.any { it.label == label })
    }
  }

  @Test
  fun `每一类轮次都允许稳定规则块`() {
    RoundType.entries.forEach { roundType ->
      assertTrue("${roundType.label} 必须允许规则块", InjectionWhitelist.allows(roundType, InjectSegment.RULES))
    }
  }

  @Test
  fun `普通轮只允许规则块与既有上下文`() {
    val allowed = InjectionWhitelist.BY_ROUND.getValue(RoundType.NORMAL)
    assertEquals(
      setOf(InjectSegment.RULES, InjectSegment.MEMORY, InjectSegment.HISTORY, InjectSegment.CURRENT_INPUT),
      allowed,
    )
    // 普通轮不许出现任何动态时间注入
    listOf(
      InjectSegment.TEMP_FACT,
      InjectSegment.PERIOD_FUSE,
      InjectSegment.DIRECTOR_EVENT,
      InjectSegment.SUMMARY,
      InjectSegment.SUMMARY_META,
    ).forEach { segment ->
      assertFalse("普通轮不允许 ${segment.label}", InjectionWhitelist.allows(RoundType.NORMAL, segment))
    }
    assertTrue(InjectionWhitelist.normalRoundHasNoTimeFact(allowed))
    assertFalse(InjectionWhitelist.normalRoundHasNoTimeFact(allowed + InjectSegment.TEMP_FACT))
  }

  @Test
  fun `其余三类轮次各自允许自己的注入`() {
    assertTrue(InjectionWhitelist.allows(RoundType.SUMMARY, InjectSegment.SUMMARY))
    assertTrue(InjectionWhitelist.allows(RoundType.SUMMARY, InjectSegment.SUMMARY_META))
    assertTrue(InjectionWhitelist.allows(RoundType.SUMMARY, InjectSegment.PERIOD_FUSE))
    assertFalse(InjectionWhitelist.allows(RoundType.SUMMARY, InjectSegment.TEMP_FACT))

    assertTrue(InjectionWhitelist.allows(RoundType.TEMP_QUERY, InjectSegment.TEMP_FACT))
    assertFalse(InjectionWhitelist.allows(RoundType.TEMP_QUERY, InjectSegment.SUMMARY))

    assertTrue(InjectionWhitelist.allows(RoundType.DIRECTOR_EVENT, InjectSegment.DIRECTOR_EVENT))
    assertFalse(InjectionWhitelist.allows(RoundType.DIRECTOR_EVENT, InjectSegment.PERIOD_FUSE))
  }

  @Test
  fun `时钟状态里没有 round_type`() {
    val state = ClockState(exactTime = 600, dateAnchor = 2)
    assertFalse(state.toJson().keys().contains("round_type"))
    // 存盘再读回来也没有
    val restored = ClockState.fromJson(Json.parse(state.toJson().encode()))
    assertEquals(state, restored)
    assertFalse(restored!!.toJson().keys().contains("round_type"))
  }

  @Test
  fun `动态时间事实的判定`() {
    assertTrue(InjectSegment.TEMP_FACT.dynamicTimeFact)
    assertTrue(InjectSegment.PERIOD_FUSE.dynamicTimeFact)
    assertTrue(InjectSegment.DIRECTOR_EVENT.dynamicTimeFact)
    assertFalse(InjectSegment.RULES.dynamicTimeFact)
    assertFalse(InjectSegment.SUMMARY.dynamicTimeFact)
    assertFalse(InjectSegment.SUMMARY_META.dynamicTimeFact)
    assertFalse(InjectSegment.MEMORY.dynamicTimeFact)
  }
}
