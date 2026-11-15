package com.reimei.intersect.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清洗脏大总结」的规划逻辑:哪些行要重写、哪些要删、哪些必须原样不动。
 *
 * 这里钉的是 [NodeClean.plan] 的判据 —— 它会**删库里的行**,所以「不该动的东西别动」
 * 和「该洗的洗干净」一样重要。
 */
class NodeCleanTest {

  private fun row(text: String, from: Int = 1, to: Int = 60) = NodeSummaryEntity(
    id = from.toLong(),
    friendId = "f1",
    fromRound = from,
    toRound = to,
    text = text,
    createdAt = 0L,
  )

  /** 真机抓到的那条泄漏原文(deepseek-flash 把英文推理当正文吐出来)。 */
  private val leaked =
    "We need answer Chinese only, main line 10-50 words, third person user/character, merge. " +
      "Need include relationship, current goal, unresolved clues. The memories: all test exchanges, " +
      "no real RP; at end character hostile toward user, hungry, healthy, time 2026-11-15 11:30, " +
      "location unknown. Need concise. 10-50 Chinese words. Maybe: " +
      "\"用户与角色仅完成六十轮测试对话,未建立关系或共同经历。"

  @Test
  fun `泄漏行会被就地重写成中文主线`() {
    val plan = NodeClean.plan(listOf(row(leaked)))

    assertEquals(1, plan.scanned)
    assertEquals(1, plan.rewrites.size)
    assertEquals(0, plan.deletes.size)

    val after = plan.rewrites.single().after
    assertTrue("清洗后不该再残留思考过程标记: $after", !after.contains("We need"))
    assertTrue("清洗后不该再残留思考过程标记: $after", !after.contains("Maybe:"))
    assertTrue("清洗后不该再有英文字母: $after", after.none { it in 'A'..'Z' || it in 'a'..'z' })
    assertEquals("用户与角色仅完成六十轮测试对话,未建立关系或共同经历。", after)
  }

  @Test
  fun `整段英文的行会被删掉`() {
    val plan = NodeClean.plan(
      listOf(row("We need answer in Chinese only. The user asked for a main line summary here."))
    )

    assertEquals(0, plan.rewrites.size)
    assertEquals(1, plan.deletes.size)
    assertTrue(plan.deletes.single().removed)
  }

  @Test
  fun `已经干净的行即使很短也不动`() {
    // 只有 5 个汉字:按写入判据它「不可用」,但它本来就干净 ——
    // 清洗是修脏数据的,不是来删老数据的。
    val plan = NodeClean.plan(listOf(row("他们见面了。")))
    assertEquals(0, plan.changed)
    assertTrue(plan.isEmpty)
  }

  @Test
  fun `干净的长正文不受影响`() {
    val clean = "用户与角色在雨夜的旧书店里初次见面,角色对用户隐瞒了自己的来意,双方约好第二天在码头碰面。"
    val plan = NodeClean.plan(listOf(row(clean)))
    assertEquals(0, plan.changed)
  }

  @Test
  fun `多行混合时只挑出脏行`() {
    val clean = "用户与角色在雨夜的旧书店里初次见面,角色对用户隐瞒了自己的来意,双方约好第二天在码头碰面。"
    val plan = NodeClean.plan(
      listOf(
        row(clean, from = 1, to = 20),
        row(leaked, from = 21, to = 40),
        row("Let me think about how to merge these memories. I should keep it short.", from = 41, to = 60),
        row(clean, from = 61, to = 80),
      )
    )

    assertEquals(4, plan.scanned)
    assertEquals(1, plan.rewrites.size)
    assertEquals(1, plan.deletes.size)
    assertEquals(2, plan.changed)
    assertEquals(21, plan.rewrites.single().row.fromRound)
    assertEquals(41, plan.deletes.single().row.fromRound)
    // changes 先重写后删除,面板按这个顺序展示
    assertEquals(listOf(false, true), plan.changes.map { it.removed })
  }

  @Test
  fun `空表扫出来是空计划`() {
    val plan = NodeClean.plan(emptyList())
    assertEquals(0, plan.scanned)
    assertTrue(plan.isEmpty)
  }
}
