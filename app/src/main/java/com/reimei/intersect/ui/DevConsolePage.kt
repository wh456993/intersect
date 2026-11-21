package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.i18n.t
import androidx.compose.runtime.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.ApiKeyStore
import com.reimei.intersect.data.ApiModels
import com.reimei.intersect.data.ApiProvider
import com.reimei.intersect.data.DevApiClient
import com.reimei.intersect.data.DevApiResult
import com.reimei.intersect.data.DevCharacter
import com.reimei.intersect.data.DevConsoleStore
import com.reimei.intersect.data.DevExchange
import com.reimei.intersect.data.DevLogFiles
import com.reimei.intersect.data.DevMsg
import com.reimei.intersect.data.DevParamSpec
import com.reimei.intersect.data.DevRequestConfig
import com.reimei.intersect.data.ImmediateMemoryState
import com.reimei.intersect.data.Item
import com.reimei.intersect.data.ItemStore
import com.reimei.intersect.data.MemoryJobs
import com.reimei.intersect.data.MemoryRounds
import com.reimei.intersect.data.MemoryStore
import com.reimei.intersect.data.MemorySummaryResult
import com.reimei.intersect.data.ModelNames
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.data.UsageLog
import com.reimei.intersect.data.User
import com.reimei.intersect.percentOffsetX
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志窗口最多留多少行,超出丢最旧的,避免无限涨。 */
private const val MAX_LOG_LINES = 300

/**
 * 开发者控制台发的请求在消耗统计里归到哪一组。
 *
 * 控制台没有「会话」的概念(有的是一次次手改请求体的实验),所以密钥组与聊天窗口
 * 都用这同一个可读标识,用户在「Token 统计」里一眼能看出这部分开销来自调试。
 */
private const val DEV_CONSOLE_GROUP = "开发者控制台"

/**
 * 控制台自己的物品目录挂在哪。
 *
 * 物品目录([ItemStore])是按「角色 id」分开存的;控制台没有通讯录里的角色,就用这个保留 id
 * 单独存一份,和任何好友的物品互不相干。好友 id 由 [FriendsStore.newFriendId] 生成
 * (`friend_<时间戳>`)或来自内置的助手,不会撞上它。
 */
private const val DEV_CONSOLE_ID = "dev-console"

/**
 * 开发者控制台:一个和主界面一样的聊天窗口,右上角挂两个入口(日志 / 报文)。
 *
 * - 「日志」:等宽字体的终端样式窗口,请求/响应/错误都写在这里,可复制、存文件;
 * - 「报文」:逐轮列出完整的请求体与完整响应,整段代码框展示,不可编辑。
 *
 * 底部附件按钮里的菜单(点加号弹出):
 * - 「全部显示 / 收起全部」:每条气泡下方是否带它自己的编码(发送方 UID v 接收方 UID:秒级时间),
 *   和主聊天附件菜单里的那一项是同一套开关;
 * - 「请求体」:整段 JSON 就是一个代码框,直接改整段文本,底部有重置与保存;
 * - 「角色信息」:和主聊天的角色信息页同一套入口(角色身份 / 物品管理器 / 时间管理器...),
 *   只是这一份只属于控制台:角色设定不写进通讯录,物品目录也不挂到任何好友名下;
 *   空间管理器与主聊天一致,仍是「开发中」;记忆库是真的(即时记忆层的总结按同样的
 *   规则注入控制台的报文,只是记忆挂在控制台自己的保留 id 上,不碰任何好友);
 * - 「模式」:切换 intersect 的扮演模式。
 *
 * 聊天记录会落盘([DevConsoleStore.loadMessages] / saveMessages):退出控制台再进来、
 * 甚至重启进程,记录都还在,不会每次清零。
 *
 * 发送是**两步**的(常开):第一次点发送不真的发出去,而是把这一轮的完整报文拼好
 * (body 自带的 system + 角色设定 + 已有对话 + 本轮输入),推进「本次请求体」页让用户
 * 逐字细改;在那个页面里点「发送」才真正 POST 出去,取消则整轮作废、原文放回输入框。
 *
 * 真正发请求走 [DevApiClient],KEY 从「API KEY 管理」里按接口地址取。
 */
