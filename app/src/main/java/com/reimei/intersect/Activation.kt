package com.reimei.intersect

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.reimei.intersect.ui.HomeShell
import com.reimei.intersect.ui.LangButton
import com.reimei.intersect.ui.LanguagePage
import com.reimei.intersect.ui.SlideOverlay
import com.reimei.intersect.ui.theme.AppTheme
import com.reimei.intersect.ui.theme.green3
import com.reimei.intersect.ui.theme.white

/** 激活页左右两侧的留白,协议页和权限页共用,保证翻页时文字基线一致。 */
private val PAGE_PADDING = 32.dp

/**
 * 应用入口:先进激活流程(协议 + 权限任务表),全部完成(或用户选了稍后)后再进主界面。
 */
@Composable
fun AppShell() {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
  var activated by remember { mutableStateOf(context.isActivationCompleted(prefs)) }
  // 刚走完激活流程:主界面从右侧滑入,普通启动则不做过场动画
  var justActivated by remember { mutableStateOf(false) }

  if (activated) {
    val slide = remember { Animatable(if (justActivated) 1f else 0f) }
    LaunchedEffect(Unit) {
      if (slide.value != 0f) {
        slide.animateTo(
          targetValue = 0f,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
          )
        )
      }
    }
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer { translationX = slide.value * size.width }
    ) {
      HomeShell()
    }
  } else {
    ActivationFlow(prefs) {
      justActivated = true
      activated = true
    }
  }
}

/**
 * 激活流程:第 1 页是协议文本,第 2 页是权限任务表。
 * 两页横向并排,点底部圆形箭头翻页。
 */
@Composable
private fun ActivationFlow(
  prefs: SharedPreferences,
  onFinished: () -> Unit
) {
  val context = LocalContext.current
  // 协议确认过就不再展示第 1 页
  var page by remember { mutableStateOf(context.activationStartPage(prefs)) }
  val pageOffset by animateFloatAsState(
    targetValue = page.toFloat(),
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessMediumLow
    )
  )

  // 每次从系统授权页返回都重新校验一遍,已授权的项自动打勾
  var checkStamp by remember { mutableStateOf(0) }
  // 刚打开过电池设置页,回来后若仍检测不到就请用户确认一下
  var awaitingBatteryReturn by remember { mutableStateOf(false) }
  var showBatteryConfirm by remember { mutableStateOf(false) }
  var showSkipConfirm by remember { mutableStateOf(false) }
  // 语言选择页:右上角小地球点开的就是它,和「设置 → 界面语言」同一页
  var showLanguage by remember { mutableStateOf(false) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        checkStamp++
        if (awaitingBatteryReturn) {
          awaitingBatteryReturn = false
          // MIUI / 澎湃 OS 的省电策略读不到,检测不到时让用户自己确认
          if (!context.isIgnoringBatteryOptimizations() && !context.isBatteryConfirmed(prefs)) {
            showBatteryConfirm = true
          }
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val doneStates = remember(checkStamp) {
    PermTask.entries.map { context.isTaskDone(prefs, it) }
  }
  val allDone = doneStates.all { it }
  val unfinished = doneStates.count { !it }

  val notificationLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    checkStamp++
    // 用户拒绝、或系统根本没弹窗(旧 targetSdk)时,直接落到通知设置页
    if (!granted && !context.notificationsEnabled()) {
      context.launchTaskGuide(prefs, PermTask.Notifications)
    }
  }

  val guide: (PermTask) -> Unit = { task ->
    when {
      // 只有 targetSdk >= 33 的应用才能用运行时弹窗,否则弹不出来也没提示
      task == PermTask.Notifications && context.canRequestNotificationPermission() ->
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

      task == PermTask.Notifications -> context.launchTaskGuide(prefs, task)

      task == PermTask.Battery -> {
        // 去电池页时挂上标记,回来才能知道该不该问用户「已经开启了吗」
        if (context.launchTaskGuide(prefs, task)) {
          awaitingBatteryReturn = true
        } else {
          // 这台机器没有可打开的相关页面,直接请用户确认
          showBatteryConfirm = true
        }
      }

      else -> context.launchTaskGuide(prefs, task)
    }
    checkStamp++
  }

  if (showBatteryConfirm) {
    AlertDialog(
      onDismissRequest = { showBatteryConfirm = false },
      title = { Text("已经开启电池无限制了吗?") },
      confirmButton = {
        TextButton(onClick = {
          context.confirmBatteryUnrestricted(prefs)
          showBatteryConfirm = false
          checkStamp++
        }) { Text("已开启") }
      },
      dismissButton = {
        TextButton(onClick = { showBatteryConfirm = false }) { Text("还没开启") }
      }
    )
  }

  if (showSkipConfirm) {
    AlertDialog(
      onDismissRequest = { showSkipConfirm = false },
      title = { Text("还有 $unfinished 项未开启") },
      confirmButton = {
        TextButton(onClick = {
          showSkipConfirm = false
          context.skipActivation(prefs)
          onFinished()
        }) { Text("仍然进入") }
      },
      dismissButton = {
        TextButton(onClick = { showSkipConfirm = false }) { Text("继续授权") }
      }
    )
  }

  Box(Modifier.fillMaxSize().background(AppTheme.colors.listItem)) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val widthPx = constraints.maxWidth.toFloat()

      AgreementPage(
        Modifier.graphicsLayer { translationX = -pageOffset * widthPx },
        onNext = {
          context.acceptAgreement(prefs)
          page = 1
        }
      )

      PermissionPage(
        Modifier.graphicsLayer { translationX = (1f - pageOffset) * widthPx },
        doneStates = doneStates,
        allDone = allDone,
        onTaskClick = guide,
        onNext = {
          if (allDone) onFinished() else showSkipConfirm = true
        }
      )
    }

    // 第一次打开应用就能换语言:右上角一个小地球,点开是语言选择页(不用先走完授权)。
    // 两页共用这一个按钮,翻页时它不跟着横向位移。
    LangButton(
      Modifier
        .align(Alignment.TopEnd)
        .statusBarsPadding()
        .padding(top = 8.dp, end = 12.dp),
      onClick = { showLanguage = true }
    )

    SlideOverlay(payload = Unit.takeIf { showLanguage }) {
      LanguagePage(onBack = { showLanguage = false })
    }
  }
}

