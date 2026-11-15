package com.reimei.intersect.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.reimei.intersect.data.memory.MemoryPipeline
import com.reimei.intersect.data.memory.MemoryRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「测试反馈」的日志报告:一台设备上一次出问题的全部证据,打包成一个 txt。
 *
 * 报告是**纯文本**、**没有服务器**:生成到应用自己的目录里,再交给系统分享面板
 * (用户在里面选 QQ 发给开发者)。这样非技术测试员只需要点两下,不用登录、不用传文件。
 *
 * 内容分四段:
 * 1. 反馈正文(测试员自己写的吐槽);
 * 2. 应用信息(包名 / 版本 / 是不是 beta 包);
 * 3. 设备与系统(厂商机型 / Android 版本 / ABI / 屏幕 / 语言);
 * 4. 运行状态(会话数、消息条数、每个角色的记忆库条数)—— 排查记忆系统时最需要的一组数字;
 * 5. 黑匣子日志([BugLog],只取尾部 [MAX_LOG_BYTES] 字节,避免报告大到发不出去)。
 *
 * 不收集:API KEY、聊天正文、账号、位置。发出去的东西只够定位问题。
 */
object BugReport {

  /** 报告文件名前缀。 */
  const val PREFIX = "Intersect_BugReport_"

  /** 报告里最多带多少日志正文(尾部优先)。 */
  const val MAX_LOG_BYTES = 800L * 1024L

  private const val DIR_NAME = "bugreport"

  /**
   * 生成报告文件(阻塞在 IO 上,调用方给协程)。
   *
   * [complaint] 是测试员填的吐槽,原样放进报告开头;空的话写「(没有填写)」。
   */
  suspend fun build(context: Context, complaint: String): File = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val body = StringBuilder()
    body.appendLine("===== Intersect 问题反馈 =====")
    body.appendLine("生成时间: ${nowStamp()}")
    body.appendLine()
    body.appendLine("----- 测试员留言 -----")
    body.appendLine(complaint.trim().ifEmpty { "(没有填写)" })
    body.appendLine()
    body.appendLine("----- 应用 -----")
    body.appendLine("包名: ${appContext.packageName}")
    body.appendLine("版本: ${versionLabel(appContext)}")
    body.appendLine()
    body.appendLine("----- 设备与系统 -----")
    body.appendLine("厂商 / 机型: ${Build.MANUFACTURER} ${Build.MODEL}")
    body.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    body.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
    body.appendLine("屏幕: ${screenLabel(appContext)}")
    body.appendLine("语言: ${Locale.getDefault()}")
    body.appendLine()
    body.appendLine("----- 运行状态 -----")
    body.append(buildStateSection(appContext))
    body.appendLine()
    body.appendLine("----- 日志(最近 ${MAX_LOG_BYTES / 1024} KB) -----")
    body.appendLine(BugLog.readTail(MAX_LOG_BYTES))

    val file = File(resolveDir(appContext), fileName())
    file.parentFile?.mkdirs()
    file.writeText(body.toString(), Charsets.UTF_8)
    file
  }

  /** 系统分享的 Intent:带上 txt 的 content:// 地址,让用户在面板里选 QQ / 微信 / 邮件。 */
  fun shareIntent(context: Context, file: File): Intent {
    val uri = uriOf(context, file)
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, "Intersect 问题反馈")
      putExtra(Intent.EXTRA_TEXT, "intersect 的反馈日志,详见附件。")
      // ClipData 也要带一份:部分收发件人只认它给的授权
      clipData = android.content.ClipData.newRawUri("bugreport", uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "发送反馈日志").apply {
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /** 报告文件的 content:// 地址(供分享用;FileProvider 已在清单里声明)。 */
  fun uriOf(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

  /** 生成报告用的目录:外部文件目录优先(文件管理器能直接看到),否则退回内部目录。 */
  fun resolveDir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, DIR_NAME)
  }

  private fun fileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
    return "$PREFIX$stamp.txt"
  }

  /**
   * 「运行状态」段:会话数、消息条数,以及每个角色在 memory.db 里的微总结 / 大总结条数。
   *
   * 这几个数字是排查记忆系统最要紧的一组 —— 比如「20 轮了却没总结」,
   * 一眼就能看出是轮数没攒够、还是微总结写失败了。
   */
  private suspend fun buildStateSection(context: Context): String {
    val chats = ChatStore.load(context).orEmpty()
    val lines = StringBuilder()
    lines.appendLine("会话数: ${chats.size}")
    lines.appendLine("消息总条数: ${chats.sumOf { it.msgs.size }}")
    val repository = MemoryRepository.get(context)
    chats.forEach { chat ->
      val friendId = chat.friend.id
      val rounds = MemoryRounds.group(chat.msgs.toList()).size
      val micros = runCatching { repository.microCount(friendId) }.getOrDefault(-1)
      val node = runCatching { repository.latestNode(friendId) }.getOrNull()
      val meta = runCatching { repository.rollingMeta(friendId) }.getOrNull()
      lines.appendLine(
        "- 「${chat.friend.name}」($friendId):轮次 $rounds · 微总结 " +
          (if (micros < 0) "读库失败" else "$micros 条") +
          " · 大总结 " + (node?.roundLabel() ?: "还没有") +
          " · 滚动状态 " + (if (meta == null) "未播种" else "已播种") +
          " · 总结任务 " + if (MemoryPipeline.isRunning(friendId)) "进行中" else "空闲"
      )
    }
    return lines.toString()
  }

  private fun versionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    // longVersionCode 是 API 28 才有的:老机器上退回 versionCode(它迟早会被弃用,这里显式压掉告警)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      info.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      info.versionCode.toLong()
    }
    val suffix = if (context.packageName.endsWith(".beta")) "(beta 调试包)" else ""
    "${info.versionName} ($code)$suffix"
  }.getOrDefault("读不到")

  private fun screenLabel(context: Context): String = runCatching {
    val metrics = context.resources.displayMetrics
    val px = "${metrics.widthPixels}x${metrics.heightPixels}"
    "$px / ${metrics.densityDpi}dpi"
  }.getOrDefault("读不到")
}
