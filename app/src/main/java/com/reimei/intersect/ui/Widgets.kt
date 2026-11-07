package com.reimei.intersect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reimei.intersect.R
import com.reimei.intersect.i18n.t
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 语言入口的小地球:点一下打开语言选择页(中文 / English / 跟随系统)。
 *
 * 只出现在首次启动的激活流程里——正式进入应用之后语言只能去「设置」改,
 * 免得每页标题栏都挂一个按钮。图标本身不加文字:「中 / EN」这类角标在中文界面下很别扭。
 */
@Composable
fun LangButton(
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
  boxSize: Dp = 44.dp,
  iconPadding: Dp = 12.dp
) {
  Icon(
    painterResource(R.drawable.ui_lang_globe),
    contentDescription = t("切换界面语言"),
    modifier
      .clickable(onClick = onClick)
      .size(boxSize)
      .padding(iconPadding),
    tint = AppTheme.colors.icon
  )
}

@Composable
fun TitleBar(
  title: String,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {}
) {
  Box(
    Modifier
      .background(AppTheme.colors.background)
      .fillMaxWidth()
      .statusBarsPadding()
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .height(48.dp)
    ) {
      if (onBack != null) {
        Icon(
          painterResource(R.drawable.ui_chevron_left),
          null,
          Modifier
            .clickable(onClick = onBack)
            .align(Alignment.CenterVertically)
            .size(36.dp)
            .padding(8.dp),
          tint = AppTheme.colors.icon
        )
      }
      Spacer(Modifier.weight(1f))
      actions()
    }
    Text(title, Modifier.align(Alignment.Center), color = AppTheme.colors.textPrimary)
  }
}

/**
 * 占住整块区域的触摸命中:盖在首页之上的全屏页面用它兜底,防止点击漏到身后那一层。
 *
 * Compose 的命中测试是「后绘制的子节点先测,命中就停」,而背景色、留白、纯文本这些**都不参与命中**。
 * 于是页面里任何没有 pointerInput 的地方(输入行左侧的标签、卡片之间的空白、说明文字)
 * 都会被继续测到下层兄弟节点——首页的 Pager 列表也在同一个 Box 里,
 * 结果就是点「添加角色」页里的「昵称」标签,实际点中了身后通讯录的列表项,页面被切走。
 *
 * 这个修饰符只让该点算作「被本页命中」,不消费事件,也不影响子组件自己的点击与滚动。
 */
fun Modifier.blockTouchesThrough(): Modifier = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) awaitPointerEvent()
  }
}

/**
 * 通用底栏。
 *
 * [followsIme] 只给聊天输入栏用:键盘弹起时它跟着上移,顶栏留在原地。首页底部栏不参与——
 * 输入法 inset 是逐帧变化的,若它也读这份 inset,动画期间每一帧都会把身后整块首页
 * (Pager 的三页列表)重新测量一遍,而这些都是看不见的无效工作,正好拖慢输入法上滑。
 */
@Composable
fun TabBar(
  modifier: Modifier = Modifier,
  followsIme: Boolean = false,
  content: @Composable RowScope.() -> Unit
) {
  Row(
    modifier
      .fillMaxWidth()
      // 让位修饰符放在 background 之前,底部那块空白也涂上底栏底色
      .bottomBarSpace(followsIme)
      .background(AppTheme.colors.bottomBar)
      .padding(4.dp, 0.dp),
    content = content
  )
}

/**
 * 底栏需要让出的底部空间。
 *
 * 不跟随键盘时就是导航栏高度(直接读,不做动画)。
 *
 * 跟随键盘时**与输入法逐帧同步**:ime inset 是多少就让出多少,没有额外的动画。
 * 这里曾经用弹簧去追 ime 高度(故意让输入框比键盘早到、过冲再回落),但输入法的曲线是
 * 我们控制不了的——各家输入法上滑/下滑的时长与缓动都不一样,自己再叠一层弹簧只会变成
 * 「键盘还在动、输入框已经停住并在原地跳一下」,上下都显得慢一拍。同步跟随则不管用户装的是
 * 什么输入法,输入框始终贴在键盘上沿,键盘停住它就停住。
 *
 * 读值放在**布局阶段**:[WindowInsets.ime] 在键盘动画期间由 WindowInsetsAnimation 逐帧更新,
 * 在 measure 里读只登记布局依赖(触发重新测量),不会重组底栏——底栏里就是输入框,
 * 每帧重组它反而会拖慢键盘上滑。
 */
@Composable
private fun Modifier.bottomBarSpace(followsIme: Boolean): Modifier {
  val navigationInsets = WindowInsets.navigationBars
  if (!followsIme) {
    return padding(bottom = navigationInsets.asPaddingValues().calculateBottomPadding())
  }
  // WindowInsets.ime 是 @Composable getter,必须在组合阶段取到引用,measure 里只读它的值
  val imeInsets = WindowInsets.ime
  val density = LocalDensity.current
  return layout { measurable, constraints ->
    // 键盘与导航栏取较大者:键盘收起时还要让出导航栏
    val bottom = maxOf(
      imeInsets.getBottom(density),
      navigationInsets.getBottom(density)
    ).coerceAtLeast(0)
    val placeable = measurable.measure(
      constraints.copy(maxHeight = (constraints.maxHeight - bottom).coerceAtLeast(0))
    )
    layout(placeable.width, placeable.height + bottom) {
      placeable.placeRelative(0, 0)
    }
  }
}
