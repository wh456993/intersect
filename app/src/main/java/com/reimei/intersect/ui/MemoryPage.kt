package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.DevMode
import com.reimei.intersect.data.ImmediateMemory
import com.reimei.intersect.data.ImmediateMemoryState
import com.reimei.intersect.data.MemoryJobs
import com.reimei.intersect.data.MemoryPromptStore
import com.reimei.intersect.data.MemoryStore
import com.reimei.intersect.data.MemorySummarizer
import com.reimei.intersect.data.MemorySummaryResult
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme
import kotlin.math.roundToInt

/** 记忆库的三个目录;目前只有「即时记忆层」是真的,另外两层是占位。 */
private enum class MemoryLayer(val title: String) {
  Immediate("即时记忆层"),
  Timeline("时间线索引层"),
  LongTerm("长期记忆层"),
}

/**
 * 记忆库页:角色信息页里「记忆库」那一行点进来的地方。
 *
 * 三个目录:
 * - **即时记忆层**(已实现):每 [ImmediateMemoryState.SUMMARY_EVERY] 轮对话自动总结一次,
 *   总结完的原文不再进请求体,只把最近 [ImmediateMemoryState.INJECT_COUNT] 条总结当成
 *   「角色过去的经历」注入(见 [com.reimei.intersect.data.ChatService.payload]);
 *   标题栏下方是目标长度滑块(200~600 token,默认 400),滑块下方是每次总结的列表;
 * - **时间线索引层** / **长期记忆层**:只有界面占位,标「即将上线」,不接任何逻辑。
 *
 * 页面自己读写本地状态([MemoryStore]):总结可能在聊天页后台跑完(自动触发),所以
 * [version] 一变就重读一次,不用用户退出重进才看到新总结。
 *
 * [apiKeyProvider] / [roundsProvider] 由调用方给:主聊天取这个会话的 KEY 与全部轮次,
 * 开发者控制台取它自己那份。
 */
