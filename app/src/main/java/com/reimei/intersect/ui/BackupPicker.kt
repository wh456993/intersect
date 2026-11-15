package com.reimei.intersect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.BackupPickItem
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 导出前的选择面板:**一次只能选一个角色 / 一条会话**。
 *
 * 单选是刻意的:导出的文件名就是「角色名 + 内容类别 + 时间」,一份文件对应一个角色,
 * 在文件管理器里一眼就能看出这是谁的备份(多选就没法用一个名字概括了)。
 * 要搬整台设备的数据,走备份页的「软件备份」整包导出。
 *
 * 选择状态放在对话框内部([remember] 挂在调用点的 composition 上,重新打开时是全新的空选择,
 * 用户上一次点过的那个不会阴魂不散地留在下一次导出里)。
 *
 * [onConfirm] 只在选中一项时才可能被调到:什么都没选时确认按钮是灰的 ——
 * 导出空备份没有意义。
 */
@Composable
internal fun ExportPickerDialog(
  title: String,
  items: List<BackupPickItem>,
  previewFallback: String,
  busy: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (BackupPickItem) -> Unit,
) {
  var pickedId by remember { mutableStateOf<String?>(null) }
  val picked = items.firstOrNull { it.id == pickedId }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = {
      Text(title, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    },
    text = {
      if (items.isEmpty()) {
        Text("这里还没有可以导出的内容。", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
      } else {
        // 列表自己滚:几百个会话也不能把对话框顶出屏幕
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
          items(items, key = { it.id }) { item ->
            PickRow(
              item = item,
              checked = item.id == pickedId,
              previewFallback = previewFallback,
              enabled = !busy,
              onPick = { pickedId = item.id }
            )
          }
        }
      }
    },
    confirmButton = {
      Text(
        if (busy) "正在导出..." else "导出",
        Modifier
          .clickable(enabled = !busy && picked != null) { picked?.let(onConfirm) }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 15.sp,
        color = if (busy || picked == null) AppTheme.colors.more else AppTheme.colors.iconCurrent
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

/** 面板里的一行:单选圆点 + 头像 + 名字(+ 会话的最后一条消息)。整行都能点。 */
@Composable
private fun PickRow(
  item: BackupPickItem,
  checked: Boolean,
  previewFallback: String,
  enabled: Boolean,
  onPick: () -> Unit,
) {
  // 角色卡没有副标题(previewFallback 给空串);会话没消息时用一句话顶上,免得只看到一个名字
  val subtitle = if (previewFallback.isEmpty()) null else item.preview ?: previewFallback
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable(enabled = enabled, onClick = onPick)
      .padding(vertical = 8.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start
  ) {
    PickRadio(checked)
    Spacer(Modifier.width(10.dp))
    UserAvatar(item.user, size = 36.dp)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(
        item.user.name,
        fontSize = 15.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (subtitle != null) {
        Spacer(Modifier.height(2.dp))
        Text(
          subtitle,
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/** 自绘单选圆点:与用量页同款思路,不引 Material 的 RadioButton(它在暗色下对比度偏低)。 */
@Composable
private fun PickRadio(checked: Boolean) {
  Box(
    Modifier
      .size(18.dp)
      .clip(CircleShape)
      .background(if (checked) AppTheme.colors.iconCurrent else AppTheme.colors.chatListDivider),
    contentAlignment = Alignment.Center
  ) {
    if (checked) {
      Box(
        Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(AppTheme.colors.listItem)
      )
    }
  }
}