@Composable
fun DevConsole(
  modifier: Modifier = Modifier,
  viewModel: MainViewModel,
  onBack: () -> Unit,
  intersectMode: RoleplayMode,
  onSwitchIntersectMode: (RoleplayMode) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboardManager.current

  var config by remember { mutableStateOf(DevConsoleStore.load(context)) }
  /** 控制台的角色设定:只在控制台里用,不写进通讯录。 */
  var character by remember { mutableStateOf(DevConsoleStore.loadCharacter(context)) }
  // 聊天记录会落盘:退出控制台 / 重启进程后接着上次的记录继续,不再每次从零开始
  val messages = remember {
    mutableStateListOf<DevMsg>().apply { addAll(DevConsoleStore.loadMessages(context)) }
  }
  /**
   * 是否在每条气泡下方显示它自己的编码(发送方 UID v 接收方 UID:精确到秒的时间)。
   *
   * 和主聊天附件菜单里的「全部显示」是同一套开关:默认显示,不想看就点「收起全部」。
   */
  var showCodes by remember { mutableStateOf(true) }
  val logs = remember { mutableStateListOf<String>() }
  val exchanges = remember { mutableStateListOf<DevExchange>() }
  var showLog by remember { mutableStateOf(false) }
  var showExchanges by remember { mutableStateOf(false) }
  var showEditor by remember { mutableStateOf(false) }
  var showCharacter by remember { mutableStateOf(false) }
  var showMode by remember { mutableStateOf(false) }
  var sending by remember { mutableStateOf(false) }
  /** 日志页上的一次性提示(已复制 N 行 / 保存到哪),不写进日志本身。 */
  var logNotice by remember { mutableStateOf<String?>(null) }
  /** 拼好待确认的报文;非 null 时「本次请求体」页在屏幕上。 */
  var pending by remember { mutableStateOf<PendingRequest?>(null) }
  /** 控制台的物品目录(自己的那份,不混进任何好友名下)。 */
  val items = remember { mutableStateListOf<Item>() }
  var showCharacterInfo by remember { mutableStateOf(false) }
  var showItems by remember { mutableStateOf(false) }
  var showAddItem by remember { mutableStateOf(false) }
  /** 记忆库:和主聊天同一页([MemoryLibraryPage]),只是记忆挂在控制台自己的保留 id 上。 */
  var showMemory by remember { mutableStateOf(false) }
  /** 记忆压测面板(TASK-04):三个上帝按钮(注入假数据 / 强制总结 / 状态看板)。 */
  var showStress by remember { mutableStateOf(false) }
  /** 输入框内容由控制台持有:取消本轮时要把原文放回输入框,不能随输入框内部状态一起丢。 */
  var draftText by remember { mutableStateOf("") }
  /** 底部附件面板是否展开(和主聊天同一套交互:点加号从屏幕底边长出来)。 */
  var attachOpen by remember { mutableStateOf(false) }
  /** 模型选择页:列出当前这家接口的所有模型,点了就换(同一个 API 的不同模型)。 */
  var showModelPicker by remember { mutableStateOf(false) }

  // 多选与气泡菜单:与主聊天([ConversationPage])同一套交互 —— 长按出菜单,多选后顶栏换成操作条
  val selected = remember { mutableStateListOf<DevMsg>() }
  val selectionMode = selected.isNotEmpty()
  /** 长按哪条就在哪条旁边弹菜单;null 表示没有打开的菜单。 */
  var menuMsg by remember { mutableStateOf<DevMsg?>(null) }
  /** 待确认删除的消息,null 表示没有弹确认框。 */
  var pendingDelete by remember { mutableStateOf<List<DevMsg>?>(null) }
  /** 待确认回滚的那条消息(删掉它以及它之后的所有消息),null 表示没有弹确认框。 */
  var pendingRollback by remember { mutableStateOf<DevMsg?>(null) }

  fun log(text: String) {
    val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    text.lines().forEach { line -> logs.add("$stamp  $line") }
    while (logs.size > MAX_LOG_LINES) logs.removeAt(0)
  }

  /** 新增一条消息并立刻落盘:控制台的聊天不会因为退出页面或重启进程被清零。 */
  fun append(msg: DevMsg) {
    messages.add(msg)
    DevConsoleStore.saveMessages(context, messages.toList())
  }

  /**
   * 控制台自己的对话轮次(用户一句 + 模型一句为一轮)。
   *
   * 控制台的记录是 [DevMsg],按同一套规则转成 [Msg] 再交给 [MemoryRounds] 分组,
   * 这样记忆库里的总结、覆盖轮次与主聊天是同一把尺子。
   */
  fun consoleRounds(): List<List<Msg>> {
    val devCharacter = User(DEV_CONSOLE_ID, "intersect", R.drawable.ui_avatar_helper)
    return MemoryRounds.group(
      messages.filter { !it.error }.map { msg ->
        Msg(
          from = if (msg.role == "user") User.Me else devCharacter,
          text = msg.text,
          time = msg.time,
        )
      }
    )
  }

  /** 删掉若干条消息并落盘;按对象身份匹配,内容相同的两条各删各的。 */
  fun deleteMsgs(targets: Collection<DevMsg>) {
    if (targets.isEmpty()) return
    val before = messages.size
    messages.removeAll { msg -> targets.any { it === msg } }
    DevConsoleStore.saveMessages(context, messages.toList())
    // 记忆层的轮数与覆盖轮次跟着校正:否则「距下次总结」会一直停着不动,
    // 还会继续注入已经被删掉的那段对话的总结
    MemoryStore.reconcile(context.applicationContext, DEV_CONSOLE_ID, consoleRounds().size)
    log("delete: ${before - messages.size} 条")
  }

  /**
   * 回滚到某条消息之前:这条以及它之后的全部消息一起删掉。
   *
   * 调试时最常用的一步 —— 报文改到一半发歪了,直接退回到出问题的那一条前面重来。
   * 与「删除」一样只动控制台自己的聊天记录,不影响主聊天、物品与记忆库。
   */
  fun rollbackTo(msg: DevMsg) {
    val index = messages.indexOfFirst { it === msg }
    if (index < 0) return
    val removed = messages.size - index
    while (messages.size > index) messages.removeAt(messages.lastIndex)
    DevConsoleStore.saveMessages(context, messages.toList())
    MemoryStore.reconcile(context.applicationContext, DEV_CONSOLE_ID, consoleRounds().size)
    log("rollback: 从第 ${index + 1} 条起回滚,删除 $removed 条")
  }

  /** 多选:全选 / 取消全选。 */
  fun selectAll() {
    if (selected.size == messages.size) {
      selected.clear()
    } else {
      selected.clear()
      selected.addAll(messages)
    }
  }

  LaunchedEffect(Unit) {
    items.addAll(ItemStore.load(context.applicationContext, DEV_CONSOLE_ID))
    log("dev console ready")
    log("chat restored: ${messages.size} 条")
    log("provider=${config.provider} model=${config.model()}")
    log("endpoint=${config.endpoint()}")
    log("character=${if (character.isBlank) "(未设置)" else character.systemPrompt()}")
    log("items=${items.size} 件")
  }

  /** 物品目录的增删:每次都整体写盘,和主聊天([MainViewModel])那边的做法一致。 */
  fun addItem(name: String, description: String) {
    items.add(Item(ItemStore.newId(), name, description))
    ItemStore.save(context.applicationContext, DEV_CONSOLE_ID, items)
    log("item added: $name")
  }

  fun deleteItem(item: Item) {
    items.removeAll { it.id == item.id }
    ItemStore.save(context.applicationContext, DEV_CONSOLE_ID, items)
    log("item deleted: ${item.name}")
  }

  /** 本次要用的 KEY 条目:先按接口地址匹配,再按模型名,最后退回第一条。 */
  fun resolveKeyObject(): ApiKey? {
    val stored = ApiKeyStore.load(context)
    val targetUrl = config.url.trim().trimEnd('/')
    val model = config.model()
    return stored.firstOrNull { it.url.trim().trimEnd('/') == targetUrl }
      ?: stored.firstOrNull { it.model.trim().equals(model, ignoreCase = true) }
      ?: stored.firstOrNull()
  }

  /** 本次要用的 KEY 原文;没有可用配置时为空串。 */
  fun resolveKey(): String = resolveKeyObject()?.key.orEmpty()

  /**
   * 一键改请求配置(模型 / 深度思考 / 联网搜索):改完立刻落盘并写一行日志。
   *
   * [next] 为 null 表示请求体不是合法 JSON 对象 —— 这时只提示,不动配置,
   * 免得用户以为「点了没反应」。返回是否真的改了。
   */
  fun applyConfig(next: DevRequestConfig?, what: String): Boolean {
    if (next == null) {
      log("error: $what 改不了 —— 请求体不是合法 JSON 对象")
      return false
    }
    config = next
    DevConsoleStore.save(context, next)
    log(
      "config: $what → model=${next.rawModel()} " +
        "thinking=${next.thinkingEnabled()} search=${next.searchEnabled()}"
    )
    return true
  }

  /**
   * 第一下发送:不真的发,先把这一轮的完整报文拼好推给「本次请求体」页。
   *
   * 拼法就是 [DevRequestConfig.payload];这里**不往 [messages] 里加这条 user 消息**——
   * 它还没发出去。等用户在编辑页点发送时才进会话(见 [dispatch]),取消就整轮作废。
   */
  fun stage(text: String) {
    if (sending) return
    // intersect 切到 Self-insert 时,控制台发的每轮对话也由时间管理器注入本地时间
    val injectTime = intersectMode == RoleplayMode.SELF_INSERT
    if (resolveKey().isBlank()) {
      log("error: 没有可用的 API KEY")
      append(
        DevMsg.intersect(
          "没有可用的 API KEY。请到「我 → API KEY 管理」里添加一条与 ${config.provider} 地址匹配的配置。",
          error = true
        )
      )
      return
    }
    val message = DevMsg.me(text, time = if (injectTime) TimeManager.nowDateTime() else null)
    // 记忆库:和控制台自己的记忆状态同口径 —— 已总结的轮次不再注入原文
    val memory = MemoryStore.load(context.applicationContext, DEV_CONSOLE_ID)
    val payload = config.payload(messages.toList() + message, character, items, memory)
    pending = PendingRequest(payload = payload, text = text, injectTime = injectTime)
    log("draft: 请求体已拼好 (${payload.length} chars),改完再点发送才会真正发出")
  }

  /**
   * 第二下发送(「本次请求体」页里的发送):把用户手改过的报文逐字发出去,不再重新拼。
   *
   * [payload] 是编辑页里的最终文本,原样交给 [DevApiClient];模型名以它为准确,
   * 因为它可能被改成别的模型(消耗统计要跟着变)。
   */
  fun dispatch(payload: String, text: String, injectTime: Boolean) {
    val endpoint = config.endpoint()
    val provider = config.provider
    val model = runCatching { JSONObject(payload).optString("model") }.getOrDefault("")
      .ifBlank { config.model() }
    val apiKey = resolveKey()
    append(DevMsg.me(text, time = if (injectTime) TimeManager.nowDateTime() else null))
    log("--> POST $endpoint")
    log("--> key ${maskKey(apiKey)} model=$model msgs=${messages.size}")
    log("--> body ${payload.replace("\n", " ")}")

    sending = true
    scope.launch {
      val result = DevApiClient.chat(apiKey, payload, endpoint)
      sending = false
      val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
      exchanges.add(
        DevExchange(
          index = exchanges.size + 1,
          time = time,
          provider = provider,
          endpoint = endpoint,
          status = result.statusCode,
          request = payload,
          response = result.raw.ifBlank { "(没有响应体)" },
        )
      )
      when (result) {
        is DevApiResult.Ok -> {
          log("<-- ${result.statusCode} ok, ${result.text.length} chars")
          append(
            DevMsg.intersect(result.text, time = if (injectTime) TimeManager.nowDateTime() else null)
          )
          // 即时记忆层:轮数按控制台自己的对话校正,攒够 20 轮就总结一次(与主聊天同一套规则)
          val rounds = consoleRounds()
          val memoryBefore = MemoryStore.load(context.applicationContext, DEV_CONSOLE_ID)
          val afterRound = MemoryStore.afterRound(
            context = context.applicationContext,
            friendId = DEV_CONSOLE_ID,
            roundsCount = rounds.size,
            injectedBlock = memoryBefore.injectBlock,
          )
          if (afterRound.needsSummary) {
            val launched = MemoryJobs.launch(
              context = context.applicationContext,
              friendId = DEV_CONSOLE_ID,
              apiKey = resolveKeyObject(),
              state = afterRound,
              rounds = rounds,
            ) { summary ->
              log(
                when (summary) {
                  null -> "memory: 总结已在跑,本次跳过"
                  is MemorySummaryResult.Ok -> "memory: 总结完成 ${summary.actualTokens} token"
                  is MemorySummaryResult.Fail -> "memory error: ${summary.message}"
                }
              )
            }
            if (launched) log("memory: 开始总结前 ${ImmediateMemoryState.SUMMARY_EVERY} 轮")
          }
          // 控制台发的请求同样花钱:一次成功调用落一条流水,归到「开发者控制台」这个伪窗口
          withContext(Dispatchers.IO) {
            UsageLog.record(
              context = context,
              keyGroupId = DEV_CONSOLE_GROUP,
              model = model,
              chatWindowId = DEV_CONSOLE_GROUP,
              usage = result.usage
            )
          }
        }

        is DevApiResult.Fail -> {
          log("<-- ${result.statusCode} fail: ${result.message}")
          append(DevMsg.intersect(result.message, error = true))
        }
      }
    }
  }

  Box(modifier.fillMaxSize()) {
    Column(
      Modifier
        .fillMaxSize()
        .background(AppTheme.colors.background)
    ) {
      if (selectionMode) {
        // 多选顶栏:全选 / 复制 / 删除,返回键退出多选
        DevSelectionTopBar(
          count = selected.size,
          total = messages.size,
          onClose = { selected.clear() },
          onSelectAll = { selectAll() },
          onCopy = {
            // 按聊天顺序复制,而不是按点选顺序:复制出去要能直接读
            val picked = messages.filter { msg -> selected.any { it === msg } }
            clipboard.setText(AnnotatedString(picked.joinToString("\n") { it.text }))
            log("copy: ${picked.size} 条")
            selected.clear()
          },
          onDelete = {
            pendingDelete = selected.toList()
          }
        )
      } else {
        DevConsoleTopBar(
          title = "${config.provider} · ${ModelNames.display(config.model())}",
          onBack = onBack,
          onLog = { showLog = true },
          onExchanges = { showExchanges = true }
        )
        // 模型 / 深度思考 / 联网搜索三个按钮:点一下就改请求配置,不用进「请求体」页手写 JSON
        DevRequestBar(
          config = config,
          onPickModel = { showModelPicker = true },
          onToggleThinking = {
            applyConfig(config.withThinking(!config.thinkingEnabled()), "深度思考")
          },
          onToggleSearch = {
            applyConfig(config.withSearch(!config.searchEnabled()), "联网搜索")
          },
        )
      }
      MessageList(
        Modifier.weight(1f),
        messages = messages,
        sending = sending,
        showCodes = showCodes,
        selected = selected,
        selectionMode = selectionMode,
        menuMsg = menuMsg,
        onLongPress = { msg ->
          if (selectionMode) toggleSelect(selected, msg) else menuMsg = msg
        },
        onTap = { msg -> if (selectionMode) toggleSelect(selected, msg) },
        onDismissMenu = { menuMsg = null },
        onCopyOne = { msg ->
          clipboard.setText(AnnotatedString(msg.text))
          log("copy: 1 条")
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
          selected.clear()
          selected.add(msg)
        },
      )
      ChatBottomBar(
        // 第一下只拼报文、开「本次请求体」页;真正发出在那一页的发送按钮里
        onSend = { stage(it) },
        // 输入内容由控制台托管:取消本轮时要把原文放回输入框
        draft = draftText,
        onDraftChange = { draftText = it },
        sheetOpen = attachOpen,
        onSheetOpenChange = { attachOpen = it },
        sheet = {
          // 和主聊天同一套底部面板:点加号从屏幕底边长出来,输入栏被顶上去
          AttachSheet(
            listOf(
              // 「全部显示」:和主聊天附件面板里那一项同一套开关,打开后每条气泡下面带上它自己的编码
              AttachEntry(
                label = if (showCodes) "收起全部" else "全部显示",
                iconRes = R.drawable.ui_menu_bars,
              ) {
                showCodes = !showCodes
                attachOpen = false
              },
              AttachEntry("请求体", R.drawable.ui_dev_request) {
                attachOpen = false
                showEditor = true
              },
              // 和主聊天一致:角色信息是一层入口,角色身份 / 物品管理器都在它下面
              AttachEntry("角色信息", R.drawable.ui_dev_character) {
                attachOpen = false
                showCharacterInfo = true
              },
              // 压测面板:注入假数据 / 强制触发总结 / 看当前记忆库状态(动的是主聊天的真实数据)
              AttachEntry("压测面板", R.drawable.ui_dev_stress) {
                attachOpen = false
                showStress = true
              },
              AttachEntry("模式", R.drawable.ui_dev_mode) {
                attachOpen = false
                showMode = true
              },
            )
          )
        },
      )
    }

    // 返回键:先收菜单,再退多选,最后才是离开控制台
    BackHandler(enabled = menuMsg != null) { menuMsg = null }
    BackHandler(enabled = menuMsg == null && selectionMode) { selected.clear() }
    // 附件面板展开时,返回先收面板(和主聊天一致)
    BackHandler(enabled = attachOpen) { attachOpen = false }

    val confirmDelete = pendingDelete
    if (confirmDelete != null) {
      AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("删除聊天记录") },
        text = {
          Text(
            if (confirmDelete.size == 1) "这条消息将被删除,且无法恢复。"
            else "选中的 ${confirmDelete.size} 条消息将被删除,且无法恢复。"
          )
        },
        confirmButton = {
          TextButton(onClick = {
            deleteMsgs(confirmDelete)
            selected.clear()
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
      val index = messages.indexOfFirst { it === confirmRollback }
      AlertDialog(
        onDismissRequest = { pendingRollback = null },
        title = { Text("确认回滚") },
        text = {
          Text(
            if (index < 0) "这条消息已经不在了。"
            else "将删除此消息之后的所有消息。此操作不可撤销,确定继续吗?"
          )
        },
        confirmButton = {
          if (index >= 0) {
            TextButton(onClick = {
              rollbackTo(confirmRollback)
              selected.clear()
              pendingRollback = null
            }) { Text("确认回滚", color = AppTheme.colors.badge) }
          }
        },
        dismissButton = {
          TextButton(onClick = { pendingRollback = null }) { Text("取消") }
        }
      )
    }

    val draftOffset by animateFloatAsState(
      targetValue = if (pending != null) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    val staged = pending
    if (staged != null) {
      StagedRequestEditor(
        Modifier.percentOffsetX(draftOffset),
        payload = staged.payload,
        endpoint = config.endpoint(),
        onCancel = {
          log("draft discarded, ${staged.payload.length} chars")
          // 本轮作废,原文放回输入框,用户接着改就是了
          draftText = staged.text
          pending = null
        },
        onSend = { edited ->
          pending = null
          dispatch(edited, staged.text, staged.injectTime)
        }
      )
    }

    val logOffset by animateFloatAsState(
      targetValue = if (showLog) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showLog) {
      LogTerminal(
        Modifier.percentOffsetX(logOffset),
        logs = logs,
        config = config,
        notice = logNotice,
        onBack = {
          showLog = false
          logNotice = null
        },
        onClear = {
          logs.clear()
          log("log cleared")
          logNotice = null
        },
        onCopy = {
          clipboard.setText(AnnotatedString(logs.joinToString("\n")))
          logNotice = "已复制 ${logs.size} 行到剪贴板"
        },
        onSave = {
          val snapshot = logs.toList()
          scope.launch {
            logNotice = runCatching { DevLogFiles.save(context, snapshot) }
              .fold(
                onSuccess = { "已保存:${it.absolutePath}" },
                onFailure = { "保存失败:${it.message ?: "无法写入外部存储"}" }
              )
          }
        }
      )
    }

    val exchangesOffset by animateFloatAsState(
      targetValue = if (showExchanges) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showExchanges) {
      ExchangePage(
        Modifier.percentOffsetX(exchangesOffset),
        exchanges = exchanges,
        onBack = { showExchanges = false }
      )
    }

    val editorOffset by animateFloatAsState(
      targetValue = if (showEditor) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showEditor) {
      RequestEditor(
        Modifier.percentOffsetX(editorOffset),
        config = config,
        onBack = { showEditor = false },
        onSave = { saved ->
          config = saved
          DevConsoleStore.save(context, saved)
          log("config saved: ${saved.provider} / ${saved.model()}")
          showEditor = false
        }
      )
    }

    val characterInfoOffset by animateFloatAsState(
      targetValue = if (showCharacterInfo) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showCharacterInfo) {
      DevCharacterInfoPage(
        Modifier.percentOffsetX(characterInfoOffset),
        character = character,
        itemCount = items.size,
        selfInsert = intersectMode == RoleplayMode.SELF_INSERT,
        onOpenIdentity = { showCharacter = true },
        onOpenMemory = { showMemory = true },
        onOpenItems = { showItems = true },
        onBack = { showCharacterInfo = false }
      )
    }

    val characterOffset by animateFloatAsState(
      targetValue = if (showCharacter) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showCharacter) {
      CharacterIdentityEditor(
        Modifier.percentOffsetX(characterOffset),
        stateKey = DEV_CONSOLE_ID,
        title = "角色身份",
        initialName = character.name,
        initialPersonality = character.personality,
        initialRole = character.role,
        onBack = { showCharacter = false },
        onSave = { name, personality, role ->
          val saved = DevCharacter(name = name, role = role, personality = personality)
          character = saved
          DevConsoleStore.saveCharacter(context, saved)
          log("character saved: ${saved.systemPrompt()}")
          showCharacter = false
        }
      )
    }

    val itemsOffset by animateFloatAsState(
      targetValue = if (showItems) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showItems) {
      ItemManagerPage(
        Modifier.percentOffsetX(itemsOffset),
        items = items,
        onBack = { showItems = false },
        onAdd = { showAddItem = true },
        onDelete = { deleteItem(it) }
      )
    }

    val addItemOffset by animateFloatAsState(
      targetValue = if (showAddItem) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showAddItem) {
      AddItemPage(
        Modifier.percentOffsetX(addItemOffset),
        onBack = { showAddItem = false },
        onSave = { name, description ->
          addItem(name, description)
          showAddItem = false
        }
      )
    }

    // 记忆压测面板:三个上帝按钮。这里动的是**主聊天**的真实会话与 memory.db
    // (而不是控制台自己的 DEV_CONSOLE_ID)—— 压测要测的就是用户真正跑的那条链路。
    val stressOffset by animateFloatAsState(
      targetValue = if (showStress) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showStress) {
      DevStressPanel(
        Modifier.percentOffsetX(stressOffset),
        viewModel = viewModel,
        onBack = { showStress = false }
      )
    }

    // 记忆库:与主聊天同一页(即时记忆层 / 引导线索层 / 长期记忆层)。
    // 控制台没有通讯录里的角色,记忆挂在 DEV_CONSOLE_ID 上,和任何好友互不相干;
    // 总结用的轮次从控制台自己的聊天记录里按「用户一句 + 模型一句」分组。
    val memoryOffset by animateFloatAsState(
      targetValue = if (showMemory) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showMemory) {
      MemoryLibraryPage(
        Modifier.percentOffsetX(memoryOffset),
        friendId = DEV_CONSOLE_ID,
        // 控制台的总结仍走旧链路(MemoryJobs → SharedPreferences),这两个页签只读 memory.db
        note = "控制台的总结写的是旧版记忆库,这里只读 memory.db,所以通常是空的;" +
          "要测真实数据请用「压测面板」选一个主聊天的角色。",
        onBack = { showMemory = false }
      )
    }

    // intersect 的扮演模式切换:手动在两种模式间切,选完即生效并写盘
    val modeOffset by animateFloatAsState(
      targetValue = if (showMode) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showMode) {
      ChoicePage(
        title = "intersect 模式",
        options = listOf(
          ChoiceOption(
            title = "角色代入",
            selected = intersectMode == RoleplayMode.AVATAR_BASED,
            onClick = {
              onSwitchIntersectMode(RoleplayMode.AVATAR_BASED)
              showMode = false
            }
          ),
          ChoiceOption(
            title = "本体代入",
            selected = intersectMode == RoleplayMode.SELF_INSERT,
            onClick = {
              onSwitchIntersectMode(RoleplayMode.SELF_INSERT)
              showMode = false
            }
          ),
        ),
        modifier = Modifier.percentOffsetX(modeOffset),
        onBack = { showMode = false }
      )
    }

    // 模型选择:只列当前这家接口自己的模型 —— 同一个 API 的不同模型,换模型不用重填 URL 与 KEY
    val modelPickerOffset by animateFloatAsState(
      targetValue = if (showModelPicker) 0f else 1f,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      )
    )
    if (showModelPicker) {
      val models = config.providerSpec().models
      val current = config.rawModel()
      ChoicePage(
        title = "选择模型",
        options = models.map { name ->
          ChoiceOption(
            title = ModelNames.display(name),
            selected = name.equals(current, ignoreCase = true),
            onClick = {
              if (applyConfig(config.withModel(name), "模型")) showModelPicker = false
            }
          )
        },
        modifier = Modifier.percentOffsetX(modelPickerOffset),
        onBack = { showModelPicker = false }
      )
    }
  }
}

/**
 * 第一次点发送时拼好的这一轮请求:还没发出去,等用户在「本次请求体」页细改确认。
 *
 * [payload] 是拼好的完整报文(用户可逐字改);[text] 是用户这一轮输入的原话,
 * 确认发送时才作为一条 user 消息进会话——取消则整轮作废,原文回填输入框。
 */
private data class PendingRequest(
  val payload: String,
  val text: String,
  val injectTime: Boolean,
)

/**
 * 可编辑的请求体代码框:深色底 + 等宽字体,顶上小字写明这份报文发往哪。
 *
 * 「请求体」配置页与「本次请求体」页共用——两边改的都是同一段 JSON 文本,
 * 只是一个写盘、一个只用于这一轮。
 */
@Composable
private fun RequestBodyBox(
  text: String,
  subtitle: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(TerminalBackground)
      .padding(10.dp)
  ) {
    Text(
      subtitle,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      color = TerminalDim,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(6.dp))
    BasicTextField(
      value = text,
      onValueChange = onValueChange,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = TerminalText
      ),
      cursorBrush = SolidColor(TerminalGreen),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
    )
  }
}

