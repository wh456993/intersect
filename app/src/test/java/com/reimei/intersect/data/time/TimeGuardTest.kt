package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.1–T6.4 验收 —— AI 输出约束与后处理。
 *
 * 三层防线各有一组断言:提示词里的只读约束、违规报时清洗、系统标签复述清洗,
 * 外加「角色查询时间用后即焚」这条路径。
 */
class TimeGuardTest {

  @Test
  fun `提示词只读约束 且没有动态值`() {
    val rules = TimeRules.RULES_TEXT
    assertTrue(rules.contains("无权修改"))
    assertTrue(rules.contains("无权播报"))
    // 不含动态值:没有精确时刻、没有日期、没有「N 点」
    assertFalse("规则块不许含 HH:mm", Regex("[0-9]{1,2}\\s*:\\s*[0-9]{2}").containsMatchIn(rules))
    assertFalse("规则块不许含日期", Regex("[0-9]{4}\\s*年").containsMatchIn(rules))
    assertFalse("规则块不许含报时", Regex("[0-9]{1,2}\\s*[点時时]").containsMatchIn(rules))
    // T6.1:规则块每轮一字不改 —— 它是 val,这里再断言内容稳定可复现
    assertEquals(rules, TimeRules.RULES_TEXT)
  }

  @Test
  fun `违规报时会被检出并删句`() {
    val text = "现在是下午三点,我该出门了。她把门关上。"
    val hits = OutputGuard.scan(text)
    assertTrue(hits.any { it.finding == GuardFinding.ANNOUNCE_TIME })
    val cleaned = OutputGuard.sanitize(text)
    assertTrue(cleaned.rewritten)
    assertFalse("报时句必须被移除", cleaned.text.contains("三点"))
    assertTrue("别的句子要留下", cleaned.text.contains("她把门关上"))
  }

  @Test
  fun `精确时刻与日期也在检测范围内`() {
    listOf("现在 14:05", "现在是14:05。", "2025年1月15日,雪停了。", "三点半了。").forEach { text ->
      val cleaned = OutputGuard.sanitize(text)
      assertTrue("应当命中:$text", cleaned.hits.isNotEmpty())
      assertFalse("时间不该留下:$text", Regex("[0-9]{1,2}[::][0-9]{2}").containsMatchIn(cleaned.text))
    }
  }

  @Test
  fun `系统标签复述会被清洗`() {
    val text = "[时间事实] 现在是黄昏。[时段变更] 入夜。她抬头看了看天。"
    val cleaned = OutputGuard.sanitize(text)
    assertTrue(cleaned.hits.any { it.finding == GuardFinding.RESTATE_TAG })
    assertFalse(cleaned.text.contains("[时间事实]"))
    assertFalse(cleaned.text.contains("[时段变更]"))
    assertTrue("正文留下", cleaned.text.contains("她抬头看了看天"))
  }

  @Test
  fun `干净输出不被改动`() {
    val text = "她笑了笑,没有说话。"
    val result = OutputGuard.sanitize(text)
    assertEquals(text, result.text)
    assertFalse(result.rewritten)
    assertFalse(result.needsRegenerate)
  }

  @Test
  fun `全是违规内容时需要重新生成`() {
    val result = OutputGuard.sanitize("现在是下午三点。")
    assertTrue(result.rewritten)
    assertTrue(result.needsRegenerate)
    assertEquals("", result.text)
  }

  @Test
  fun `角色查询时间 只在查询轮可见 用后即焚`() {
    val store = TempTimeFactStore(InMemoryTimeLog())
    val clock = ClockState(exactTime = 21 * 60 + 40, dateAnchor = 0)

    assertTrue(CharacterTimeQuery.visibleOnlyInQueryRound(RoundType.TEMP_QUERY))
    assertFalse(CharacterTimeQuery.visibleOnlyInQueryRound(RoundType.NORMAL))

    CharacterTimeQuery.issue(clock, store, atRound = 4, logger = InMemoryTimeLog(), at = 111L)
    assertFalse(store.isEmpty())

    val queryRound = RoundOrchestrator.plan(
      RoundType.TEMP_QUERY,
      RoundContext(),
      tempFacts = store,
      logger = InMemoryTimeLog(),
    )
    assertTrue(queryRound.text().contains("21:40"))
    assertTrue("取走即焚", store.isEmpty())

    // 之后普通轮不再出现精确时间
    val next = RoundOrchestrator.plan(RoundType.NORMAL, RoundContext(), tempFacts = store, logger = InMemoryTimeLog())
    assertFalse(next.text().contains("21:40"))
    assertFalse(next.hasTimeFact())
  }
}
