package com.reimei.intersect.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.reimei.intersect.R
import com.reimei.intersect.data.CharacterModule
import com.reimei.intersect.data.FriendsStore
import com.reimei.intersect.data.Item
import com.reimei.intersect.data.ItemStore
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.User
import com.reimei.intersect.data.Worldview
import com.reimei.intersect.data.time.BaseTime
import com.reimei.intersect.data.time.ClockState
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加角色页:填头像与昵称,然后选扮演模式。
 * 昵称必填(3-20 字)。不填签名 —— 角色卡上不再有这一行。
 *
 * 扮演模式的两行是**入口**:点哪一个就进这个模式自己的角色信息页([CharacterInfoPage] 的草稿形态)。两套界面彼此独立,所以这里不打勾、不表示「当前选中」;
 * 角色代入那一套多出空间管理器、物品管理器、世界观、背景与可改的基准时间。
 * 草稿里填的身份 / 物品 / 世界观 / 背景 / 基准时间随「完成」一起落盘。
 */
@Composable
fun AddFriendPage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  onConfirm: (User, ClockState?) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // 存得住:转屏重建后同一次创建还是个 id,子页的 rememberSaveable 才靠得住
  val friendId = rememberSaveable { FriendsStore.newFriendId() }

  // 昵称 / 扮演模式 / 各项设定都用 rememberSaveable:旋转屏幕会把 Activity 重建,
  // 世界观与背景是 4×500 字的长文本,转一下屏就清空的话代价太大
  var name by rememberSaveable { mutableStateOf("") }
  var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var pendingCrop by remember { mutableStateOf<Bitmap?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  // 枚举本身不可保存,存名字再还原(与落盘时用同一个 fromName)
  var modeName by rememberSaveable { mutableStateOf(RoleplayMode.AVATAR_BASED.name) }
  val mode = RoleplayMode.fromName(modeName)
  // 角色设定里可以在创建阶段先填好的几项:性格 / 身份 / 世界观四项 / 背景
  var personality by rememberSaveable { mutableStateOf("") }
  var role by rememberSaveable { mutableStateOf("") }
  var laws by rememberSaveable { mutableStateOf("") }
  var factions by rememberSaveable { mutableStateOf("") }
  var economy by rememberSaveable { mutableStateOf("") }
  var history by rememberSaveable { mutableStateOf("") }
  var background by rememberSaveable { mutableStateOf("") }
  // 基准时间只存人读文本(BaseTime.format 出来的样子),空白 = 还没改 = 用出厂基准
  var baseTimeText by rememberSaveable { mutableStateOf("") }
  // 草稿里的物品:创建完成时才按 friendId 落盘(与头像同一条纪律:没点完成就不留东西)
  var draftItems by rememberSaveable(stateSaver = DraftItemsSaver) {
    mutableStateOf(emptyList<Item>())
  }
  /** 创建流程里当前打开的子页;null 表示停在表单上。重建 Activity 时回到表单即可 */
  var page by remember { mutableStateOf<DraftPage?>(null) }

  val openGallery = rememberGalleryPicker(
    onPicked = { pendingCrop = it },
    onError = { error = "无法读取这张图片,请换一张试试" }
  )

  BackHandler {
    when {
      pendingCrop != null -> Unit          // 剪裁页自己处理返回
      page == null -> onBack()
      // 子页都自带 BackHandler,这里只是兜底:从子页退一层,退到草稿的角色信息页
      page != DraftPage.Card -> page = DraftPage.Card
      else -> page = null
    }
  }

  if (pendingCrop != null) {
    CropImageScreen(
      bitmap = pendingCrop!!,
      onCancel = { pendingCrop = null },
      onDone = { cropped ->
        pendingCrop = null
        avatarBitmap = cropped
      }
    )
    return
  }

  val trimmedName = name.trim()
  val nameOk = trimmedName.length in 3..20
  val canConfirm = nameOk

  // 草稿角色:创建流程里那几页共享这一份(还没落库,所以不带 uid)。
  // 世界侧设定(世界观 / 背景)只在声明了它们模块的模式里才有地方可填,
  // 所以落草稿时也只看这个模式自己有没有;输入本身留在上面的状态里,切回来还在
  val worldview = Worldview(
    laws = laws.trim().ifEmpty { null },
    factions = factions.trim().ifEmpty { null },
    economy = economy.trim().ifEmpty { null },
    history = history.trim().ifEmpty { null },
  )
  val draftUser = User(
    id = friendId,
    name = trimmedName.ifBlank { "未命名角色" },
    avatar = R.drawable.ui_avatar_blank,
    role = role.trim().ifEmpty { null },
    personality = personality.trim().ifEmpty { null },
    roleplayMode = mode,
    worldview = if (mode.has(CharacterModule.WORLDVIEW)) worldview.takeIf { !it.isEmpty } else null,
    background = if (mode.has(CharacterModule.BACKGROUND)) background.trim().ifEmpty { null } else null,
  )
  // 基准时间没改过就是空白(用出厂基准),改过就是 BaseTime.format 出来的文本。
  // 与物品 / 世界观同一道门:只有声明了这个模块的模式才写,否则切到本体代入提交也会留下账本
  val draftBaseTime = if (mode.has(CharacterModule.BASE_TIME)) {
    baseTimeText.trim().ifEmpty { null }?.let { BaseTime.parseOrEmpty(it) }
  } else {
    null
  }

  // 子页盖在表单之上;返回 null 就回到表单(表单的输入全在 remember 里,不会丢)
  when (page) {
    DraftPage.Card -> {
      CharacterInfoPage(
        friend = draftUser,
        // 未改过基准时间时也显示出厂值:创建完成后的那一行同样是具体时刻,前后口径一致
        baseTime = if (mode.has(CharacterModule.BASE_TIME)) {
          BaseTime.format(BaseTime.parseOrEmpty(baseTimeText) ?: BaseTime.DEFAULT)
        } else {
          ""
        },
        draft = true,
        // 标题带上模式名:进的是哪一套界面一眼能看出来
        title = "角色信息 · ${mode.label}",
        onOpenIdentity = { page = DraftPage.Identity },
        onOpenMemory = {},                    // 记忆库要有聊天记录,草稿形态下这一行不可点
        onOpenItemManager = { page = DraftPage.Items },
        onOpenWorldview = { page = DraftPage.Worldview },
        onOpenBackground = { page = DraftPage.Background },
        onOpenTimeManager = { page = DraftPage.BaseTime },
        onBack = { page = null },
      )
      return
    }

    DraftPage.Items -> {
      ItemManagerPage(
        items = draftItems,
        onBack = { page = DraftPage.Card },
        onAdd = { page = DraftPage.AddItem },
        onDelete = { item -> draftItems = draftItems.filterNot { it.id == item.id } },
      )
      return
    }

    DraftPage.AddItem -> {
      AddItemPage(
        onBack = { page = DraftPage.Items },
        onSave = { itemName, itemDescription ->
          draftItems = draftItems + Item(ItemStore.newId(), itemName, itemDescription)
          page = DraftPage.Items
        }
      )
      return
    }

    DraftPage.BaseTime -> {
      BaseTimePage(
        stateKey = friendId,
        initialText = baseTimeText,
        onBack = { page = DraftPage.Card },
        onSave = { text ->
          baseTimeText = text
          page = DraftPage.Card
        }
      )
      return
    }

    DraftPage.Identity -> {
      CharacterIdentityEditor(
        stateKey = friendId,
        title = "角色身份",
        initialName = trimmedName,
        initialPersonality = personality.ifEmpty { null },
        initialRole = role.ifEmpty { null },
        // 与表单的昵称同一道门:短名字存下去会让表单的「完成」莫名变灰
        minNameLength = 3,
        onBack = { page = DraftPage.Card },
        onSave = { nextName, nextPersonality, nextRole ->
          name = nextName
          personality = nextPersonality.orEmpty()
          role = nextRole.orEmpty()
          page = DraftPage.Card
        }
      )
      return
    }

    DraftPage.Worldview -> {
      WorldviewPage(
        stateKey = friendId,
        original = worldview,
        onBack = { page = DraftPage.Card },
        onSave = { saved ->
          laws = saved.laws.orEmpty()
          factions = saved.factions.orEmpty()
          economy = saved.economy.orEmpty()
          history = saved.history.orEmpty()
          page = DraftPage.Card
        }
      )
      return
    }

    DraftPage.Background -> {
      BackgroundPage(
        stateKey = friendId,
        original = background,
        onBack = { page = DraftPage.Card },
        onSave = { saved ->
          background = saved
          page = DraftPage.Card
        }
      )
      return
    }

    null -> Unit
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "添加角色",
      actionText = "完成",
      actionEnabled = canConfirm,
      onBack = onBack,
      onAction = {
        if (!canConfirm) return@EditTopBar
        scope.launch {
          val avatarPath = if (avatarBitmap != null) {
            withContext(Dispatchers.IO) {
              val file = FriendsStore.avatarFile(context, friendId)
              saveBitmapToFile(avatarBitmap!!, file)
              file.absolutePath
            }
          } else {
            null
          }
          // 草稿里加的物品按这个角色的 id 落盘(没点完成就不会走到这里)。
          // 只在这个模式真有物品管理器时才写:否则切回本体代入提交后,数据存进去了却无处可看
          if (draftItems.isNotEmpty() && mode.has(CharacterModule.ITEMS)) {
            withContext(Dispatchers.IO) { ItemStore.save(context, friendId, draftItems) }
          }
          onConfirm(draftUser.copy(avatarPath = avatarPath, name = trimmedName), draftBaseTime)
        }
      }
    )

    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      Row(
        Modifier
          .fillMaxWidth()
          .clickable(onClick = openGallery)
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("头像", Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
        AvatarPreview(avatarBitmap)
        Spacer(Modifier.width(8.dp))
        Icon(
          painterResource(R.drawable.ui_chevron_right),
          contentDescription = null,
          Modifier.size(14.dp),
          tint = AppTheme.colors.more
        )
      }
      Box(
        Modifier
          .fillMaxWidth()
          .padding(start = 16.dp)
          .height(1.dp)
          .background(AppTheme.colors.chatListDivider)
      )
      ProfileEditRow(
        label = "昵称",
        value = name,
        maxLength = 20,
        onValueChange = { name = it }
      )
    }

    Spacer(Modifier.height(12.dp))

    // 扮演模式:两行都是入口 —— 点哪行就进那个模式自己的角色信息页(草稿形态)。
    // 不打勾:进页即可,最后进的那个模式就是完成后要创建的模式
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      Text(
        "扮演模式",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        fontSize = 13.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(
        "点选后进入该模式的功能页,完成后按最后进入的模式创建",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.more
      )
      ChoiceEntryRow(
        title = RoleplayMode.AVATAR_BASED.label,
        onClick = {
          modeName = RoleplayMode.AVATAR_BASED.name
          page = DraftPage.Card
        }
      )
      ChoiceDivider()
      ChoiceEntryRow(
        title = RoleplayMode.SELF_INSERT.label,
        onClick = {
          modeName = RoleplayMode.SELF_INSERT.name
          page = DraftPage.Card
        }
      )
    }

    if (error != null) {
      Text(
        error!!,
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.badge
      )
    }
  }
}