/**
 * 第一下发送之后的「本次请求体」页:这一轮真正要发出去的报文全文,可以先改再发。
 *
 * 与「请求体」配置页的区别:这里改的只影响这一轮,不写回配置(配置是后续每一轮的底子)。
 * 「取消」= 本轮作废,原文回填输入框;「发送」= 把框里这份报文逐字 POST 出去。
 */
@Composable
private fun StagedRequestEditor(
  modifier: Modifier = Modifier,
  payload: String,
  endpoint: String,
  onCancel: () -> Unit,
  onSend: (String) -> Unit,
) {
  BackHandler { onCancel() }
  // 按 payload 记住草稿:换了一轮请求体就重新从这里开始改
  var text by remember(payload) { mutableStateOf(payload) }
  var error by remember { mutableStateOf<String?>(null) }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "本次请求体", onBack = onCancel)
    Text(
      "这就是这一轮真正要发出去的报文,可以逐字改:再点「发送」才发出,点「取消」则整轮作废(原文回填输入框)。改动不写回「请求体」配置。",
      Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
    Spacer(Modifier.height(8.dp))
    RequestBodyBox(
      text = text,
      subtitle = "POST $endpoint",
      onValueChange = {
        text = it
        error = null
      },
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 12.dp)
    )
    if (error != null) {
      Text(
        error!!,
        Modifier.padding(horizontal = 16.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.badge
      )
    }
    Row(
      Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.listItem)
          .clickable(onClick = onCancel)
          .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("取消", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      }
      Spacer(Modifier.width(12.dp))
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.iconCurrent)
          .clickable {
            // 报文是要发给服务商的,发之前至少要是个 JSON 对象,否则只会拿回一个 400
            if (runCatching { JSONObject(text) }.getOrNull() == null) {
              error = "报文不是合法的 JSON 对象"
            } else {
              error = null
              onSend(text)
            }
          }
          .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "发送",
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.listItem
        )
      }
    }
  }
}

