package com.reimei.intersect.data.time

/**
 * T7.1 —— 时间子系统的日志。
 *
 * 四类日志,每类都必须带**时间戳**与**上下文**(键值对),验收清单里逐条自动检查:
 * - [TimeLogCategory.TRANSACTION]:每一次时钟事务(改前/改后/操作者/快照);
 * - [TimeLogCategory.SUMMARY]:总结触发与元数据生成;
 * - [TimeLogCategory.INJECT]:每一轮注入计划的组成;
 * - [TimeLogCategory.FALLBACK]:每一次名词降级(哪一级 → 哪一级)。
 *
 * 实现只依赖 [TimeLogger] 接口,所以单测可以塞一个 [InMemoryTimeLog] 进去断言内容;
 * 应用侧默认也用它(内存环形缓冲),后续要落盘只需换一个实现。
 */
enum class TimeLogCategory(val label: String) {
  TRANSACTION("事务"),
  SUMMARY("总结"),
  INJECT("注入"),
  FALLBACK("降级"),
}

data class TimeLogEntry(
  val category: TimeLogCategory,
  val message: String,
  /** epoch 毫秒。 */
  val at: Long,
  val context: Map<String, String> = emptyMap(),
) {
  /** 一行式输出(调试面板/日志文件用)。 */
  fun line(): String = buildString {
    append('[')
    append(category.label)
    append("] ")
    append(message)
    if (context.isNotEmpty()) {
      append(' ')
      append(context.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }
  }
}

interface TimeLogger {
  fun log(entry: TimeLogEntry)
}

/** 内存实现:定长环形缓冲,新的覆盖旧的。单测与调试面板都用它。 */
class InMemoryTimeLog(private val limit: Int = 400) : TimeLogger {
  private val lock = Any()
  private val buffer = mutableListOf<TimeLogEntry>()

  val entries: List<TimeLogEntry>
    get() = synchronized(lock) { buffer.toList() }

  override fun log(entry: TimeLogEntry) {
    synchronized(lock) {
      buffer.add(entry)
      while (buffer.size > limit) buffer.removeAt(0)
    }
  }

  fun of(category: TimeLogCategory): List<TimeLogEntry> = entries.filter { it.category == category }

  fun last(): TimeLogEntry? = entries.lastOrNull()

  fun clear() {
    synchronized(lock) { buffer.clear() }
  }
}

/** 全局日志出口:默认内存实现;应用侧可以换成落盘的实现(不改变任何调用点)。 */
object TimeLogs {
  @Volatile
  var sink: TimeLogger = InMemoryTimeLog()

  fun log(
    category: TimeLogCategory,
    message: String,
    context: Map<String, String> = emptyMap(),
    at: Long = System.currentTimeMillis(),
  ) {
    sink.log(TimeLogEntry(category, message, at, context))
  }

  /** 单测用:换一个干净的内存日志进去。 */
  internal fun useMemory(limit: Int = 400): InMemoryTimeLog {
    val memory = InMemoryTimeLog(limit)
    sink = memory
    return memory
  }
}
