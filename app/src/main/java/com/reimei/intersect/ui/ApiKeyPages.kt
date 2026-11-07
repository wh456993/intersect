package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reimei.intersect.R
import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.ApiModelListResult
import com.reimei.intersect.data.ApiModels
import com.reimei.intersect.data.ApiProbe
import com.reimei.intersect.data.ApiTestResult
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「API KEY 管理」页。
 *
 * 上方是已添加的 API KEY 列表(点一条进入修改),最下方固定一个「添加」按钮。
 */
@Composable
fun ApiKeyListPage(
  modifier: Modifier = Modifier,
  keys: List<ApiKey>,
  onBack: () -> Unit,
  onEdit: (ApiKey) -> Unit,
  onAdd: () -> Unit
) {
  BackHandler { onBack() }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "API KEY 管理", onBack = onBack)
    Box(
      Modifier
        .weight(1f)
        .fillMaxWidth()
    ) {
      if (keys.isEmpty()) {
        EmptyApiKeyHint()
      } else {
        LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
          items(keys, key = { it.id }) { apiKey ->
            ApiKeyRow(apiKey) { onEdit(apiKey) }
            Spacer(Modifier.height(8.dp))
          }
        }
      }
    }
    AddApiKeyButton(onClick = onAdd)
  }
}

@Composable
private fun EmptyApiKeyHint() {
  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text("还没有添加 API KEY", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
  }
}

@Composable
private fun ApiKeyRow(apiKey: ApiKey, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        apiKey.name.ifEmpty { "未命名" },
        fontSize = 16.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(Modifier.height(4.dp))
      Text(
        listOf(apiKey.model, apiKey.url).filter { it.isNotEmpty() }.joinToString(" · "),
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    Spacer(Modifier.width(8.dp))
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(14.dp),
      tint = AppTheme.colors.more
    )
  }
}

@Composable
private fun AddApiKeyButton(onClick: () -> Unit) {
  Box(
    Modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = 12.dp, vertical = 12.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(AppTheme.colors.iconCurrent)
      .clickable(onClick = onClick)
      .padding(vertical = 13.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      "添加 API KEY",
      fontSize = 16.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.listItem
    )
  }
}

/**
 * API KEY 的添加 / 修改页。
 *
 * 表单自上而下是:名称 → 模型 → API URL → API KEY。
 * 填完点「完成」才落盘,返回则丢弃改动;修改已有条目时底部可以删除。
 */