/**
 * 开发者控制台的「角色信息」页:与控制台自己那份角色设定对应的入口。
 *
 * 排版与条目组件和主聊天的 [CharacterInfoPage] 是同一套,但**入口清单以主聊天为准**:
 * 控制台的 [DevCharacter] 没有扮演模式,也没有世界观 / 背景,所以这两项在这里不出现,
 * 空间管理器与主聊天一致,仍是「开发中」;记忆库是真的:
 * 它打开的是同一页 [MemoryLibraryPage],只是记忆挂在控制台自己的保留 id 上。
 *
 * [selfInsert] 为 true 时(全局 intersect 模式是 Self-insert)时间管理器显示实时时间,
 * 和主聊天里 Self-insert 角色的那一行一致。
 */
@Composable
private fun DevCharacterInfoPage(
  modifier: Modifier = Modifier,
  character: DevCharacter,
  itemCount: Int,
  selfInsert: Boolean,
  onOpenIdentity: () -> Unit,
  onOpenMemory: () -> Unit,
  onOpenItems: () -> Unit,
  onBack: () -> Unit,
) {
  BackHandler { onBack() }

  // 时间管理器每 30 秒重取一次,与主聊天同一节奏
  var now by remember { mutableStateOf(TimeManager.nowDateTime()) }
  if (selfInsert) {
    LaunchedEffect(Unit) {
      while (true) {
        now = TimeManager.nowDateTime()
        delay(30_000L)
      }
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "角色信息",
      actionText = "",
      actionEnabled = false,
      onBack = onBack,
      onAction = {}
    )

    Column(
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
    ) {
      // 名片:头像用内置的助手图(控制台没有真实角色),副标题直接显示这份设定拼出来的 system 内容
      CharacterProfileCard(
        avatar = User(DEV_CONSOLE_ID, character.name, R.drawable.ui_avatar_helper),
        name = character.name.ifBlank { "未命名角色" },
        detail = if (character.isBlank) "还没有填写角色设定" else character.systemPrompt(),
      )

      Spacer(Modifier.height(12.dp))

      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        CharacterEntryRow(
          title = "角色身份",
          detail = identitySummary(character.name, character.personality, character.role),
          onClick = onOpenIdentity
        )
        CharacterDivider()
        CharacterEntryRow(
          title = "记忆库",
          detail = "即时记忆 / 引导线索 / 长期记忆",
          onClick = onOpenMemory
        )
        CharacterDivider()
        ComingSoonRow("空间管理器")
        CharacterDivider()
        CharacterEntryRow(
          title = "物品管理器",
          detail = if (itemCount == 0) "登记物品,对话提到名称时自动补充简介"
          else "已登记 $itemCount 件 · 对话提到名称时自动补充简介",
          onClick = onOpenItems
        )
        CharacterDivider()
        if (selfInsert) {
          TimeManagerRow(now)
        } else {
          ComingSoonRow("时间管理器", BASE_TIME_NOTE)
        }
      }
    }
  }
}

