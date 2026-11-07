package com.reimei.intersect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.reimei.intersect.data.ThemeMode
import com.reimei.intersect.data.ThemeSetting

/**
 * 外观选择页:浅色模式 / 深色模式 / 跟随系统,三选一。
 *
 * 与 [LanguagePage] 同一套版式,入口只有「设置 → 外观」。
 * 选中即生效(改的是 [ThemeSetting],整个界面按新明暗重组),不需要再点保存。
 */
@Composable
fun ThemePage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit
) {
  val context = LocalContext.current

  ChoicePage(
    title = "外观",
    options = listOf(
      ChoiceOption(
        title = "浅色模式",
        selected = ThemeSetting.mode == ThemeMode.Light,
        onClick = { ThemeSetting.setMode(context, ThemeMode.Light) }
      ),
      ChoiceOption(
        title = "深色模式",
        selected = ThemeSetting.mode == ThemeMode.Dark,
        onClick = { ThemeSetting.setMode(context, ThemeMode.Dark) }
      ),
      ChoiceOption(
        title = "跟随系统",
        selected = ThemeSetting.mode == ThemeMode.System,
        onClick = { ThemeSetting.setMode(context, ThemeMode.System) }
      )
    ),
    modifier = modifier,
    onBack = onBack
  )
}
