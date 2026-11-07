package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.BackupCryptoException
import com.reimei.intersect.data.BackupDocument
import com.reimei.intersect.data.BackupDrafts
import com.reimei.intersect.data.BackupFiles
import com.reimei.intersect.data.BackupFormatException
import com.reimei.intersect.data.BackupPreview
import com.reimei.intersect.data.BackupSection
import com.reimei.intersect.data.BackupService
import com.reimei.intersect.data.RestoreMode
import com.reimei.intersect.ui.theme.AppTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备份文件口令的位数:6 位纯数字,好记也好输入。 */
private const val PASSWORD_LENGTH = 6

/** 备份列表里的一条:能解析出文件头的才是有效备份。 */
private class BackupItem(
  val document: BackupDocument,
  val preview: BackupPreview?,
  val error: String?,
)

/**
 * 「数据备份」页。
 *
 * 上半部分导出:勾选要带走的内容(角色 / 聊天记录 / API KEY),设一个 6 位数字口令,
 * 生成一份用口令加密的备份文件。落到哪里由用户自己在系统选择器里点 —— 不能写进应用私有目录,
 * 否则卸载应用就把备份一起删了。下半部分恢复:从选择器挑一份备份文件(或本机旧目录里扫描到的),
 * 输口令即可合并或覆盖回本机。
 *
 * 口令本身不做任何存储:它只在这一刻参与 PBKDF2 派生,丢了就再也解不开那份文件。
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

  var checked by remember { mutableStateOf(BackupSection.entries.toSet()) }
  var password by remember { mutableStateOf("") }
  var confirm by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<BackupMessage?>(null) }
  var restoring by remember { mutableStateOf<BackupItem?>(null) }

  var items by remember { mutableStateOf<List<BackupItem>>(emptyList()) }
  var listLoading by remember { mutableStateOf(true) }
  var listKey by remember { mutableStateOf(0) }

  // 待写入的备份刻意不放 remember:选择器盖住应用期间组合可能被销毁重建,
  // 放 remember 里那份内容会消失,回调就只能默默什么都不写(见 BackupDrafts 的说明)。

  fun previewOf(document: BackupDocument): BackupItem =
    runCatching { BackupService.preview(document) }.fold(
      onSuccess = { BackupItem(document, it, null) },
      onFailure = { BackupItem(document, null, it.message ?: "无法读取") }
    )

  LaunchedEffect(listKey) {
    listLoading = true
    items = withContext(Dispatchers.IO) {
      BackupFiles.list(context).map { file -> previewOf(BackupDocument.of(file)) }
    }
    listLoading = false
  }

  // 导出:系统的「保存到」选择器拿到 Uri 后再写进去。
  //
  // 待写入的内容存在缓存目录的暂存文件里,不存在任何界面/进程状态里 ——
  // 真机上 MIUI 会在选择器占着前台的时候把我们的进程杀掉,回传时进程已经是新的。
  val exportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(BackupFiles.MIME_TYPE)
  ) { uri ->
    busy = false
    if (uri == null) {
      // 用户取消了保存,什么都没建
      BackupDrafts.clear(context)
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
      if (outcome == null) {
        message = BackupMessage(
          "备份未完成",
          "已经选好位置,但待写入的备份找不到了。\n\n请重新点一次「开始备份」。"
        )
        return@launch
      }
      outcome.fold(
        onSuccess = { result ->
          // 口令已经不参与后续操作,导出完就把它从输入框里抹掉
          password = ""
          confirm = ""
          message = BackupMessage(
            title = "备份完成",
            body = "${result.counts.summary()}\n\n已保存到你选择的位置:\n${result.name}"
          )
        },
        onFailure = { e ->
          message = BackupMessage("备份失败", e.message ?: "未知错误")
        }
      )
    }
  }

  // 恢复:系统的「打开文件」选择器挑一份备份(可以是别的设备 / 别的位置上的)
  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      val item = withContext(Dispatchers.IO) {
        previewOf(BackupFiles.document(context, uri))
      }
      if (item.preview == null) {
        message = BackupMessage(
          "无法打开该文件",
          item.error ?: "它可能不是本应用的备份文件"
        )
      } else {
        restoring = item
      }
    }
  }

  val passwordOk = isBackupPassword(password)
  val canExport = checked.isNotEmpty() && passwordOk && password == confirm && !busy

  fun startExport() {
    if (!canExport) return
    busy = true
    val draftPassword = password
    val draftSections = checked
    scope.launch {
      val outcome = runCatching {
        withContext(Dispatchers.IO) {
          // 打包 + 暂存都在选位置之前做完:选完位置之后可能连进程都不是原来那个了
          BackupDrafts.stage(context, BackupService.prepare(context, draftPassword, draftSections))
        }
      }
      outcome.fold(
        onSuccess = { staged ->
          runCatching { exportLauncher.launch(staged.name) }.onFailure { e ->
            BackupDrafts.clear(context)
            busy = false
            message = BackupMessage("备份失败", e.message ?: "打不开文件选择器")
          }
        },
        onFailure = { e ->
          BackupDrafts.clear(context)
          busy = false
          message = BackupMessage("备份失败", e.message ?: "未知错误")
        }
      )
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
      // ---------------- 导出
      BackupCard(title = "导出备份") {
        BackupSection.entries.forEach { section ->
          BackupSectionRow(
            section = section,
            checked = section in checked,
            onToggle = {
              checked = if (section in checked) checked - section else checked + section
            }
          )
        }
        BackupDivider()
        PasswordField(
          label = "备份口令",
          value = password,
          onValueChange = { password = it }
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
          label = "确认口令",
          summary = if (confirm.isNotEmpty() && confirm != password) "两次输入不一致" else "再输入一次",
          value = confirm,
          onValueChange = { confirm = it },
          error = confirm.isNotEmpty() && confirm != password
        )
        Spacer(Modifier.height(16.dp))
        BackupButton(text = if (busy) "正在备份..." else "开始备份", enabled = canExport, onClick = { startExport() })
      }

      Spacer(Modifier.height(12.dp))

      // ---------------- 恢复
      BackupCard(title = "从备份恢复") {
        BackupButton(text = "选择备份文件", enabled = !busy) {
          importLauncher.launch(arrayOf("*/*"))
        }
        if (listLoading) {
          BackupHint("正在扫描本机旧备份...")
        } else if (items.isEmpty()) {
          BackupHint("本机旧目录里没有备份文件")
        } else {
          BackupDivider()
          items.forEachIndexed { index, item ->
            if (index > 0) BackupDivider()
            BackupFileRow(item) { restoring = item }
          }
          Spacer(Modifier.height(8.dp))
        }
        BackupButton(text = "重新扫描本机旧备份", enabled = !listLoading) { listKey++ }
      }
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
        Text(current.body, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
      }
    )
  }

  val target = restoring
  if (target != null) {
    RestoreDialog(
      item = target,
      onDismiss = { restoring = null },
      onRestored = { result ->
        restoring = null
        message = BackupMessage("恢复完成", result)
        // 磁盘上的数据已经变了,让 ViewModel 重新读一遍
        onRestored()
      }
    )
  }
}