/** KEY 只露头尾,日志里不留完整密钥。 */
private fun maskKey(key: String): String =
  if (key.length <= 8) "****" else "${key.take(4)}...${key.takeLast(4)}"

/** 顶栏:返回 + 居中标题 + 右上两个入口(日志 / 报文)。其余入口挪到底部附件按钮里。 */
@Composable
private fun DevConsoleTopBar(
  title: String,
  onBack: () -> Unit,
  onLog: () -> Unit,
  onExchanges: () -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(AppTheme.colors.background)
      .statusBarsPadding(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painterResource(R.drawable.ui_chevron_left),
      contentDescription = "返回",
      Modifier
        .clickable(onClick = onBack)
        .size(48.dp)
        .padding(14.dp),
      tint = AppTheme.colors.icon
    )
    // 标题占中间的剩余空间(两个入口是固定宽度),放不下就省略
    Text(
      title,
      Modifier
        .weight(1f)
        .padding(horizontal = 4.dp, vertical = 14.dp),
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center
    )
    Row {
      TopAction("日志", onLog)
      TopAction("报文", onExchanges)
    }
  }
}

@Composable
private fun TopAction(text: String, onClick: () -> Unit) {
  Text(
    text,
    Modifier
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 14.dp),
    fontSize = 13.sp,
    color = AppTheme.colors.iconCurrent
  )
}