@Composable
fun MemoryLibraryPage(
  modifier: Modifier = Modifier,
  friendId: String,
  apiKeyProvider: () -> ApiKey?,
  roundsProvider: () -> List<List<Msg>>,
  version: Int = 0,
  onBack: () -> Unit,
) {
  BackHandler { onBack() }

  val context = LocalContext.current

  var state by remember(friendId) { mutableStateOf(MemoryStore.load(context, friendId)) }
  var layer by remember(friendId) { mutableStateOf(MemoryLayer.Immediate) }
  var target by remember(friendId) { mutableStateOf(state.targetTokens) }
  var expanded by remember(friendId) { mutableStateOf(setOf<String>()) }
  var devOpen by remember(friendId) { mutableStateOf(false) }
  var injectedOpen by remember(friendId) { mutableStateOf(false) }
  var confirmClear by remember(friendId) { mutableStateOf(false) }
  var notice by remember(friendId) { mutableStateOf<String?>(null) }

  // 提示词编辑态放在这一层(而不是放在 LazyColumn 的 item 里):滚出屏幕被回收时,
  // 改了一半的文本不会丢。页面级状态,与角色无关,所以不按 friendId 记忆。
  var promptEnabled by remember { mutableStateOf(MemoryPromptStore.enabled) }
  var promptText by remember { mutableStateOf(MemoryPromptStore.draft()) }
  var promptNotice by remember { mutableStateOf<String?>(null) }

  // 自动总结跑完(version 变)/ 换了角色:重读本地状态
  LaunchedEffect(friendId, version) {
    val reloaded = MemoryStore.load(context, friendId)
    state = reloaded
    target = reloaded.targetTokens
  }

  // 读 MemoryJobs 的列表 → 总结开始 / 结束时本页自动重组(按钮置灰、「总结中」提示)
  val running = MemoryJobs.isRunning(friendId)

  fun reload() {
    val reloaded = MemoryStore.load(context, friendId)
    state = reloaded
    target = reloaded.targetTokens
  }

  /**
   * 手动触发一次总结。
   *
   * 任务交给 [MemoryJobs](它有自己的作用域):点完立刻返回上一页也不会被取消,
   * 结果回来后再刷新本页。
   */
  fun startSummary() {
    if (MemoryJobs.isRunning(friendId)) return
    val launched = MemoryJobs.launch(
      context = context,
      friendId = friendId,
      apiKey = apiKeyProvider(),
      // 用磁盘上的那份:页面里的 state 可能刚被滑块改过
      state = MemoryStore.load(context, friendId),
      rounds = roundsProvider(),
    ) { result ->
      reload()
      notice = when (result) {
        null -> null
        is MemorySummaryResult.Ok -> "总结完成 · 实际 ${result.actualTokens} token"
        is MemorySummaryResult.Fail -> "总结失败:${result.message}"
      }
    }
    if (!launched) {
      notice = if (MemoryJobs.isRunning(friendId)) null else "还没有可以总结的轮次"
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "记忆库",
      actionText = "",
      actionEnabled = false,
      onBack = onBack,
      onAction = {}
    )
    MemoryLayerTabs(current = layer, onPick = { layer = it })
    when (layer) {
      MemoryLayer.Immediate -> ImmediateLayer(
        modifier = Modifier.weight(1f),
        state = state,
        target = target,
        running = running,
        notice = notice,
        expanded = expanded,
        devOpen = devOpen,
        injectedOpen = injectedOpen,
        promptEnabled = promptEnabled,
        promptText = promptText,
        promptNotice = promptNotice,
        onPromptEnabled = { value ->
          promptEnabled = value
          MemoryPromptStore.setEnabled(context, value)
          promptNotice = if (value) "已改用自定义提示词" else "已回到内置模板"
        },
        onPromptText = {
          // 超长粘贴在这里就裁住,不然请求的 max_tokens 会被模板自己吃满
          promptText = MemoryPromptStore.cap(it)
          promptNotice = null
        },
        onPromptSave = {
          MemoryPromptStore.setPrompt(context, promptText)
          promptNotice = "已保存,下次总结生效"
        },
        onPromptReset = {
          // 清空写过的文本 = 回到内置模板:以后内置模板升级了也能跟着变
          MemoryPromptStore.setPrompt(context, "")
          promptText = MemoryPromptStore.draft()
          promptNotice = "已恢复内置模板"
        },
        onTargetChange = { target = it },
        onTargetCommit = {
          val saved = MemoryStore.setTarget(context, friendId, it)
          state = saved
          target = saved.targetTokens
        },
        onToggleSummary = { id ->
          expanded = if (id in expanded) expanded - id else expanded + id
        },
        onToggleDev = { devOpen = !devOpen },
        onToggleInjected = { injectedOpen = !injectedOpen },
        onManualSummary = { startSummary() },
        onClear = { confirmClear = true },
        onDismissNotice = { notice = null },
      )

      MemoryLayer.Timeline -> ComingSoonLayer(
        modifier = Modifier.weight(1f),
        title = "时间线索引层",
        detail = "把每段记忆按发生时间排成一条线,方便按时间回看角色经历过什么。",
      )

      MemoryLayer.LongTerm -> ComingSoonLayer(
        modifier = Modifier.weight(1f),
        title = "长期记忆层",
        detail = "把反复出现的人、地点与约定沉淀成长期设定,不必每次都从总结里推断。",
      )
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("清空即时记忆?", color = AppTheme.colors.textPrimary, fontSize = 16.sp) },
      text = {
        Text(
          "这一层里的全部总结、错误日志都会删掉(滑块值与当前进度保留),删除后无法恢复;之后新聊的轮次从头开始攒。",
          fontSize = 13.sp,
          color = AppTheme.colors.textSecondary
        )
      },
      confirmButton = {
        Text(
          "清空",
          Modifier
            .clickable {
              confirmClear = false
              state = MemoryStore.clear(context, friendId, roundsProvider().size)
              target = state.targetTokens
              notice = null
              expanded = emptySet()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.badge
        )
      },
      dismissButton = {
        Text(
          "取消",
          Modifier
            .clickable { confirmClear = false }
            .padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }
}

/** 三个目录的切换行:选中项用主题色 + 一条短底线,和设置页的分段选择一致。 */
@Composable
private fun MemoryLayerTabs(current: MemoryLayer, onPick: (MemoryLayer) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    MemoryLayer.entries.forEach { layer ->
      val selected = layer == current
      Column(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(8.dp))
          .clickable { onPick(layer) }
          .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          layer.title,
          fontSize = 14.sp,
          fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
          color = if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.textSecondary,
          maxLines = 1
        )
        Spacer(Modifier.height(5.dp))
        Box(
          Modifier
            .width(20.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.background)
        )
      }
    }
  }
}

