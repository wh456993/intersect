package com.reimei.intersect.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 消耗统计的本地库(SQLite)。
 *
 * 两张表就是「Token 统计」页的全部数据来源,不经过任何后端接口:
 * - `api_call_logs`:每次 API 调用的流水(密钥组 / 模型 / 聊天窗口 / 各类 token / 费用),
 *   多维聚合全部交给 SQL 的 GROUP BY;
 * - `user_heartbeats`:每 10 分钟一条的活跃采样,用来算连续使用天数与前台时长。
 *
 * 时间戳统一存 epoch 毫秒(INTEGER):SQLite 没有真正的 DATETIME 类型,
 * 存整数才能在 SQL 里按本地日期分组(strftime('%Y-%m-%d', timestamp/1000, 'unixepoch', 'localtime'))。
 */
class UsageDb private constructor(context: Context) : SQLiteOpenHelper(
  context.applicationContext, DB_NAME, null, DB_VERSION
) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE api_call_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp INTEGER NOT NULL,
        key_group_id TEXT NOT NULL DEFAULT '',
        model_name TEXT NOT NULL DEFAULT '',
        chat_window_id TEXT NOT NULL DEFAULT '',
        prompt_tokens INTEGER NOT NULL DEFAULT 0,
        completion_tokens INTEGER NOT NULL DEFAULT 0,
        cached_tokens INTEGER NOT NULL DEFAULT 0,
        total_tokens INTEGER NOT NULL DEFAULT 0,
        cost REAL NOT NULL DEFAULT 0
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE user_heartbeats (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp INTEGER NOT NULL,
        app_state TEXT NOT NULL
      )
      """.trimIndent()
    )
    // 两张表都只按时间查(按天聚合 / 取活跃日期),模型与窗口维度靠 GROUP BY 现场扫
    db.execSQL("CREATE INDEX index_call_logs_timestamp ON api_call_logs(timestamp)")
    db.execSQL("CREATE INDEX index_heartbeats_timestamp ON user_heartbeats(timestamp)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // 目前只有 v1。统计属于「可丢」的数据:真要改表结构时直接重建,不做逐版本迁移。
    db.execSQL("DROP TABLE IF EXISTS api_call_logs")
    db.execSQL("DROP TABLE IF EXISTS user_heartbeats")
    onCreate(db)
  }

  companion object {
    private const val DB_NAME = "usage.db"
    private const val DB_VERSION = 1

    @Volatile
    private var instance: UsageDb? = null

    /** 单例:同一个进程里多个页面同时查统计,共用一条连接(helper 本身是线程安全的)。 */
    fun of(context: Context): UsageDb =
      instance ?: synchronized(this) {
        instance ?: UsageDb(context).also { instance = it }
      }
  }
}

/**
 * 流水的写入入口:一次成功的调用落一条,心跳按 [Heartbeat] 的节奏落一条。
 *
 * 所有写操作都吞掉异常:统计是旁路,绝不能因为它出错就把一次正常对话打断
 * (例如磁盘满、库文件损坏)。
 */
object UsageLog {
  const val STATE_FOREGROUND = "foreground"
  const val STATE_BACKGROUND = "background"

  /**
   * 记一次调用。
   *
   * [usage] 为 null 表示服务商没回 usage(流式未开 stream_options.include_usage、
   * 或个别网关不回 usage):token 与费用都无从得知,这时**不入库**,
   * 免得记一条全 0 的假流水把聚合结果搅浑。
   *
   * 换句话说,本工程的请求都是非流式(stream=false),usage 在同一个响应体里必然存在,
   * 所以正常情况下每次成功调用都会留下一条。
   */
  fun record(
    context: Context,
    keyGroupId: String,
    model: String,
    chatWindowId: String,
    usage: ApiUsage?,
    timestamp: Long = System.currentTimeMillis()
  ) {
    if (usage == null) return
    val cost = PriceConfigStore.costOf(
      context,
      model = model,
      promptTokens = usage.promptTokens,
      cachedTokens = usage.cachedTokens,
      completionTokens = usage.completionTokens
    )
    val values = ContentValues().apply {
      put("timestamp", timestamp)
      put("key_group_id", keyGroupId)
      put("model_name", model)
      put("chat_window_id", chatWindowId)
      put("prompt_tokens", usage.promptTokens)
      put("completion_tokens", usage.completionTokens)
      put("cached_tokens", usage.cachedTokens)
      put("total_tokens", usage.totalTokens)
      put("cost", cost)
    }
    runCatching {
      UsageDb.of(context).writableDatabase.insert("api_call_logs", null, values)
    }
  }

  /** 记一条活跃采样(前台 / 后台),由 [Heartbeat] 每 10 分钟调用一次。 */
  fun sample(context: Context, foreground: Boolean, timestamp: Long = System.currentTimeMillis()) {
    val values = ContentValues().apply {
      put("timestamp", timestamp)
      put("app_state", if (foreground) STATE_FOREGROUND else STATE_BACKGROUND)
    }
    runCatching {
      UsageDb.of(context).writableDatabase.insert("user_heartbeats", null, values)
    }
  }
}