/**
 * 顶栏下面那条请求配置条:模型 / 深度思考 / 联网搜索。
 *
 * 三个都是「点一下就换状态」的按钮,改完立刻写进请求体并落盘:
 * - **模型**:打开几选一页,列出当前这家接口自己的模型(同一个 API 的不同模型,不用重填 URL 与 KEY);
 * - **深度思考 / 联网搜索**:直接开 / 关。写法按服务商走(见 [DevParamSpec]):千问是请求体里的
 *   `enable_thinking` / `enable_search`,DeepSeek 的思考靠换模型,没内置参数的那家按钮置灰。
 *
 * 放在顶栏下而不是塞进附件菜单,是因为这两项要能一眼看出「现在开着还是关着」;
 * 三个按钮排在一行,窄屏放不下时整条可以横向滑。
 */
@Composable
private fun DevRequestBar(
  config: DevRequestConfig,
  onPickModel: () -> Unit,
  onToggleThinking: () -> Unit,
  onToggleSearch: () -> Unit,
) {
  val provider = config.providerSpec()
  val thinkingOn = config.thinkingEnabled()
  val searchOn = config.searchEnabled()
  Row(
    Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    DevBarChip(
      text = config.rawModel().ifEmpty { "未设置模型" },
      active = false,
      onClick = onPickModel
    )
    DevBarChip(
      text = toggleLabel("深度思考", thinkingOn, provider.thinking.supported),
      active = thinkingOn,
      enabled = provider.thinking.supported,
      onClick = onToggleThinking
    )
    DevBarChip(
      text = toggleLabel("联网搜索", searchOn, provider.search.supported),
      active = searchOn,
      enabled = provider.search.supported,
      onClick = onToggleSearch
    )
  }
}

/** 开关按钮上的文案:支持就写当前状态,不支持就直说 —— 免得点了没反应还一头雾水。 */
@Composable
private fun toggleLabel(title: String, on: Boolean, supported: Boolean): String = when {
  !supported -> t("{0}:不支持", t(title))
  on -> t("{0}:开", t(title))
  else -> t("{0}:关", t(title))
}

/** 配置条上的一个小按钮:选中态用主题色实心,置灰的表示这家没有这个开关。 */
@Composable
private fun DevBarChip(
  text: String,
  active: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  Box(
    Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(
        when {
          !enabled -> AppTheme.colors.textFieldBackground
          active -> AppTheme.colors.iconCurrent
          else -> AppTheme.colors.listItem
        }
      )
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp)
  ) {
    Text(
      text,
      fontSize = 11.sp,
      maxLines = 1,
      color = when {
        !enabled -> AppTheme.colors.more
        active -> AppTheme.colors.listItem
        else -> AppTheme.colors.textPrimary
      }
    )
  }
}

/**
 * 多选时替换顶栏的操作条:全选 / 复制 / 删除。
 *
 * 和主聊天([ConversationPage])的顶栏一致:左边的返回键 = 退出多选,不离开控制台。
 */
@Composable
private fun DevSelectionTopBar(
  count: Int,
  total: Int,
  onClose: () -> Unit,
  onSelectAll: () -> Unit,
  onCopy: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(AppTheme.colors.background)
      .statusBarsPadding(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painterResource(R.drawable.ui_chevron_left),
      contentDescription = "退出多选",
      Modifier
        .clickable(onClick = onClose)
        .size(48.dp)
        .padding(14.dp),
      tint = AppTheme.colors.icon
    )
    Text(
      "已选择 $count 条",
      Modifier
        .weight(1f)
        .padding(horizontal = 4.dp, vertical = 14.dp),
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Row {
      // 全选 / 取消全选:消息一条都没有时没什么可选的,但按钮留着不碍事
      TopAction(if (count == total && total > 0) "取消全选" else "全选", onSelectAll)
      TopAction("复制", onCopy)
      Text(
        "删除",
        Modifier
          .clickable(enabled = count > 0, onClick = onDelete)
          .padding(horizontal = 6.dp, vertical = 14.dp),
        fontSize = 13.sp,
        color = if (count > 0) AppTheme.colors.badge else AppTheme.colors.more
      )
    }
  }
}

/** 气泡列表:自己的消息靠右(绿),模型回复靠左(白),错误用红色淡底。 */
@Composable
private fun MessageList(
  modifier: Modifier = Modifier,
  messages: List<DevMsg>,
  sending: Boolean,
  showCodes: Boolean,
  selected: List<DevMsg> = emptyList(),
  selectionMode: Boolean = false,
  menuMsg: DevMsg? = null,
  onLongPress: (DevMsg) -> Unit = {},
  onTap: (DevMsg) -> Unit = {},
  onDismissMenu: () -> Unit = {},
  onCopyOne: (DevMsg) -> Unit = {},
  onDeleteOne: (DevMsg) -> Unit = {},
  onRollbackOne: (DevMsg) -> Unit = {},
  onMultiSelect: (DevMsg) -> Unit = {},
) {
  val listState = rememberLazyListState()
  val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
  LaunchedEffect(messages.size, sending) {
    val last = messages.lastIndex + if (sending) 1 else 0
    if (last >= 0) listState.animateScrollToItem(last)
  }
  LazyColumn(modifier.fillMaxSize(), state = listState) {
    itemsIndexed(messages) { _, msg ->
      DevBubble(
        msg = msg,
        maxWidth = maxBubble,
        showCode = showCodes,
        selected = selected.any { it === msg },
        selectionMode = selectionMode,
        showMenu = menuMsg === msg,
        onLongPress = { onLongPress(msg) },
        onTap = { onTap(msg) },
        onDismissMenu = onDismissMenu,
        onCopy = { onCopyOne(msg) },
        onDelete = { onDeleteOne(msg) },
        onRollback = { onRollbackOne(msg) },
        onMultiSelect = { onMultiSelect(msg) },
      )
    }
    if (sending) {
      item {
        Row(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.Start
        ) {
          Text(
            "正在请求...",
            Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(AppTheme.colors.bubbleOthers)
              .padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 14.sp,
            color = AppTheme.colors.textSecondary
          )
        }
      }
    }
    if (messages.isEmpty() && !sending) {
      item {
        Spacer(Modifier.height(48.dp))
      }
    }
  }
}

