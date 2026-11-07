package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.MeMenu
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 「我」页里各功能入口的二级页。
 *
 * 目前统一是占位:顶栏标题 + 一句「功能开发中」。
 * 每个入口的真实内容后续单独填,先把导航和返回手势打通。
 */
@Composable
fun MeSettingPage(
  modifier: Modifier = Modifier,
  menu: MeMenu,
  onBack: () -> Unit
) {
  BackHandler { onBack() }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = menu.title, onBack = onBack)
    Column(
      Modifier
        .fillMaxSize()
        .padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        "功能开发中",
        fontSize = 16.sp,
        color = AppTheme.colors.textPrimary
      )
    }
  }
}
