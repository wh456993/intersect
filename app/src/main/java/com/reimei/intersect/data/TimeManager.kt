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

  /**
   * 把秒级时间戳按本地时区格式化成 "yyyy-MM-dd HH:mm:ss"。
   *
   * 记忆索引里存的是 epoch 秒(不存格式化后的字符串):换语言、换时区都还能正确显示。
   */
  fun formatSecond(epochSecond: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochSecond * 1000L))

  /** 同上,精确到分钟 —— 即时记忆总结的生成时间用它。 */
  fun formatMinute(epochSecond: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSecond * 1000L))

  /** 本地此刻的秒级时间戳(epoch second):总结的生成时间用这个口径存。 */
  fun nowSecond(): Long = System.currentTimeMillis() / 1000L
}
