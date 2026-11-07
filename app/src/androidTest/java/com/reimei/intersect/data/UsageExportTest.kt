package com.reimei.intersect.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/**
 * 导出 JSON 的结构测试。
 *
 * 这份文件是给「用户的 AI」读的格式契约:字段名、单位、口径一旦漂了,对方的分析就会错,
 * 而界面上看不出来。所以把关键字段逐个钉住(org.json 在 JVM 单测里是空实现,只能放真机跑)。
 */
@RunWith(AndroidJUnit4::class)
class UsageExportTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val zone = TimeZone.getTimeZone("GMT+8")

  /** 2024-01-10 12:00:00 (GMT+8) */
  private val day1 = 1_704_859_200_000L
  private val dayMs = 86_400_000L

  private fun snapshot(): UsageSnapshot {
    val filter = UsageFilter(range = UsageRange.lastDays(7, day1 + 3 * dayMs))
    val trend = listOf(
      DayUsage("2024-01-10", UsageDays.dayIndex(day1, zone), tokens = 1500, cost = 0.01, requests = 2),
      DayUsage("2024-01-11", UsageDays.dayIndex(day1 + dayMs, zone), tokens = 500, cost = 0.005, requests = 1),
    )
    return UsageSnapshot(
      filter = filter,
      split = TokenSplit(cached = 800, uncached = 400, output = 800),
      requests = 3,
      cost = 0.015,
      byKeyGroup = listOf(UsageGroup("key_1", 2000, 3, 800, 0.015)),
      byModel = listOf(UsageGroup("deepseek-chat", 2000, 3, 800, 0.015)),
      byWindow = listOf(UsageGroup("self_local", 2000, 3, 800, 0.015)),
      trend = trend,
      streak = UsageStreak(current = 2, longest = 5),
      foregroundSamples = 6,
      activeDayIndexes = listOf(
        UsageDays.dayIndex(day1, zone),
        UsageDays.dayIndex(day1 + dayMs, zone)
      ),
      configs = listOf(
        ConfigUsage("key_1", requests = 3, tokens = 2000, cost = 0.015, lastUsedMs = day1 + 3600_000L)
      )
    )
  }

  private fun build(): JSONObject = JSONObject(
    UsageExport.build(
      snapshot = snapshot(),
      period = UsagePeriod.Week,
      priceConfig = PriceConfig(usdToCny = 7.2),
      appVersion = "1.0",
      keyGroupLabel = { "My DeepSeek" },
      windowLabel = { "小先" },
      exportedAtMs = day1,
      zone = zone
    )
  )

  @Test
  fun 顶层字段与版本号固定() {
    val root = build()
    assertEquals(UsageExport.SCHEMA, root.getString("schema"))
    assertEquals(UsageExport.SCHEMA_VERSION, root.getInt("schema_version"))
    assertEquals("2024-01-10T12:00:00+08:00", root.getString("exported_at"))
    assertEquals(day1, root.getLong("exported_at_epoch_ms"))
    assertEquals("com.reimei.intersect", root.getJSONObject("app").getString("package"))
  }

  @Test
  fun 查询口径与采样周期写在文件里() {
    val query = build().getJSONObject("query")
    assertEquals(7, query.getJSONObject("range").getInt("preset_days"))
    assertEquals("all", query.getJSONObject("filter").getString("dimension"))
    assertEquals("week", query.getString("display_period"))
    assertEquals(Heartbeat.SAMPLE_INTERVAL_MS, query.getLong("foreground_sample_interval_ms"))
  }

  @Test
  fun 四个token数必须同层级() {
    val tokens = build().getJSONObject("totals").getJSONObject("tokens")
    assertEquals(800L, tokens.getLong("prompt_cached"))
    assertEquals(400L, tokens.getLong("prompt_uncached"))
    // prompt = cached + uncached,total = prompt + completion
    assertEquals(1200L, tokens.getLong("prompt"))
    assertEquals(800L, tokens.getLong("completion"))
    assertEquals(2000L, tokens.getLong("total"))
  }

  @Test
  fun 分组明细保留id与可读名字() {
    val root = build()
    val keyGroup = root.getJSONArray("by_key_group").getJSONObject(0)
    assertEquals("key_1", keyGroup.getString("id"))
    assertEquals("My DeepSeek", keyGroup.getString("label"))
    assertEquals(0.015, keyGroup.getDouble("cost_cny"), 1e-9)
    // 聊天窗口的 label 是用户内容,允许非英文
    assertEquals("小先", root.getJSONArray("by_chat_window").getJSONObject(0).getString("label"))
  }

  @Test
  fun 按天数据不随粒度折叠() {
    // period 传的是 Week,但 daily 仍然是两天
    assertEquals(2, build().getJSONArray("daily").length())
  }

  @Test
  fun 活跃时长是采样数乘周期() {
    val activity = build().getJSONObject("activity")
    assertEquals(2, activity.getInt("active_days"))
    assertEquals(2, activity.getInt("current_streak_days"))
    assertEquals(5, activity.getInt("longest_streak_days"))
    assertEquals(6 * Heartbeat.SAMPLE_INTERVAL_MS, activity.getLong("foreground_estimated_ms"))
    assertEquals("2024-01-10", activity.getJSONArray("active_dates").getString(0))
  }

  @Test
  fun 单价一起导出且单位明确() {
    val pricing = build().getJSONObject("pricing")
    assertEquals(7.2, pricing.getDouble("usd_to_cny"), 1e-9)
    assertTrue(pricing.getString("unit").contains("1_000_000"))
    val price = pricing.getJSONObject("models").getJSONObject("deepseek-chat")
    assertEquals(Pricing.DEFAULT_PRICES.getValue("deepseek-chat").input, price.getDouble("input"), 1e-9)
  }

  @Test
  fun 说明里写清费用公式与未知模型为0() {
    val notes = build().getJSONArray("notes")
    val joined = (0 until notes.length()).joinToString(" ") { notes.getString(it) }
    assertTrue(joined.contains("prompt_uncached"))
    assertTrue(joined.contains("priced 0"))
  }

  /**
   * 落盘测试:点「保存」走的正是这条路径,所以这里把「写到哪个文件、能不能被重新解析、
   * 是否混进中文」一并验一遍。
   */
  @Test
  fun 导出文件能被重新解析且不含中文() {
    val json = UsageExport.build(
      snapshot = snapshot(),
      period = UsagePeriod.Day,
      priceConfig = PriceConfig(),
      appVersion = UsageExportFiles.appVersion(context),
      keyGroupLabel = { "My Key" },
      // 刻意给一个中文名字:用户内容允许非英文,但格式化后的结构里不该出现
      windowLabel = { "小先" },
      exportedAtMs = day1,
      zone = zone
    )
    // 去掉用户内容那段后再断言「没有中文」
    val machinePart = json.replace("小先", "")
    assertFalse(
      "结构里不该出现中文(那是给 AI 读的)",
      machinePart.any { it.code in 0x4E00..0x9FFF }
    )

    val file = UsageExportFiles.save(context, json)
    try {
      assertTrue(file.exists())
      assertTrue(file.name.endsWith(".json"))
      assertTrue(file.length() > 0L)
      // 落盘后再读回来解析:UTF-8 写坏了、被截断了都能在这里暴露
      val reread = JSONObject(file.readText(Charsets.UTF_8))
      assertEquals(UsageExport.SCHEMA_VERSION, reread.getInt("schema_version"))
      assertEquals(2, reread.getJSONArray("daily").length())
    } finally {
      file.delete()
    }
  }
}
