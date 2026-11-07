package com.reimei.intersect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.reimei.intersect.data.LangMode
import com.reimei.intersect.data.LangSetting

/**
 * 语言选择页:中文 / English / 跟随系统,三选一。
 *
 * 两个入口共用这一页:激活流程右上角的小地球,以及「设置 → 界面语言」。
 * 选中即生效(改的是 [LangSetting],界面立刻按新语言重组),不需要再点保存。
 */
@Composable
fun LanguagePage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit
) {
  val context = LocalContext.current

  ChoicePage(
    title = "界面语言",
    options = listOf(
      ChoiceOption(
        // 语言名用各自的母语写法,切到英文之后也认得出哪一项是中文
        title = "中文",
        selected = LangSetting.mode == LangMode.Chinese,
        onClick = { LangSetting.setMode(context, LangMode.Chinese) }
      ),
      ChoiceOption(
        title = "English",
        selected = LangSetting.mode == LangMode.English,
        onClick = { LangSetting.setMode(context, LangMode.English) }
      ),
      ChoiceOption(
        title = "跟随系统",
        selected = LangSetting.mode == LangMode.System,
        onClick = { LangSetting.setMode(context, LangMode.System) }
      )
    ),
    modifier = modifier,
    onBack = onBack
  )
}
