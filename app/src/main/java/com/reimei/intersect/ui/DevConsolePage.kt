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
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.reimei.intersect.data.DevRequestConfig
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.data.UsageLog
import com.reimei.intersect.percentOffsetX
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
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
 * 开发者控制台:一个和主界面一样的聊天窗口,右上角挂两个入口(日志 / 报文)。
 *
 * - 「日志」:等宽字体的终端样式窗口,请求/响应/错误都写在这里,可复制、存文件;
 * - 「报文」:逐轮列出完整的请求体与完整响应,整段代码框展示,不可编辑。
 *
 * 其余三个入口(请求体 / 角色 / 模式)挪到底部输入框右侧的附件按钮里:点加号弹出菜单。
 * - 「请求体」:整段 JSON 就是一个代码框,直接改整段文本,底部有重置与保存;
 * - 「角色」:给模型一个临时的角色设定(昵称 / 身份 / 性格),发送时拼成 system 消息;
 * - 「模式」:切换 intersect 的扮演模式。
 *
 * 真正发请求走 [DevApiClient],KEY 从「API KEY 管理」里按接口地址取。
 */
@Composable
fun DevConsole(
  modifier: Modifier = Modifier,
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
  val messages = remember { mutableStateListOf<DevMsg>() }
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

  fun log(text: String) {
    val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    text.lines().forEach { line -> logs.add("$stamp  $line") }
    while (logs.size > MAX_LOG_LINES) logs.removeAt(0)
  }

  LaunchedEffect(Unit) {
    log("dev console ready")
    log("provider=${config.provider} model=${config.model()}")
    log("endpoint=${config.endpoint()}")
    log("character=${if (character.isBlank) "(未设置)" else character.systemPrompt()}")
  }

  fun send(text: String) {
    if (sending) return
    // intersect 切到 Self-insert 时,控制台发的每轮对话也由时间管理器注入本地时间
    val injectTime = intersectMode == RoleplayMode.SELF_INSERT
    val stored = ApiKeyStore.load(context)
    val targetUrl = config.url.trim().trimEnd('/')
    val apiKey = (
      stored.firstOrNull { it.url.trim().trimEnd('/') == targetUrl }
        ?: stored.firstOrNull { it.model.trim().equals(config.model(), ignoreCase = true) }
        ?: stored.firstOrNull()
      )?.key.orEmpty()

    messages.add(
      DevMsg("user", text, time = if (injectTime) TimeManager.nowDateTime() else null)
    )
    if (apiKey.isBlank()) {
      log("error: 没有可用的 API KEY")
      messages.add(
        DevMsg(
          "assistant",
          "没有可用的 API KEY。请到「我 → API KEY 管理」里添加一条与 ${config.provider} 地址匹配的配置。",
          error = true
        )
      )
      return
    }

    val payload = config.payload(messages.toList(), character)
    val endpoint = config.endpoint()
    val provider = config.provider
    val model = config.model()
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
          messages.add(
            DevMsg("assistant", result.text, time = if (injectTime) TimeManager.nowDateTime() else null)
          )
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
          messages.add(DevMsg("assistant", result.message, error = true))
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
      DevConsoleTopBar(
        title = "${config.provider} · ${config.model()}",
        onBack = onBack,
        onLog = { showLog = true },
        onExchanges = { showExchanges = true }
      )
      MessageList(
        Modifier.weight(1f),
        messages = messages,
        sending = sending
      )
      ChatBottomBar(
        onSend = { send(it) },
        attachItems = listOf(
          AttachItem("请求体", R.drawable.ui_dev_request) { showEditor = true },
          AttachItem("角色", R.drawable.ui_dev_character) { showCharacter = true },
          AttachItem("模式", R.drawable.ui_dev_mode) { showMode = true },
        ),
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
        stateKey = "dev-console",
        title = "角色设定",
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
            title = "Avatar-based Role-Play",
            selected = intersectMode == RoleplayMode.AVATAR_BASED,
            onClick = {
              onSwitchIntersectMode(RoleplayMode.AVATAR_BASED)
              showMode = false
            }
          ),
          ChoiceOption(
            title = "Self-insert Role-Play",
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

/** 气泡列表:自己的消息靠右(绿),模型回复靠左(白),错误用红色淡底。 */
@Composable
private fun MessageList(
  modifier: Modifier = Modifier,
  messages: List<DevMsg>,
  sending: Boolean
) {
  val listState = rememberLazyListState()
  val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
  LaunchedEffect(messages.size, sending) {
    val last = messages.lastIndex + if (sending) 1 else 0
    if (last >= 0) listState.animateScrollToItem(last)
  }
  LazyColumn(modifier.fillMaxSize(), state = listState) {
    itemsIndexed(messages) { _, msg ->
      DevBubble(msg, maxBubble)
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

@Composable
private fun DevBubble(msg: DevMsg, maxWidth: androidx.compose.ui.unit.Dp) {
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
    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
  ) {
    Text(
      msg.text,
      Modifier
        .widthIn(max = maxWidth)
        .clip(RoundedCornerShape(10.dp))
        .background(background)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      fontSize = 15.sp,
      color = if (msg.error) AppTheme.colors.badge else AppTheme.colors.textPrimary
    )
  }
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
      "$ ${config.provider} / ${config.model()} / ${config.endpoint()}",
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

  val provider = ApiModels.PROVIDERS.firstOrNull { it.label == draft.provider }
    ?: ApiModels.PROVIDERS.first()

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
    Text(
      "角色设定在「角色」页填写,发送时会作为一条 system 消息插到最前面;这里只放请求体本身。",
      Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
    Spacer(Modifier.height(8.dp))
    Column(
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(TerminalBackground)
        .padding(10.dp)
    ) {
      Text(
        "POST ${draft.endpoint()}",
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = TerminalDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(Modifier.height(6.dp))
      BasicTextField(
        value = draft.body,
        onValueChange = {
          draft = draft.copy(body = it)
          error = null
        },
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
      .padding(horizontal = 12.dp)
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
