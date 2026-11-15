package com.reimei.intersect.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.data.BugLog
import com.reimei.intersect.data.BugReport
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「测试反馈」页(黑匣子):给非技术测试员用,不需要服务器,也不需要账号。
 *
 * 流程只有两步:
 * 1. 点「一键生成日志报告」—— 把设备信息、黑匣子日志([BugLog])和下面这框吐槽
 *    打包成 `Intersect_BugReport_时间.txt`;
 * 2. 点「通过 QQ 发送」—— 走系统分享面板([BugReport.shareIntent]),在里面选 QQ 发给开发者。
 *
 * 为什么走系统分享而不是直接拉起 QQ:各家 ROM 上 QQ 的分享入口不一样(QQ / QQ 轻聊 / TIM),
 * 硬编码包名会有「这台机器上没有这个包」的崩溃;系统面板既能兜住这些差异,
 * 也不会要求应用去申请「查询已安装应用」这类权限。
 */
@Composable
fun FeedbackPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
  BackHandler { onBack() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var complaint by remember { mutableStateOf("") }
  /** 上一次生成的报告;null 表示还没有 —— 「发送」按钮据此置灰。 */
  var report by remember { mutableStateOf<File?>(null) }
  var busy by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf<String?>(null) }
  /** 黑匣子现有日志文件(在 IO 上读,不在组合期做文件 IO)。 */
  var logFiles by remember { mutableStateOf<List<File>>(emptyList()) }
  var logTick by remember { mutableStateOf(0) }

  LaunchedEffect(logTick) {
    logFiles = withContext(Dispatchers.IO) { BugLog.files() }
  }

  fun toast(text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
  }

  /** 生成报告:整段在 IO 上跑(读日志、查库都在里面),完成后再回到主线程改状态。 */
  fun generate(onReady: (File) -> Unit = {}) {
    if (busy) return
    busy = true
    notice = null
    scope.launch {
      val outcome = runCatching { BugReport.build(context, complaint) }
      busy = false
      outcome.fold(
        onSuccess = { file ->
          report = file
          notice = "已生成:${file.name}(共 ${sizeLabel(file.length())})"
          logTick++
          onReady(file)
        },
        onFailure = { error ->
          notice = "生成失败:${error.message ?: "写不进存储"}"
        }
      )
    }
  }

  /** 拉起系统分享面板;没装任何能接收的应用时只提示,不崩。 */
  fun share(file: File) {
    try {
      context.startActivity(BugReport.shareIntent(context, file))
      notice = "已打开分享面板:在里面选 QQ 发给开发者"
    } catch (e: ActivityNotFoundException) {
      notice = "这台设备上没有可以接收文件的应用"
    } catch (e: Exception) {
      notice = "分享失败:${e.message ?: "未知原因"}"
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "测试反馈", onBack = onBack)
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
      Card {
        Text(
          "遇到闪退、总结不生成、报文不对时,先在上面写两句当时在做什么," +
            "然后点「一键生成日志报告」,再点「通过 QQ 发送」把文件发给开发者。",
          Modifier.padding(14.dp),
          fontSize = 12.sp,
          lineHeight = 18.sp,
          color = AppTheme.colors.textSecondary
        )
      }

      Spacer(Modifier.height(12.dp))
      Text(
        "吐槽 / 问题描述",
        Modifier.padding(start = 4.dp, bottom = 6.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
      Card {
        Box(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp)
            .padding(12.dp)
        ) {
          if (complaint.isEmpty()) {
            Text(
              "比如:「聊到 20 轮没生成微总结」「发消息偶现回复重复」...",
              fontSize = 13.sp,
              color = AppTheme.colors.more
            )
          }
          BasicTextField(
            value = complaint,
            // 长度兜住:报告是纯文本,没人需要往里贴一整篇小说
            onValueChange = { complaint = it.take(MAX_COMPLAINT) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 13.sp, color = AppTheme.colors.textPrimary),
            cursorBrush = SolidColor(AppTheme.colors.iconCurrent)
          )
        }
      }

      Spacer(Modifier.height(16.dp))
      ActionButton(
        text = if (busy) "正在生成..." else "一键生成日志报告",
        icon = R.drawable.ui_backup_history,
        enabled = !busy
      ) {
        generate { file -> toast("报告已生成:${file.name}") }
      }
      Spacer(Modifier.height(10.dp))
      ActionButton(
        text = "通过 QQ 发送",
        icon = R.drawable.ui_send_arrow,
        enabled = !busy && report != null,
        filled = true
      ) {
        val file = report ?: return@ActionButton
        share(file)
      }

      notice?.let { line ->
        Spacer(Modifier.height(10.dp))
        Text(
          line,
          Modifier.padding(horizontal = 4.dp),
          fontSize = 12.sp,
          lineHeight = 17.sp,
          color = AppTheme.colors.textSecondary
        )
      }
      report?.let { file ->
        Spacer(Modifier.height(4.dp))
        Text(
          file.absolutePath,
          Modifier.padding(horizontal = 4.dp),
          fontSize = 10.sp,
          lineHeight = 14.sp,
          color = AppTheme.colors.more
        )
      }

      Spacer(Modifier.height(20.dp))
      // 黑匣子自身状态:测试员看到「已有 N 份日志」就知道报告里带着东西,不是空文件
      Card {
        Column(Modifier.padding(14.dp)) {
          Text(
            "黑匣子日志",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.colors.textPrimary
          )
          Spacer(Modifier.height(4.dp))
          val files = logFiles
          Text(
            if (files.isEmpty()) "还没有日志(应用刚启动时可能为空)"
            else "已有 ${files.size} 份 · 共 ${sizeLabel(files.sumOf { it.length() })}\n" +
              "单个文件上限 2 MB,最多保留 3 份历史" +
              "(闪退堆栈 / 网络请求结果 / 总结流水线关键节点都在里面)",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = AppTheme.colors.textSecondary
          )
          Spacer(Modifier.height(10.dp))
          Text(
            "清空日志",
            Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable {
                BugLog.clear()
                logTick++
                notice = "日志已清空"
              }
              .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = AppTheme.colors.badge
          )
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

/** 吐槽文本框的长度上限。 */
private const val MAX_COMPLAINT = 4000

@Composable
private fun Card(content: @Composable () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
  ) { content() }
}

/** 整行按钮:[filled] 是主操作(实心底),否则是次要操作(淡底)。 */
@Composable
private fun ActionButton(
  text: String,
  icon: Int,
  enabled: Boolean,
  filled: Boolean = false,
  onClick: () -> Unit,
) {
  val tint = if (filled) AppTheme.colors.iconCurrent else AppTheme.colors.textPrimary
  val content = if (enabled) tint else AppTheme.colors.more
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(
        when {
          !enabled -> AppTheme.colors.chatListDivider
          filled -> AppTheme.colors.iconCurrent
          else -> AppTheme.colors.listItem
        }
      )
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = 13.dp),
    contentAlignment = Alignment.Center
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        painterResource(icon),
        contentDescription = null,
        Modifier.size(18.dp),
        tint = if (filled && enabled) AppTheme.colors.onFab else content
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = if (filled && enabled) AppTheme.colors.onFab else content
      )
    }
  }
}

/** 文件大小的可读写法。 */
private fun sizeLabel(bytes: Long): String = when {
  bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
  bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
  else -> "$bytes B"
}
