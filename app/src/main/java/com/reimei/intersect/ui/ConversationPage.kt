package com.reimei.intersect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import com.reimei.intersect.i18n.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reimei.intersect.R
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.Chat
import com.reimei.intersect.data.DevMode
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.User
import com.reimei.intersect.data.time.SceneDraft
import com.reimei.intersect.ui.theme.AppTheme
import androidx.compose.ui.unit.IntOffset

/**
 * 消息让位动画的规格。
 *
 * 提到顶层常量:原来每帧都要现 new 一个 spring 对象,既产生垃圾又让 modify 每次都不相等。
 */
private val ItemPlacementSpec = spring<IntOffset>(
  dampingRatio = Spring.DampingRatioNoBouncy,
  stiffness = Spring.StiffnessMediumLow
)

@Composable
fun ConversationPage(
  modifier: Modifier = Modifier,
  viewModel: MainViewModel = viewModel(),
  chat: Chat?,
  onBack: (() -> Unit),
  onOpenInfo: () -> Unit = {}
) {
  if (chat == null) return
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  // 开发者模式:关着的时候这个页面上看不到任何存储层的东西
  val devMode = DevMode.enabled
  // 已点开「全部显示」:每条 AI 回复下面附带这一轮的记忆索引(角色 UID / 用户 UID / 落盘时间)
  var showMemory by remember(chat) { mutableStateOf(false) }

  // 输入栏上方的面板:附件条 ⇄ 导演模式面板,两者共用同一个弹出位置(同一时刻只开一个)
  var attachPanelOpen by remember(chat) { mutableStateOf(false) }
  var directorOpen by remember(chat) { mutableStateOf(false) }
  // 面板里的草稿只在面板开着时存在:关掉就丢,不会以「半截输入」的形态进请求体
  var sceneDraft by remember(chat) { mutableStateOf(SceneDraft()) }
  var sceneError by remember(chat) { mutableStateOf<String?>(null) }

  // 已读状态只在进入会话时写一次。以前放在组合体里,每帧重组都会扫一遍全部消息
  LaunchedEffect(chat) { viewModel.read(context, chat) }

  val selected = viewModel.selectedMsgs
  val selectionMode = selected.isNotEmpty()
  // 长按哪条就在哪条旁边弹菜单;null 表示没有打开的菜单
  var menuMsg by remember(chat) { mutableStateOf<Msg?>(null) }
  // 待确认删除的消息,null 表示没有弹确认框
  var pendingDelete by remember(chat) { mutableStateOf<List<Msg>?>(null) }
  // 待确认回滚的那条消息(它和它之后的都会被删掉),null 表示没有弹确认框
  var pendingRollback by remember(chat) { mutableStateOf<Msg?>(null) }

  // 菜单开着时,返回先收起菜单;ConversationPage 比首页的预测性返回后注册,所以它优先
  BackHandler(enabled = menuMsg != null) { menuMsg = null }
  // 附件 / 导演面板开着时,返回先收起面板(先收导演面板,再收附件条)
  BackHandler(enabled = attachPanelOpen) {
    if (directorOpen) {
      directorOpen = false
      sceneError = null
    } else {
      attachPanelOpen = false
    }
  }

  val copySelected = {
    val text = chat.msgs.filter { msg -> selected.any { it === msg } }
      .joinToString("\n") { it.text }
    clipboard.setText(AnnotatedString(text))
    viewModel.clearSelection()
  }

  Scaffold(
    modifier.background(AppTheme.colors.background),
    topBar = {
      if (selectionMode) {
        SelectionTopBar(
          count = selected.size,
          allSelected = selected.size == chat.msgs.size,
          onBack = viewModel::clearSelection,
          onToggleAll = { viewModel.toggleSelectAll(chat) },
          onCopy = copySelected,
          onDelete = { pendingDelete = selected.toList() },
        )
      } else {
        TitleBar(chat.friend.name, onBack) {
          Icon(
            painterResource(R.drawable.ui_menu_bars),
            contentDescription = "聊天信息",
            Modifier
              .clickable(onClick = onOpenInfo)
              .align(Alignment.CenterVertically)
              .size(44.dp)
              .padding(12.dp),
            tint = AppTheme.colors.icon
          )
        }
      }
    },
    bottomBar = {
      Column {
        // 等回复 / 请求失败的状态行:只在需要时出现,平时不占高度
        ChatStatusLine(
          error = viewModel.replyError,
          notice = viewModel.clockNotice,
          replying = viewModel.replyingCount > 0,
          onDismissError = { viewModel.clearReplyError() },
          onDismissNotice = { viewModel.clearClockNotice() }
        )
        ChatBottomBar(
          onSend = { text -> viewModel.send(context, chat, text) },
          sheetOpen = attachPanelOpen,
          onSheetOpenChange = { open ->
            attachPanelOpen = open
            directorOpen = false
            sceneError = null
            // 每次打开都重读一次当前场景:面板外的 /time 指令也可能刚改过时钟
            if (open) sceneDraft = viewModel.sceneDraft(context, chat.friend.id)
          },
          sheet = {
            if (directorOpen) {
              DirectorScenePanel(
                draft = sceneDraft,
                onDraftChange = { sceneDraft = it },
                error = sceneError,
                onSave = {
                  // null 表示真的落账了(或本来就没改):收起面板;
                  // 非 null 是格式/长度问题,留在面板里让用户改
                  val reason = viewModel.saveScene(context, chat, sceneDraft)
                  sceneError = reason
                  if (reason == null) {
                    directorOpen = false
                    attachPanelOpen = false
                  }
                },
                onBack = {
                  directorOpen = false
                  sceneError = null
                },
              )
            } else {
              AttachSheet(
                buildList {
                  add(AttachEntry(label = "导演模式") {
                    directorOpen = true
                    sceneError = null
                  })
                  // 开发者的「全部显示」和导演模式并列在同一个面板里
                  if (devMode) {
                    add(
                      AttachEntry(
                        label = if (showMemory) "收起全部" else "全部显示",
                        onClick = {
                          showMemory = !showMemory
                          attachPanelOpen = false
                        },
                        iconRes = R.drawable.ui_menu_bars,
                      )
                    )
                  }
                }
              )
            }
          },
        )
      }
    }
  ) { paddingValues ->
    Spacer(
      Modifier
        .background(AppTheme.colors.chatPage)
        .fillMaxSize()
    )
    // 新消息进来时滚到最新一条,否则发出去的话会在屏幕外
    val listState = rememberLazyListState()
    val messages = rememberUpdatedState(chat.msgs)
    LaunchedEffect(key1 = chat.msgs.size) {
      if (chat.msgs.isNotEmpty()) {
        listState.animateScrollToItem(chat.msgs.lastIndex)
      }
    }
    // 键盘弹起会把列表的可视区压矮,而 LazyColumn 默认只认「第一个可见项」的位置,
    // 最后几条会被输入框盖住。这里盯住可视区高度的变化:只有原本就贴在最后一条、
    // 且最后一条真的被挤出去时才跟一次,让内容跟着键盘一起上移;内容本来就装得下
    // (短会话,常见于首次弹键盘)或用户正在翻历史,则一次都不滚,画面完全不动。
    LaunchedEffect(listState) {
      var lastViewportHeight = 0
      var pinnedToEnd = true
      snapshotFlow { listState.layoutInfo.viewportSize.height to !listState.canScrollForward }
        .collect { (viewportHeight, atEnd) ->
          val shrunk = viewportHeight > 0 && viewportHeight < lastViewportHeight
          if (shrunk && pinnedToEnd && messages.value.isNotEmpty() && listState.isLastItemClipped()) {
            // 逐帧跟一次:可视区收缩和列表上移同步发生,看着是滑上去而不是最后跳一下。
            // 这期间不动 pinnedToEnd,免得键盘动画的中间帧把「贴底」状态丢掉。
            listState.scrollToItem(messages.value.lastIndex)
          } else if (!shrunk) {
            pinnedToEnd = atEnd
          }
          if (viewportHeight > 0) lastViewportHeight = viewportHeight
        }
    }
    MessageList(
      messages = chat.msgs,
      selected = selected,
      selectionMode = selectionMode,
      showMemory = showMemory,
      listState = listState,
      modifier = Modifier.padding(paddingValues),
      onLongPress = { msg ->
        if (selectionMode) viewModel.toggleSelect(msg) else menuMsg = msg
      },
      onTap = { msg -> if (selectionMode) viewModel.toggleSelect(msg) },
      menuMsg = menuMsg,
      onDismissMenu = { menuMsg = null },
      onCopyOne = { msg ->
        clipboard.setText(AnnotatedString(msg.text))
        menuMsg = null
      },
      onDeleteOne = { msg ->
        menuMsg = null
        pendingDelete = listOf(msg)
      },
      onRollbackOne = { msg ->
        menuMsg = null
        pendingRollback = msg
      },
      onMultiSelect = { msg ->
        menuMsg = null
        viewModel.selectOnly(msg)
      },
    )
  }

  val confirmTarget = pendingDelete
  if (confirmTarget != null) {
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("删除聊天记录") },
      text = {
        Text(
          if (confirmTarget.size == 1) "这条消息将被删除,且无法恢复。"
          else "选中的 ${confirmTarget.size} 条消息将被删除,且无法恢复。"
        )
      },
      confirmButton = {
        TextButton(onClick = {
          viewModel.deleteMsgs(context, chat, confirmTarget)
          viewModel.clearSelection()
          pendingDelete = null
        }) { Text("删除", color = AppTheme.colors.badge) }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) { Text("取消") }
      }
    )
  }

  val confirmRollback = pendingRollback
  if (confirmRollback != null) {
    // 条数在弹窗里实时算:打开弹窗到点确认之间消息可能又变了(比如对方的回复刚到)
    val index = chat.msgs.indexOfFirst { it === confirmRollback }
    // count 是「从这条起到底」的总条数(含它自己),文案照这个说,不再写「以及它之后的 N 条」
    val count = if (index < 0) 0 else chat.msgs.size - index
    AlertDialog(
      onDismissRequest = { pendingRollback = null },
      title = { Text("回滚到这里") },
      text = {
        Text(
          if (index < 0) "这条消息已经不在了。"
          else "从这条起的 $count 条都会被删除,回到它之前的状态。删除后无法恢复。"
        )
      },
      confirmButton = {
        // 目标已不在时没什么可回滚的,只留一个关闭按钮
        if (index >= 0) {
          TextButton(onClick = {
            viewModel.rollbackTo(context, chat, confirmRollback)
            viewModel.clearSelection()
            pendingRollback = null
          }) { Text("回滚", color = AppTheme.colors.badge) }
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingRollback = null }) { Text("取消") }
      }
    )
  }
}

