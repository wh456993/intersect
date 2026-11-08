package com.reimei.intersect.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reimei.intersect.R
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.DawnAssistant
import com.reimei.intersect.data.MeMenu
import com.reimei.intersect.data.Module
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun HomeBottomBar(current: Int, currentChanged: (Int) -> Unit) {
  TabBar {
    HomeBottomItem(
      Modifier
        .weight(1f)
        .clickable { currentChanged(0) },
      if (current == 0) R.drawable.ui_tab_chat_on else R.drawable.ui_tab_chat_off,
      "聊天",
      if (current == 0) AppTheme.colors.iconCurrent else AppTheme.colors.icon
    )
    HomeBottomItem(
      Modifier
        .weight(1f)
        .clickable { currentChanged(1) },
      if (current == 1) R.drawable.ui_tab_people_on else R.drawable.ui_tab_people_off,
      "通讯录",
      if (current == 1) AppTheme.colors.iconCurrent else AppTheme.colors.icon
    )
    HomeBottomItem(
      Modifier
        .weight(1f)
        .clickable { currentChanged(2) },
      if (current == 2) R.drawable.ui_tab_me_on else R.drawable.ui_tab_me_off,
      "我",
      if (current == 2) AppTheme.colors.iconCurrent else AppTheme.colors.icon
    )
  }
}

