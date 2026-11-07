package com.reimei.intersect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Divider
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.Chat
import com.reimei.intersect.ui.theme.AppTheme
import com.reimei.intersect.unread

@Composable
fun ChatListTopBar() {
  // 语言与明暗都只在「设置」里改,标题栏不再挂切换按钮
  TitleBar(title = "intersect")
}

@Composable
fun SessionRow(
  chat: Chat,
  modifier: Modifier = Modifier,
  viewModel: MainViewModel = viewModel(),
) {
  val last = chat.msgs.lastOrNull()
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = {
        viewModel.startChat(chat)
      })
  ) {
    UserAvatar(
      chat.friend,
      modifier = Modifier
        .padding(12.dp, 8.dp, 8.dp, 8.dp)
        .unread(last?.read ?: true, AppTheme.colors.badge),
      size = 48.dp
    )
    Column(
      Modifier
        .weight(1f)
        .align(Alignment.CenterVertically)
    ) {
      Text(
        chat.friend.name,
        fontSize = 17.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        when {
          last == null -> "还没有聊天记录"
          else -> last.text
        },
        fontSize = 14.sp,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    Text(
      "13:48",
      Modifier.padding(8.dp, 8.dp, 12.dp, 8.dp),
      fontSize = 11.sp, color = AppTheme.colors.textSecondary
    )
  }
}

@Composable
fun SessionList(viewModel: MainViewModel = viewModel()) {
  Column(Modifier.fillMaxSize()) {
    ChatListTopBar()
    Box(
      Modifier
        .background(AppTheme.colors.background)
        .fillMaxSize()
    ) {
      SessionList(chats = viewModel.chats)
    }
  }
}

@Composable
fun SessionList(chats: List<Chat>) {
  LazyColumn(
    Modifier
      .background(AppTheme.colors.listItem)
      .fillMaxWidth()
  ) {
    itemsIndexed(chats) { index, chat ->
      SessionRow(chat)
      if (index < chats.size - 1) {
        Divider(startIndent = 68.dp, color = AppTheme.colors.chatListDivider, thickness = 0.8f.dp)
      }
    }
  }
}