@Composable
fun ApiKeyEditPage(
  modifier: Modifier = Modifier,
  original: ApiKey?,
  onBack: () -> Unit,
  onSave: (name: String, model: String, url: String, key: String) -> Unit,
  onDelete: (() -> Unit)? = null
) {
  BackHandler { onBack() }

  var name by remember { mutableStateOf(original?.name ?: "") }
  var model by remember { mutableStateOf(original?.model ?: "") }
  var url by remember { mutableStateOf(original?.url ?: "") }
  var key by remember { mutableStateOf(original?.key ?: "") }
  var pickerVisible by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var testing by remember { mutableStateOf(false) }
  var testResult by remember { mutableStateOf<ApiTestResult?>(null) }
  var fetching by remember { mutableStateOf(false) }
  var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
  var fetchError by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  var testJob by remember { mutableStateOf<Job?>(null) }
  var fetchJob by remember { mutableStateOf<Job?>(null) }
  // 每次测试 / 拉取的序号:只用来避免「取消后又立刻发起 / 表单又改了」时,
  // 上一次的结果和 finally 把新一次的状态盖掉
  var testSeq by remember { mutableStateOf(0) }
  var fetchSeq by remember { mutableStateOf(0) }
  var testCancelled by remember { mutableStateOf(false) }

  /**
   * 测试 / 拉取进行中时每秒刷一下已用秒数。
   *
   * 请求最长只等 20 / 3 秒(见 ApiProbe 的总时限),但光看一个不动的「正在测试...」
   * 分不出是在跑还是卡死了,把秒数显出来才知道它真的在走。
   */
  var testSeconds by remember { mutableStateOf(0) }
  var fetchSeconds by remember { mutableStateOf(0) }
  LaunchedEffect(testing) {
    testSeconds = 0
    if (!testing) return@LaunchedEffect
    val start = System.currentTimeMillis()
    while (true) {
      testSeconds = ((System.currentTimeMillis() - start) / 1000L).toInt()
      delay(500L)
    }
  }
  LaunchedEffect(fetching) {
    fetchSeconds = 0
    if (!fetching) return@LaunchedEffect
    val start = System.currentTimeMillis()
    while (true) {
      fetchSeconds = ((System.currentTimeMillis() - start) / 1000L).toInt()
      delay(500L)
    }
  }

  /**
   * 表单一改,之前的测试结论 / 拉取结果就不对应当前配置了,直接作废(否则会误导用户保存坏配置)。
   *
   * 「作废」不只是清掉旧结果:在跑的那次请求也得停掉 —— 否则它回来后会把旧地址 / 旧 KEY 的结论
   * 写回界面,看着像是对当前配置验证过了。序号 +1 后,旧协程的结果和 finally 都不再生效。
   */
  fun invalidateProbe(keepModels: Boolean = true) {
    testJob?.cancel()
    fetchJob?.cancel()
    testSeq++
    fetchSeq++
    testing = false
    fetching = false
    testCancelled = false
    testResult = null
    fetchError = null
    if (!keepModels) fetchedModels = emptyList()
  }

  /**
   * 选中模型后顺手带出它所属服务商的接口地址。
   *
   * 用户没主动填 URL(空着),或者 URL 还是某个内置地址(说明没被改过)时覆盖它;
   * 手填的地址不抢。从接口拉到的模型没有归属服务商,调用处传回来的 preset 为 null,URL 原样不动。
   */
  fun applyModel(picked: String) {
    model = picked
    invalidateProbe()
    val preset = ApiModels.urlFor(picked) ?: return
    if (url.isBlank() || ApiModels.isPresetUrl(url)) url = preset
  }

  val trimmedName = name.trim()
  val trimmedModel = model.trim()
  val trimmedUrl = url.trim()
  val trimmedKey = key.trim()
  val nameOk = trimmedName.isNotEmpty() && trimmedName.length <= ApiKey.NAME_MAX
  val urlOk = ApiKey.isUrlOk(trimmedUrl)
  val keyOk = trimmedKey.length >= ApiKey.KEY_MIN
  val canSave = nameOk && trimmedModel.isNotEmpty() && urlOk && keyOk

  /** 用当前填的地址 / KEY / 模型发一句问候,结果留在卡片里给用户看。进行中再点一次则取消。 */
  fun runTest() {
    if (testing) {
      // 取消是「立即撒手」:不阻塞的请求根本拦不住,只能主动抡断(见 ApiProbe.withDeadline 的 abort)
      testJob?.cancel()
      testSeq++
      testing = false
      testCancelled = true
      testResult = null
      return
    }
    if (!urlOk || !keyOk) return
    if (trimmedModel.isEmpty()) {
      testCancelled = false
      testResult = ApiTestResult(false, 0, "先在上面的「模型」里选一个再测试")
      return
    }
    val targetUrl = trimmedUrl
    val targetKey = trimmedKey
    val targetModel = trimmedModel
    testing = true
    testCancelled = false
    testResult = null
    val token = testSeq + 1
    testSeq = token
    testJob = scope.launch {
      try {
        val result = ApiProbe.test(targetKey, targetModel, targetUrl)
        if (testSeq == token) testResult = result
      } finally {
        // 取消(关页面 / 再点一次)也要把状态复位,否则再次打开会卡在「正在测试」
        if (testSeq == token) testing = false
      }
    }
  }

  /** 按当前 URL 与 KEY 向接口要一份模型列表,结果留在面板里,不随面板关闭丢弃。进行中再点一次则取消。 */
  fun fetchModels() {
    if (fetching) {
      fetchJob?.cancel()
      fetchSeq++
      fetching = false
      fetchError = null
      return
    }
    when {
      !urlOk -> {
        fetchError = "先把 URL 填成 http:// 或 https:// 开头"
        return
      }

      !keyOk -> {
        fetchError = "先把 KEY 填好"
        return
      }
    }
    val targetUrl = trimmedUrl
    val targetKey = trimmedKey
    fetching = true
    fetchError = null
    val token = fetchSeq + 1
    fetchSeq = token
    fetchJob = scope.launch {
      try {
        val result = ApiProbe.listModels(targetKey, targetUrl)
        if (fetchSeq != token) return@launch
        when (result) {
          is ApiModelListResult.Ok -> {
            fetchedModels = result.models
            if (result.models.isEmpty()) fetchError = "接口没有返回模型"
          }

          is ApiModelListResult.Fail -> fetchError = result.message
        }
      } finally {
        if (fetchSeq == token) fetching = false
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
      title = if (original == null) "添加 API KEY" else "修改 API KEY",
      actionText = "完成",
      actionEnabled = canSave,
      onBack = onBack,
      onAction = {
        if (!canSave) return@EditTopBar
        onSave(trimmedName, trimmedModel, trimmedUrl, trimmedKey)
      }
    )
    Column(
      Modifier
        .fillMaxSize()
        .imePadding()
        .verticalScroll(rememberScrollState())
    ) {
      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        ProfileEditRow(
          label = "名称",
          value = name,
          hint = if (name.length > ApiKey.NAME_MAX) "最多 ${ApiKey.NAME_MAX} 个字符" else "",
          hintColor = AppTheme.colors.badge,
          maxLength = ApiKey.NAME_MAX,
          onValueChange = { name = it }
        )
        FormDivider()
        ModelSelectRow(model = model, onClick = { pickerVisible = true })
        FormDivider()
        ProfileEditRow(
          label = "URL",
          value = url,
          hint = if (url.isNotEmpty() && !urlOk) "必须以 http:// 或 https:// 开头" else "",
          hintColor = AppTheme.colors.badge,
          maxLength = 200,
          onValueChange = {
            url = it
            // 换了服务商,之前拉到的模型列表也不再属于这个地址
            invalidateProbe(keepModels = false)
          }
        )
        FormDivider()
        SecretKeyRow(
          value = key,
          hint = if (key.isNotEmpty() && !keyOk) "至少 ${ApiKey.KEY_MIN} 个字符" else "",
          hintColor = AppTheme.colors.badge,
          onValueChange = {
            key = it
            invalidateProbe()
          }
        )
      }

      ConnectionTestCard(
        enabled = urlOk && keyOk,
        testing = testing,
        cancelled = testCancelled,
        seconds = testSeconds,
        result = testResult,
        onClick = { runTest() }
      )

      if (onDelete != null) {
        Box(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.listItem)
            .clickable { confirmDelete = true }
            .padding(vertical = 14.dp),
          contentAlignment = Alignment.Center
        ) {
          Text("删除这条 API KEY", fontSize = 16.sp, color = AppTheme.colors.badge)
        }
      }
    }
  }

  if (pickerVisible) {
    ModelPicker(
      current = model,
      fetched = fetchedModels,
      fetching = fetching,
      seconds = fetchSeconds,
      fetchError = fetchError,
      onFetch = { fetchModels() },
      onPick = {
        applyModel(it)
        pickerVisible = false
      },
      onDismiss = { pickerVisible = false }
    )
  }

  if (confirmDelete && onDelete != null) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("删除这条 API KEY?", color = AppTheme.colors.textPrimary, fontSize = 16.sp) },
      text = {
        Text(
          "删除后无法恢复,使用它的对话将无法调用服务。",
          fontSize = 13.sp,
          color = AppTheme.colors.textSecondary
        )
      },
      confirmButton = {
        Text(
          "删除",
          Modifier
            .clickable {
              confirmDelete = false
              onDelete.invoke()
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
            .clickable { confirmDelete = false }
            .padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }
}

@Composable
private fun FormDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

/**
 * 「测试连接」卡片:点一下用当前填的地址 / KEY / 模型发一句「请仅回复数字1」。
 *
 * URL 与 KEY 还没填成合法值时置灰(点了也没意义);测试中再点一下则取消(请求本身也有总时限)。
 * 界面只给结论:通过就是「测试成功」,不展示任何回执正文;失败时只报状态码,
 * 连响应都没有(连不上 / 超时)才多解释一句原因。
 */
@Composable
private fun ConnectionTestCard(
  enabled: Boolean,
  testing: Boolean,
  cancelled: Boolean,
  seconds: Int,
  result: ApiTestResult?,
  onClick: () -> Unit
) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(top = 12.dp, start = 12.dp, end = 12.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        // 测试中即使表单被改成不合法也要能点:否则「取消」按不下去,只能眼看秒数涨到超时
        .clickable(enabled = enabled || testing, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "测试连接",
        fontSize = 16.sp,
        color = if (enabled || testing) AppTheme.colors.textPrimary else AppTheme.colors.more
      )
      Spacer(Modifier.weight(1f))
      val status = when {
        testing -> if (seconds > 0) "测试中 ${seconds}s · 取消" else "测试中..."
        cancelled -> ApiProbe.CANCELLED
        result == null -> ""
        // 通过就只说「测试成功」:回执正文一个字都不展示
        result.ok -> "测试成功"
        else -> if (result.statusCode > 0) "失败 · HTTP ${result.statusCode}" else "失败"
      }
      if (status.isNotEmpty()) {
        Text(
          status,
          fontSize = 13.sp,
          color = when {
            testing || cancelled || result == null -> AppTheme.colors.textSecondary
            result.ok -> AppTheme.colors.iconCurrent
            else -> AppTheme.colors.badge
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    // 只有「连响应都没有」(连不上 / 超时)时才解释一句:服务端给了状态码的那类不再附正文
    if (!testing && !cancelled && result != null && !result.ok &&
      result.statusCode == 0 && result.message.isNotBlank()
    ) {
      FormDivider()
      Text(
        result.message,
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
        fontSize = 13.sp,
        color = AppTheme.colors.badge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/** 模型行:点开面板选常用模型,也可以手填一个。 */
@Composable
private fun ModelSelectRow(model: String, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text("模型", Modifier.width(64.dp), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    Text(
      model.ifEmpty { "选择或填写模型" },
      Modifier.weight(1f),
      fontSize = 16.sp,
      color = if (model.isEmpty()) AppTheme.colors.textSecondary else AppTheme.colors.textPrimary,
      textAlign = TextAlign.End,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.width(8.dp))
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(14.dp),
      tint = AppTheme.colors.more
    )
  }
}

/** API KEY 行:默认打码显示,右侧可以切到明文核对。 */
@Composable
private fun SecretKeyRow(
  value: String,
  hint: String,
  hintColor: androidx.compose.ui.graphics.Color,
  onValueChange: (String) -> Unit
) {
  var visible by remember { mutableStateOf(false) }
  var focused by remember { mutableStateOf(false) }
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("KEY", Modifier.width(64.dp), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
          .weight(1f)
          .onFocusChanged { focused = it.isFocused },
        textStyle = TextStyle(
          fontSize = 16.sp,
          color = AppTheme.colors.textPrimary,
          textAlign = TextAlign.End
        ),
        singleLine = true,
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Password,
          imeAction = ImeAction.Done
        )
      )
      Spacer(Modifier.width(8.dp))
      Text(
        if (visible) "隐藏" else "显示",
        Modifier
          .clickable { visible = !visible }
          .padding(vertical = 4.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    if (focused && hint.isNotEmpty()) {
      Text(
        hint,
        Modifier
          .fillMaxWidth()
          .padding(top = 6.dp),
        fontSize = 11.sp,
        color = hintColor,
        textAlign = TextAlign.End
      )
    }
  }
}

/**
 * 模型选择面板:底部弹出。
 *
 * 上面可以手填模型名,右上「拉取模型」按当前填的 URL 与 KEY 向接口要一份模型列表
 * (GET /models),拉到的列在列表最前面;还没拉过时下面是内置的三家服务商(preset)。
 * 一旦拉到了列表,内置的那三家就不再显示 —— 它们跟当前地址无关,列出来只会让人选错。
 * 点一下即选中并收起。
 * 拉取中右上角变成「取消」,再点一下就把这次请求停掉(请求本身也有 20 秒总时限)。
 *
 * 拉取状态由编辑页持有:面板关掉再打开,上次拉到的列表还在,不用重新发一次带 KEY 的请求。
 */
@Composable
private fun ModelPicker(
  current: String,
  fetched: List<String>,
  fetching: Boolean,
  seconds: Int,
  fetchError: String?,
  onFetch: () -> Unit,
  onPick: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var input by remember { mutableStateOf(current) }
  // 小屏 / 横屏下别把底部按钮顶出屏幕:列表高度按可用屏幕算,不写死
  val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(androidx.compose.ui.graphics.Color(0x66000000))
        .clickable(onClick = onDismiss),
      contentAlignment = Alignment.BottomCenter
    ) {
      Column(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
          .background(AppTheme.colors.listItem)
          .clickable(enabled = false) {}
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 16.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "选择模型",
            Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.colors.textPrimary
          )
          Text(
            if (fetching) {
              if (seconds > 0) "取消 ${seconds}s" else "取消"
            } else {
              "拉取模型"
            },
            Modifier
              .clickable(onClick = onFetch)
              .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            fontSize = 14.sp,
            color = if (fetching) AppTheme.colors.textSecondary else AppTheme.colors.iconCurrent
          )
        }
        Spacer(Modifier.height(12.dp))
        Box(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.bottomBar)
            .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
          BasicTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 15.sp, color = AppTheme.colors.textPrimary),
            singleLine = true,
            cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
          )
        }
        if (fetchError != null) {
          Text(
            fetchError,
            Modifier.padding(top = 8.dp),
            fontSize = 14.sp,
            color = AppTheme.colors.badge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.heightIn(max = maxListHeight)) {
          if (fetched.isNotEmpty()) {
            item(key = "fetched_header") {
              ProviderHeader("从接口拉取 (${fetched.size})")
            }
            items(fetched, key = { "fetched|$it" }) { name ->
              ModelOption(
                model = name,
                selected = name == current,
                onClick = { onPick(name) }
              )
            }
            item(key = "fetched_gap") { Spacer(Modifier.height(8.dp)) }
          }
          // 已按当前 URL 拉到了列表:其它内置服务商的模型与这个地址无关,一律不显示
          if (fetched.isEmpty()) {
            ApiModels.PROVIDERS.forEach { provider ->
              item(key = "provider_${provider.label}") {
                ProviderHeader(provider.label)
              }
              items(provider.models, key = { "${provider.label}_$it" }) { preset ->
                ModelOption(
                  model = preset,
                  selected = preset == current,
                  onClick = { onPick(preset) }
                )
              }
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        Box(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.iconCurrent)
            .clickable { onPick(input.trim()) }
            .padding(vertical = 12.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            "使用填写的模型",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.colors.listItem
          )
        }
      }
    }
  }
}

@Composable
private fun ProviderHeader(label: String) {
  Text(
    label,
    Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    fontSize = 12.sp,
    color = AppTheme.colors.textSecondary
  )
}

@Composable
private fun ModelOption(model: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      model,
      Modifier.weight(1f),
      fontSize = 15.sp,
      color = if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    if (selected) {
      Text("当前", fontSize = 12.sp, color = AppTheme.colors.iconCurrent)
    }
  }
}
