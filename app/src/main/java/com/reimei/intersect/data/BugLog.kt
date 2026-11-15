package com.reimei.intersect.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 黑匣子日志:给非技术测试员准备的那一份「出事了能拿去排查」的记录。
 *
 * 三件事:
 * 1. **异步落盘** —— [log] 只是往无界队列里丢一行,真正的写文件在 IO 线程上做,
 *    调用它的地方(网络回调、总结流水线、UI 线程)不会被文件 IO 拖住;
 * 2. **有界** —— 单文件最大 [MAX_FILE_BYTES],写满就滚动:当前文件改名成 `.1`,
 *    `.1 → .2`、`.2 → .3`,最老的 `.3` 直接删。最多保留 [MAX_HISTORY] 份历史;
 * 3. **崩溃同步写** —— 进程马上要死,排进队列就写不进去了,所以
 *    [installCrashHandler] 里绕开队列直接写文件,写完再交回系统原来的处理器。
 *
 * 日志目录放在应用外部文件目录(`Android/data/<包名>/files/buglog`)下:测试员不需要权限就能
 * 用文件管理器打开,分享报告也不需要额外授权(见 [BugReport])。
 *
 * 这里只记**排查需要的东西**:异常堆栈、网络请求的结果、总结流水线的关键节点。
 * 绝不记 API KEY 原文、聊天正文 —— 报告要发给开发者,用户不该为了报 bug 交出这些。
 */
object BugLog {

  /** 单个日志文件的上限:2 MB。 */
  const val MAX_FILE_BYTES = 2 * 1024 * 1024

  /** 保留几份历史日志(不含当前文件)。 */
  const val MAX_HISTORY = 3

  /** 当前正在写的文件名。 */
  const val CURRENT_NAME = "intersect_log.txt"

  private const val DIR_NAME = "buglog"
  private const val TAG = "BugLog"

  /** 写文件用的后台作用域:不随任何页面销毁。 */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** 待写的行;无界,因为丢日志比丢一行内存更糟。 */
  private val queue = Channel<String>(Channel.UNLIMITED)

  /** 滚动 / 追加 / 读之间互斥 —— 写文件只有一条线程在跑,但崩溃那条路是另一条。 */
  private val lock = Any()

  @Volatile
  private var dir: File? = null

  @Volatile
  private var started = false

  private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

  /** 一行日志的时间戳;SimpleDateFormat 不是线程安全的,这里自己兜一层锁。 */
  private fun stamp(): String = synchronized(stampFormat) { stampFormat.format(Date()) }

  /**
   * 启动黑匣子:确定目录、起写入协程、装崩溃处理器。
   *
   * 重复调用无副作用(第二次进来直接返回)。
   */
  fun init(context: Context) {
    if (started) return
    started = true
    val appContext = context.applicationContext
    dir = resolveDir(appContext)
    scope.launch {
      for (line in queue) append(line)
    }
    installCrashHandler()
    log("APP", "会话开始 · ${appContext.packageName} · v${versionName(appContext)}")
  }

  /** 记一行:[tag] 是分类(网络 / 总结 / 崩溃 ...),[message] 是内容。 */
  fun log(tag: String, message: String) {
    if (!started) return
    val line = "${stamp()} [$tag] $message"
    // 队列满是不可能的(无界),这里仍用 trySend:调用方绝不能因为日志崩掉
    queue.trySend(line + "\n")
  }

  /** 记一个异常:类的全名 + 消息 + 堆栈。 */
  fun log(tag: String, error: Throwable) {
    log(tag, "${error.javaClass.name}: ${error.message}\n${stackOf(error)}")
  }

  /** 日志目录(外部文件目录优先);还没 init 时返回 null。 */
  fun dir(): File? = dir

