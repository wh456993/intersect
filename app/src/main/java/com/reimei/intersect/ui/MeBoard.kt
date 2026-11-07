package com.reimei.intersect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reimei.intersect.R
import com.reimei.intersect.data.MeMenu
import com.reimei.intersect.data.MeProfile
import com.reimei.intersect.data.UserStatus
import com.reimei.intersect.ui.theme.AppTheme

@Composable
fun MeListTopBar(onClick: () -> Unit, onStatusClick: () -> Unit) {
  // 选了状态就把名片底色渐变成该状态的颜色,没选时保持默认。
  // 暗色下直接用状态色当底色会太亮,改成把它按比例叠在列表底色上。
  val base = AppTheme.colors.listItem
  val background by animateColorAsState(
    targetValue = MeProfile.status?.let { status ->
      if (AppTheme.colors.isDark) status.tint.copy(alpha = 0.22f).compositeOver(base)
      else status.background
    } ?: base,
    animationSpec = tween(durationMillis = 420)
  )
  Row(
    Modifier
      .background(background)
      .fillMaxWidth()
      .height(224.dp)
      .statusBarsPadding()
      .clickable(onClick = onClick)
  ) {
    MeAvatar(
      modifier = Modifier
        .align(Alignment.CenterVertically)
        .padding(start = 24.dp),
      size = 64.dp,
      shape = CircleShape
    )
    Column(
      Modifier
        .weight(1f)
        .padding(start = 12.dp)
    ) {
      Text(
        MeProfile.name,
        Modifier.padding(top = 64.dp),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        MeProfile.signature,
        Modifier.padding(top = 8.dp),
        fontSize = 13.sp,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(Modifier.height(12.dp))
      StatusChip(onStatusClick)
    }
    Icon(
      painterResource(R.drawable.ui_chevron_right), contentDescription = "更多",
      Modifier
        .align(Alignment.CenterVertically)
        .padding(end = 16.dp)
        .size(16.dp),
      tint = AppTheme.colors.more
    )
  }
}

/**
 * 状态胶囊:没设过状态时显示「+ 状态」,设过之后换成状态图标 + 文案。
 * 两种形态之间做缩放淡入的过渡,再点就是切换。
 */
@Composable
private fun StatusChip(onClick: () -> Unit) {
  val current = MeProfile.status
  val borderColor = current?.tint ?: AppTheme.colors.onBackground
  AnimatedContent(
    targetState = current,
    transitionSpec = {
      (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.8f)) togetherWith
          (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.9f))
    },
    label = "statusChip"
  ) { status ->
    Row(
      Modifier
        .clip(RoundedCornerShape(50))
        .border(1.dp, borderColor, RoundedCornerShape(50))
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (status == null) {
        Text(
          "+ 状态",
          fontSize = 14.sp,
          color = AppTheme.colors.onBackground
        )
      } else {
        Icon(
          painterResource(status.icon),
          contentDescription = status.label,
          Modifier.size(15.dp),
          tint = status.tint
        )
        Spacer(Modifier.width(5.dp))
        Text(
          status.label,
          fontSize = 14.sp,
          color = status.tint,
          maxLines = 1
        )
      }
    }
  }
}

/** 状态选择面板:底部弹出的网格,选中即生效并收起。 */
@Composable
fun StatusPicker(onDismiss: () -> Unit) {
  val context = LocalContext.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(androidx.compose.ui.graphics.Color(0x66000000))
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
          .padding(horizontal = 12.dp, vertical = 16.dp)
      ) {
        Text(
          "选择状态",
          Modifier.padding(start = 8.dp, bottom = 4.dp),
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.textPrimary
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
          columns = GridCells.Fixed(3),
          modifier = Modifier.heightIn(max = 360.dp)
        ) {
          items(UserStatus.entries) { status ->
            StatusCell(
              status = status,
              selected = MeProfile.status == status,
              onClick = {
                MeProfile.setStatus(context, status)
                onDismiss()
              }
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Text(
          "清除状态",
          Modifier
            .align(Alignment.CenterHorizontally)
            .clip(RoundedCornerShape(50))
            .clickable {
              MeProfile.setStatus(context, null)
              onDismiss()
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
          fontSize = 13.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    }
  }
}

@Composable
private fun StatusCell(status: UserStatus, selected: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .padding(6.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(if (selected) status.background else AppTheme.colors.bottomBar)
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      Modifier
        .size(34.dp)
        .clip(CircleShape)
        .background(if (selected) status.tint else status.background),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        painterResource(status.icon),
        contentDescription = status.label,
        Modifier.size(19.dp),
        tint = if (selected) AppTheme.colors.listItem else status.tint
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
      status.label,
      fontSize = 12.sp,
      color = if (selected) status.tint else AppTheme.colors.textPrimary,
      maxLines = 1
    )
  }
}

@Composable
fun MeBoard(onOpenProfile: () -> Unit, onOpenMenu: (MeMenu) -> Unit = {}) {
  var pickerVisible by remember { mutableStateOf(false) }
  Box(
    Modifier
      .background(AppTheme.colors.background)
      .fillMaxSize()
  ) {
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
    ) {
      MeListTopBar(
        onClick = onOpenProfile,
        onStatusClick = { pickerVisible = true }
      )
      // 名片下方的功能入口,和「个人资料」页一样是一张分组卡片
      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        MeMenu.entries.forEachIndexed { index, menu ->
          if (index > 0) MeMenuDivider()
          MeMenuRow(menu = menu, onClick = { onOpenMenu(menu) })
        }
      }
    }
  }
  if (pickerVisible) {
    StatusPicker(onDismiss = { pickerVisible = false })
  }
}

@Composable
private fun MeMenuDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

@Composable
private fun MeMenuRow(menu: MeMenu, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      menu.title,
      Modifier.weight(1f),
      fontSize = 16.sp,
      color = AppTheme.colors.textPrimary
    )
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(14.dp),
      tint = AppTheme.colors.more
    )
  }
}