private class BackupMessage(val title: String, val body: String)

/** 口令规则:恰好 6 位纯数字。 */
internal fun isBackupPassword(text: String): Boolean =
  text.length == PASSWORD_LENGTH && text.all { it in '0'..'9' }

@Composable
private fun BackupCard(
  title: String,
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 16.dp, vertical = 14.dp)
  ) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary)
    Spacer(Modifier.height(8.dp))
    content()
  }
}

@Composable
private fun BackupSectionRow(section: BackupSection, checked: Boolean, onToggle: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(section.label, fontSize = 15.sp, color = AppTheme.colors.textPrimary)
    }
    Spacer(Modifier.width(12.dp))
    // 勾选框只做展示,点击交给整行
    Checkbox(
      checked = checked,
      onCheckedChange = null,
      colors = CheckboxDefaults.colors(
        checkedColor = AppTheme.colors.iconCurrent,
        uncheckedColor = AppTheme.colors.textSecondary,
        checkmarkColor = AppTheme.colors.onFab
      )
    )
  }
}

@Composable
private fun PasswordField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  error: Boolean = false,
  summary: String? = null
) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.width(84.dp), fontSize = 15.sp, color = AppTheme.colors.textPrimary)
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(8.dp))
          .background(AppTheme.colors.textFieldBackground)
          .padding(horizontal = 12.dp, vertical = 8.dp)
      ) {
        if (value.isEmpty()) {
          Text(
            "•".repeat(PASSWORD_LENGTH),
            fontSize = 16.sp,
            color = AppTheme.colors.more
          )
        }
        BasicTextField(
          value = value,
          onValueChange = { input ->
            // 只收数字,且最多 6 位
            onValueChange(input.filter { it in '0'..'9' }.take(PASSWORD_LENGTH))
          },
          modifier = Modifier.fillMaxWidth(),
          textStyle = TextStyle(fontSize = 16.sp, color = AppTheme.colors.textPrimary),
          singleLine = true,
          cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Next
          )
        )
      }
    }
    if (summary != null) {
      Text(
        summary,
        Modifier.padding(top = 4.dp, start = 84.dp),
        fontSize = 12.sp,
        color = if (error) AppTheme.colors.badge else AppTheme.colors.textSecondary
      )
    }
  }
}

