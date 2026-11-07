package com.reimei.intersect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.User
import com.reimei.intersect.ui.theme.AppTheme

@Composable
fun ContactListTopBar() {
  TitleBar(title = "通讯录")
}

@Composable
fun ContactListItem(
  contact: User,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 2.dp)
  ) {
    UserAvatar(
      contact,
      modifier = Modifier.padding(12.dp, 8.dp, 8.dp, 8.dp),
      size = 36.dp
    )
    Text(
      contact.name,
      Modifier
        .weight(1f)
        .align(Alignment.CenterVertically),
      fontSize = 17.sp,
      color = AppTheme.colors.textPrimary
    )
  }
}

@Composable
fun PeopleList(viewModel: MainViewModel = viewModel()) {
  val context = LocalContext.current
  Column(Modifier.fillMaxSize()) {
    ContactListTopBar()
    Box(
      Modifier
        .background(AppTheme.colors.background)
        .fillMaxSize()
    ) {
      PeopleList(
        contacts = viewModel.contacts,
        onContactClick = { viewModel.startChatWith(context, it) }
      )
      FloatingActionButton(
        onClick = { viewModel.openAddFriend() },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(start = 24.dp, end = 24.dp, bottom = 48.dp)
          .size(64.dp),
        backgroundColor = AppTheme.colors.fab,
        contentColor = AppTheme.colors.onFab
      ) {
        Icon(Icons.Filled.Add, contentDescription = "添加", modifier = Modifier.size(30.dp))
      }
    }
  }
}

@Composable
fun PeopleList(
  contacts: List<User>,
  onContactClick: (User) -> Unit = {},
) {
  LazyColumn(
    Modifier
      .background(AppTheme.colors.listItem)
      .fillMaxWidth()
  ) {
    item {
      Text(
        "朋友",
        Modifier
          .background(AppTheme.colors.background)
          .fillMaxWidth()
          .padding(12.dp, 8.dp),
        fontSize = 14.sp,
        color = AppTheme.colors.onBackground
      )
    }
    itemsIndexed(contacts) { index, contact ->
      ContactListItem(contact, onClick = { onContactClick(contact) })
      if (index < contacts.size - 1) {
        Divider(startIndent = 56.dp, color = AppTheme.colors.chatListDivider, thickness = 0.8f.dp)
      }
    }
  }
}
