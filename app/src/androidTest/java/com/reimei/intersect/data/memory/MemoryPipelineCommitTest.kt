package com.reimei.intersect.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.User
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK-03 的三条验收,在真机上对着真实 memory.db 跑:
 *
 * 1. 模拟对话到 20 轮 → micro_summary 新增一条;
 * 2. 模拟跑到 60 轮 → node_summary 新增一条(3 条微总结压 1 条主线);
 * 3. 总结请求在飞时删 5 条消息 → AI 返回的总结**不写入**数据库。
 *
 * 网络那一层不在这里(不能为测试去打真实 API):这里直接调流水线的落库入口
 * [MemoryPipeline.commitMicro] / [MemoryPipeline.commitNode],喂一份手写的 AI 结果 ——
 * 判定逻辑与真实路径完全同一份代码(取段 [MemoryPipeline.nextRange] → 落库 commitMicro)。
 *
 * 用的是真实库里的一个专用 friendId,前后都清掉,不给用户的记忆库留垃圾。
 */
@RunWith(AndroidJUnit4::class)
class MemoryPipelineCommitTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val friendId = "friend_pipeline_test"
  private val friend = User(friendId, "小明", 0)
  private lateinit var repository: MemoryRepository

  /** 造 n 轮对话:[List] 的每一项是一轮(用户一句 + 角色一句),流水线只看轮数。 */
  private fun rounds(n: Int): List<List<Msg>> = List(n) { index ->
    listOf(
      Msg(User.Me, "第 ${index + 1} 句"),
      Msg(friend, "回 ${index + 1}"),
    )
  }

  private fun okResult(text: String = "这一段对话的微总结") = MicroSummaryResult.Ok(
    text = text,
    score = 70,
    update = RollingMetaUpdate(favorability = 60),
    usage = null,
  )

  @Before
  fun setUp() = runBlocking {
    repository = MemoryRepository.get(context)
    repository.clear(friendId)
  }

  @After
  fun tearDown() = runBlocking {
    repository.clear(friendId)
  }

  @Test
  fun 二十轮之后微总结表新增一条() = runBlocking {
    val range = MemoryPipeline.nextRange(0, rounds(20))
    assertTrue("20 轮应当凑出一段", range != null)
    assertEquals(1, range!!.fromRound)
    assertEquals(20, range.toRound)

    val written = MemoryPipeline.commitMicro(
      context = context,
      friendId = friendId,
      range = range,
      ok = okResult("用户与角色在码头碰头。"),
      snapshotCount = 40, // 20 轮 = 40 条消息
      currentCount = 40,
    )

    assertTrue("正常情况应当写入", written)
    val micros = repository.allMicro(friendId)
    assertEquals(1, micros.size)
    assertEquals(1, micros.first().fromRound)
    assertEquals(20, micros.first().toRound)
    assertEquals(70, micros.first().score)
    assertEquals("用户与角色在码头碰头。", micros.first().text)
  }

  @Test
  fun 六十轮之后大总结表新增一条() = runBlocking {
    // 模拟流水线跑完三段:1-20 / 21-40 / 41-60,每段都按水位往后接
    (1..3).forEach { index ->
      val covered = repository.recentMicro(friendId, 1).firstOrNull()?.toRound ?: 0
      val range = MemoryPipeline.nextRange(covered, rounds(60))!!
      assertEquals((index - 1) * 20 + 1, range.fromRound)
      MemoryPipeline.commitMicro(context, friendId, range, okResult("第 $index 段"), 120, 120)
    }
    assertEquals(3, repository.microCount(friendId))
    assertNull("还不够 3 条并发过时不该有大总结", repository.latestNode(friendId))

    // 3 条微总结 → 一条主线大总结
    val watermark = repository.latestNode(friendId)?.toRound ?: 0
    val plan = MemoryPipeline.nodePlan(watermark, repository.pendingMicro(friendId, watermark))
    assertTrue("3 条连续微总结应当凑出大总结", plan != null)
    assertEquals(1, plan!!.fromRound)
    assertEquals(60, plan.toRound)
    assertEquals(3, plan.texts.size)
    MemoryPipeline.commitNode(context, friendId, plan, "两人关系稳步推进,约定天亮前离开镇子。")

    val node = repository.latestNode(friendId)
    assertTrue("60 轮之后应当有一条大总结", node != null)
    assertEquals(1, node!!.fromRound)
    assertEquals(60, node.toRound)
    assertEquals("两人关系稳步推进,约定天亮前离开镇子。", node.text)

    // 水位已经推到 60:同样这三条微总结不会被再压一次(不重复花一次请求)
    assertNull(MemoryPipeline.nodePlan(60, repository.pendingMicro(friendId, 60)))
  }

  @Test
  fun 请求在飞时删了五条消息则不写入() = runBlocking {
    val range = MemoryPipeline.nextRange(0, rounds(20))!!

    // 启动总结时 40 条,AI 回来时只剩 35 条(用户删了 5 条)
    val written = MemoryPipeline.commitMicro(
      context = context,
      friendId = friendId,
      range = range,
      ok = okResult("这一段描述的是已经被删掉的对话"),
      snapshotCount = 40,
      currentCount = 35,
    )

    assertFalse("消息被删过,这次结果必须丢弃", written)
    assertEquals("数据库里不该多出任何微总结", 0, repository.microCount(friendId))
    assertNull(repository.latestNode(friendId))
  }

  @Test
  fun 补跑同一段不会写出两条重复微总结() = runBlocking {
    val first = MemoryPipeline.nextRange(0, rounds(20))!!
    assertTrue(MemoryPipeline.commitMicro(context, friendId, first, okResult("第一次"), 40, 40))

    // 手动触发把同一段又跑了一遍(覆盖轮次还是 1-20):判定为重复,不写
    val again = MemoryPipeline.commitMicro(context, friendId, first, okResult("第二次"), 40, 40)
    assertFalse(again)
    assertEquals(1, repository.microCount(friendId))
    assertEquals("第一次", repository.recentMicro(friendId, 1).first().text)
  }
}
