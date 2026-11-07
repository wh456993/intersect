package com.reimei.intersect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局圆角规格。
 *
 * 界面上的卡片、列表分组、输入框都取 medium,小控件(标签、徽标)取 small;
 * 对话气泡与输入框有自己的圆角,不走这里。大块容器保持直角,贴合整屏的观感。
 */
val AppShapes = Shapes(
  small = RoundedCornerShape(3.dp),
  medium = RoundedCornerShape(10.dp),
  large = RoundedCornerShape(18.dp),
)