@Composable
private fun BackupButton(text: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(if (enabled) AppTheme.colors.iconCurrent else AppTheme.colors.chatListDivider)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = 10.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text,
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
      color = if (enabled) AppTheme.colors.onFab else AppTheme.colors.textSecondary
    )
  }
}

@Composable
private fun BackupHint(text: String) {
  Text(
    text,
    Modifier
      .fillMaxWidth()
      .padding(vertical = 12.dp),
    fontSize = 13.sp,
    color = AppTheme.colors.textSecondary,
    textAlign = TextAlign.Center
  )
}

@Composable
private fun BackupDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

@Composable
private fun BackupFileRow(item: BackupItem, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(enabled = item.preview != null, onClick = onClick)
      .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        item.document.name,
        fontSize = 14.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(Modifier.height(2.dp))
      val detail = item.preview?.let { preview ->
        preview.exportedAt + " · " + preview.sections.joinToString("/") { it.label } + "\n" +
            preview.counts.summary()
      } ?: (item.error ?: "无法读取")
      Text(detail, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
    }
    Spacer(Modifier.width(8.dp))
    Text(
      item.preview?.let { formatBytes(it.size) } ?: "",
      fontSize = 12.sp,
      color = AppTheme.colors.more
    )
  }
}

private fun formatBytes(bytes: Long): String = when {
  bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
  bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
  else -> "$bytes B"
}

/**
 * 恢复确认框:输入口令 → 选合并 / 覆盖 → 执行。
 *
 * 解密要跑 PBKDF2(20 万次迭代),放在 IO 线程,期间按钮显示「正在恢复...」并且不可再点。
 */
@Composable
private fun RestoreDialog(
  item: BackupItem,
  onDismiss: () -> Unit,
  onRestored: (String) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var password by remember { mutableStateOf("") }
  var mode by remember { mutableStateOf(RestoreMode.Merge) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  val preview = item.preview
  val ready = isBackupPassword(password) && preview != null && !busy

  fun startRestore() {
    if (!ready) return
    busy = true
    error = null
    scope.launch {
      val outcome = runCatching {
        withContext(Dispatchers.IO) { BackupService.restore(context, item.document, password, mode) }
      }
      busy = false
      outcome.fold(
        onSuccess = { result -> onRestored("${mode.label}完成\n${result.summary()}") },
        onFailure = { e ->
          error = when (e) {
            is BackupCryptoException -> e.message ?: "口令不正确"
            is BackupFormatException -> e.message ?: "备份文件不合法"
            else -> e.message ?: "恢复失败"
          }
        }
      )
    }
  }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("恢复备份", fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
    text = {
      Column {
        if (preview != null) {
          Text(
            "${preview.exportedAt} · 应用版本 ${preview.appVersion.ifEmpty { "未知" }}",
            fontSize = 12.sp,
            color = AppTheme.colors.textSecondary
          )
          Spacer(Modifier.height(4.dp))
          Text(preview.counts.summary(), fontSize = 13.sp, color = AppTheme.colors.textPrimary)
          Spacer(Modifier.height(12.dp))
        }
        PasswordField(
          label = "备份口令",
          value = password,
          onValueChange = { password = it; error = null }
        )
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
          when (mode) {
            RestoreMode.Merge -> "合并:保留本机已有数据,只补充备份里多出来的角色、会话消息与 KEY"
            RestoreMode.Replace -> "覆盖:本机多出来的角色、会话与 KEY 会被清掉,只留备份里的内容"
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
          .clickable(enabled = ready) { startRestore() }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 15.sp,
        color = if (ready) AppTheme.colors.iconCurrent else AppTheme.colors.more
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
