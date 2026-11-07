package com.reimei.intersect.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 只供开发期核对 UI 用:往统计库里灌一批演示数据,或把它们清掉。
 *
 * 这不是断言型测试(没有 assert),所以不会参与常规的 connectedAndroidTest 结果判定;
 * 需要时用 `am instrument -e class com.reimei.intersect.data.UsageSeedForScreenshot#seed` 手动跑。
 *
 * 演示数据带有可识别的标记,清理时只删自己写的那部分:
 * - 流水的 key_group_id 固定为 [GROUP];
 * - 心跳只写「今天以前」的采样,清理时删 `timestamp < 今天零点`,当天的真实采样不受影响。
 */
@RunWith(AndroidJUnit4::class)
class UsageSeedForScreenshot {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val db = UsageDb.of(context).writableDatabase

  @Test
  fun seed() {
    val now = System.currentTimeMillis()
    val day = UsageDays.MILLIS_PER_DAY
    val today = UsageDays.localDayStart(UsageDays.today(), java.util.TimeZone.getDefault())

    // 20 天的流水:近 3 天用得多、中间一段空着,正好能看出折线的起伏与断档
    val loads = listOf(0, 1, 2, 4, 5, 6, 7, 9, 12, 13, 17, 18, 19)
    loads.forEach { offset ->
      val ts = now - offset * day
      val weight = if (offset <= 2) 1.0 else 0.35
      repeat(if (offset <= 2) 14 else 5) { index ->
        val prompt = (1200 + index * 70) * weight + 200
        val cached = prompt * 0.82
        val completion = (320 + index * 12) * weight
        val cachedInt = cached.toInt()
        val model = if (index % 3 == 0) "qwen-plus" else "deepseek-chat"
        db.execSQL(
          "INSERT INTO api_call_logs (timestamp, key_group_id, model_name, chat_window_id, " +
            "prompt_tokens, completion_tokens, cached_tokens, total_tokens, cost) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
          arrayOf<Any>(
            ts + index * 37_000L,
            GROUP,
            model,
            if (index % 2 == 0) "self_local" else "friend_demo",
            prompt.toInt(),
            completion.toInt(),
            cachedInt,
            prompt.toInt() + completion.toInt()
          )
        )
      }
      // 每天 6 条前台采样(对应一小时前台)
      repeat(6) { index ->
        db.execSQL(
          "INSERT INTO user_heartbeats (timestamp, app_state) VALUES (?, 'foreground')",
          arrayOf<Any>(ts + index * Heartbeat.SAMPLE_INTERVAL_MS)
        )
      }
    }
    // 费用用当前单价补齐,免得分组里的钱全是 0
    PriceConfigStore.recompute(context)
    check(today > 0)
  }

  @Test
  fun clear() {
    db.execSQL("DELETE FROM api_call_logs WHERE key_group_id = ?", arrayOf<Any>(GROUP))
    val todayStart = UsageDays.localDayStart(UsageDays.today(), java.util.TimeZone.getDefault())
    db.execSQL("DELETE FROM user_heartbeats WHERE timestamp < ?", arrayOf<Any>(todayStart))
  }

  private companion object {
    const val GROUP = "key_demo_screenshot"
  }
}
