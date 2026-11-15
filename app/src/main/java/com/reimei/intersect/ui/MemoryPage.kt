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
import com.reimei.intersect.data.ChatService
import com.reimei.intersect.data.DevMode
import com.reimei.intersect.data.ImmediateMemory
import com.reimei.intersect.data.ImmediateMemoryState
import com.reimei.intersect.data.MemoryPromptStore
import com.reimei.intersect.data.MemoryStore
import com.reimei.intersect.data.MemorySummarizer
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.data.TokenEstimator
import com.reimei.intersect.data.memory.MemoryPipeline
import com.reimei.intersect.data.memory.MemoryRepository
import com.reimei.intersect.data.memory.MemorySnapshot
import com.reimei.intersect.data.memory.MicroSummaryEntity
import com.reimei.intersect.data.memory.NodeSummaryEntity
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
 * - **即时记忆层**(已实现):列表与数据都来自 **memory.db**([MemoryRepository])——
 *   每 [MemoryPipeline.MICRO_EVERY_ROUNDS] 轮自动生成一条微总结,每 3 条微总结压一条主线大总结;
 *   请求体只注入「最新 1 条大总结 + 最近 3 条微总结」(见 [ChatService.snapshotBlock])。
 *   旧库(SharedPreferences 里的 [ImmediateMemoryState])降级为**只读兼容层**:它的历史总结
 *   在列表下方以「旧版归档」展示,不再写入、不再注入。
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
  // 新库(memory.db)是记忆的唯一数据源:列表上展示的就是它。
  // 旧库(MemoryStore / ImmediateMemoryState)降级为只读兼容层 —— 备份与混合检索仍在读它,
  // 所以它的历史总结在列表下方以「旧版归档」只读展示,不写、不删、不参与计时。
  var micros by remember(friendId) { mutableStateOf<List<MicroSummaryEntity>>(emptyList()) }
  var node by remember(friendId) { mutableStateOf<NodeSummaryEntity?>(null) }
  // 读库是 suspend:用一个整数当「重读信号」,手动总结完成后不必写一份重复的读库代码
  var dbTick by remember(friendId) { mutableStateOf(0) }
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

  // 自动总结跑完(version 变)/ 换了角色 / 手动总结收尾(dbTick 变):重读本地状态
  LaunchedEffect(friendId, version, dbTick) {
    val reloaded = MemoryStore.load(context, friendId)
    state = reloaded
    target = reloaded.targetTokens
    val repository = MemoryRepository.get(context)
    // allMicro 是旧的在前,列表要新的在前
    micros = runCatching { repository.allMicro(friendId) }.getOrDefault(emptyList()).reversed()
    node = runCatching { repository.latestNode(friendId) }.getOrNull()
  }

  // 读流水线的单飞标记 → 总结开始 / 结束时本页自动重组(按钮置灰、「总结中」提示)
  val running = MemoryPipeline.isRunning(friendId)

  fun reload() {
    val reloaded = MemoryStore.load(context, friendId)
    state = reloaded
    target = reloaded.targetTokens
    // 库那边交给 LaunchedEffect(dbTick):它是 suspend,这里只负责「发起一次重读」
    dbTick++
  }

  /**
   * 手动触发一次总结。
   *
   * 与自动触发走**同一套**落库逻辑([MemoryPipeline.launchManual]):写的是 memory.db,
   * 不再写旧库 —— 新库是唯一写入方。任务挂在流水线自己的作用域上,点完立刻返回上一页也不会被取消。
   */
  fun startSummary() {
    if (MemoryPipeline.isRunning(friendId)) return
    val launched = MemoryPipeline.launchManual(
      context = context,
      friendId = friendId,
      apiKey = apiKeyProvider(),
      rounds = roundsProvider(),
      // 防脏数据的快照比对看的是「消息条数」,与自动触发同一条尺寸
      messageCount = { roundsProvider().sumOf { it.size } },
    ) { ok ->
      reload()
      notice = if (ok) "总结完成,已写入记忆库" else "这次没有写入:可能刚刚已经总结过这一段,或请求失败"
    }
    if (!launched) {
      notice = if (MemoryPipeline.isRunning(friendId)) null else "还没有可以总结的轮次"
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
        micros = micros,
        node = node,
        roundsNow = roundsProvider().size,
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
              // 新库也要一起清:它是唯一写入方,不清就会“旧库清空了、报文里还在注入”
              MemoryRepository.clearAsync(context, friendId)
              micros = emptyList()
              node = null
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
  micros: List<MicroSummaryEntity>,
  node: NodeSummaryEntity?,
  roundsNow: Int,
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
      item { StatusBar(text = "正在总结下一段对话(最多 ${ImmediateMemoryState.SUMMARY_EVERY} 轮)...", highlight = true) }
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

    if (node != null) {
      item {
        NodeCard(
          node = node,
          expanded = nodeKey(node.id) in expanded,
          onClick = { onToggleSummary(nodeKey(node.id)) }
        )
        Spacer(Modifier.height(8.dp))
      }
    }

    if (micros.isEmpty()) {
      item { EmptyMemoryHint(running = running) }
    } else {
      items(micros, key = { microKey(it.id) }) { micro ->
        MicroCard(
          micro = micro,
          expanded = microKey(micro.id) in expanded,
          onClick = { onToggleSummary(microKey(micro.id)) }
        )
        Spacer(Modifier.height(8.dp))
      }
    }

    // 旧版归档:并存过渡期的只读历史(备份与混合检索仍在读旧库)。
    // 不参与计时、不注入请求体,只是为了不把用户以前的总结藏起来。
    if (state.summaries.isNotEmpty()) {
      item {
        ArchiveSection(summaries = state.summaries, expanded = expanded, onToggle = onToggleSummary)
      }
    }

    item {
      Spacer(Modifier.height(4.dp))
      DevPanel(
        state = state,
        micros = micros,
        node = node,
        roundsNow = roundsNow,
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
 * `{target}` / `{min}` 在发请求前换成滑块的长度上限与固定下限(250 token),滑块因此仍然有效。
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
        // 诚实标注:新流水线(memory.db)的总结格式是固定的(正文 + 评分 + 状态三段),
        // 换模板会把解析需要的标签弄丢,所以自定义模板只作用于旧版归档总结。
        Text(
          "只作用于旧版归档总结;新记忆库的总结固定用内置模板。",
          Modifier.padding(top = 2.dp),
          fontSize = 10.sp,
          color = AppTheme.colors.more
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
        "${MemorySummarizer.TARGET_SLOT} 长度上限、${MemorySummarizer.MIN_SLOT} 长度下限(固定 ${ImmediateMemoryState.TARGET_MIN})。" +
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

/** 长度上限滑块:250~600 token,每 25 token 一档,松手即落盘。调的是最大长度,实际在 250~该值间浮动。 */
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
      .padding(horizontal = 16.dp, vertical = 8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("总结长度上限", Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      Text(
        "最大 $target token",
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
      "每 ${ImmediateMemoryState.SUMMARY_EVERY} 轮对话自动总结一次;这是长度上限,实际长度在 ${ImmediateMemoryState.TARGET_MIN} token 到该值之间按内容浮动。",
      Modifier.padding(top = 4.dp),
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
 * 一条微总结(memory.db):抬头是生成时间 / 覆盖轮次 / 评分,下面是正文。
 *
 * 折叠时正文最多三行,点一下展开全部 —— 与旧版归档卡片一致的操作习惯。
 */
@Composable
private fun MicroCard(micro: MicroSummaryEntity, expanded: Boolean, onClick: () -> Unit) {
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
        micro.timeLabel(),
        Modifier.weight(1f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Text(micro.roundLabel(), fontSize = 12.sp, color = AppTheme.colors.iconCurrent)
    }
    Row(Modifier.padding(top = 4.dp)) {
      Text(
        "评分 ${micro.score} · 微总结",
        Modifier.weight(1f),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(if (expanded) "收起" else "展开", fontSize = 11.sp, color = AppTheme.colors.more)
    }
    Spacer(Modifier.height(8.dp))
    Text(
      micro.text,
      fontSize = 13.sp,
      color = AppTheme.colors.textPrimary,
      maxLines = if (expanded) Int.MAX_VALUE else 3,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/** 主线大总结:3 条微总结压出来的一段,请求体里只注入这一条。 */
@Composable
private fun NodeCard(node: NodeSummaryEntity, expanded: Boolean, onClick: () -> Unit) {
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
        "主线大总结",
        Modifier.weight(1f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.iconCurrent
      )
      Text(
        if (node.fromRound == node.toRound) "第 ${node.fromRound} 轮" else "第 ${node.fromRound}-${node.toRound} 轮",
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Row(Modifier.padding(top = 4.dp)) {
      Text(
        node.timeLabel(),
        Modifier.weight(1f),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(if (expanded) "收起" else "展开", fontSize = 11.sp, color = AppTheme.colors.more)
    }
    Spacer(Modifier.height(8.dp))
    Text(
      node.text,
      fontSize = 13.sp,
      color = AppTheme.colors.textPrimary,
      maxLines = if (expanded) Int.MAX_VALUE else 3,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * 旧版归档(只读):并存过渡期把旧库里的总结原样列出来。
 *
 * 默认折起来 —— 它不是这个角色现在的记忆,只是以前存下的历史(备份与混合检索仍在读它)。
 */
@Composable
private fun ArchiveSection(
  summaries: List<ImmediateMemory>,
  expanded: Set<String>,
  onToggle: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.textFieldBackground)
      .padding(horizontal = 12.dp, vertical = 10.dp)
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable { open = !open },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Text("旧版归档", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
        Text(
          "${summaries.size} 条,只读:旧结构里的历史总结,不再注入请求体",
          fontSize = 10.sp,
          color = AppTheme.colors.more
        )
      }
      Text(if (open) "收起" else "展开", fontSize = 11.sp, color = AppTheme.colors.more)
    }
    if (!open) return@Column
    Spacer(Modifier.height(8.dp))
    summaries.forEach { memory ->
      SummaryCard(
        memory = memory,
        expanded = memory.id in expanded,
        onClick = { onToggle(memory.id) }
      )
      Spacer(Modifier.height(8.dp))
    }
  }
}

/** 新库卡片在 [expanded] 集合里的 key:两类卡片的 id 来自不同表,加前缀避免撞号。 */
private fun microKey(id: Long): String = "micro_$id"

private fun nodeKey(id: Long): String = "node_$id"

/**
 * 一条总结(旧版归档):抬头是生成时间 / 覆盖轮次 / 长度,下面是正文。
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
        "上限 ${memory.targetTokens} token · 实际 ${memory.actualTokens} token",
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
  micros: List<MicroSummaryEntity>,
  node: NodeSummaryEntity?,
  roundsNow: Int,
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

    // 下面这些数字全部来自 memory.db(与真正进请求体的那一份同源)
    val dueIn = ImmediateMemoryState.SUMMARY_EVERY - roundsNow % ImmediateMemoryState.SUMMARY_EVERY
    val injectedMicros = micros.take(MemoryRepository.SNAPSHOT_MICRO_COUNT)
    val injectBlock = ChatService.memoryBlock(
      MemorySnapshot(
        nodeSummary = node?.text.orEmpty(),
        microSummaries = injectedMicros.map { it.text },
      )
    )

    Spacer(Modifier.height(8.dp))
    DevRow("当前轮数", "$roundsNow 轮")
    DevRow("距下次自动总结", "$dueIn 轮(每 ${ImmediateMemoryState.SUMMARY_EVERY} 轮一次)")
    DevRow("微总结 / 大总结", "${micros.size} 条 / ${if (node == null) 0 else 1} 条")
    DevRow("本轮注入", "大总结 ${if (node == null) 0 else 1} 条 + 微总结 ${injectedMicros.size} 条")
    DevRow("注入 token 估算", "${TokenEstimator.estimate(injectBlock)} token")
    DevRow(
      "上次总结时间",
      micros.firstOrNull()?.timeLabel() ?: "还没有总结过"
    )
    DevRow("滑块当前值", "$target token(微总结正文上限)")
    DevRow("总结提示词", "内置模板(新流水线固定)")

    Spacer(Modifier.height(10.dp))
    DevButton(
      text = if (running) "总结中..." else "手动触发总结",
      enabled = !running,
      primary = true,
      onClick = onManualSummary
    )

    Spacer(Modifier.height(8.dp))
    DevButton(
      text = if (injectedOpen) "收起本轮会注入的记忆块" else "查看本轮会注入的记忆块",
      enabled = true,
      primary = false,
      onClick = onToggleInjected
    )
    if (injectedOpen) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          if (injectBlock.isEmpty()) {
            "还没有微总结:这一轮的状态快照里只有元信息,system 仍带着记忆规则。"
          } else {
            "下一轮请求的状态快照里会带上这一块(memory.db 直出):"
          },
          fontSize = 11.sp,
          color = AppTheme.colors.textSecondary
        )
        if (injectBlock.isNotEmpty()) {
          Spacer(Modifier.height(6.dp))
          Box(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(AppTheme.colors.textFieldBackground)
              .padding(10.dp)
          ) {
            Text(
              injectBlock,
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
      "注入格式:system(角色卡 + 元信息/记忆/时间规则) → 状态快照(rolling_meta + 大总结 + 最近 3 条微总结) → 最近一轮回复 → 当前输入。",
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
