package com.reimei.intersect.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 统计库的真机测试。
 *
 * SQL 写得对不对,单元测试看不出来(没有 SQLite 引擎),只能拿真库跑一遍:
 * 建表 → 插流水/心跳 → 按维度聚合 → 校验每一块的数字。
 * 价格配置也顺便验一遍「改单价 + 重算历史」。
 *
 * 测试用的是应用自己的数据库,所以每个用例前后都把两张表清空,避免污染真实统计。
 */
@RunWith(AndroidJUnit4::class)
class UsageQueriesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val db = UsageDb.of(context).writableDatabase

  /** 固定时间点:2024-01-02 03:04:05 UTC,后面按整天平移。 */
  private val base = 1_704_164_645_000L
  private val dayMs = 86_400_000L

  @Before
  fun setUp() {
    db.execSQL("DELETE FROM api_call_logs")
    db.execSQL("DELETE FROM user_heartbeats")
    PriceConfigStore.save(context, PriceConfig())
  }

  @After
  fun tearDown() {
    db.execSQL("DELETE FROM api_call_logs")
    db.execSQL("DELETE FROM user_heartbeats")
    PriceConfigStore.save(context, PriceConfig())
  }

  private fun insertLog(
    timestamp: Long,
    keyGroup: String,
    model: String,
    window: String,
    prompt: Int,
    cached: Int,
    completion: Int,
    cost: Double
  ) {
    db.execSQL(
      "INSERT INTO api_call_logs (timestamp, key_group_id, model_name, chat_window_id, " +
        "prompt_tokens, completion_tokens, cached_tokens, total_tokens, cost) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
      arrayOf<Any>(timestamp, keyGroup, model, window, prompt, completion, cached, prompt + completion, cost)
    )
  }

  @Test
  fun 按密钥组与模型分组聚合() {
    insertLog(base, "key_1", "deepseek-chat", "friend_a", prompt = 1000, cached = 400, completion = 500, cost = 0.5)
    insertLog(base, "key_1", "deepseek-chat", "friend_b", prompt = 200, cached = 0, completion = 100, cost = 0.2)
    insertLog(base, "key_2", "qwen-max", "friend_a", prompt = 300, cached = 100, completion = 200, cost = 0.9)

    val snapshot = UsageQueries.load(context, UsageFilter())
    assertEquals(3L, snapshot.requests)
    assertEquals(1.6, snapshot.cost, 1e-9)

    val key1 = snapshot.byKeyGroup.first { it.key == "key_1" }
    assertEquals(2L, key1.requests)
    assertEquals(1800L, key1.tokens) // (1000+500) + (200+100)
    assertEquals(0.7, key1.cost, 1e-9)

    val chat = snapshot.byModel.first { it.key == "deepseek-chat" }
    assertEquals(1800L, chat.tokens)

    // Token 构成:缓存 500、未缓存 1000-400 + 200-0 + 300-100 = 1000、输出 800
    assertEquals(500L, snapshot.split.cached)
    assertEquals(1000L, snapshot.split.uncached)
    assertEquals(800L, snapshot.split.output)
    assertEquals(2300L, snapshot.split.total)
  }

  @Test
  fun 筛选条件会作用到所有聚合() {
    insertLog(base, "key_1", "deepseek-chat", "friend_a", prompt = 1000, cached = 0, completion = 0, cost = 0.5)
    insertLog(base, "key_2", "qwen-max", "friend_a", prompt = 300, cached = 0, completion = 0, cost = 0.9)

    val filtered = UsageQueries.load(context, UsageFilter(UsageDimension.KeyGroup, "key_2"))
    assertEquals(1L, filtered.requests)
    assertEquals(0.9, filtered.cost, 1e-9)
    assertEquals(300L, filtered.split.total)
    assertEquals(1, filtered.byKeyGroup.size)
    assertEquals(1, filtered.byWindow.size)

    // 下拉候选:两条密钥组都在
    assertEquals(listOf("key_1", "key_2").toSet(), UsageQueries.options(context, UsageDimension.KeyGroup).toSet())
  }

  @Test
  fun 按天聚合趋势并升序排列() {
    insertLog(base, "key_1", "deepseek-chat", "friend_a", prompt = 100, cached = 0, completion = 0, cost = 0.1)
    insertLog(base + dayMs, "key_1", "deepseek-chat", "friend_a", prompt = 200, cached = 0, completion = 0, cost = 0.2)

    val trend = UsageQueries.load(context, UsageFilter()).trend
    assertEquals(2, trend.size)
    assertTrue(trend[0].day < trend[1].day)
    assertEquals(100L, trend[0].tokens)
    assertEquals(200L, trend[1].tokens)
  }

  @Test
  fun 心跳采样决定连续天数() {
    val now = System.currentTimeMillis()
    UsageLog.sample(context, foreground = true, timestamp = now)
    UsageLog.sample(context, foreground = true, timestamp = now - dayMs)
    UsageLog.sample(context, foreground = true, timestamp = now - 2 * dayMs)
    UsageLog.sample(context, foreground = false, timestamp = now - 3 * dayMs)

    val snapshot = UsageQueries.load(context, UsageFilter(), now = now)
    assertEquals(3L, snapshot.foregroundSamples)
    assertEquals(3, snapshot.streak.current)
    assertEquals(3, snapshot.streak.longest)
    assertEquals(3 * Heartbeat.SAMPLE_INTERVAL_MS, UsageQueries.foregroundDurationMs(snapshot.foregroundSamples))
  }

  @Test
  fun 改单价后重算历史费用() {
    insertLog(base, "key_1", "deepseek-chat", "friend_a", prompt = 1_000_000, cached = 0, completion = 0, cost = 1.0)

    // 把 deepseek-chat 的输入价改成 3 元/百万 token,重算后 1M 输入 = 3 元
    PriceConfigStore.save(context, PriceConfig().withPrice("deepseek-chat", ModelPrice(3.0, 0.5, 8.0)))
    assertEquals(1, PriceConfigStore.recompute(context))

    val cost = UsageQueries.load(context, UsageFilter()).cost
    assertEquals(3.0, cost, 1e-6)
  }

  @Test
  fun 没有用量时不留流水() {
    UsageLog.record(context, keyGroupId = "key_1", model = "deepseek-chat", chatWindowId = "w", usage = null)
    assertEquals(0L, UsageQueries.load(context, UsageFilter()).requests)
  }
}
