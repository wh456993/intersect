package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reimei.intersect.R
import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.Chat
import com.reimei.intersect.data.DawnAssistant
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.User
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 聊天信息页:查聊天记录、改角色设定、换模型、删好友。
 *
 * 顶部名片是一张可点的入口:点进去是该角色的设置页(名称 / 身份 / 性格)。
 * [apiKey] 是这个会话实际生效的那条 API KEY(未关联时为列表里第一条),
 * 选择面板里点哪条就通过 [onPickApiKey] 回传;传 null 表示改回跟随默认。
 */
@Composable
fun ChatInfoPage(
  modifier: Modifier = Modifier,
  chat: Chat,
  apiKeys: List<ApiKey>,
  apiKey: ApiKey?,
  onPickApiKey: (ApiKey?) -> Unit,
  onOpenCharacter: () -> Unit,
  onBack: () -> Unit,
  onDelete: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var confirmDelete by remember { mutableStateOf(false) }
  var pickModel by remember { mutableStateOf(false) }

  BackHandler { onBack() }

  // 关键词非空时展示命中的聊天记录。
  // 按「关键词 + 消息条数」缓存:每次重组都扫一遍全部消息在长会话里很贵。
  val keyword = query.trim()
  val matched: List<Msg> = remember(keyword, chat.msgs.size) {
    if (keyword.isEmpty()) {
      emptyList()
    } else {
      chat.msgs.filter { it.text.contains(keyword, ignoreCase = true) }
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "聊天信息",
      actionText = "",
      actionEnabled = false,
      onBack = onBack,
      onAction = {}
    )

    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      Row(
        Modifier
          .fillMaxWidth()
          .clickable(onClick = onOpenCharacter)
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        UserAvatar(chat.friend, size = 52.dp)
        Column(
          Modifier
            .weight(1f)
            .padding(start = 12.dp)
        ) {
          Text(
            chat.friend.name,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          val signature = chat.friend.signature
          Text(
            signature?.takeIf { it.isNotBlank() } ?: "这个人很懒,什么都没写",
            Modifier.padding(top = 4.dp),
            fontSize = 12.sp,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(Modifier.width(8.dp))
        // 名片本身就是入口:右侧的箭头表示点它能进角色设置
        Icon(
          painterResource(R.drawable.ui_chevron_right),
          contentDescription = null,
          Modifier.size(14.dp),
          tint = AppTheme.colors.more
        )
      }
    }

    Spacer(Modifier.height(12.dp))

    // 这个会话用哪个模型。模型跟着 API KEY 走,所以这一行本质上是在选一条 KEY
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      ChatModelRow(
        apiKey = apiKey,
        // 关联的那条被删掉时会回落到第一条,这时也算跟随默认
        followDefault = apiKey == null || chat.apiKeyId != apiKey.id,
        enabled = apiKeys.isNotEmpty()
      ) { pickModel = true }
    }

    Spacer(Modifier.height(12.dp))

    // 查询聊天记录
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      Row(
        Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("查询聊天记录", Modifier.width(96.dp), fontSize = 15.sp, color = AppTheme.colors.textPrimary)
        BasicTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.textFieldBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
          textStyle = TextStyle(fontSize = 14.sp, color = AppTheme.colors.textPrimary),
          singleLine = true,
          cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
          decorationBox = { inner ->
            if (query.isEmpty()) {
              Text("输入关键词", fontSize = 14.sp, color = AppTheme.colors.more)
            }
            inner()
          }
        )
      }
      if (keyword.isNotEmpty()) {
        Box(
          Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(AppTheme.colors.chatListDivider)
        )
        Text(
          if (matched.isEmpty()) "没有找到包含「$keyword」的记录" else "找到 ${matched.size} 条记录",
          Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary
        )
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
          items(matched) { msg ->
            MessageSearchItem(msg)
          }
        }
        Spacer(Modifier.height(8.dp))
      }
    }

    Spacer(Modifier.height(12.dp))

    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      if (DawnAssistant.isLocked(chat.friend.id)) {
        // 内置角色不允许删除
        Text(
          "${DawnAssistant.NAME}是系统内置角色,不能删除",
          Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
          fontSize = 14.sp,
          color = AppTheme.colors.textSecondary
        )
      } else {
        DangerRow("删除该好友", enabled = !confirmDelete) { confirmDelete = true }
      }
    }
  }

  if (pickModel) {
    ApiKeyPicker(
      apiKeys = apiKeys,
      selectedId = chat.apiKeyId,
      onPick = { picked ->
        pickModel = false
        onPickApiKey(picked)
      },
      onDismiss = { pickModel = false }
    )
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("删除好友") },
      text = { Text("会同时清除与该好友的聊天记录,且无法恢复。") },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = false
          onDelete()
        }) { Text("删除", color = AppTheme.colors.badge) }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = false }) { Text("取消") }
      }
    )
  }
}