  /** 报告要收集的文件:**从旧到新**排列(历史在前,当前文件最后)。 */
  fun files(): List<File> {
    val base = dir ?: return emptyList()
    val out = mutableListOf<File>()
    // 最老的先给:读报告的人按时间顺序往下看才连得上
    for (index in MAX_HISTORY downTo 1) {
      val file = File(base, "$CURRENT_NAME.$index")
      if (file.isFile && file.length() > 0) out.add(file)
    }
    val current = File(base, CURRENT_NAME)
    if (current.isFile && current.length() > 0) out.add(current)
    return out
  }

  /**
   * 读日志正文,最多读 [maxBytes] 字节 —— 取**最近**的那一段(尾部),不是开头。
   *
   * 越界时从某一行的开头开始截,免得报告里第一行是半句话。
   */
  fun readTail(maxBytes: Long): String {
    val all = files()
    if (all.isEmpty()) return "(没有日志)"
    val builder = StringBuilder()
    var budget = maxBytes
    // 从最新的往前读,读够了就停,最后整体反转回时间顺序
    val chunks = mutableListOf<String>()
    for (file in all.asReversed()) {
      if (budget <= 0) break
      val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
      val bytes = text.toByteArray(Charsets.UTF_8).size
      if (bytes <= budget) {
        chunks.add(text)
        budget -= bytes
      } else {
        // 这个文件只留尾部:按字符近似切(UTF-8 中文 3 字节,留点余量)
        val keep = (budget / 3).toInt().coerceAtLeast(0)
        chunks.add(if (keep <= 0) "" else text.takeLast(keep).substringAfter('\n'))
        budget = 0
      }
    }
    chunks.asReversed().forEach { builder.append(it) }
    return builder.toString().ifEmpty { "(没有日志)" }
  }

  /** 清空全部日志(测试员重来一遍时用,报告生成后可以留着不动)。 */
  fun clear() {
    val base = dir ?: return
    synchronized(lock) {
      runCatching { base.listFiles()?.forEach { it.delete() } }
    }
  }

  // —— 内部实现 ——

  /** 一次追加:先看会不会超上限,超了先滚动,再写。 */
  private fun append(line: String) {
    val base = dir ?: return
    synchronized(lock) {
      runCatching {
        base.mkdirs()
        val file = File(base, CURRENT_NAME)
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (file.length() + bytes.size > MAX_FILE_BYTES) rotate(base)
        file.appendBytes(bytes)
      }.onFailure { Log.w(TAG, "写日志失败: ${it.message}") }
    }
  }

  /** 滚动:`.3` 删掉,`.2 → .3`,`current → .1`。 */
  private fun rotate(base: File) {
    File(base, "$CURRENT_NAME.$MAX_HISTORY").delete()
    for (index in MAX_HISTORY - 1 downTo 1) {
      val from = File(base, "$CURRENT_NAME.$index")
      if (from.isFile) from.renameTo(File(base, "$CURRENT_NAME.${index + 1}"))
    }
    File(base, CURRENT_NAME).renameTo(File(base, "$CURRENT_NAME.1"))
  }

  /**
   * 装崩溃处理器:先把堆栈同步写进日志,再交回系统原来的处理器(它才负责弹崩溃对话框 / 结束进程)。
   *
   * 这里**不能**走队列:进程立刻就要没了,排队的行永远写不进去。
   */
  private fun installCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching {
        append(
          "${stamp()} [CRASH] 线程 ${thread.name}\n" +
            "${error.javaClass.name}: ${error.message}\n${stackOf(error)}\n"
        )
      }
      if (previous != null) {
        previous.uncaughtException(thread, error)
      } else {
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(10)
      }
    }
  }

  private fun stackOf(error: Throwable): String = runCatching {
    val writer = StringWriter()
    error.printStackTrace(PrintWriter(writer))
    writer.toString()
  }.getOrDefault("(堆栈打不出来)")

  private fun resolveDir(context: Context): File {
    val external = context.getExternalFilesDir(null)
    val base = external ?: context.filesDir
    return File(base, DIR_NAME)
  }

  private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
  }.getOrDefault("")
}

/** 时间格式化的小工具:日志头与报告头都用同一套。 */
internal fun nowStamp(): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
