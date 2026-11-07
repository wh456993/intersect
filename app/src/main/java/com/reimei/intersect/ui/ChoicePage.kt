package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme

/** 选项页里的一行:标题 + 是否选中 + 点了做什么。 */
internal class ChoiceOption(
  val title: String,
  val selected: Boolean,
  val onClick: () -> Unit
)

/**
 * 通用「几选一」页:语言、明暗都用它,样子和交互保持一致。
 *
 * 选中即生效(调用方在 [ChoiceOption.onClick] 里落盘并更新状态),不需要再点保存。
 */
@Composable
internal fun ChoicePage(
  title: String,
  options: List<ChoiceOption>,
  modifier: Modifier = Modifier,
  onBack: () -> Unit
) {
  BackHandler { onBack() }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = title, onBack = onBack)
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      options.forEachIndexed { index, option ->
        if (index > 0) ChoiceDivider()
        ChoiceRow(
          title = option.title,
          selected = option.selected,
          onClick = option.onClick
        )
      }
    }
  }
}

/** 单行选项:左边标题,右边选中打勾。整行可点,点已选中的项也无副作用。 */
@Composable
internal fun ChoiceRow(
  title: String,
  selected: Boolean,
  onClick: () -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    }
    Spacer(Modifier.width(12.dp))
    if (selected) {
      Icon(
        Icons.Filled.Check,
        contentDescription = null,
        Modifier.size(20.dp),
        tint = AppTheme.colors.iconCurrent
      )
    } else {
      // 未选中时也占同样宽度,避免选中前后文字左右跳动
      Spacer(Modifier.size(20.dp))
    }
  }
}

@Composable
internal fun ChoiceDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}
