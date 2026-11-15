package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.data.BackupDocument
import com.reimei.intersect.data.BackupDrafts
import com.reimei.intersect.data.BackupFiles
import com.reimei.intersect.data.BackupFormat
import com.reimei.intersect.data.BackupFormatException
import com.reimei.intersect.data.BackupPreview
import com.reimei.intersect.data.BackupSection
import com.reimei.intersect.data.BackupService
import com.reimei.intersect.data.BackupStats
import com.reimei.intersect.data.RestoreMode
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「数据备份」页。
 *
 * 四张卡片:聊天记录、角色卡、记忆库、模型配置。每一类都独立导出 / 导入,
 * 一份文件只装一类内容,互不影响 —— 只想带走角色设定就不会顺带把 API KEY 也发出去。
 *
 * 备份文件是**明文 `.json`**:没有口令、没有加密,用文本编辑器打开就能读。
 * 换机、排障都简单,代价是任何拿到文件的人也能读,所以页面上明确写着这一点。
 *
 * 落到哪里由用户自己在系统选择器里点 —— 不能写进应用私有目录,
 * 否则卸载应用就把备份一起删了(见 [BackupDrafts] 与 [BackupFiles] 的说明)。
 */
@Composable
fun BackupPage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  onRestored: () -> Unit
) {
  BackHandler { onBack() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var stats by remember { mutableStateOf(BackupStats()) }
  var busy by remember { mutableStateOf<BackupSection?>(null) }
  var message by remember { mutableStateOf<BackupMessage?>(null) }
  var pending by remember { mutableStateOf<PendingRestore?>(null) }
  var confirmClear by remember { mutableStateOf(false) }

  // 待写入的备份刻意不放 remember:选择器盖住应用期间组合可能被销毁重建,
  // 放 remember 里那份内容会消失,回调就只能默默什么都不写(见 BackupDrafts 的说明)。

  // 上次导出到一半进程被杀时,暂存目录里可能还留着一份明文:进页面先清掉
  LaunchedEffect(Unit) { BackupDrafts.clear(context) }

  // 读盘要跑在 IO 上,而调用点既在协程里(页面首帧)也在回调里(导入完成),所以内部自己开协程
  fun refreshStats() {
    scope.launch { stats = withContext(Dispatchers.IO) { BackupService.stats(context) } }
  }

  LaunchedEffect(Unit) { refreshStats() }

  // 导出:系统的「保存到」选择器拿到 Uri 后再写进去。
  //
  // 待写入的内容存在缓存目录的暂存文件里,不存在任何界面/进程状态里 ——
  // 真机上 MIUI 会在选择器占着前台的时候把我们的进程杀掉,回传时进程已经是新的。
  val exportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(BackupFiles.MIME_TYPE)
  ) { uri ->
    if (uri == null) {
      // 用户取消了保存,什么都没建
      BackupDrafts.clear(context)
      busy = null
      return@rememberLauncherForActivityResult
    }
    scope.launch {
      val outcome = withContext(Dispatchers.IO) {
        val staged = BackupDrafts.take(context)
        if (staged == null) {
          // 选择器已经建好了空文件,而我们没有内容可写:删掉那个空文件并告知用户
          BackupFiles.deleteIfEmpty(context, uri)
          null
        } else {
          runCatching { BackupService.export(context, uri, staged.readText(Charsets.UTF_8)) }
            .also { staged.delete() }
        }
      }
      // 写盘结束才放开按钮:中途放开再点一次会把这份暂存文件删掉
      busy = null
      if (outcome == null) {
        message = BackupMessage(
          "导出未完成",
          "已经选好位置,但待写入的备份找不到了。\n\n请重新点一次「导出」。"
        )
        return@launch
      }
      outcome.fold(
        onSuccess = { result ->
          message = BackupMessage(
            title = "导出完成",
            body = result.counts.summary(),
            // 拆成独立行而不是拼成一整串:整串查不到翻译表,i18n 会静默失效
            detail = "已保存到你选择的位置:\n${result.name}"
          )
        },
        onFailure = { e ->
          message = BackupMessage("导出失败", e.message ?: "未知错误")
        }
      )
    }
  }

  fun startExport(section: BackupSection) {
    if (busy != null) return
    busy = section
    scope.launch {
      val outcome = runCatching {
        withContext(Dispatchers.IO) {
          // 打包 + 暂存都在选位置之前做完:选完位置之后可能连进程都不是原来那个了
          BackupDrafts.stage(context, BackupService.prepare(context, section))
        }
      }
      outcome.fold(
        onSuccess = { staged ->
          runCatching { exportLauncher.launch(staged.name) }.onFailure { e ->
            BackupDrafts.clear(context)
            busy = null
            message = BackupMessage("导出失败", e.message ?: "打不开文件选择器")
          }
        },
        onFailure = { e ->
          BackupDrafts.clear(context)
          busy = null
          message = BackupMessage("导出失败", e.message ?: "未知错误")
        }
      )
    }
  }

  // 导入:系统的「打开文件」选择器挑一份备份(可以是别的设备 / 别的位置上的)
  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = null
    scope.launch {
      val outcome = runCatching {
        withContext(Dispatchers.IO) {
          val document = BackupFiles.document(context, uri)
          PendingRestore(document, BackupService.preview(document))
        }
      }
      outcome.fold(
        onSuccess = { pending = it },
        onFailure = { e ->
          message = BackupMessage(
            "无法打开该文件",
            when (e) {
              is BackupFormatException -> e.message ?: "它可能不是本应用的备份文件"
              else -> e.message ?: "它可能不是本应用的备份文件"
            }
          )
        }
      )
    }
  }

  fun startImport() {
    if (busy != null) return
    runCatching { importLauncher.launch(BackupFiles.PICKER_TYPES) }.onFailure { e ->
      message = BackupMessage("无法打开文件选择器", e.message ?: "未知错误")
    }
  }

  fun clearChats() {
    confirmClear = false
    scope.launch {
      val count = withContext(Dispatchers.IO) { BackupService.clearChats(context) }
      refreshStats()
      // 磁盘上的数据已经变了,让 ViewModel 重新读一遍
      onRestored()
      message = BackupMessage("已删除", "已清空 $count 个会话的全部聊天记录。")
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "数据备份", onBack = onBack)
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
      Text(
        "备份文件是明文 .json,不加密,导出时由你选择保存位置。",
        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
      Spacer(Modifier.height(10.dp))

      SectionCard(
        title = BackupSection.Chats.label,
        subtitle = "跨格式备份、导出与恢复",
        icon = R.drawable.ui_backup_history,
        summary = "当前共有 ${stats.chats} 条聊天记录(共 ${stats.messages} 条消息)。",
        busy = busy == BackupSection.Chats,
        onExport = { startExport(BackupSection.Chats) },
        onImport = { startImport() },
      ) {
        PillButton(
          text = "删除全部",
          icon = R.drawable.ui_backup_delete,
          modifier = Modifier.fillMaxWidth(),
          tint = AppTheme.colors.badge,
          onClick = { confirmClear = true }
        )
      }

      Spacer(Modifier.height(12.dp))

      SectionCard(
        title = BackupSection.Characters.label,
        subtitle = "备份与恢复角色卡配置",
        icon = R.drawable.ui_dev_character,
        summary = "当前共有 ${stats.characters} 个角色卡。",
        busy = busy == BackupSection.Characters,
        onExport = { startExport(BackupSection.Characters) },
        onImport = { startImport() },
      )

      Spacer(Modifier.height(12.dp))

      SectionCard(
        title = BackupSection.Memories.label,
        subtitle = "跨配置备份与恢复,保持思维链一致",
        icon = R.drawable.ui_backup_memory,
        summary = "当前共有 ${stats.memories} 条记忆(${stats.memoryOwners} 个角色)。",
        busy = busy == BackupSection.Memories,
        onExport = { startExport(BackupSection.Memories) },
        onImport = { startImport() },
      )

      Spacer(Modifier.height(12.dp))

      SectionCard(
        title = BackupSection.ApiKeys.label,
        subtitle = "备份与恢复所有模型设置,包括 API 密钥和参数",
        icon = R.drawable.ui_backup_model,
        summary = "当前共有 ${stats.apiKeys} 个模型配置。",
        busy = busy == BackupSection.ApiKeys,
        onExport = { startExport(BackupSection.ApiKeys) },
        onImport = { startImport() },
      )
    }
  }

  message?.let { current ->
    AlertDialog(
      onDismissRequest = { message = null },
      confirmButton = {
        Text(
          "好",
          Modifier
            .clickable { message = null }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.iconCurrent
        )
      },
      title = { Text(current.title, fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
      text = {
        Column {
          Text(current.body, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
          current.detail?.let { detail ->
            Spacer(Modifier.height(8.dp))
            Text(detail, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
          }
        }
      }
    )
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("删除全部聊天记录", fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
      text = {
        Text(
          "本机的 ${stats.chats} 个会话及其中的 ${stats.messages} 条消息会全部删除,无法恢复。\n\n角色卡、记忆库与模型配置不受影响。",
          fontSize = 13.sp,
          color = AppTheme.colors.textSecondary
        )
      },
      confirmButton = {
        Text(
          "删除",
          Modifier
            .clickable { clearChats() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.badge
        )
      },
      dismissButton = {
        Text(
          "取消",
          Modifier
            .clickable { confirmClear = false }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }

  val target = pending
  if (target != null) {
    RestoreDialog(
      item = target,
      onDismiss = { pending = null },
      onDone = { title, body ->
        pending = null
        message = BackupMessage(title, body)
        refreshStats()
        // 磁盘上的数据已经变了,让 ViewModel 重新读一遍
        onRestored()
      }
    )
  }
}

private class BackupMessage(
  val title: String,
  val body: String,
  /** 次一级的说明(如保存到了哪里),正常颜色。 */
  val detail: String? = null,
)

/** 已经读出来、等用户确认要不要恢复的一份文件。 */
private class PendingRestore(
  val document: BackupDocument,
  val preview: BackupPreview,
)

/** 一张卡片:图标 + 标题 + 副标题 + 现在有多少条 + 导出/导入按钮(可再加一行按钮)。 */
@Composable
private fun SectionCard(
  title: String,
  subtitle: String,
  @DrawableRes icon: Int,
  summary: String,
  busy: Boolean,
  onExport: () -> Unit,
  onImport: () -> Unit,
  footer: @Composable (() -> Unit)? = null
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(AppTheme.colors.listItem)
      .border(1.dp, AppTheme.colors.chatListDivider, RoundedCornerShape(16.dp))
      .padding(16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(44.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.iconCurrent.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          painterResource(icon),
          contentDescription = null,
          Modifier.size(24.dp),
          tint = AppTheme.colors.iconCurrent
        )
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
      }
    }
    Spacer(Modifier.height(16.dp))
    Text(summary, fontSize = 15.sp, color = AppTheme.colors.textPrimary)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
      // 「导出 / 导入」各自占一半:两个按钮一样大,手指不用瞄准
      PillButton(
        text = if (busy) "正在导出..." else "导出",
        icon = R.drawable.ui_download_tray,
        modifier = Modifier.weight(1f),
        enabled = !busy,
        onClick = onExport
      )
      Spacer(Modifier.width(12.dp))
      PillButton(
        text = "导入",
        icon = R.drawable.ui_backup_upload,
        modifier = Modifier.weight(1f),
        enabled = !busy,
        onClick = onImport
      )
    }
    if (footer != null) {
      Spacer(Modifier.height(12.dp))
      footer()
    }
  }
}

/** 卡片里的按钮:浅色底 + 同色图标与文字,危险操作用红色传 [tint]。 */
@Composable
private fun PillButton(
  text: String,
  @DrawableRes icon: Int,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color = AppTheme.colors.iconCurrent,
  onClick: () -> Unit
) {
  val content = if (enabled) tint else AppTheme.colors.textSecondary
  Box(
    modifier
      .clip(RoundedCornerShape(12.dp))
      .background(if (enabled) tint.copy(alpha = 0.12f) else AppTheme.colors.chatListDivider)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = 12.dp),
    contentAlignment = Alignment.Center
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        painterResource(icon),
        contentDescription = null,
        Modifier.size(18.dp),
        tint = content
      )
      Spacer(Modifier.width(8.dp))
      Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = content)
    }
  }
}