/**
 * 即时记忆层:总结提示词 → 滑块调节器 → 总结列表 → 开发者面板。
 *
 * 列表按时间倒序(新的在前),每条可以点开看全文;没有总结时给空状态,
 * 总结正在进行时顶上显示进度并禁用触发。
 */
@Composable
private fun ImmediateLayer(
  modifier: Modifier = Modifier,
  state: ImmediateMemoryState,
  target: Int,
  running: Boolean,
  notice: String?,
  expanded: Set<String>,
  devOpen: Boolean,
  injectedOpen: Boolean,
  promptEnabled: Boolean,
  promptText: String,
  promptNotice: String?,
  onPromptEnabled: (Boolean) -> Unit,
  onPromptText: (String) -> Unit,
  onPromptSave: () -> Unit,
  onPromptReset: () -> Unit,
  onTargetChange: (Int) -> Unit,
  onTargetCommit: (Int) -> Unit,
  onToggleSummary: (String) -> Unit,
  onToggleDev: () -> Unit,
  onToggleInjected: () -> Unit,
  onManualSummary: () -> Unit,
  onClear: () -> Unit,
  onDismissNotice: () -> Unit,
) {
  LazyColumn(
    modifier
      .fillMaxSize()
      .navigationBarsPadding(),
    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
  ) {
    // 标题栏下方第一项:总结记忆提示词的开关与编辑框
    item {
      PromptCard(
        enabled = promptEnabled,
        text = promptText,
        notice = promptNotice,
        onEnabledChange = onPromptEnabled,
        onTextChange = onPromptText,
        onSave = onPromptSave,
        onReset = onPromptReset
      )
    }

    // 接着是滑块调节器
    item { TargetSliderCard(target = target, onChange = onTargetChange, onCommit = onTargetCommit) }

    // 状态条:总结中 / 上一次的结果 / 上一次的失败(带重试)
    if (running) {
      item { StatusBar(text = "正在总结前 ${ImmediateMemoryState.SUMMARY_EVERY} 轮对话...", highlight = true) }
    } else if (notice != null) {
      item { StatusBar(text = notice, onClick = onDismissNotice) }
    } else if (state.lastError.isNotBlank()) {
      item {
        StatusBar(
          text = "上次总结失败:${state.lastError}",
          error = true,
          actionText = "重试",
          onAction = onManualSummary
        )
      }
    }

    if (state.summaries.isEmpty()) {
      item { EmptyMemoryHint(running = running) }
    } else {
      items(state.summaries, key = { it.id }) { memory ->
        SummaryCard(
          memory = memory,
          expanded = memory.id in expanded,
          onClick = { onToggleSummary(memory.id) }
        )
        Spacer(Modifier.height(8.dp))
      }
    }

    item {
      Spacer(Modifier.height(4.dp))
      DevPanel(
        state = state,
        target = target,
        running = running,
        open = devOpen,
        injectedOpen = injectedOpen,
        onToggleOpen = onToggleDev,
        onToggleInjected = onToggleInjected,
        onManualSummary = onManualSummary,
        onClear = onClear
      )
    }
  }
}

/**
 * 总结记忆提示词的开关与编辑框。
 *
 * 开关打开后用这里写的文本当总结请求的 system(见 [MemoryPromptStore.template]);
 * 关着(或文本为空)时一律用内置模板 —— 这样以后内置模板升级,用着内置的人能跟着变。
 * `{target}` / `{min}` 在发请求前换成滑块的目标长度,滑块因此仍然有效。
 *
 * 编辑态由 [MemoryLibraryPage] 持有:它是 LazyColumn 里的第一项,滚出屏幕会被回收,
 * 状态留在这里的话改了一半的文本会静默丢掉。
 */
