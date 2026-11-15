package com.reimei.intersect.data.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reimei.intersect.data.MetaInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * memory.db 的读写测试(真机跑:Room 需要 Android 运行时)。
 *
 * TASK-01 的验收就在这里:「建库成功」「插一条假微总结再读出来」。
 * 用的是**内存库**(inMemoryDatabaseBuilder),不碰设备上真实的 memory.db。
 *
 * 另外把仓库层的三条语义钉住:最近 N 条微总结、最新 1 条大总结、滚动状态的播种与回流合并。
 */
@RunWith(AndroidJUnit4::class)
class MemoryRepositoryTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private lateinit var db: MemoryDb
  private lateinit var repository: MemoryRepository

  private val friendId = "friend_memory_test"

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
      .allowMainThreadQueries()
      .build()
    repository = MemoryRepository.of(db.memory())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun 插入一条假微总结并从数据库读出来() = runBlocking {
    repository.insertMicro(
      friendId = friendId,
      fromRound = 1,
      toRound = 20,
      text = "用户与角色在码头碰头,约定天亮前离开。",
      score = 78,
      createdAt = 1_700_000_000L,
    )

    val read = repository.recentMicro(friendId, 3)
    assertEquals(1, read.size)
    assertEquals(1, read.first().fromRound)
    assertEquals(20, read.first().toRound)
    assertEquals(78, read.first().score)
    assertEquals("用户与角色在码头碰头,约定天亮前离开。", read.first().text)
    assertEquals(1, repository.microCount(friendId))
  }

  @Test
  fun 最近N条微总结按轮次倒序() = runBlocking {
    listOf(1 to 20, 21 to 40, 41 to 60, 61 to 80).forEach { (from, to) ->
      repository.insertMicro(friendId, from, to, "第 $from-$to 轮", 60)
    }
    val recent = repository.recentMicro(friendId, 3)
    assertEquals(listOf(80, 60, 40), recent.map { it.toRound })
  }

  @Test
  fun 最新一条大总结只有一条生效() = runBlocking {
    repository.insertNode(friendId, 1, 60, "第一段主线")
    repository.insertNode(friendId, 61, 120, "第二段主线")
    assertEquals("第二段主线", repository.latestNode(friendId)?.text)
    assertTrue(repository.hasNode(friendId, 61, 120))
    assertFalse(repository.hasNode(friendId, 121, 180))
  }

  @Test
  fun 状态快照取最新大总结加最近三条微总结() = runBlocking {
    (1..4).forEach { index ->
      val from = (index - 1) * 20 + 1
      repository.insertMicro(friendId, from, index * 20, "第 $from-${index * 20} 轮", 60)
    }
    repository.insertNode(friendId, 1, 60, "主线正文")

    val snapshot = repository.loadSnapshot(friendId)
    assertEquals("主线正文", snapshot.nodeSummary)
    assertEquals(3, snapshot.microSummaries.size)
    assertEquals("第 61-80 轮", snapshot.microSummaries.first())
    assertEquals(false, snapshot.isEmpty)
  }

  @Test
  fun 元信息首次读取用设置页的值播种_之后以库为准() = runBlocking {
    val fallback = MetaInfo(friendId = friendId, favorability = 50, location = "镇口酒馆")
    val seeded = repository.loadMeta(friendId, fallback)
    assertEquals(50, seeded.favorability)
    assertEquals("镇口酒馆", seeded.location)

    // 总结回流改了状态:再读就是新值
    repository.applyMetaUpdate(friendId, RollingMetaUpdate(favorability = 72, health = 60))
    val updated = repository.loadMeta(friendId, fallback)
    assertEquals(72, updated.favorability)
    assertEquals(60, updated.health)
    // 没给的那一项沿用旧值,不会被清零
    assertEquals("镇口酒馆", updated.location)
  }

  @Test
  fun 没有行时回流更新不写空数据() = runBlocking {
    repository.applyMetaUpdate(friendId, RollingMetaUpdate(favorability = 72))
    assertNull(repository.rollingMeta(friendId))
  }

  @Test
  fun 删角色会把它在新库里的数据一起清掉() = runBlocking {
    repository.insertMicro(friendId, 1, 20, "微总结", 60)
    repository.insertNode(friendId, 1, 20, "大总结")
    repository.applyMetaUpdate(friendId, RollingMetaUpdate()) // 空更新不写入,这里只是别让它炸
    repository.loadMeta(friendId, MetaInfo(friendId = friendId))
    assertNotNull(repository.rollingMeta(friendId))

    repository.clear(friendId)
    assertEquals(0, repository.microCount(friendId))
    assertNull(repository.latestNode(friendId))
    assertNull(repository.rollingMeta(friendId))
  }

  @Test
  fun 真实库能建表并读写() = runBlocking {
    // 用真实单例(设备上的 memory.db),验证「新建数据库成功」这条验收:
    // 表建出来了、能插能读。写完的行放在 finally 里清掉,断言失败也不给用户的真实库留垃圾。
    val real = MemoryRepository.get(context)
    val id = "friend_smoke_test"
    try {
      real.clear(id)
      real.insertMicro(id, 1, 20, "冒烟测试", 60)
      assertEquals(1, real.recentMicro(id, 3).size)
    } finally {
      real.clear(id)
    }
    assertEquals(0, real.microCount(id))
  }

  @Test
  fun 回滚后裁掉超出现有轮数的总结() = runBlocking {
    (1..4).forEach { index ->
      val from = (index - 1) * 20 + 1
      repository.insertMicro(friendId, from, index * 20, "第 $from-${index * 20} 轮", 60)
    }
    repository.insertNode(friendId, 1, 60, "主线正文")

    // 用户回滚到只剩 30 轮:21-40 之后的那几段都盖到了已不存在的对话上
    repository.trimBeyond(friendId, 30)
    val recent = repository.recentMicro(friendId, 3)
    assertEquals(listOf(20), recent.map { it.toRound })
    assertNull(repository.latestNode(friendId))
    // 水位退回去之后,新的 41-60 段还能重新总结(不会被旧水位吞掉)
    repository.insertMicro(friendId, 41, 60, "回滚后的第 41-60 轮", 60)
    assertEquals(60, repository.recentMicro(friendId, 1).first().toRound)
  }

  @Test
  fun 未并入大总结的微总结按水位取出() = runBlocking {
    (1..4).forEach { index ->
      val from = (index - 1) * 20 + 1
      repository.insertMicro(friendId, from, index * 20, "第 $from-${index * 20} 轮", 60)
    }
    // 还没建过大总结:水位 0,取最旧的三条
    val first = repository.pendingMicro(friendId, 0)
    assertEquals(listOf(1, 21, 41), first.map { it.fromRound })

    repository.insertNode(friendId, 1, 60, "第一段主线")
    // 建过之后水位是 60,只剩第 61-80 轮那一条待处理
    val watermark = repository.latestNode(friendId)!!.toRound
    val pending = repository.pendingMicro(friendId, watermark)
    assertEquals(listOf(61), pending.map { it.fromRound })
  }

  @Test
  fun 其余字段从others里原样读回() = runBlocking {
    val meta = MetaInfo(
      friendId = friendId,
      favorability = 88,
      health = 70,
      hunger = 30,
      time = "深夜",
      relationship = "同门",
      location = "后山",
      characters = "旅人、守卫",
      weather = "大雨",
      buffs = listOf("失血"),
    )
    repository.loadMeta(friendId, meta)
    val read = repository.loadMeta(friendId, MetaInfo(friendId = friendId))
    assertEquals("同门", read.relationship)
    assertEquals("旅人、守卫", read.characters)
    assertEquals("大雨", read.weather)
    assertEquals("深夜", read.time)
    assertEquals(listOf("失血"), read.buffs)
  }
}