/**
 * 恢复确认框:选合并 / 覆盖 → 执行。
 *
 * 备份是明文,没有口令要输;但「合并」会改动本机数据,所以仍要用户看一眼内容再点确认。
 */
@Composable
private fun RestoreDialog(
  item: PendingRestore,
  onDismiss: () -> Unit,
  onDone: (title: String, body: String) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var mode by remember { mutableStateOf(RestoreMode.Merge) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  val preview = item.preview

  fun startRestore() {
    if (busy) return
    busy = true
    error = null
    scope.launch {
      val outcome = runCatching {
        withContext(Dispatchers.IO) { BackupService.restore(context, item.document, mode) }
      }
      busy = false
      outcome.fold(
        onSuccess = { result -> onDone("${mode.label}完成", result.summary()) },
        onFailure = { e ->
          error = when (e) {
            is BackupFormatException -> e.message ?: "备份文件不合法"
            else -> e.message ?: "恢复失败"
          }
        }
      )
    }
  }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("导入${preview.section.label}", fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
    text = {
      Column {
        Text(
          preview.exportedAt,
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary
        )
        Spacer(Modifier.height(2.dp))
        // 版本单独一行:拼成「时间 · 应用版本 X」后整串查不到翻译表
        Text(
          "应用版本 ${preview.appVersion.ifEmpty { "未知" }}",
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary
        )
        Spacer(Modifier.height(4.dp))
        Text(preview.counts.summary(), fontSize = 13.sp, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        Text("恢复方式", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
          RestoreMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Box(
              Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                  if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.bottomBar
                )
                .clickable(enabled = !busy) { mode = candidate }
                .padding(vertical = 8.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                candidate.label,
                fontSize = 14.sp,
                color = if (selected) AppTheme.colors.onFab else AppTheme.colors.textPrimary
              )
            }
          }
        }
        Spacer(Modifier.height(6.dp))
        Text(
          when (preview.section) {
            BackupSection.Characters -> when (mode) {
              RestoreMode.Merge -> "合并:保留本机已有角色,补充备份里多出来的;同 id 的角色以备份为准"
              RestoreMode.Replace -> "覆盖:本机多出来的角色会被清掉,只留备份里的角色卡"
            }
            BackupSection.Chats -> when (mode) {
              RestoreMode.Merge -> "合并:保留本机会话,只补充备份里多出来的消息"
              RestoreMode.Replace -> "覆盖:本机多出来的会话会被清掉,只留备份里的聊天记录"
            }
            BackupSection.Memories -> when (mode) {
              RestoreMode.Merge -> "合并:只补本机还没有记忆的角色,已有记忆保持不动"
              RestoreMode.Replace -> "覆盖:本机所有角色的记忆都会被替换成备份里的内容"
            }
            BackupSection.ApiKeys -> when (mode) {
              RestoreMode.Merge -> "合并:保留本机配置,补充备份里多出来的;同 id 的配置以备份为准"
              RestoreMode.Replace -> "覆盖:本机多出来的配置会被清掉,只留备份里的模型配置"
            }
          },
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary
        )
        error?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, fontSize = 12.sp, color = AppTheme.colors.badge)
        }
      }
    },
    confirmButton = {
      Text(
        if (busy) "正在恢复..." else "恢复",
        Modifier
          .clickable(enabled = !busy) { startRestore() }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 15.sp,
        color = if (busy) AppTheme.colors.more else AppTheme.colors.iconCurrent
      )
    },
    dismissButton = {
      Text(
        "取消",
        Modifier
          .clickable(enabled = !busy, onClick = onDismiss)
          .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 15.sp,
        color = AppTheme.colors.textSecondary
      )
    }
  )
}