/**
 * 一条控制台消息气泡。
 *
 * 长按出菜单:复制 / 删除 / 回滚 / 多选 —— 回滚 = 删掉这条与它之后的所有消息,
 * 调试报文发歪了可以直接退回到出问题之前重来。多选模式下点一下即勾选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevBubble(
  msg: DevMsg,
  maxWidth: androidx.compose.ui.unit.Dp,
  showCode: Boolean,
  selected: Boolean = false,
  selectionMode: Boolean = false,
  showMenu: Boolean = false,
  onLongPress: () -> Unit = {},
  onTap: () -> Unit = {},
  onDismissMenu: () -> Unit = {},
  onCopy: () -> Unit = {},
  onDelete: () -> Unit = {},
  onRollback: () -> Unit = {},
  onMultiSelect: () -> Unit = {},
) {
  val mine = msg.role == "user"
  val background = when {
    msg.error -> AppTheme.colors.badge.copy(alpha = 0.12f)
    mine -> AppTheme.colors.bubbleMe
    else -> AppTheme.colors.bubbleOthers
  }
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp),
    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (selectionMode && !mine) SelectDot(selected)
    Box {
      Column(
        Modifier
          .widthIn(max = maxWidth)
          .clip(RoundedCornerShape(10.dp))
          .background(background)
          .combinedClickable(onClick = onTap, onLongClick = onLongPress)
          .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
      ) {
        Text(
          msg.text,
          fontSize = 15.sp,
          color = if (msg.error) AppTheme.colors.badge else AppTheme.colors.textPrimary
        )
        // 这条消息自己的编码:发送方 UID v 接收方 UID + 秒级时间,例 (1v0:2026-11-07 20:42:31)
        val code = msg.code()
        if (showCode && code != null) {
          Text(
            code,
            Modifier.padding(top = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary
          )
        }
      }
      // 菜单锚在气泡上:自己的消息在右边,往左挪一点免得出屏
      DropdownMenu(
        expanded = showMenu,
        onDismissRequest = onDismissMenu,
        offset = if (mine) DpOffset((-112).dp, 0.dp) else DpOffset(0.dp, 0.dp),
      ) {
        DevMenuRow("复制") { onCopy() }
        DevMenuRow("删除") { onDelete() }
        DevMenuRow("回滚") { onRollback() }
        DevMenuRow("多选") { onMultiSelect() }
      }
    }
    if (selectionMode && mine) SelectDot(selected)
  }
}

@Composable
private fun DevMenuRow(text: String, onClick: () -> Unit) {
  DropdownMenuItem(onClick = onClick) {
    Text(text, fontSize = 15.sp, color = AppTheme.colors.textPrimary)
  }
}

/** 多选模式下气泡旁的小圆勾。 */
@Composable
private fun SelectDot(selected: Boolean) {
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

/** 多选时按身份切换勾选状态:内容相同的两条消息各自独立。 */
private fun toggleSelect(selected: MutableList<DevMsg>, msg: DevMsg) {
  val index = selected.indexOfFirst { it === msg }
  if (index >= 0) selected.removeAt(index) else selected.add(msg)
}


/**
 * 日志窗口:Linux 终端的样子——深色底、等宽字体、每行带时间戳,底部自动跟随最新一行。
 *
 * 顶栏给三个动作:复制全部到剪贴板、存文件(用户目录下的「intersect日志」)、清空。
 */
@Composable
private fun LogTerminal(
  modifier: Modifier = Modifier,
  logs: List<String>,
  config: DevRequestConfig,
  notice: String?,
  onBack: () -> Unit,
  onClear: () -> Unit,
  onCopy: () -> Unit,
  onSave: () -> Unit
) {
  BackHandler { onBack() }
  val listState = rememberLazyListState()
  LaunchedEffect(logs.size) {
    if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
  }
  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(TerminalBackground)
      .statusBarsPadding()
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        painterResource(R.drawable.ui_chevron_left),
        contentDescription = "返回",
        Modifier
          .clickable(onClick = onBack)
          .size(36.dp)
          .padding(8.dp),
        tint = TerminalGreen
      )
      Text(
        "dev@intersect:~/logs",
        Modifier.weight(1f),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = TerminalGreen,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      TerminalAction("复制", onCopy)
      TerminalAction("保存", onSave)
      TerminalAction("清空", onClear)
    }
    Text(
      "$ ${config.provider} / ${ModelNames.display(config.model())} / ${config.endpoint()}",
      Modifier.padding(horizontal = 12.dp),
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      color = TerminalDim
    )
    if (notice != null) {
      Text(
        notice,
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = TerminalGreen
      )
    }
    Spacer(Modifier.height(4.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(
        Modifier
          .fillMaxSize()
          .padding(horizontal = 12.dp)
      ) {
        items(logs) { line ->
          Text(
            line,
            Modifier.padding(vertical = 1.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (line.contains("error") || line.contains("fail")) TerminalRed else TerminalText
          )
        }
      }
    }
  }
}

@Composable
private fun TerminalAction(text: String, onClick: () -> Unit) {
  Text(
    text,
    Modifier
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    color = TerminalGreen
  )
}

private val TerminalBackground = Color(0xFF0C120E)
private val TerminalText = Color(0xFFD7E3D6)
private val TerminalGreen = Color(0xFF8BE07A)
private val TerminalDim = Color(0xFF6E8672)
private val TerminalRed = Color(0xFFF08A8A)

/**
 * 「报文」页:每一轮一行抬头 + 请求体代码框 + 响应代码框。
 *
 * 全是只读的完整原文(能解析成 JSON 的就按 2 空格缩进展开),没有可以逐项编辑的地方;
 * 右上一键复制本轮,方便贴到别处。
 */
@Composable
private fun ExchangePage(
  modifier: Modifier = Modifier,
  exchanges: List<DevExchange>,
  onBack: () -> Unit
) {
  BackHandler { onBack() }
  val clipboard = LocalClipboardManager.current
  val listState = rememberLazyListState()
  LaunchedEffect(exchanges.size) {
    if (exchanges.isNotEmpty()) listState.animateScrollToItem(exchanges.lastIndex)
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "报文", onBack = onBack)
    if (exchanges.isEmpty()) {
      Column(
        Modifier
          .fillMaxSize()
          .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text("还没有请求", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      }
      return@Column
    }
    LazyColumn(
      Modifier.fillMaxSize(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    ) {
      items(exchanges, key = { it.index }) { exchange ->
        ExchangeCard(
          exchange = exchange,
          onCopy = {
            clipboard.setText(
              AnnotatedString(
                buildString {
                  appendLine("#${exchange.index} ${exchange.time} ${exchange.provider} ${exchange.endpoint}")
                  appendLine("HTTP ${exchange.status}")
                  appendLine("--- request ---")
                  appendLine(pretty(exchange.request))
                  appendLine("--- response ---")
                  append(pretty(exchange.response))
                }
              )
            )
          }
        )
        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun ExchangeCard(exchange: DevExchange, onCopy: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "#${exchange.index}",
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.iconCurrent
      )
      Spacer(Modifier.width(8.dp))
      Text(
        "${exchange.time} · ${exchange.provider} · HTTP ${exchange.status}",
        Modifier.weight(1f),
        fontSize = 12.sp,
        color = if (exchange.status in 200..299) AppTheme.colors.textSecondary
        else AppTheme.colors.badge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        "复制",
        Modifier
          .clickable(onClick = onCopy)
          .padding(horizontal = 6.dp, vertical = 4.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.iconCurrent
      )
    }
    Text(
      exchange.endpoint,
      Modifier.padding(top = 4.dp),
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(8.dp))
    // 按 exchange 本身缓存:请求/响应都是不可变字符串,格式化一遍就够。
    // 不能用 exchange.index —— 列表清空重填或流式追加时 index 不变而内容会变,会展示旧报文。
    val reqText = remember(exchange) { pretty(exchange.request) }
    val respText = remember(exchange) { pretty(exchange.response) }
    CodeBox(title = "请求体", code = reqText)
    Spacer(Modifier.height(8.dp))
    CodeBox(title = "响应", code = respText)
  }
}

/**
 * 只读代码框:深色底 + 等宽字体,里面是完整原文,横向可以拖,纵向限高。
 *
 * 用可横向滚动的 Text 而不是 TextField:这类内容只给看与复制,不给改。
 */
@Composable
private fun CodeBox(title: String, code: String) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(TerminalBackground)
      .padding(8.dp)
  ) {
    Text(
      title,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      color = TerminalDim
    )
    Spacer(Modifier.height(4.dp))
    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(max = 260.dp)
        .verticalScroll(rememberScrollState())
    ) {
      Text(
        code,
        Modifier.horizontalScroll(rememberScrollState()),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = TerminalText
      )
    }
  }
}

/** 能解析成 JSON 就按 2 空格缩进展开,否则原样返回。 */
private fun pretty(raw: String): String =
  runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }

