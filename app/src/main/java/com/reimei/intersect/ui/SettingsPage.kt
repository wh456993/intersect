package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.data.BackgroundSetting
import com.reimei.intersect.data.DevAccess
import com.reimei.intersect.data.LangMode
import com.reimei.intersect.data.LangSetting
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.ThemeMode
import com.reimei.intersect.data.ThemeSetting
import com.reimei.intersect.i18n.t
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 「设置」页。
 *
 * 语言与明暗都只显示当前值,点进去是同一个版式的选择页:
 * [LanguagePage](中文 / English / 跟随系统)与 [ThemePage](浅色 / 深色 / 跟随系统)。
 * 后台常驻与最近任务隐藏在改动的当下就去启停服务 / 调整任务卡片;
 * 最后一项是开发者入口,点进去要先过口令校验才看得到内容。
 */
@Composable
fun SettingsPage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  onOpenDeveloper: () -> Unit
) {
  BackHandler { onBack() }
  val context = LocalContext.current
  // 语言 / 外观两个选择页都盖在设置页之上,按这两个状态推入 / 退出
  var showLanguage by remember { mutableStateOf(false) }
  var showTheme by remember { mutableStateOf(false) }

  Box(modifier.fillMaxSize()) {
    Column(
      Modifier
        .fillMaxSize()
        .blockTouchesThrough()
        .background(AppTheme.colors.background)
    ) {
      TitleBar(title = "设置", onBack = onBack)
      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        // 语言:只显示当前值,点进去选。做成开关会逼用户在「开 / 关」和具体语言之间换算
        SettingsRow(
          title = "界面语言",
          value = langLabel(),
          onClick = { showLanguage = true }
        )
        ChoiceDivider()
        // 明暗与语言一样点进去选,不在设置页里摊开三个选项
        SettingsRow(
          title = "外观",
          value = themeLabel(),
          onClick = { showTheme = true }
        )
        SettingsDivider()
        SettingsSwitchRow(
          title = "后台常驻",
          checked = BackgroundSetting.keepAlive,
          onCheckedChange = { BackgroundSetting.setKeepAlive(context, it) }
        )
        SettingsDivider()
        SettingsSwitchRow(
          title = "从最近任务隐藏",
          checked = BackgroundSetting.hideFromRecents,
          onCheckedChange = { BackgroundSetting.setHideFromRecents(context, it) }
        )
        SettingsDivider()
        SettingsRow(title = "开发者", onClick = onOpenDeveloper)
      }
    }

    SlideOverlay(payload = Unit.takeIf { showLanguage }) {
      LanguagePage(onBack = { showLanguage = false })
    }
    SlideOverlay(payload = Unit.takeIf { showTheme }) {
      ThemePage(onBack = { showTheme = false })
    }
  }
}

/** 当前语言的显示名:跟随系统时不显示具体语言,只说明它跟着系统走。 */
@Composable
private fun langLabel(): String = when (LangSetting.mode) {
  LangMode.Chinese -> "中文"
  LangMode.English -> "English"
  LangMode.System -> t("跟随系统")
}

/** 当前明暗的显示名。 */
@Composable
private fun themeLabel(): String = when (ThemeSetting.mode) {
  ThemeMode.Light -> t("浅色模式")
  ThemeMode.Dark -> t("深色模式")
  ThemeMode.System -> t("跟随系统")
}

@Composable
private fun SettingsDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    }
    Spacer(Modifier.width(12.dp))
    // 开关只做展示,点击交给整行,避免行内区域出现「点了没反应」的死角
    Switch(
      checked = checked,
      onCheckedChange = null,
      colors = SwitchDefaults.colors(
        checkedThumbColor = AppTheme.colors.onFab,
        checkedTrackColor = AppTheme.colors.iconCurrent,
        uncheckedThumbColor = AppTheme.colors.textSecondary,
        uncheckedTrackColor = AppTheme.colors.chatListDivider
      )
    )
  }
}

@Composable
private fun SettingsRow(title: String, value: String? = null, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      title,
      Modifier.weight(1f),
      fontSize = 16.sp,
      color = AppTheme.colors.textPrimary
    )
    if (value != null) {
      // 右侧先给当前值,再给箭头:一眼看出现在是哪种语言,不用点进去才知道
      Text(
        value,
        Modifier.padding(end = 8.dp),
        fontSize = 14.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(14.dp),
      tint = AppTheme.colors.more
    )
  }
}

/**
 * 开发者页:8 位口令验权,过了才进控制台。
 *
 * 口令不落在源码里,只用 [DevAccess] 做哈希比对;控制台状态只属于本次进入,退出即需要重新验证。
 */
@Composable
fun DeveloperPage(
  modifier: Modifier = Modifier,
  intersectMode: RoleplayMode,
  onSwitchIntersectMode: (RoleplayMode) -> Unit,
  onBack: () -> Unit
) {
  var unlocked by remember { mutableStateOf(false) }
  if (unlocked) {
    DevConsole(
      modifier,
      onBack = onBack,
      intersectMode = intersectMode,
      onSwitchIntersectMode = onSwitchIntersectMode
    )
  } else {
    LockedDeveloperPage(modifier, onBack = onBack, onUnlock = { unlocked = true })
  }
}

@Composable
private fun LockedDeveloperPage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  onUnlock: () -> Unit
) {
  BackHandler { onBack() }

  var input by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  fun submit() {
    if (DevAccess.verify(input)) {
      error = null
      onUnlock()
    } else {
      error = "口令不正确"
      input = ""
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "开发者", onBack = onBack)
    Column(
      Modifier
        .fillMaxSize()
        .imePadding()
        .padding(horizontal = 12.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Column(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
          .padding(horizontal = 16.dp, vertical = 16.dp)
      ) {
        Text("开发者口令", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          BasicTextField(
            value = input,
            onValueChange = {
              if (it.length <= DevAccess.PASSWORD_LENGTH) {
                input = it
                error = null
              }
            },
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
              fontSize = 16.sp,
              color = AppTheme.colors.textPrimary,
              textAlign = TextAlign.Start
            ),
            singleLine = true,
            cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() })
          )
          Spacer(Modifier.width(12.dp))
          Box(
            Modifier
              .clip(RoundedCornerShape(8.dp))
              .background(AppTheme.colors.iconCurrent)
              .clickable(enabled = input.isNotEmpty()) { submit() }
              .padding(horizontal = 16.dp, vertical = 8.dp)
          ) {
            Text(
              "验证",
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              color = AppTheme.colors.listItem
            )
          }
        }
        if (error != null) {
          Text(
            error!!,
            Modifier.padding(top = 8.dp),
            fontSize = 12.sp,
            color = AppTheme.colors.badge
          )
        }
      }
    }
  }
}