@Composable
fun HomeBottomItem(
  modifier: Modifier = Modifier,
  @DrawableRes iconId: Int,
  title: String,
  tint: Color
) {
  Column(
    modifier.padding(0.dp, 8.dp, 0.dp, 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Icon(painterResource(iconId), null, Modifier.size(24.dp), tint = tint)
    Text(title, fontSize = 11.sp, color = tint)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeShell() {
  val viewModel: MainViewModel = viewModel()
  val context = LocalContext.current
  // 首次进入时把好友列表读进来
  LaunchedEffect(Unit) { viewModel.ensureLoaded(context) }
  // 退出聊天页时把输入法收下去。
  // ConversationPage 在退出动画期间仍留在组合里(由 SlideOverlay 的 payload 驱动,位移在 graphicsLayer 上),
  // 输入框继续保持焦点的话键盘会一直挂着 —— 表现为退到首页、切到任何一页都下不去,
  // 而且 IME inset 一直在,底栏也被顶在屏幕中间。这里在 openModule 变回 null 时主动收焦点 + 收键盘。
  val focusManager = LocalFocusManager.current
  val rootView = LocalView.current
  LaunchedEffect(viewModel.openModule) {
    if (viewModel.openModule == null) {
      focusManager.clearFocus(force = true)
      val imm = rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.hideSoftInputFromWindow(rootView.windowToken, 0)
    }
  }
  // 预测性返回:聊天页跟着手指走,首页同步缩小,松手取消则回弹
  // 不用 by 委扐:需要把 State 本身传下去,让读取发生在 draw 阶段
  // (本工程 targetSdk 30,拿不到逐帧 progress,这条通路目前不生效,见 PREDICTIVE_BACK_AVAILABLE)
  val backAnim = animateFloatAsState(
    targetValue = viewModel.backProgress,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessHigh
    )
  )
  // enabled 与下面聊天页的 payload 必须同源(都看 openChat)。
  // 只看 currentChat 不行:退出动画跑完才会清它,若与配置重建碰撞,
  // 残留的 currentChat 会让这个 handler 一直吃掉返回键。
  val openChat = viewModel.currentChat?.takeIf { viewModel.openModule == Module.Chat }
  PredictiveBackHandler(enabled = openChat != null) { progress ->
    try {
      progress.collect { event ->
        // 多选时返回不退出聊天,所以也不让它跟着手指往外滑
        if (!viewModel.hasSelection) viewModel.onBackProgress(event.progress)
      }
    } catch (e: CancellationException) {
      viewModel.cancelBack()
      throw e
    }
    if (viewModel.hasSelection) {
      // 第一次返回:先取消勾选,留在聊天页
      viewModel.clearSelection()
      viewModel.cancelBack()
    } else {
      // 第二次返回:退出到主界面
      viewModel.endChat()
    }
  }
  Box(Modifier.background(AppTheme.colors.background)) {
    Column(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          val scale = 1f - BACK_SCALE * backAnim.value
          scaleX = scale
          scaleY = scale
        }
    ) {
      // 三页(聊天 / 通讯录 / 我)交给官方 Pager:拖动、惯性、回弹都由它负责,
      // 这里只声明页数与每页内容。
      val pagerState = rememberPagerState { 3 }
      val pagerScope = rememberCoroutineScope()
      HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
        when (page) {
          0 -> {
            SessionList()
          }
          1 -> {
            PeopleList()
          }
          else -> {
            // 两个回调按 viewModel 缓存:MeBoard 是稳定参数,不缓存的话
            // 每次重组都会得到新的 lambda 实例,让它无效重组。
            val onOpenProfile = remember(viewModel) { { viewModel.openProfile() } }
            val onOpenMenu = remember(viewModel) { { menu: MeMenu -> viewModel.openMeMenu(menu) } }
            MeBoard(
              onOpenProfile = onOpenProfile,
              onOpenMenu = onOpenMenu
            )
          }
        }
      }
      HomeBottomBar(pagerState.currentPage) { target ->
        // 点标签直接换页,不做滑动动画:与「点一下就到位」的手感一致。
        pagerScope.launch { pagerState.scrollToPage(target) }
      }
    }
    SlideOverlay(
      payload = openChat,
      onHidden = { chat -> viewModel.clearCurrentChatIf(chat) },
      extraOffset = if (PREDICTIVE_BACK_AVAILABLE) ({ backAnim.value }) else ({ 0f }),
    ) { chat ->
      // 按会话做 key:退出动画进行的 300ms 内若已切到另一个会话,
      // 共用同一个组合实例会把上一个会话的滚动位置/输入状态带过来。
      key(chat) {
        ConversationPage(
          chat = chat,
          onBack = { viewModel.endChat() },
          onOpenInfo = { viewModel.openChatInfo(chat) },
        )
      }
    }
    // 个人资料页从右侧推入,盖住首页
    SlideOverlay(payload = Unit.takeIf { viewModel.showProfile }) {
      ProfilePage(onBack = { viewModel.closeProfile() })
    }
    // 「我」页的功能页(API KEY管理 / Token统计 / ...)从右侧推入
    SlideOverlay(payload = viewModel.meMenu) { meMenu ->
      when (meMenu) {
        // API KEY 管理有自己的列表页,其余入口还是占位页
        MeMenu.ApiKey -> ApiKeyListPage(
          keys = viewModel.apiKeys,
          onBack = { viewModel.closeMeMenu() },
          onEdit = { viewModel.openApiKeyEdit(it) },
          onAdd = { viewModel.openApiKeyEdit(null) }
        )

        MeMenu.Settings -> SettingsPage(
          onBack = { viewModel.closeMeMenu() },
          onOpenDeveloper = { viewModel.openDeveloper() }
        )

        // Token 统计:本地 SQLite 里的调用流水与心跳采样,按密钥组 / 模型 / 聊天窗口多维聚合
        MeMenu.Usage -> UsagePage(
          onBack = { viewModel.closeMeMenu() },
          keyGroupName = { id -> viewModel.apiKeys.firstOrNull { it.id == id }?.name?.ifEmpty { id } ?: id },
          windowName = { id -> viewModel.contacts.firstOrNull { it.id == id }?.name ?: id }
        )

        // 数据备份:导出加密备份文件,或从已有备份恢复
        MeMenu.Backup -> BackupPage(
          onBack = { viewModel.closeMeMenu() },
          onRestored = { viewModel.reloadFromStores(context) }
        )

        // 隐私政策:与开屏页同一份正文的只读模式(顶栏 + 返回 + 撤销授权)
        MeMenu.Privacy -> PrivacyPolicyPage(onBack = { viewModel.closeMeMenu() })

        else -> MeSettingPage(
          menu = meMenu,
          onBack = { viewModel.closeMeMenu() }
        )
      }
    }
    // 开发者控制台页:盖在设置页之上
    SlideOverlay(payload = Unit.takeIf { viewModel.showDeveloper }) {
      val intersectMode = viewModel.contacts.firstOrNull { it.id == DawnAssistant.ID }?.roleplayMode
        ?: RoleplayMode.SELF_INSERT
      DeveloperPage(
        intersectMode = intersectMode,
        onSwitchIntersectMode = { mode -> viewModel.setIntersectMode(context, mode) },
        onBack = { viewModel.closeDeveloper() }
      )
    }
    // API KEY 的添加 / 修改页:盖在列表页之上
    SlideOverlay(payload = viewModel.apiKeyEdit) { target ->
      val original = viewModel.apiKeys.firstOrNull { it.id == target.id }
      key(target.id) {
        ApiKeyEditPage(
          original = original,
          onBack = { viewModel.closeApiKeyEdit() },
          onSave = { name, model, url, key ->
            viewModel.saveApiKey(context, original, name, model, url, key)
            viewModel.closeApiKeyEdit()
          },
          onDelete = if (original == null) {
            null
          } else {
            {
              viewModel.deleteApiKey(context, original)
              viewModel.closeApiKeyEdit()
            }
          }
        )
      }
    }
    // 添加角色页
    SlideOverlay(payload = Unit.takeIf { viewModel.showAddFriend }) {
      AddFriendPage(
        onBack = { viewModel.closeAddFriend() },
        onConfirm = { friend ->
          viewModel.addFriend(context, friend)
          viewModel.closeAddFriend()
        }
      )
    }
    // 聊天信息页:盖在聊天页之上
    val chatInfo = viewModel.chatInfoChat
    SlideOverlay(payload = chatInfo) { info ->
      key(info) {
        ChatInfoPage(
          chat = info,
          apiKeys = viewModel.apiKeys,
          apiKey = viewModel.apiKeyOf(info),
          onPickApiKey = { picked -> viewModel.bindApiKey(context, info, picked) },
          onOpenCharacter = { viewModel.openCharacterInfo(info.friend.id) },
          onBack = { viewModel.closeChatInfo() },
          onDelete = { viewModel.deleteFriend(context, info.friend) }
        )
      }
    }
    // 角色信息页:盖在聊天信息页之上,点进角色名片才出现。
    // 按 id 现取:角色信息可能刚被改过,直接缓存一份 User 会显示旧名字
    val characterFriend = viewModel.characterInfoId?.let { id ->
      chatInfo?.friend?.takeIf { it.id == id }
    }
    SlideOverlay(payload = characterFriend) { friend ->
      CharacterInfoPage(
        friend = friend,
        onOpenIdentity = { viewModel.openCharacterIdentity() },
        onOpenMemory = { viewModel.openMemory(friend.id) },
        onOpenItemManager = { viewModel.openItemManager(context, friend.id) },
        onBack = { viewModel.closeCharacterInfo() }
      )
    }
    // 角色身份页:接在角色信息页之后(而非嵌在它的 content 里)。
    // 嵌进去的话父页滑出会把子页一起带着走,两层同时退出时子页位移翻倍、飞得比父页还快。
    SlideOverlay(payload = characterFriend?.takeIf { viewModel.showCharacterIdentity }) { friend ->
      key(friend.id) {
        CharacterIdentityPage(
          original = friend,
          onBack = { viewModel.closeCharacterIdentity() },
          onSave = { name, personality, role ->
            viewModel.saveCharacter(context, friend, name, personality, role)
            viewModel.closeCharacterIdentity()
          }
        )
      }
    }
    // 记忆库页:接在角色信息页之后(即时记忆层 / 时间线索引层 / 长期记忆层)
    val memoryFriend = characterFriend?.takeIf { viewModel.memoryFriendId != null }
    SlideOverlay(payload = memoryFriend) { friend ->
      key(friend.id) {
        MemoryLibraryPage(
          friendId = friend.id,
          apiKeyProvider = { viewModel.apiKeyForFriend(friend.id) },
          roundsProvider = { viewModel.roundsOf(friend.id) },
          version = viewModel.memoryVersion,
          onBack = { viewModel.closeMemory() }
        )
      }
    }
    // 物品管理器页:接在角色信息页之后
    SlideOverlay(payload = characterFriend?.takeIf { viewModel.itemManagerFriendId != null }) { friend ->
      key(friend.id) {
        ItemManagerPage(
          items = viewModel.items,
          onBack = { viewModel.closeItemManager() },
          onAdd = { viewModel.openAddItem() },
          onDelete = { item -> viewModel.deleteItem(context, item) },
        )
      }
    }
    // 添加物品页:盖在物品管理器页之上
    SlideOverlay(payload = characterFriend?.takeIf { viewModel.itemManagerFriendId != null && viewModel.showAddItem }) { friend ->
      key(friend.id) {
        AddItemPage(
          onBack = { viewModel.closeAddItem() },
          onSave = { name, description ->
            viewModel.addItem(context, name, description)
            viewModel.closeAddItem()
          }
        )
      }
    }
  }
}

/** 预测性返回时首页的回缩幅度。 */
private const val BACK_SCALE = 0.06f

/**
 * 预测性返回是否真的推进。
 *
 * 本工程 targetSdk 30,系统不会给应用派发 predictive back 的逐帧进度(门禁 A 静态判定为 false),
 * 所以聊天页的退出位移只由自己的动画驱动,不从返回手势取偏移。
 */
private const val PREDICTIVE_BACK_AVAILABLE = false