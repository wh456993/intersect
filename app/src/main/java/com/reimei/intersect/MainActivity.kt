package com.reimei.intersect

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.reimei.intersect.data.BackgroundSetting
import com.reimei.intersect.data.DevMode
import com.reimei.intersect.data.Heartbeat
import com.reimei.intersect.data.LangSetting
import com.reimei.intersect.data.MeProfile
import com.reimei.intersect.data.MemoryPromptStore
import com.reimei.intersect.data.ThemeSetting
import com.reimei.intersect.service.startKeepAlive
import com.reimei.intersect.ui.theme.AppTheme
import com.reimei.intersect.ui.theme.isDarkTheme

class MainActivity : AppCompatActivity() {
  /** 退到后台后再补设一次「从最近任务隐藏」用。 */
  private lateinit var recentsHandler: Handler
  private val recentsWork = Runnable { setRecentsExcluded(BackgroundSetting.hideFromRecents) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    WindowCompat.setDecorFitsSystemWindows(window, false)

    // 个人资料(头像/昵称/签名)在组合之前先读一遍
    MeProfile.init(this)
    // 外观设置(浅色 / 深色 / 跟随系统)同理
    ThemeSetting.init(this)
    // 界面语言(中文 / 英文):文案统一走 i18n.t(),这里先读一次,组合一开始就是对的语言
    LangSetting.init(this)
    // 后台设置(后台常驻 / 退出后从最近任务隐藏)
    BackgroundSetting.init(this)
    // 开发者模式:关着时界面上没有任何存储层信息,开着主聊天才有「全部显示」入口
    DevMode.init(this)
    // 总结记忆提示词的自定义开关(开发者模式下可以在记忆库页改)
    MemoryPromptStore.init(this)
    // 常驻开着的话顺手确认服务在跑:进程被系统回收后重启是全新进程,状态得自己接上
    if (BackgroundSetting.keepAlive) startKeepAlive()

    // 活跃采样:每 10 分钟记一条前台/后台状态,给「Token 统计」里的连续天数与使用时长用
    Heartbeat.start(this)

    recentsHandler = Handler(Looper.getMainLooper())

    setContent {
      // 跟随系统语言:系统语言变了 Activity 会重建,这里跟着重算一次(其它模式内部会直接返回)
      val configuration = LocalConfiguration.current
      LaunchedEffect(configuration) { LangSetting.refreshFromSystem(this@MainActivity) }
      AppTheme {
        // 状态栏与导航栏的图标颜色跟着明暗走:浅色模式用深色图标,深色模式用浅色图标。
        // 放在 SideEffect 里,切换模式时重跑,不需要重建 Activity。
        val dark = isDarkTheme()
        SideEffect {
          WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
          }
        }
        AppShell()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // 在前台时就先把标记挂上:系统生成「最近任务」时按标记处理,不依赖离开时的那一下时序
    setRecentsExcluded(BackgroundSetting.hideFromRecents)
  }

  override fun onStart() {
    super.onStart()
    // 界面可见 = 前台:之后的每次心跳采样都记 foreground
    Heartbeat.setForeground(true)
  }

  override fun onStop() {
    super.onStop()
    // 界面不可见 = 后台;进程还能活着(常驻服务)的话,采样会继续记 background
    Heartbeat.setForeground(false)
    // 退到后台的这一刻再设一次。只写在 onPause 里不够:从应用内上滑进最近任务时,
    // 任务此刻仍被算作前台,设置会被忽略,等它真转成后台时系统又不会重新判断。
    val hide = BackgroundSetting.hideFromRecents
    setRecentsExcluded(hide)
    // 个别 ROM 在任务刚转后台时会用旧状态刷一次列表,稍后再补一刀
    recentsHandler.removeCallbacks(recentsWork)
    if (hide) recentsHandler.postDelayed(recentsWork, RECENTS_REAPPLY_DELAY_MS)
  }

  override fun onDestroy() {
    recentsHandler.removeCallbacks(recentsWork)
    super.onDestroy()
  }

  /** 让本应用的任务从「最近任务」里消失 / 恢复显示。 */
  private fun setRecentsExcluded(hide: Boolean) {
    runCatching {
      val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return@runCatching
      // 可能有多个任务(比如从通知另开的),一起处理
      manager.appTasks.forEach { it.setExcludeFromRecents(hide) }
    }
  }

  private companion object {
    /** 任务刚转后台后再补设一次的延时。 */
    const val RECENTS_REAPPLY_DELAY_MS = 500L
  }
}

/**
 * 按容器宽度的百分比水平平移。
 *
 * 早先用 layout + placeRelative 实现,每帧位移都要重新 measure/layout 整棵子树;
 * 改成 graphicsLayer 后位移只在 draw 阶段生效,推入/退出的每一帧不再触发布局。
 * 原实现的 placeRelative 在 RTL 下自动镜像,translationX 不会,这里显式补上。
 */
fun Modifier.percentOffsetX(percent: Float): Modifier = this.composed {
  // graphicsLayer 的作用域里拿不到布局方向(GraphicsLayerScope 只有 Density),
  // 方向在本次组合内不变,读一次就好。
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  graphicsLayer {
    val dx = size.width * percent
    translationX = if (rtl) -dx else dx
  }
}

/**
 * 增加未读小红点
 */
fun Modifier.unread(read: Boolean, badgeColor: Color) = this
  .drawWithContent {
    drawContent()
    if (!read) {
      drawCircle(
        color = badgeColor,
        radius = 5.dp.toPx(),
        center = Offset(size.width - 1.dp.toPx(), 1.dp.toPx()),
      )
    }
  }