@Composable
private fun PromptCard(
  enabled: Boolean,
  text: String,
  notice: String?,
  onEnabledChange: (Boolean) -> Unit,
  onTextChange: (String) -> Unit,
  onSave: () -> Unit,
  onReset: () -> Unit,
) {
  val activeCustom = MemoryPromptStore.isCustom(enabled, text)
  Column(
    Modifier
      .fillMaxWidth()
      .padding(bottom = 8.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("自定义总结提示词", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
        Text(
          if (activeCustom) "当前:自定义" else "当前:内置模板",
          fontSize = 11.sp,
          color = AppTheme.colors.textSecondary
        )
      }
      Switch(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        colors = SwitchDefaults.colors(
          checkedThumbColor = AppTheme.colors.onFab,
          checkedTrackColor = AppTheme.colors.iconCurrent,
          uncheckedThumbColor = AppTheme.colors.more,
          uncheckedTrackColor = AppTheme.colors.textFieldBackground
        )
      )
    }

    // 状态提示放在分支之前:关掉开关时也要能看到「已回到内置模板」
    if (notice != null) {
      Text(
        notice,
        Modifier.padding(top = 4.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.iconCurrent
      )
    }

    if (!enabled) {
      Text(
        "打开后可以把总结请求的 system 换成自己写的一段(比如要求按时间线写、保留情绪变化)。",
        Modifier.padding(top = 6.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
      return@Column
    }

    if (text.isBlank()) {
      Text(
        "还没写内容,现在用的是内置模板;写一段再点保存。",
        Modifier.padding(top = 6.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(8.dp))
    // 限高 + 内部滚动:粘长文时「保存」按钮不会被顶出屏幕
    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 160.dp, max = 280.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(AppTheme.colors.textFieldBackground)
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
      BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
          .fillMaxWidth()
          .fillMaxHeight(),
        textStyle = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = AppTheme.colors.textPrimary
        ),
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent)
      )
    }
    Text(
      "占位符:" +
        "${MemorySummarizer.TARGET_SLOT} 目标 token、${MemorySummarizer.MIN_SLOT} 目标下限(约 70%)。" +
        "写成空文本会退回内置模板。",
      Modifier.padding(top = 6.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
    // 字数与上限:粘长文时能一眼看出是不是已经贴到上限了
    Text(
      "已用 ${text.length} / ${MemoryPromptStore.MAX_LENGTH} 字符",
      Modifier
        .fillMaxWidth()
        .padding(top = 4.dp),
      fontSize = 11.sp,
      color = if (text.length >= MemoryPromptStore.MAX_LENGTH) AppTheme.colors.badge
      else AppTheme.colors.textSecondary,
      textAlign = TextAlign.End
    )
    Spacer(Modifier.height(8.dp))
    Row {
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.textFieldBackground)
          .clickable(onClick = onReset)
          .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("恢复默认", fontSize = 13.sp, color = AppTheme.colors.textPrimary)
      }
      Spacer(Modifier.width(8.dp))
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.iconCurrent)
          .clickable(onClick = onSave)
          .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "保存",
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.listItem
        )
      }
    }
  }
}