/**
 * 聊天记录列表。
 *
 * 每项都用消息对象身份做 key:内容相同的两条消息各自独立,删除/插入时才不会串位;
 * 配合 animateItemPlacement,增删消息会平滑地让位,而不是整列表跳一下。
 */
/** 最后一条是否已经被可视区下沿挤出去。整段内容装得下时它一直是 false,不用跟着滚。 */
private fun androidx.compose.foundation.lazy.LazyListState.isLastItemClipped(): Boolean {
  val info = layoutInfo
  val last = info.visibleItemsInfo.lastOrNull() ?: return false
  return last.offset + last.size > info.viewportEndOffset
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(
  messages: List<Msg>,
  selected: List<Msg>,
  selectionMode: Boolean,
  showMemory: Boolean,
  listState: androidx.compose.foundation.lazy.LazyListState,
  modifier: Modifier = Modifier,
  onLongPress: (Msg) -> Unit,
  onTap: (Msg) -> Unit,
  menuMsg: Msg?,
  onDismissMenu: () -> Unit,
  onCopyOne: (Msg) -> Unit,
  onDeleteOne: (Msg) -> Unit,
  onRollbackOne: (Msg) -> Unit,
  onMultiSelect: (Msg) -> Unit,
) {
  LazyColumn(
    modifier
      .fillMaxSize(),
    state = listState
  ) {
    items(
      count = messages.size,
      key = { index -> System.identityHashCode(messages[index]) },
    ) { index ->
      val msg = messages[index]
      MessageItem(
        msg = msg,
        selected = selected.any { it === msg },
        selectionMode = selectionMode,
        showMemory = showMemory,
        showMenu = menuMsg === msg,
        modifier = Modifier.animateItemPlacement(ItemPlacementSpec),
        onLongPress = { onLongPress(msg) },
        onTap = { onTap(msg) },
        onDismissMenu = onDismissMenu,
        onCopy = { onCopyOne(msg) },
        onDelete = { onDeleteOne(msg) },
        onRollback = { onRollbackOne(msg) },
        onMultiSelect = { onMultiSelect(msg) },
      )
    }
    if (messages.isEmpty()) {
      item {
        Text(
          "还没有聊天记录,打个招呼吧",
          Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
          fontSize = 13.sp,
          color = AppTheme.colors.textSecondary,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

/**
 * 多选状态的顶栏:返回 · 已选条数 · 全选/复制/删除。
 *
 * 不复用 [TitleBar]:那个把标题居中叠在 [Box] 上,而多选时右边有三个动作
 * (全选 / 复制 / 删除),窄屏上居中的标题会被按钮压住。这里改成一行
 * 「返回 · 标题(吃掉剩余宽度,放不下就省略) · 动作」,三者永不重叠。
 */
@Composable
private fun SelectionTopBar(
  count: Int,
  allSelected: Boolean,
  onBack: () -> Unit,
  onToggleAll: () -> Unit,
  onCopy: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(AppTheme.colors.background)
      .statusBarsPadding()
      // heightIn 而不是 height:大字号下 15sp 文本不会被前后留白裁掉
      .heightIn(min = 48.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painterResource(R.drawable.ui_chevron_left),
      contentDescription = "退出多选",
      Modifier
        .clickable(onClick = onBack)
        .size(44.dp)
        .padding(14.dp),
      tint = AppTheme.colors.icon
    )
    Text(
      "已选择 $count 条",
      // 标题拿剩余宽度:按钮再多也只是把标题省略,不会互相盖住
      Modifier
        .weight(1f)
        .padding(start = 2.dp, end = 4.dp),
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    BarAction(if (allSelected) "取消全选" else "全选", AppTheme.colors.iconCurrent, onToggleAll)
    BarAction("复制", AppTheme.colors.iconCurrent, onCopy)
    BarAction("删除", AppTheme.colors.badge, onDelete)
  }
}

/** 顶栏上的文字按钮(多选时的全选/复制/删除)。 */
@Composable
private fun RowScope.BarAction(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
  Text(
    text,
    Modifier
      .clickable(onClick = onClick)
      .align(Alignment.CenterVertically)
      .padding(horizontal = 10.dp, vertical = 12.dp),
    fontSize = 15.sp,
    color = color,
    maxLines = 1
  )
}

/**
 * 输入栏上方的一行状态:正在等回复时显示「对方正在输入...」,请求失败时显示原因。
 *
 * 失败原因只是这一轮请求的状态,不进聊天记录:它不是谁说过的话,点一下就能收起。
 */
@Composable
private fun ChatStatusLine(
  error: String?,
  notice: String?,
  replying: Boolean,
  onDismissError: () -> Unit,
  onDismissNotice: () -> Unit,
) {
  val text = error ?: notice ?: if (replying) "对方正在输入..." else null
  if (text == null) return
  val dismiss = if (error != null) onDismissError else if (notice != null) onDismissNotice else null
  Row(
    Modifier
      .fillMaxWidth()
      .background(AppTheme.colors.background)
      .clickable(enabled = dismiss != null, onClick = { dismiss?.invoke() })
      .padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text,
      Modifier.weight(1f),
      fontSize = 12.sp,
      color = if (error != null) AppTheme.colors.badge else AppTheme.colors.textSecondary,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis
    )
    if (dismiss != null) {
      Text("知道了", Modifier.padding(start = 12.dp), fontSize = 12.sp, color = AppTheme.colors.iconCurrent)
    }
  }
}

/** 输入框右侧按钮的直径:附件和发送必须一样大,并排时视觉重量才一致。 */
private val BAR_BUTTON_SIZE = 36.dp
/** 按钮外圈留白:扩大点击区,同时不让按钮贴住输入框和屏幕右边缘。 */
private val BAR_BUTTON_PADDING = 4.dp

/**
 * 聊天页底栏:输入栏 + 可从屏幕底边展开的附件面板。
 *
 * 面板**不在输入栏上方**,也不是锚在加号旁的下拉菜单 —— 它排在输入栏下面、自己占住屏幕底边,
 * 展开时把输入栏顶上去(微信 / QQ / Telegram 的「+」都是这个交互,位移方向和输入法弹起一致)。
 * 主聊天和开发者控制台都用这一个组件,两边的附件入口长得一样。
 */
@Composable
fun ChatBottomBar(
  onSend: (String) -> Unit,
  /**
   * 底部面板的内容(入口网格或导演模式表单)。null 表示这页没有附件入口,
   * 加号只作视觉占位,和发送按钮并排时重量一致。
   */
  sheet: (@Composable () -> Unit)? = null,
  sheetOpen: Boolean = false,
  onSheetOpenChange: (Boolean) -> Unit = {},
  /**
   * 外部托管的输入内容:非 null 时输入框由调用方持有(而不是自己 remember 一份)。
   *
   * 开发者控制台的「两步发送」需要它:第一次点发送后输入框被清空、内容进了报文编辑页,
   * 用户在那里取消时要把原文放回输入框——这份文本不能跟着输入框的内部状态一起丢掉。
   */
  draft: String? = null,
  onDraftChange: (String) -> Unit = {},
) {
  var internalText by remember { mutableStateOf("") }
  val editingText = draft ?: internalText
  val writeText: (String) -> Unit = { if (draft == null) internalText = it else onDraftChange(it) }
  val canSend = editingText.isNotBlank()
  val focusManager = LocalFocusManager.current
  val send = {
    val text = editingText.trim()
    if (text.isNotEmpty()) {
      onSend(text)
      writeText("")
    }
  }
  Column {
    ChatInputRow(
      editingText = editingText,
      writeText = writeText,
      send = send,
      canSend = canSend,
      hasSheet = sheet != null,
      sheetOpen = sheetOpen,
      onToggleSheet = {
        // 展开前先收键盘:面板要占的就是键盘那一块地方,两个同时在场只会互相遮挡
        if (!sheetOpen) focusManager.clearFocus()
        onSheetOpenChange(!sheetOpen)
      },
      onTextPressed = { if (sheetOpen) onSheetOpenChange(false) },
    )
    if (sheet != null) {
      // 面板排在输入栏**下面**,并自己让出键盘 / 导航栏的空间:
      // 展开时它从屏幕底边长出来、把输入栏顶上去,和输入法弹起是同一个位移方向。
      AnimatedVisibility(
        visible = sheetOpen,
        enter = expandVertically(
          animationSpec = tween(durationMillis = 180),
          expandFrom = Alignment.Bottom
        ) + fadeIn(animationSpec = tween(durationMillis = 120)),
        exit = shrinkVertically(
          animationSpec = tween(durationMillis = 160),
          shrinkTowards = Alignment.Bottom
        ) + fadeOut(animationSpec = tween(durationMillis = 100)),
      ) {
        Column(
          Modifier
            .fillMaxWidth()
            .sheetBottomSpace()
            .background(AppTheme.colors.bottomBar)
        ) {
          sheet()
        }
      }
    }
  }
}

/**
 * 底部面板要让出的空间:键盘弹起时让出键盘(面板被顶到键盘上方,导演模式里填时间时才不会被挡住),
 * 否则让出导航栏。
 *
 * 和 [TabBar] 的 `bottomBarSpace` 一样在**布局阶段**读值:键盘动画期间 inset 逐帧变化,
 * 放在组合阶段读会让整个面板每帧重组。
 */
@Composable
private fun Modifier.sheetBottomSpace(): Modifier {
  val imeInsets = WindowInsets.ime
  val navigationInsets = WindowInsets.navigationBars
  val density = LocalDensity.current
  return layout { measurable, constraints ->
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

/** 输入栏本体(输入框 + 加号 + 发送)。拆出来只为让 [ChatBottomBar] 里「输入栏 + 面板」的结构看得清。 */
@Composable
private fun ChatInputRow(
  editingText: String,
  writeText: (String) -> Unit,
  send: () -> Unit,
  canSend: Boolean,
  hasSheet: Boolean,
  sheetOpen: Boolean,
  onToggleSheet: () -> Unit,
  onTextPressed: () -> Unit,
) {
  TabBar(followsIme = true, reserveBottomSpace = !sheetOpen) {
    // 点输入框就收起面板(然后键盘自己弹出来):读交互事件而不是给输入框盖一层 clickable,
    // 盖上去会抢掉 BasicTextField 自己的聚焦与光标定位。
    val textInteraction = remember { MutableInteractionSource() }
    val pressed = rememberUpdatedState(onTextPressed)
    LaunchedEffect(textInteraction) {
      textInteraction.interactions.collect { if (it is PressInteraction.Press) pressed.value() }
    }
    BasicTextField(
      editingText, onValueChange = writeText,
      Modifier
        .weight(1f)
        .padding(4.dp, 8.dp)
        .height(40.dp)
        .clip(MaterialTheme.shapes.small)
        .background(AppTheme.colors.textFieldBackground)
        .padding(start = 8.dp, top = 10.dp, end = 8.dp),
      cursorBrush = SolidColor(AppTheme.colors.textPrimary),
      textStyle = TextStyle(fontSize = 15.sp, color = AppTheme.colors.textPrimary),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { send() }),
      interactionSource = textInteraction,
    )
    val plusRotation by animateFloatAsState(
      targetValue = if (sheetOpen) PLUS_OPEN_ROTATION else 0f
    )
    if (hasSheet) {
      // 有面板:加号是面板开关,展开时转成「×」
      Icon(
        painterResource(R.drawable.ui_plus_frame),
        contentDescription = "附件",
        Modifier
          .align(Alignment.CenterVertically)
          .clickable(onClick = onToggleSheet)
          .rotate(plusRotation)
          .padding(BAR_BUTTON_PADDING)
          .size(BAR_BUTTON_SIZE),
        tint = AppTheme.colors.textPrimary
      )
    } else {
      // 没有面板时,加号只是视觉占位(和发送按钮并排的重量一致)
      Icon(
        painterResource(R.drawable.ui_plus_frame),
        contentDescription = "附件",
        Modifier
          .align(Alignment.CenterVertically)
          .padding(BAR_BUTTON_PADDING)
          .size(BAR_BUTTON_SIZE),
        tint = AppTheme.colors.textPrimary
      )
    }
    SendButton(
      modifier = Modifier.align(Alignment.CenterVertically),
      canSend = canSend,
      onSend = send,
    )
  }
}

/** 面板展开时加号转成的角度(转 45° 就是「×」)。 */
private const val PLUS_OPEN_ROTATION = 45f

/**
 * 输入框右侧的发送按钮:有文字时从右边「长出来」。
 *
 * 它不占用附件按钮的位置,而是自己挤出一块宽度——展开动画一帧帧把附件按钮往左推,
 * 输入框(weight 1f)同时被挤窄,所以看起来是滑过去,而不是加号突然被换成小飞机。
 *
 * 单独拆成函数,是为了让 [AnimatedVisibility] 落到顶层的那个重载上——
 * 直接写在 [TabBar] 的 RowScope 里会和 RowScope.AnimatedVisibility 抢解析。
 */
@Composable
private fun SendButton(
  modifier: Modifier = Modifier,
  canSend: Boolean,
  onSend: () -> Unit,
) {
  AnimatedVisibility(
    visible = canSend,
    modifier = modifier,
    enter = expandHorizontally(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      ),
      expandFrom = Alignment.End
    ) + fadeIn(animationSpec = tween(durationMillis = 120)),
    exit = shrinkHorizontally(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      ),
      shrinkTowards = Alignment.End
    ) + fadeOut(animationSpec = tween(durationMillis = 120)),
  ) {
    Box(Modifier.padding(BAR_BUTTON_PADDING)) {
      Box(
        Modifier
          .size(BAR_BUTTON_SIZE)
          .clip(CircleShape)
          .background(AppTheme.colors.fab)
          .clickable(onClick = onSend),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          painterResource(R.drawable.ui_send_arrow),
          contentDescription = "发送",
          Modifier.size(20.dp),
          tint = AppTheme.colors.onFab
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
  msg: Msg,
  selected: Boolean = false,
  selectionMode: Boolean = false,
  showMemory: Boolean = false,
  showMenu: Boolean = false,
  modifier: Modifier = Modifier,
  onLongPress: () -> Unit = {},
  onTap: () -> Unit = {},
  onDismissMenu: () -> Unit = {},
  onCopy: () -> Unit = {},
  onDelete: () -> Unit = {},
  onRollback: () -> Unit = {},
  onMultiSelect: () -> Unit = {},
) {
  val mine = msg.from == User.Me
  val bubbleColor = if (mine) AppTheme.colors.bubbleMe else AppTheme.colors.bubbleOthers
  // drawBehind 不在 @Composable 作用域里,颜色必须先取好
  val selectedTint = AppTheme.colors.badge.copy(alpha = 0.16f)
  val haptic = LocalHapticFeedback.current
  Row(
    modifier
      .fillMaxWidth()
      .padding(8.dp),
    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
  ) {
    if (!mine) UserAvatar(msg.from, size = 40.dp)
    if (selectionMode && !mine) SelectMark(selected)
    Box {
      Column(
        Modifier
          .clip(RoundedCornerShape(4.dp))
          .combinedClickable(
            onClick = onTap,
            onLongClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onLongPress()
            }
          )
          // 用 drawWithCache:气泡路径只在尺寸/选中态变化时重建,滚动时不再每帧重算
          .drawWithCache {
            val bubble = Path().apply {
              val rect = RoundRect(
                10.dp.toPx(),
                0f,
                size.width - 10.dp.toPx(),
                size.height,
                4.dp.toPx(),
                4.dp.toPx()
              )
              addRoundRect(rect)
              // 小尖角指向说话的人:自己发朝右,对方发朝左
              if (mine) {
                moveTo(size.width - 10.dp.toPx(), 15.dp.toPx())
                lineTo(size.width - 5.dp.toPx(), 20.dp.toPx())
                lineTo(size.width - 10.dp.toPx(), 25.dp.toPx())
              } else {
                moveTo(10.dp.toPx(), 15.dp.toPx())
                lineTo(5.dp.toPx(), 20.dp.toPx())
                lineTo(10.dp.toPx(), 25.dp.toPx())
              }
              close()
            }
            onDrawBehind {
              drawPath(bubble, bubbleColor)
              // 选中时盖一层淡红底色,和没选中的区分开
              if (selected) drawPath(bubble, selectedTint)
            }
          }
          .padding(20.dp, 10.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
      ) {
        Text(
          msg.text,
          color = if (mine) AppTheme.colors.textPrimaryMe else AppTheme.colors.textPrimary
        )
        // Self-insert 会话:气泡里带一条本地时间,标记这一轮发生在几点几分
        msg.time?.let { time ->
          Text(
            time,
            Modifier.padding(top = 4.dp),
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary
          )
        }
        // 开发者模式的「全部显示」:这一轮落盘时的记忆索引,只挂在 AI 回复那一侧。
        // 条件里再判一次 DevMode:关掉开发者模式后,这些存储信息一定不会出现在普通界面上
        if (!mine && showMemory && DevMode.enabled) {
          msg.memory?.let { memory ->
            Text(
              memory.label(),
              Modifier.padding(top = 4.dp),
              fontSize = 10.sp,
              color = AppTheme.colors.textSecondary
            )
          }
        }
      }
      // 菜单锚定在这条气泡上,从它旁边长出来,而不是从屏幕底部弹出
      DropdownMenu(
        expanded = showMenu,
        onDismissRequest = onDismissMenu,
        offset = if (mine) DpOffset((-112).dp, 0.dp) else DpOffset(0.dp, 0.dp),
      ) {
        MenuRow("复制") { onCopy() }
        MenuRow("删除") { onDelete() }
        MenuRow("回滚") { onRollback() }
        MenuRow("多选") { onMultiSelect() }
      }
    }
    if (selectionMode && mine) SelectMark(selected)
    if (mine) UserAvatar(msg.from, size = 40.dp)
  }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
  DropdownMenuItem(onClick = onClick) {
    Text(text, fontSize = 15.sp, color = AppTheme.colors.textPrimary)
  }
}

/** 多选模式下气泡旁的小圆勾。 */
@Composable
private fun SelectMark(selected: Boolean) {
  Box(
    Modifier
      .padding(horizontal = 6.dp)
      .size(22.dp)
      .clip(CircleShape)
      .background(
        if (selected) AppTheme.colors.badge else AppTheme.colors.more.copy(alpha = 0.35f)
      ),
    contentAlignment = Alignment.Center
  ) {
    if (selected) {
      Icon(
        Icons.Filled.Check,
        contentDescription = "已选择",
        Modifier.size(14.dp),
        tint = AppTheme.colors.onBadge
      )
    }
  }
}