/**
 * 创建流程里能打开的子页。
 *
 * [Card] 是这个模式的角色信息页(草稿形态),[Identity] / [Items] / [AddItem] /
 * [Worldview] / [Background] / [BaseTime] 是它下面的填写页 —— 与已创建角色的角色信息页
 * 是同一套页面,只是数据来自创建页的草稿。
 */
private enum class DraftPage { Card, Identity, Items, AddItem, Worldview, Background, BaseTime }

/**
 * 草稿物品的保存器:旋转屏幕时把已经加进去的几件带过去。
 *
 * 扁平成 `id, name, description` 三连再存:[Item] 自己不是 Saveable,而 rememberSaveable
 * 只能装 Bundle 认得的基础类型。
 */
private val DraftItemsSaver = listSaver<List<Item>, String>(
  save = { items -> items.flatMap { listOf(it.id, it.name, it.description) } },
  restore = { flat -> flat.chunked(3).filter { it.size == 3 }.map { Item(it[0], it[1], it[2]) } }
)

@Composable
private fun AvatarPreview(bitmap: Bitmap?) {
  Box(
    Modifier
      .size(52.dp)
      .clip(CircleShape)
  ) {
    if (bitmap != null) {
      Image(
        bitmap.asImageBitmap(),
        contentDescription = "头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    } else {
      Image(
        painterResource(R.drawable.ui_avatar_blank),
        contentDescription = "头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    }
  }
}

/** 标题栏:返回 + 标题 + 右侧动作(完成不可点时变淡)。 */
@Composable
fun EditTopBar(
  title: String,
  actionText: String,
  actionEnabled: Boolean,
  onBack: () -> Unit,
  onAction: () -> Unit
) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(AppTheme.colors.background)
      .statusBarsPadding()
  ) {
    Icon(
      painterResource(R.drawable.ui_chevron_left),
      contentDescription = "返回",
      Modifier
        .align(Alignment.CenterStart)
        .clickable(onClick = onBack)
        .size(48.dp)
        .padding(14.dp),
      tint = AppTheme.colors.icon
    )
    Text(
      title,
      Modifier
        .align(Alignment.Center)
        .padding(vertical = 14.dp),
      fontSize = 17.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary
    )
    // 右上角原本就有「完成」
    Row(
      Modifier.align(Alignment.CenterEnd),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        actionText,
        Modifier
          .clickable(enabled = actionEnabled, onClick = onAction)
          .padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 15.sp,
        color = if (actionEnabled) AppTheme.colors.iconCurrent else AppTheme.colors.more
      )
    }
  }
}

/** 右侧对齐的单行输入行,聚焦时在下面显示字数提示。 */
@Composable
fun ProfileEditRow(
  label: String,
  value: String,
  maxLength: Int,
  onValueChange: (String) -> Unit,
  hint: String = "",
  hintColor: Color = AppTheme.colors.textSecondary
) {
  var focused by remember { mutableStateOf(false) }
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.width(64.dp), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      BasicTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
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