/**
 * 请求体编辑页:整段 JSON 就是一个代码框。
 *
 * 直接改整段文本(不再一项一项地填),顶部能切服务商(切过去先按该家的默认体填好),
 * 底部是重置与保存。保存前校验:必须是 JSON 对象、且带 model 字段。
 */
@Composable
private fun RequestEditor(
  modifier: Modifier = Modifier,
  config: DevRequestConfig,
  onBack: () -> Unit,
  onSave: (DevRequestConfig) -> Unit
) {
  BackHandler { onBack() }
  var draft by remember { mutableStateOf(config) }
  var error by remember { mutableStateOf<String?>(null) }

  val provider = draft.providerSpec()

  /** 改请求体失败(JSON 坏了)时统一给这句提示,别让按钮看着像坏了。 */
  fun apply(next: DevRequestConfig?) {
    if (next == null) {
      error = "请求体不是合法的 JSON 对象"
    } else {
      draft = next
      error = null
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "请求体", onBack = onBack)
    ProviderChips(
      current = provider,
      onPick = { draft = DevRequestConfig.defaultFor(it) }
    )
    UrlRow(
      url = draft.url,
      onValueChange = { draft = draft.copy(url = it) }
    )
    // 模型:同一个接口地址下的不同模型,点一下只改 body 里的 model 字段
    ModelChips(
      models = provider.models,
      current = draft.rawModel(),
      onPick = { name -> apply(draft.withModel(name)) }
    )
    DevToggleRow(
      title = "深度思考",
      hint = specHint(provider.thinking),
      checked = draft.thinkingEnabled(),
      supported = provider.thinking.supported,
      onToggle = { apply(draft.withThinking(!draft.thinkingEnabled())) }
    )
    DevToggleRow(
      title = "联网搜索",
      hint = specHint(provider.search),
      checked = draft.searchEnabled(),
      supported = provider.search.supported,
      onToggle = { apply(draft.withSearch(!draft.searchEnabled())) }
    )
    Text(
      "角色设定在「角色」页填写,发送时会作为一条 system 消息插到最前面;这里只放请求体本身。" +
        "模型与两个开关也是改这份请求体,改完点下面的「保存」才落盘。",
      Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
    Spacer(Modifier.height(8.dp))
    RequestBodyBox(
      text = draft.body,
      subtitle = "POST ${draft.endpoint()}",
      onValueChange = {
        draft = draft.copy(body = it)
        error = null
      },
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 12.dp)
    )
    if (error != null) {
      Text(
        error!!,
        Modifier.padding(horizontal = 16.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.badge
      )
    }
    Row(
      Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.listItem)
          .clickable {
            draft = DevRequestConfig.defaultFor(provider)
            error = null
          }
          .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("重置默认", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      }
      Spacer(Modifier.width(12.dp))
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.iconCurrent)
          .clickable {
            val url = draft.url.trim()
            val parsed = runCatching { JSONObject(draft.body) }.getOrNull()
            when {
              !(url.startsWith("http://") || url.startsWith("https://")) ->
                error = "URL 必须以 http:// 或 https:// 开头"

              parsed == null -> error = "请求体不是合法的 JSON 对象"
              parsed.optString("model").isBlank() -> error = "请求体里缺少 model 字段"
              else -> {
                error = null
                onSave(draft.copy(url = url))
              }
            }
          }
          .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "保存",
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.listItem
        )
      }
    }
  }
}

@Composable
private fun ProviderChips(current: ApiProvider, onPick: (ApiProvider) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp)
  ) {
    ApiModels.PROVIDERS.forEach { provider ->
      val selected = provider.label == current.label
      Box(
        Modifier
          .weight(1f)
          .padding(horizontal = 4.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.listItem)
          .clickable { onPick(provider) }
          .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          provider.label,
          fontSize = 13.sp,
          color = if (selected) AppTheme.colors.listItem else AppTheme.colors.textPrimary,
          maxLines = 1
        )
      }
    }
  }
}

/** URL 是独立配置(不在请求体里),所以单独一行放;它下面的代码框只放请求体。 */
@Composable
private fun UrlRow(url: String, onValueChange: (String) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      "URL",
      Modifier.width(44.dp),
      fontSize = 13.sp,
      color = AppTheme.colors.textSecondary
    )
    BasicTextField(
      value = url,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = AppTheme.colors.textPrimary
      ),
      singleLine = true,
      cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
    )
  }
}

/**
 * 这家接口自己的模型清单:都是同一个 URL / 同一把 KEY 下的不同模型,点一下就换。
 *
 * 模型名长短差得多(如 qwen3-coder-plus),所以整行可横向滑,不截断。
 */
@Composable
private fun ModelChips(models: List<String>, current: String, onPick: (String) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    Text(
      "模型",
      Modifier.padding(bottom = 6.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
    Row(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      models.forEach { name ->
        val selected = name.equals(current, ignoreCase = true)
        Box(
          Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
              if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.listItem
            )
            .clickable { onPick(name) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
          Text(
            ModelNames.display(name),
            fontSize = 13.sp,
            maxLines = 1,
            color = if (selected) AppTheme.colors.listItem else AppTheme.colors.textPrimary
          )
        }
      }
    }
  }
}

/**
 * 一行开关:深度思考 / 联网搜索共用。
 *
 * [checked] 从请求体里读出来(不是自己记一份),所以在代码框里手改参数后这里的开关会跟着变;
 * [supported] 为 false 表示这家没有内置这个参数:整行置灰,只留一句「可以手写」的小字。
 */
@Composable
private fun DevToggleRow(
  title: String,
  hint: String,
  checked: Boolean,
  supported: Boolean,
  onToggle: () -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 2.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(AppTheme.colors.listItem)
      .clickable(enabled = supported, onClick = onToggle)
      .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        title,
        fontSize = 13.sp,
        color = if (supported) AppTheme.colors.textPrimary else AppTheme.colors.more
      )
      Text(hint, fontSize = 11.sp, color = AppTheme.colors.textSecondary)
    }
    Spacer(Modifier.width(8.dp))
    // 开关只做展示,点击交给整行,和自己那套设置项一致
    Switch(
      checked = checked && supported,
      enabled = supported,
      onCheckedChange = null,
      colors = SwitchDefaults.colors(
        checkedThumbColor = AppTheme.colors.onFab,
        checkedTrackColor = AppTheme.colors.iconCurrent,
        uncheckedThumbColor = AppTheme.colors.textSecondary,
        uncheckedTrackColor = AppTheme.colors.chatListDivider
      )
    )
  }
}

/** 开关下面那行小字:写清楚这个开关会往请求体里写什么,或者为什么用不了。 */
@Composable
private fun specHint(spec: DevParamSpec): String = when {
  spec.key != null -> t("写进请求体:{0}", spec.key)
  spec.onModel != null -> t("打开时模型切到 {0}", ModelNames.display(spec.onModel))
  else -> t("该服务商未内置这个参数,可以在请求体里手写")
}