/** 目标长度滑块:200~600 token,每 20 token 一档,松手即落盘。 */
@Composable
private fun TargetSliderCard(
  target: Int,
  onChange: (Int) -> Unit,
  onCommit: (Int) -> Unit,
) {
  // 松手要提交的是滑块最后给出的那一档:最后一次 onValueChange 引发的重组还没跑完时,
  // 外面的 target 可能仍是上一档,直接提交 target 会让滑块视觉上回跳一格
  var latest by remember { mutableStateOf(target) }
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("总结目标长度", Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      Text(
        "$target token",
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.iconCurrent
      )
    }
    val steps = (ImmediateMemoryState.TARGET_MAX - ImmediateMemoryState.TARGET_MIN) /
      ImmediateMemoryState.TARGET_STEP - 1
    Slider(
      value = target.toFloat(),
      onValueChange = {
        latest = it.roundToInt()
        onChange(latest)
      },
      onValueChangeFinished = { onCommit(latest) },
      valueRange = ImmediateMemoryState.TARGET_MIN.toFloat()..ImmediateMemoryState.TARGET_MAX.toFloat(),
      steps = steps,
      colors = SliderDefaults.colors(
        thumbColor = AppTheme.colors.iconCurrent,
        activeTrackColor = AppTheme.colors.iconCurrent,
        inactiveTrackColor = AppTheme.colors.textFieldBackground
      )
    )
    Row {
      Text(
        "${ImmediateMemoryState.TARGET_MIN}",
        Modifier.weight(1f),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(
        "${ImmediateMemoryState.TARGET_MAX}",
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Text(
      "每 ${ImmediateMemoryState.SUMMARY_EVERY} 轮对话自动总结一次;数值越大保留的细节越多,注入请求体时占的上下文也越多。",
      Modifier.padding(top = 6.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}

/** 一行状态提示:总结中 / 刚完成 / 失败重试。 */
@Composable
private fun StatusBar(
  text: String,
  highlight: Boolean = false,
  error: Boolean = false,
  actionText: String? = null,
  onAction: (() -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) {
  val tint = when {
    error -> AppTheme.colors.badge
    highlight -> AppTheme.colors.iconCurrent
    else -> AppTheme.colors.textSecondary
  }
  Row(
    Modifier
      .fillMaxWidth()
      .padding(top = 8.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(AppTheme.colors.listItem)
      .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(text, Modifier.weight(1f), fontSize = 12.sp, color = tint)
    if (actionText != null && onAction != null) {
      Spacer(Modifier.width(8.dp))
      Text(
        actionText,
        Modifier
          .clickable(onClick = onAction)
          .padding(horizontal = 4.dp, vertical = 2.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.iconCurrent
      )
    }
  }
}

/** 还没有总结时的空状态。 */
@Composable
private fun EmptyMemoryHint(running: Boolean) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 40.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      if (running) "正在生成第一条总结..." else "还没有即时记忆",
      fontSize = 16.sp,
      color = AppTheme.colors.textPrimary
    )
    Text(
      if (running) "生成完会出现在这里,不用离开这个页面。"
      else "每聊完 ${ImmediateMemoryState.SUMMARY_EVERY} 轮会自动总结一次,总结之后这些轮的原文不再发给模型,只发总结。",
      Modifier.padding(top = 8.dp),
      fontSize = 12.sp,
      color = AppTheme.colors.textSecondary,
      textAlign = TextAlign.Center
    )
  }
}

/**
 * 一条总结:抬头是生成时间 / 覆盖轮次 / 长度,下面是正文。
 *
 * 折叠时正文最多三行,点一下展开全部 —— 总结通常比三行长,但也不该一张卡就占半屏。
 */
@Composable
private fun SummaryCard(memory: ImmediateMemory, expanded: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        memory.timeLabel(),
        Modifier.weight(1f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Text(
        memory.roundLabel(),
        fontSize = 12.sp,
        color = AppTheme.colors.iconCurrent
      )
    }
    Row(Modifier.padding(top = 4.dp)) {
      Text(
        "目标 ${memory.targetTokens} token · 实际 ${memory.actualTokens} token",
        Modifier.weight(1f),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(
        if (expanded) "收起" else "展开",
        fontSize = 11.sp,
        color = AppTheme.colors.more
      )
    }
    Spacer(Modifier.height(8.dp))
    Text(
      memory.text,
      fontSize = 13.sp,
      color = AppTheme.colors.textPrimary,
      maxLines = if (expanded) Int.MAX_VALUE else 3,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * 开发者面板:可折叠,里面是这个角色记忆层的运行数据与几个手动动作。
 *
 * 面板里的数字都从磁盘上的那份状态来(与真正注入请求体的那一份同源),
 * 「最近一次请求体记忆部分」看的是上一轮**真的发出去**的记忆块原文。
 */
@Composable
private fun DevPanel(
  state: ImmediateMemoryState,
  target: Int,
  running: Boolean,
  open: Boolean,
  injectedOpen: Boolean,
  onToggleOpen: () -> Unit,
  onToggleInjected: () -> Unit,
  onManualSummary: () -> Unit,
  onClear: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onToggleOpen),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("开发者面板", Modifier.weight(1f), fontSize = 14.sp, color = AppTheme.colors.textPrimary)
      Text(
        if (open) "收起" else "展开",
        fontSize = 12.sp,
        color = AppTheme.colors.more
      )
    }
    if (!open) return@Column

    Spacer(Modifier.height(8.dp))
    DevRow("当前轮数", "${state.rounds} 轮")
    DevRow("距下次总结", "${state.roundsUntilNext} 轮(已攒 ${state.pendingRounds} 轮)")
    DevRow(
      "上次总结时间",
      if (state.lastSummaryAt > 0L) TimeManager.formatMinute(state.lastSummaryAt) else "还没有总结过"
    )
    DevRow("当前注入总结条数", "${state.latest.size} / ${ImmediateMemoryState.INJECT_COUNT}")
    DevRow("当前注入 token 估算", "${state.injectTokens} token")
    DevRow("滑块当前值", "$target token")
    DevRow(
      "总结提示词",
      if (MemoryPromptStore.isCustom(MemoryPromptStore.enabled, MemoryPromptStore.prompt)) "自定义" else "内置模板"
    )

    Spacer(Modifier.height(10.dp))
    DevButton(
      text = if (running) "总结中..." else "手动触发总结",
      enabled = !running,
      primary = true,
      onClick = onManualSummary
    )

    Spacer(Modifier.height(8.dp))
    DevButton(
      text = if (injectedOpen) "收起最近一次请求体的记忆部分" else "查看最近一次请求体记忆部分",
      enabled = true,
      primary = false,
      onClick = onToggleInjected
    )
    if (injectedOpen) {
      // lastInjectedAt 用来区分「没发过请求」和「发过但那一轮没有记忆块可注入」
      val sent = state.lastInjectedAt > 0L
      val block = state.lastInjectedBlock
      val emptySent = sent && block.isBlank()
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          when {
            !sent -> "还没有发过请求。下面是按现在的总结算出来的记忆块(下一轮会注入这一份):"
            emptySent -> "上一轮请求里没有记忆块(那时还没有总结):system 仍带着记忆使用规则,记忆块整块省略。"
            else -> "上一轮请求体里的记忆部分:"
          },
          fontSize = 11.sp,
          color = AppTheme.colors.textSecondary
        )
        if (!emptySent) {
          Spacer(Modifier.height(6.dp))
          Box(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(AppTheme.colors.textFieldBackground)
              .padding(10.dp)
          ) {
            Text(
              block.ifBlank { state.injectBlock }.ifBlank { "【暂无历史总结】这一轮不注入记忆块。" },
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = AppTheme.colors.textPrimary
            )
          }
        }
      }
    }

    Spacer(Modifier.height(8.dp))
    DevButton(text = "清空即时记忆", enabled = true, primary = false, danger = true, onClick = onClear)

    Spacer(Modifier.height(10.dp))
    Text("错误日志", fontSize = 12.sp, color = AppTheme.colors.textPrimary)
    Spacer(Modifier.height(4.dp))
    if (state.errors.isEmpty()) {
      Text("没有错误", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
    } else {
      state.errors.forEach { line ->
        Text(
          line,
          Modifier.padding(vertical = 1.dp),
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = AppTheme.colors.badge
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Text(
      "注入格式:system(角色卡 + 记忆规则) → 未总结原文 → 一条独立的记忆块 → 当前输入。",
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}

/** 开发者面板里的一行「名字 ......... 值」。 */
@Composable
private fun DevRow(label: String, value: String) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, Modifier.weight(1f), fontSize = 12.sp, color = AppTheme.colors.textSecondary)
    Text(value, fontSize = 12.sp, color = AppTheme.colors.textPrimary)
  }
}

/** 开发者面板里的动作按钮:主色 / 普通 / 危险三档。 */
@Composable
private fun DevButton(
  text: String,
  enabled: Boolean,
  primary: Boolean,
  danger: Boolean = false,
  onClick: () -> Unit,
) {
  val background = when {
    !enabled -> AppTheme.colors.textFieldBackground
    danger -> AppTheme.colors.textFieldBackground
    primary -> AppTheme.colors.iconCurrent
    else -> AppTheme.colors.textFieldBackground
  }
  val foreground = when {
    !enabled -> AppTheme.colors.more
    danger -> AppTheme.colors.badge
    primary -> AppTheme.colors.listItem
    else -> AppTheme.colors.textPrimary
  }
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(background)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = 11.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = foreground)
  }
}

/** 时间线索引层 / 长期记忆层的占位:只有说明与「即将上线」,不接任何逻辑。 */
@Composable
private fun ComingSoonLayer(modifier: Modifier = Modifier, title: String, detail: String) {
  Column(
    modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text(title, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    Text(
      "即将上线",
      Modifier.padding(top = 6.dp),
      fontSize = 13.sp,
      color = AppTheme.colors.iconCurrent
    )
    Text(
      detail,
      Modifier.padding(top = 12.dp),
      fontSize = 12.sp,
      color = AppTheme.colors.textSecondary,
      textAlign = TextAlign.Center
    )
  }
}
