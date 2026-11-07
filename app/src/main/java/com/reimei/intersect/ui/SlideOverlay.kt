package com.reimei.intersect.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * 从右侧推入 / 退出的通用容器。
 *
 * 与原来「animateFloatAsState 算位移 + Modifier.layout 平移」的写法相比,这里有两个要点:
 *
 * 1. 位移只在 draw 阶段读取([graphicsLayer] 的 block),动画期间既不重组也不重新布局。
 *    原来的写法每帧都要给整棵子树重新 measure/layout,推入/退出时掉帧就是从这里来的。
 * 2. 页面是否在组合里由 [payload] 驱动,退出动画期间仍用最后一次非 null 值渲染。
 *    因此关闭瞬间不会先变空白再滑出去,也不需要在外层写 `if (x != null)`。
 *
 * 三条不变式:
 * 1. [onHidden] 只在「曾经可见 → 现在不可见 → 动画结束」时调用一次,首次组合不触发。
 * 2. 逐帧变化的量只在 draw 阶段读取,动画期间不重组、不重新布局。
 * 3. [payload] 为 null 后,退出动画期间仍用最后一次非 null 值渲染,避免滑出空白页。
 * 4. 滑动距离等于容器宽度,不依赖内容是否满宽(根 Box 带 fillMaxSize)。
 *    因此 [modifier] 只该放不改尺寸的修饰符(tag 之类);padding/size 会让 Box 窄于容器,
 *    退出后会留一条边。
 */
@Composable
fun <T : Any> SlideOverlay(
  payload: T?,
  modifier: Modifier = Modifier,
  onHidden: (T) -> Unit = {},
  /** 预测性返回进度,draw 阶段读取;未生效时保持 `{ 0f }` */
  extraOffset: () -> Float = { 0f },
  content: @Composable (T) -> Unit,
) {
  val visible = payload != null
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

  // 最后一次非 null 的 payload:关闭时内容不能立刻变空,否则退出动画滑出的是空白
  var last by remember { mutableStateOf(payload) }
  if (payload != null) last = payload

  val anim = remember { Animatable(if (visible) 0f else 1f) }

  // 组合期写 state 是有意为之:打开必须在当帧挂载,晚一帧会看到闪白。
  // 每次导航只发生一次;改 SideEffect 会晚一帧,不要换。
  var composed by remember { mutableStateOf(visible) }
  if (visible) composed = true

  // 首次组合 LaunchedEffect 必然执行一次;没有门闩会在 visible=false 起步时误调 onHidden
  var hasBeenVisible by remember { mutableStateOf(visible) }

  LaunchedEffect(visible) {
    if (!visible && !hasBeenVisible) return@LaunchedEffect
    hasBeenVisible = true
    anim.animateTo(
      targetValue = if (visible) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
      ),
    )
    if (!visible) {
      composed = false
      last?.let(onHidden)
    }
  }

  val shown = last
  if (composed && shown != null) {
    Box(
      modifier
        // 铺满容器:滑动距离按容器宽度算,页内容不再满宽时也不会滑一半留一条边
        .fillMaxSize()
        .graphicsLayer {
        val p = (anim.value + extraOffset()).coerceIn(0f, 1f)
        val dx = size.width * p
        translationX = if (rtl) -dx else dx
      }
    ) { content(shown) }
  }
}
