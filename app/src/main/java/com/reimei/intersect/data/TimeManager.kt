package com.reimei.intersect.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间管理器:给 Self-insert 会话注入每轮对话的时间。
 *
 * 时间从用户本地拉取(默认时区),精确到分钟。每次调用都现建 [SimpleDateFormat],
 * 省得为一个单例去处理它的非线程安全。
 */
object TimeManager {
  /** 本地当前时间,精确到分钟,如 "14:05"。 */
  fun nowMinute(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

  /** 本地当前日期时间,精确到分钟,如 "2025-01-15 14:05"。 */
  fun nowDateTime(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
}