@Composable
private fun MessageSearchItem(msg: Msg) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.Top
  ) {
    UserAvatar(msg.from, size = 28.dp)
    Column(
      Modifier
        .weight(1f)
        .padding(start = 10.dp)
    ) {
      Text(
        if (msg.from.id == User.Me.id) "我" else msg.from.name,
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(
        msg.text,
        Modifier.padding(top = 2.dp),
        fontSize = 14.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * 「模型」一行:主文字是这个会话实际生效的模型,副文字是它来自哪条 API KEY。
 *
 * 一条 API KEY 都没注册时这一行不给点(点了也没得选),直接说明原因。
 */
@Composable
private fun ChatModelRow(
  apiKey: ApiKey?,
  followDefault: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled, onClick = onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text("模型", Modifier.width(96.dp), fontSize = 15.sp, color = AppTheme.colors.textPrimary)
    Column(Modifier.weight(1f)) {
      Text(
        when {
          apiKey == null -> "还没有注册 API KEY"
          apiKey.model.isBlank() -> "未填写模型"
          else -> apiKey.model
        },
        fontSize = 15.sp,
        color = if (apiKey == null) AppTheme.colors.textSecondary else AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (apiKey != null) {
        Text(
          apiKey.name.ifEmpty { "未命名" } + if (followDefault) " · 跟随默认" else "",
          Modifier.padding(top = 4.dp),
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    if (enabled) {
      Icon(
        painterResource(R.drawable.ui_chevron_right),
        contentDescription = null,
        Modifier.size(14.dp),
        tint = AppTheme.colors.more
      )
    }
  }
}

/**
 * 模型 / API KEY 选择面板:底部弹出。
 *
 * 每一项就是一条已注册的 API KEY(模型跟着 KEY 走),另外给一条「跟随默认」——
 * 它不写死具体某条,列表第一条换了它也跟着换,新建的会话默认就走这一档。
 */
@Composable
private fun ApiKeyPicker(
  apiKeys: List<ApiKey>,
  selectedId: String?,
  onPick: (ApiKey?) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color(0x66000000))
        .clickable(onClick = onDismiss),
      contentAlignment = Alignment.BottomCenter
    ) {
      Column(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
          .background(AppTheme.colors.listItem)
          .clickable(enabled = false) {}
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 16.dp)
      ) {
        Text(
          "选择模型",
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.textPrimary
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
          item(key = "follow_default") {
            ApiKeyOption(
              name = "跟随默认",
              detail = apiKeys.firstOrNull()?.model?.takeIf { it.isNotBlank() } ?: "",
              selected = selectedId == null,
              onClick = { onPick(null) }
            )
          }
          items(apiKeys, key = { it.id }) { key ->
            ApiKeyOption(
              name = key.name.ifEmpty { "未命名" },
              detail = key.model.ifBlank { "未填写模型" },
              selected = key.id == selectedId,
              onClick = { onPick(key) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ApiKeyOption(
  name: String,
  detail: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        name,
        fontSize = 15.sp,
        color = if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (detail.isNotEmpty()) {
        Text(
          detail,
          Modifier.padding(top = 2.dp),
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    if (selected) {
      Text("当前", fontSize = 12.sp, color = AppTheme.colors.iconCurrent)
    }
  }
}

@Composable
private fun DangerRow(text: String, enabled: Boolean, onClick: () -> Unit) {
  Text(
    text,
    Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 16.dp),
    fontSize = 16.sp,
    color = if (enabled) AppTheme.colors.badge else AppTheme.colors.more
  )
}