/** 第 1 页:协议与隐私政策。正文内容待补充,现在只放标题。 */
@Composable
private fun AgreementPage(modifier: Modifier, onNext: () -> Unit) {
  Box(modifier.fillMaxSize()) {
    Column(
      Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .padding(horizontal = PAGE_PADDING)
    ) {
      Spacer(Modifier.height(112.dp))
      Text(
        text = "开源协议及隐私政策",
        fontSize = 32.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.textPrimary
      )
    }
    CircleArrowButton(
      enabled = true,
      onClick = onNext,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 48.dp)
    )
  }
}

/** 第 2 页:权限任务表,逐项跳系统授权页,回来后自动校验并打勾。 */
@Composable
private fun PermissionPage(
  modifier: Modifier,
  doneStates: List<Boolean>,
  allDone: Boolean,
  onTaskClick: (PermTask) -> Unit,
  onNext: () -> Unit
) {
  Box(modifier.fillMaxSize()) {
    Column(
      Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .padding(horizontal = PAGE_PADDING)
    ) {
      Spacer(Modifier.height(96.dp))
      Text(
        text = "开启必要权限",
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.textPrimary
      )
      Spacer(Modifier.height(28.dp))
      PermTask.entries.forEachIndexed { index, task ->
        PermissionTaskRow(
          task = task,
          done = doneStates.getOrElse(index) { false },
          onClick = { onTaskClick(task) }
        )
        Spacer(Modifier.height(12.dp))
      }
      Spacer(Modifier.weight(1f))
      // 给底部圆形箭头留位置
      Spacer(Modifier.height(140.dp))
    }

    Column(
      Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = if (allDone) "已完成全部授权" else "可稍后再授权",
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
      Spacer(Modifier.height(14.dp))
      // 始终可点:没全部完成时点一下会确认是否稍后再授权
      CircleArrowButton(enabled = true, onClick = onNext)
    }
  }
}

@Composable
private fun PermissionTaskRow(task: PermTask, done: Boolean, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(AppTheme.colors.bottomBar)
      .clickable(onClick = onClick)
      .padding(horizontal = 18.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = task.title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = task.summary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.width(12.dp))
    TaskStateBadge(done)
  }
}

/** 未授权时是一个灰色箭头,校验通过后弹出一个绿色小勾。 */
@Composable
private fun TaskStateBadge(done: Boolean) {
  val checkScale by animateFloatAsState(
    targetValue = if (done) 1f else 0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMedium
    )
  )
  val arrowAlpha by animateFloatAsState(targetValue = if (done) 0f else 1f)

  Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
    if (arrowAlpha > 0.01f) {
      Icon(
        painter = painterResource(R.drawable.ui_chevron_right),
        contentDescription = null,
        modifier = Modifier
          .size(14.dp)
          .graphicsLayer { alpha = arrowAlpha },
        tint = AppTheme.colors.more
      )
    }
    if (checkScale > 0.01f) {
      Canvas(
        Modifier
          .size(24.dp)
          .graphicsLayer {
            scaleX = checkScale
            scaleY = checkScale
          }
      ) {
        drawCircle(color = green3, radius = size.minDimension / 2)
        val stroke = size.minDimension * 0.12f
        val path = Path().apply {
          moveTo(size.width * 0.28f, size.height * 0.53f)
          lineTo(size.width * 0.44f, size.height * 0.69f)
          lineTo(size.width * 0.74f, size.height * 0.34f)
        }
        drawPath(
          path = path,
          color = white,
          style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
      }
    }
  }
}

/** 底部居中的圆形箭头按钮,外面用圆包住,不可用时整体变淡。 */
@Composable
private fun CircleArrowButton(
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val alpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.35f)
  Box(
    modifier = modifier
      .size(64.dp)
      .graphicsLayer { this.alpha = alpha }
      .clip(CircleShape)
      .background(AppTheme.colors.fab)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      painter = painterResource(R.drawable.ui_chevron_right),
      contentDescription = if (enabled) "继续" else "请先完成授权",
      modifier = Modifier
        .size(20.dp)
        .padding(start = 2.dp),
      tint = AppTheme.colors.onFab
    )
  }
}
