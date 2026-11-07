package com.reimei.intersect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.reimei.intersect.data.ThemeMode
import com.reimei.intersect.data.ThemeSetting

private val LightColorPalette = AppColors(
  bottomBar = white1,
  background = white2,
  listItem = white,
  chatListDivider = white3,
  chatPage = white2,
  textPrimary = black3,
  textPrimaryMe = black3,
  textSecondary = grey1,
  onBackground = grey3,
  icon = black,
  iconCurrent = green3,
  badge = red1,
  onBadge = white,
  bubbleMe = green1,
  bubbleOthers = white,
  textFieldBackground = white,
  more = grey4,
  fab = blue1,
  onFab = white,
  isDark = false,
)

private val DarkColorPalette = AppColors(
  bottomBar = night3,
  background = night1,
  listItem = night2,
  chatListDivider = night4,
  chatPage = night1,
  textPrimary = nightText,
  textPrimaryMe = nightOnGreen,
  textSecondary = nightTextSecondary,
  onBackground = nightTextSection,
  icon = nightIcon,
  iconCurrent = green3,
  badge = red1,
  onBadge = white,
  bubbleMe = nightGreen,
  bubbleOthers = night4,
  textFieldBackground = night3,
  more = nightMore,
  fab = blue1,
  onFab = white,
  isDark = true,
)

private val LightMaterialColors = lightColors(
  primary = green3,
  primaryVariant = green3,
  secondary = green3,
  background = white2,
  surface = white,
  onPrimary = white,
  onSecondary = white,
  onBackground = black3,
  onSurface = black3,
  error = red1,
)

private val DarkMaterialColors = darkColors(
  primary = green3,
  primaryVariant = green3,
  secondary = green3,
  background = night1,
  surface = night3,
  onPrimary = white,
  onSecondary = white,
  onBackground = nightText,
  onSurface = nightText,
  error = red1,
)

private val LocalWeColors = staticCompositionLocalOf { LightColorPalette }

object AppTheme {
  val colors: AppColors
    @Composable
    get() = LocalWeColors.current
}

class AppColors(
  val bottomBar: Color,
  val background: Color,
  val listItem: Color,
  val chatListDivider: Color,
  val chatPage: Color,
  val textPrimary: Color,
  val textPrimaryMe: Color,
  val textSecondary: Color,
  val onBackground: Color,
  val icon: Color,
  val iconCurrent: Color,
  val badge: Color,
  val onBadge: Color,
  val bubbleMe: Color,
  val bubbleOthers: Color,
  val textFieldBackground: Color,
  val more: Color,
  val fab: Color,
  val onFab: Color,
  /** 当前调色板是不是暗色。需要按明暗挑不同色值时用它,而不是各自去读设置。 */
  val isDark: Boolean,
)

/**
 * 应用主题。
 *
 * 明暗由设置里的 [ThemeSetting.mode] 决定(浅色 / 深色 / 跟随系统),
 * 两套调色板之外,也给 MaterialTheme 传一份对应明暗的配色,
 * 否则 AlertDialog 这类内部只认 MaterialTheme 的组件在暗色下还是白底浅字。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
  val dark = isDarkTheme()
  CompositionLocalProvider(LocalWeColors provides if (dark) DarkColorPalette else LightColorPalette) {
    MaterialTheme(
      colors = if (dark) DarkMaterialColors else LightMaterialColors,
      shapes = AppShapes
    ) {
      content()
    }
  }
}

/**
 * 此刻是不是暗色。跟随系统时读系统的夜间模式,系统切换会触发重组。
 *
 * 状态栏 / 导航栏图标颜色之类在组合之外做不了的事,用它在组合里先算好再传出去。
 */
@Composable
fun isDarkTheme(): Boolean = when (ThemeSetting.mode) {
  ThemeMode.System -> isSystemInDarkTheme()
  ThemeMode.Dark -> true
  ThemeMode.Light -> false
}
