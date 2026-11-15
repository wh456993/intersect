package com.reimei.intersect

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reimei.intersect.data.ApiKey
import com.reimei.intersect.data.ApiKeyEditTarget
import com.reimei.intersect.data.ApiKeyStore
import com.reimei.intersect.data.Chat
import com.reimei.intersect.data.ChatReply
import com.reimei.intersect.data.ChatService
import com.reimei.intersect.data.ChatStore
import com.reimei.intersect.data.DawnAssistant
import com.reimei.intersect.data.FriendsStore
import com.reimei.intersect.data.Item
import com.reimei.intersect.data.ItemStore
import com.reimei.intersect.data.MeMenu
import com.reimei.intersect.data.MemoryRounds
import com.reimei.intersect.data.MemoryStamp
import com.reimei.intersect.data.MemoryStore
import com.reimei.intersect.data.MetaInfoStore
import com.reimei.intersect.data.memory.MemoryPipeline
import com.reimei.intersect.data.memory.MemoryRepository
import com.reimei.intersect.data.Module
import com.reimei.intersect.data.Msg
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.SceneTime
import com.reimei.intersect.data.SeedStore
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.data.time.BaseTime
import com.reimei.intersect.data.time.ClockSession
import com.reimei.intersect.data.time.ClockState
import com.reimei.intersect.data.time.ClockStore
import com.reimei.intersect.data.time.DirectorParser
import com.reimei.intersect.data.time.InjectionPlan
import com.reimei.intersect.data.time.OutputGuard
import com.reimei.intersect.data.time.RoundType
import com.reimei.intersect.data.time.SceneDraft
import com.reimei.intersect.data.time.SceneCalendar
import com.reimei.intersect.data.time.SceneEditor
import com.reimei.intersect.data.time.TimeFallback
import com.reimei.intersect.data.time.TimeLogs
import com.reimei.intersect.data.time.TimeRounds
import com.reimei.intersect.data.truncateAt
import com.reimei.intersect.data.UidStore
import com.reimei.intersect.data.UsageLog
import com.reimei.intersect.data.User
import com.reimei.intersect.data.Worldview
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
  /** 会话列表(含聊天记录),从本地存储加载;首次启动时播种内置助手。 */
  val chats = mutableStateListOf<Chat>()

  /** 通讯录里的好友,从本地存储加载。 */
  val contacts = mutableStateListOf<User>()

  /** 已添加的 API KEY,从本地存储加载。 */
  val apiKeys = mutableStateListOf<ApiKey>()

  /** 聊天页里被勾选的消息。放在 ViewModel 里:返回手势和多选状态才能互相配合。 */
  val selectedMsgs = mutableStateListOf<Msg>()

  /** 是否处于多选状态。 */
  val hasSelection: Boolean get() = selectedMsgs.isNotEmpty()

  /** 正在等回复的请求数(>0 时聊天页显示「对方正在输入...」)。 */
  var replyingCount by mutableStateOf(0)
    private set

  /** 上一轮请求失败的原因;null 表示没有失败。不属于聊天记录,不写盘。 */
  var replyError: String? by mutableStateOf(null)
    private set

  /** 导演时间指令 / 时钟回滚的回显(成功与拒绝都走这里)。与 [replyError] 分开:这不是错误。 */
  var clockNotice: String? by mutableStateOf(null)
    private set

  private var loaded = false

  var openModule: Module? by mutableStateOf(null)
  var currentChat: Chat? by mutableStateOf(null)
    private set

  /** 个人资料页是否展开在首页之上。 */
  var showProfile by mutableStateOf(false)
    private set

  /** 添加角色页是否展开。 */
  var showAddFriend by mutableStateOf(false)
    private set

  /** 聊天信息页当前对应的会话,null 表示没打开。 */
  var chatInfoChat: Chat? by mutableStateOf(null)
    private set

  /** 角色信息页对应的角色 id,null 表示没打开。 */
  var characterInfoId: String? by mutableStateOf(null)
    private set

  /** 角色信息页里「角色身份」子页是否打开。 */
  var showCharacterIdentity: Boolean by mutableStateOf(false)
    private set

  /** 角色信息页里「世界观」子页是否打开(角色代入专属)。 */
  var showWorldview: Boolean by mutableStateOf(false)
    private set

  /** 角色信息页里「背景」子页是否打开(角色代入专属)。 */
  var showBackground: Boolean by mutableStateOf(false)
    private set

  /** 角色信息页里「时间管理器」子页(基准时间编辑器)是否打开。 */
  var showBaseTime: Boolean by mutableStateOf(false)
    private set

  /** 物品管理器页对应的角色 id,null 表示没打开。 */
  var itemManagerFriendId: String? by mutableStateOf(null)
    private set

  /** 物品管理器里「添加物品」表单是否打开。 */
  var showAddItem: Boolean by mutableStateOf(false)
    private set

  /** 物品管理器里当前角色的物品目录。 */
  val items = mutableStateListOf<Item>()

  /** 记忆库页对应的角色 id,null 表示没打开。 */
  var memoryFriendId: String? by mutableStateOf(null)
    private set

  /**
   * 自动总结完成后 +1。
   *
   * 记忆库页据此重读本地状态:总结是在聊天页后台跑完的,页面上那份快照得跟着刷新,
   * 否则用户切过去看到的还是「还没总结」。
   */
  var memoryVersion by mutableStateOf(0)
    private set

  /**
   * 「这个角色有一轮新的微总结待生效」的标记(只在内存里)。
   *
   * 时间子系统的总结轮需要知道「这一轮是不是刚有了新总结」:有就推进 `last_summary_period`
   * 并吸收 pending 导演补丁。旧实现靠「记忆里有总结」判断,那会让每一轮都算总结轮;
   * 现在只在流水线真的写进一条新微总结后置位,用完即清。
   *
   * 进程重启后丢掉是可以接受的:它只影响时钟的记账时机,不影响报文结构。
   */
  private val summaryRoundPending = mutableSetOf<String>()

  /** 「我」页里当前打开的功能入口,null 表示没打开。 */
  var meMenu: MeMenu? by mutableStateOf(null)
    private set

  /** API KEY 的添加 / 修改页,null 表示没打开。 */
  var apiKeyEdit: ApiKeyEditTarget? by mutableStateOf(null)
    private set

  /** 开发者控制台页是否展开在设置页之上。 */
  var showDeveloper by mutableStateOf(false)
    private set

  /** 预见性返回手势的进度:0 表示没在拖,1 表示已经滑到底。 */
  var backProgress by mutableStateOf(0f)
    private set

  /** 首次进入主界面时把好友和会话都读进来,重复调用无副作用。 */
  fun ensureLoaded(context: Context) {
    if (loaded) return
    loaded = true
    val appContext = context.applicationContext
    // 老版本内置的两个示例角色换掉,并把内置助手(和欢迎语)只播种这一次
    SeedStore.migrate(appContext)
    reloadFromStores(appContext)
  }

  /**
   * 从磁盘重新读入全部数据。
   *
   * 恢复备份会直接改写 SharedPreferences,内存里这份列表就过期了;这里整体重读一次,
   * 界面才会立刻反映出恢复后的角色、会话与 KEY。已经打开的聊天页/聊天信息页指向的是
   * 被替换掉的旧对象,所以一并收起来,让用户从列表重新进入。
   */
  fun reloadFromStores(context: Context) {
    val appContext = context.applicationContext
    contacts.clear()
    contacts.addAll(FriendsStore.load(appContext))
    // 会话也来自本地存储:发过的消息、删除重启后都还在
    chats.clear()
    chats.addAll(ChatStore.load(appContext) ?: listOf(DawnAssistant.greetingChat()))
    // 唯一 ID 跟着角色记录走:老数据 / 恢复回来的角色在这里补号、登记与纠正撞号
    ensureCharacterUids(appContext)
    apiKeys.clear()
    apiKeys.addAll(ApiKeyStore.load(appContext))
    // 让会话里的好友信息跟着通讯录走,头像、签名才能反映到界面
    chats.forEach { chat ->
      contacts.firstOrNull { it.id == chat.friend.id }?.let { chat.friend = it }
    }
    selectedMsgs.clear()
    currentChat = null
    chatInfoChat = null
    characterInfoId = null
    showCharacterIdentity = false
    showWorldview = false
    showBackground = false
    showBaseTime = false
    itemManagerFriendId = null
    showAddItem = false
    items.clear()
    memoryFriendId = null
    openModule = null
    backProgress = 0f
    replyError = null
  }

  /** 会话是内存里的可变列表,任何改动都得显式写盘才算永久。 */
  private fun saveChats(context: Context) {
    ChatStore.save(context.applicationContext, chats)
  }

  /**
   * 保证每个角色都有一个唯一 ID。
   *
   * 三件事:老数据里还没发号的角色补一个(按创建顺序,最早加的拿最小的号)、把已有的号
   * 登记进计数器(恢复备份 / 换机后号可能比本机计数器大)、以及纠正撞号
   * (两台设备各自发过同一个号,合并恢复后会有两个角色共用一个号)。
   *
   * 补出来的号写回通讯录并落盘 —— 从这个版本起号跟着角色走,备份也就带着它了。
   */
  private fun ensureCharacterUids(context: Context) {
    // 第一轮:先登记已有的号(撞号的一个先判出来),否则接下来补号可能正好补到某个号上
    val seen = HashSet<Long>()
    val conflicting = HashSet<String>()
    contacts.asReversed().forEach { user ->
      val uid = user.uid ?: return@forEach
      if (!seen.add(uid)) conflicting.add(user.id)
    }
    seen.forEach { uid -> UidStore.reserve(context, uid) }
    // 不在通讯录里的角色(只在会话里留了一份快照)同样算数
    chats.forEach { chat -> chat.friend.uid?.let { uid -> UidStore.reserve(context, uid) } }

    // 第二轮:缺号的补一个,撞号(后到的那个)换一个
    var changed = false
    val fixed = contacts.map { user ->
      if (user.uid != null && user.id !in conflicting) {
        user
      } else {
        changed = true
        user.copy(uid = UidStore.nextUid(context))
      }
    }
    if (changed) {
      contacts.clear()
      contacts.addAll(fixed)
      FriendsStore.save(context, contacts)
    }
  }

  /**
   * 这个会话对方角色的 UID;老数据里的角色还没有号时当场发一个,并写进会话。
   *
   * 会话里存着一份对方快照,写进去就随消息一起落盘,下一次不必再发号。
   */
  private fun characterUidOf(context: Context, chat: Chat): Long {
    chat.friend.uid?.let { return it }
    val uid = UidStore.nextUid(context)
    chat.friend = chat.friend.copy(uid = uid)
    return uid
  }

  fun openProfile() {
    showProfile = true
  }

  fun closeProfile() {
    showProfile = false
  }

  fun openAddFriend() {
    showAddFriend = true
  }

  fun closeAddFriend() {
    showAddFriend = false
  }

  fun openMeMenu(menu: MeMenu) {
    meMenu = menu
  }

  fun closeMeMenu() {
    meMenu = null
    apiKeyEdit = null
    showDeveloper = false
  }

  fun openDeveloper() {
    showDeveloper = true
  }

  /** 退出开发者页就关掉控制台页。 */
  fun closeDeveloper() {
    showDeveloper = false
  }

  /** 打开 API KEY 的添加(key 传 null)或修改页。 */
  fun openApiKeyEdit(key: ApiKey?) {
    apiKeyEdit = ApiKeyEditTarget(key?.id)
  }

  fun closeApiKeyEdit() {
    apiKeyEdit = null
  }

  /** 保存 API KEY:原文存在就替换那一条,否则新增,然后整体落盘。 */
  fun saveApiKey(
    context: Context,
    original: ApiKey?,
    name: String,
    model: String,
    url: String,
    key: String
  ) {
    val stored = if (original == null) {
      ApiKey(ApiKeyStore.newId(), name, model, url, key)
    } else {
      original.copy(name = name, model = model, url = url, key = key)
    }
    val index = apiKeys.indexOfFirst { it.id == stored.id }
    if (index >= 0) apiKeys[index] = stored else apiKeys.add(stored)
    ApiKeyStore.save(context.applicationContext, apiKeys)
  }

  fun deleteApiKey(context: Context, key: ApiKey) {
    apiKeys.removeAll { it.id == key.id }
    ApiKeyStore.save(context.applicationContext, apiKeys)
  }

  /**
   * 这个会话实际生效的 API KEY。
   *
   * 优先用它自己关联的那条;没关联过、或者关联的那条已经被删掉了,就回落到列表里的第一条。
   * 这样每个聊天都「总是有一个模型」——新会话与老会话都不用先去设置里点一遍。
   */
  fun apiKeyOf(chat: Chat): ApiKey? =
    chat.apiKeyId?.let { id -> apiKeys.firstOrNull { it.id == id } } ?: apiKeys.firstOrNull()

  /** 把会话关联到某条 API KEY(模型跟着 KEY 走);传 null 表示改回跟随默认。 */
  fun bindApiKey(context: Context, chat: Chat, key: ApiKey?) {
    val target = key?.id
    if (chat.apiKeyId == target) return
    chat.apiKeyId = target
    saveChats(context)
  }

  /** 某个角色实际生效的 API KEY:会话关联的那条,没有关联就用列表里的第一条。 */
  fun apiKeyForFriend(friendId: String): ApiKey? =
    chats.firstOrNull { it.friend.id == friendId }?.let { apiKeyOf(it) } ?: apiKeys.firstOrNull()

  /**
   * 某个角色的全部轮次(用户一句 + 角色一句为一轮)。
   *
   * 记忆库页拿它手动触发总结,发消息时拿它切「还没总结的原文」—— 两边必须是同一份分组,
   * 否则覆盖轮次会对不上。
   */
  fun roundsOf(friendId: String): List<List<Msg>> =
    chats.firstOrNull { it.friend.id == friendId }
      ?.let { chat -> MemoryRounds.group(chat.msgs.toList()) }
      ?: emptyList()

  /**
   * 后台跑一次记忆流水线(20 轮微总结 → 3 条微总结合成大总结)。
   *
   * 只在攒够轮数时由 [send] 调用;是否该跑、是否已有任务在跑,都由 [MemoryPipeline] 判断。
   * 失败静默 —— 轻量架构不重试,等下一次轮数到 20 的倍数再试。
   *
   * 成功之后把这个角色标成「有一轮新总结待生效」:下一轮请求因此算总结轮
   * (时钟据此推进 `last_summary_period`、吸收 pending 导演补丁),但**报文里不再多出
   * 一条总结消息** —— 记忆内容只走状态快照。
   */
  private fun summarizeMemory(
    context: Context,
    chat: Chat,
    rounds: List<List<Msg>>,
  ) {
    val friendId = chat.friend.id
    MemoryPipeline.maybeLaunch(
      context = context,
      friendId = friendId,
      apiKey = apiKeyOf(chat),
      rounds = rounds,
      // 防脏数据的快照比对读的是「此刻会话里还有多少条消息」,所以走 ViewModel 里的实时数据
      messageCount = { chats.firstOrNull { it.friend.id == friendId }?.msgs?.size ?: 0 },
    ) { ok ->
      if (ok) {
        summaryRoundPending.add(friendId)
        // 让记忆库页重读:新库有数据了
        memoryVersion++
      }
    }
  }

  // —— 开发者压测(TASK-04)——
  //
  // 三个上帝按钮的入口都在开发者控制台的「压测面板」里,但它们动的是**主聊天的真实数据**
  // (chat_store 里的会话 + memory.db),所以实现放在 ViewModel 这一侧:
  // 只有这里持有内存里那份活着的 [chats],绕过它直接改盘上的 JSON 会在下一次
  // saveChats 时被整体覆盖回去。

  /**
   * 一键注入假数据:向某个会话追加 [rounds] 轮「用户:测试语句N / 角色:测试回复N」。
   *
   * **纯本地** —— 不发任何网络请求,只为把轮数瞬间拉满,方便立刻测总结流水线。
   * 只写会话表(chat_store);memory.db 不塞假总结:那会污染微总结的水位,
   * 让之后真实的总结被误判成「这段已总结过」而吞掉。假数据要进记忆库,
   * 就在压测面板里点「强制触发记忆总结」,让流水线按真实数据流走一遍。
   *
   * 返回实际注入的轮数;-1 表示找不到这个会话。
   */
  fun injectFakeRounds(context: Context, friendId: String, rounds: Int): Int {
    val chat = chats.firstOrNull { it.friend.id == friendId } ?: return -1
    val count = rounds.coerceIn(1, MAX_FAKE_ROUNDS)
    repeat(count) { index ->
      val seq = index + 1
      chat.msgs.add(Msg(User.Me, "测试语句$seq").apply { read = true })
      chat.msgs.add(Msg(chat.friend, "测试回复$seq").apply { read = true })
    }
    saveChats(context.applicationContext)
    return count
  }

  /**
   * 强制触发记忆总结:**无视 `rounds % 20 == 0`**,直接按当前水位往后总结一段。
   *
   * 走的是和自动触发完全同一套落库逻辑([MemoryPipeline.launchManual]),所以这里测出来的
   * 报文、消耗与写入的库表都和真实运行一致 —— 唯一区别是「什么时候开始」由人决定。
   *
   * 返回 false 表示没启动:没有会话 / 没有可用 KEY / 这个角色正有一次总结在跑 / 没有可总结的轮次。
   */
  fun forceSummary(context: Context, friendId: String, onFinish: (Boolean) -> Unit = {}): Boolean =
    MemoryPipeline.launchManual(
      context = context.applicationContext,
      friendId = friendId,
      apiKey = apiKeyForFriend(friendId),
      rounds = roundsOf(friendId),
      // 防脏数据的快照比对读的是此刻会话里还剩多少条消息(与自动触发同一条尺子)
      messageCount = { chats.firstOrNull { it.friend.id == friendId }?.msgs?.size ?: 0 },
    ) { ok ->
      if (ok) {
        summaryRoundPending.add(friendId)
        // 记忆库页 / 压测看板据此重读:新库有数据了
        memoryVersion++
      }
      onFinish(ok)
    }

  /**
   * 压测面板就地改过 `memory.db`(比如「清洗脏大总结」)之后调用,让记忆库页重读。
   *
   * 为什么需要它:[memoryVersion] 是 `private set`,而清洗不经过流水线,没有别的机会自增 ——
   * 不通知的话用户切到记忆库页看到的还是清洗前的那条脏大总结。
   */
  fun notifyMemoryChanged() {
    memoryVersion++
  }

  /** 打开某个角色的角色信息页(从聊天信息页的角色名片进来)。 */
  fun openCharacterInfo(friendId: String) {
    characterInfoId = friendId
    showCharacterIdentity = false
    showWorldview = false
    showBackground = false
    showBaseTime = false
  }

  /** 关掉角色信息页,连同它下面的子页。 */
  fun closeCharacterInfo() {
    characterInfoId = null
    showCharacterIdentity = false
    showWorldview = false
    showBackground = false
    showBaseTime = false
    closeMemory()
    closeItemManager()
  }

  /** 打开某角色的记忆库页(即时记忆层 / 时间线索引层 / 长期记忆层)。 */
  fun openMemory(friendId: String) {
    memoryFriendId = friendId
  }

  fun closeMemory() {
    memoryFriendId = null
  }

  fun openCharacterIdentity() {
    showCharacterIdentity = true
  }

  fun closeCharacterIdentity() {
    showCharacterIdentity = false
  }

  /** 打开某角色的世界观页(角色代入专属)。 */
  fun openWorldview() {
    showWorldview = true
  }

  fun closeWorldview() {
    showWorldview = false
  }

  /** 打开某角色的背景页(角色代入专属)。 */
  fun openBackground() {
    showBackground = true
  }

  fun closeBackground() {
    showBackground = false
  }

  /** 打开时间管理器页(基准时间编辑器,角色代入专属)。 */
  fun openBaseTime() {
    showBaseTime = true
  }

  fun closeBaseTime() {
    showBaseTime = false
  }

  /** 打开某角色的物品管理器,并把它名下的物品读进来。 */
  fun openItemManager(context: Context, friendId: String) {
    itemManagerFriendId = friendId
    showAddItem = false
    items.clear()
    items.addAll(ItemStore.load(context.applicationContext, friendId))
  }

  /** 关掉物品管理器页,连同它下面的添加表单。 */
  fun closeItemManager() {
    itemManagerFriendId = null
    showAddItem = false
    items.clear()
  }

  fun openAddItem() {
    showAddItem = true
  }

  fun closeAddItem() {
    showAddItem = false
  }

  /** 新增一件物品并落盘。 */
  fun addItem(context: Context, name: String, description: String) {
    val friendId = itemManagerFriendId ?: return
    items.add(Item(ItemStore.newId(), name, description))
    ItemStore.save(context.applicationContext, friendId, items)
  }

  /** 删除一件物品并落盘。 */
  fun deleteItem(context: Context, item: Item) {
    val friendId = itemManagerFriendId ?: return
    items.removeAll { it.id == item.id }
    ItemStore.save(context.applicationContext, friendId, items)
  }

  /**
   * 保存角色身份(名称 / 性格 / 身份)。
   *
   * 通讯录与所有用到这个角色的会话一起改:会话里各存了一份对方信息,不同步的话
   * 聊天页标题、聊天信息页的名片以及下一轮请求体里的角色设定都还是旧的。
   */
  fun saveCharacter(
    context: Context,
    original: User,
    name: String,
    personality: String?,
    role: String?
  ) {
    // copy 只改这三项,唯一 ID / 模式 / 世界侧设定等其它字段原样保留
    updateCharacter(context, original) { it.copy(name = name, personality = personality, role = role) }
  }

  /**
   * 保存世界观四项(角色代入专属,**只本地保存**)。
   *
   * 四项全空的 [Worldview] 存成 null:老数据与「没填过」是同一种状态,不落一份空壳。
   */
  fun saveCharacterWorldview(context: Context, original: User, worldview: Worldview) {
    updateCharacter(context, original) { it.copy(worldview = worldview.takeIf { !worldview.isEmpty }) }
  }

  /** 保存背景(角色代入专属,只本地保存);清空后存 null。 */
  fun saveCharacterBackground(context: Context, original: User, background: String) {
    updateCharacter(context, original) { it.copy(background = background.trim().ifEmpty { null }) }
  }

  /**
   * 保存基准时间(角色信息页的时间管理器)。
   *
   * 改的是**已存在角色**:用 [BaseTime.mergeInto] 只换时间四项,地点 / 天气 / 倍率 / 暂停 /
   * 待注入的导演补丁全部留着(直接拿 [BaseTime.parse] 的结果整份覆盖会把它们静默抹掉),
   * 事务与快照也不动 —— 以前的时间记录还能回滚。
   * 解析在界面里就拦下了(看不懂时「完成」不可点);这里再兜一次,看不懂就什么都不写。
   */
  fun saveBaseTime(context: Context, friendId: String, text: String) {
    val appContext = context.applicationContext
    val ledger = ClockStore.load(appContext, friendId)
    val merged = BaseTime.mergeInto(ledger.state, text) ?: return
    ClockStore.save(appContext, friendId, ledger.copy(state = merged))
  }

  /**
   * 改一个角色的记录:通讯录里那一条 + 所有会话里存的对方快照一起换成 [transform] 的结果。
   *
   * 角色信息页的每个子页(身份 / 世界观 / 背景)都走这里,免得三处各写一遍同步逻辑;
   * 只改字段,不碰唯一 ID、扮演模式与聊天记录。
   */
  private fun updateCharacter(context: Context, original: User, transform: (User) -> User) {
    val appContext = context.applicationContext
    val updated = transform(original)
    val index = contacts.indexOfFirst { it.id == original.id }
    if (index >= 0) {
      contacts[index] = updated
      FriendsStore.save(appContext, contacts)
    }
    var touched = false
    chats.forEach { chat ->
      if (chat.friend.id == original.id) {
        chat.friend = updated
        touched = true
      }
    }
    if (touched) saveChats(appContext)
  }

  /**
   * 保存新角色:分配唯一 ID + 写盘 + 插到通讯录最前面。
   *
   * [baseTime] 非空表示给这个角色写一份初始时钟账本(程序设定的开局时刻),创建角色页的
   * 时间管理器与 [saveBaseTime] 共用这一层([BaseTime.seedLedger])。不传时账本从出厂默认值开始。
   * 它不是一次导演事务,所以账本里没有事务与快照 —— 从空日志开始。
   */
  fun addFriend(context: Context, friend: User, baseTime: ClockState? = null) {
    // 唯一 ID 在创建这一刻按顺序发下来,和昵称 / 创建时间 / 签名都无关;之后再改名、
    // 换签名、换头像都不会变(删掉再添加算一个新角色,会拿新号)
    val stored = if (friend.uid != null) friend
    else friend.copy(uid = UidStore.nextUid(context.applicationContext))
    contacts.add(0, stored)
    FriendsStore.save(context.applicationContext, contacts)
    if (baseTime != null) {
      ClockStore.save(context.applicationContext, stored.id, BaseTime.seedLedger(baseTime))
    }
  }

  /**
   * 切换内置角色 intersect 的扮演模式(开发者控制台里手动切换)。
   *
   * 通讯录与所有用到 intersect 的会话一起改,和 [saveCharacter] 同一个理由:
   * 会话里各存了一份对方信息,不同步的话下一轮请求体里还是旧的模式。
   */
  fun setIntersectMode(context: Context, mode: RoleplayMode) {
    val appContext = context.applicationContext
    val index = contacts.indexOfFirst { it.id == DawnAssistant.ID }
    if (index >= 0) {
      val updated = contacts[index].copy(roleplayMode = mode)
      contacts[index] = updated
      FriendsStore.save(appContext, contacts)
    }
    var touched = false
    chats.forEach { chat ->
      if (chat.friend.id == DawnAssistant.ID && chat.friend.roleplayMode != mode) {
        chat.friend = chat.friend.copy(roleplayMode = mode)
        touched = true
      }
    }
    if (touched) saveChats(appContext)
  }

  fun startChat(chat: Chat) {
    selectedMsgs.clear()
    currentChat = chat
    openModule = Module.Chat
  }

  /** 从联系人名片进入聊天;没有会话时新建一个空的。 */
  fun startChatWith(context: Context, user: User) {
    val chat = chats.firstOrNull { it.friend.id == user.id }
    if (chat != null) {
      startChat(chat)
    } else {
      val created = Chat(user, mutableStateListOf())
      chats.add(created)
      saveChats(context)
      startChat(created)
    }
  }

  fun endChat() {
    selectedMsgs.clear()
    backProgress = 0f
    openModule = null
  }

  /**
   * 按对象身份清理:退出动画结束的瞬间若用户已重新进入同一会话,不能误清。
   *
   * 聊天页的退出动画跑完才走到这里。这期间用户完全可能又点开同一个会话——
   * 不加身份判断的话,那次点击打开的新页面会被上一轮的收尾动作一起关掉。
   */
  fun clearCurrentChatIf(target: Chat) {
    if (currentChat === target) currentChat = null
  }

  /** 进入多选并只选中这一条(长按菜单里的「多选」)。 */
  fun selectOnly(msg: Msg) {
    selectedMsgs.clear()
    selectedMsgs.add(msg)
  }

  /** 切换某条消息的勾选状态(按对象身份,内容相同的两条互不影响)。 */
  fun toggleSelect(msg: Msg) {
    val index = selectedMsgs.indexOfFirst { it === msg }
    if (index >= 0) selectedMsgs.removeAt(index) else selectedMsgs.add(msg)
  }

  /** 取消勾选,回到普通聊天界面。 */
  fun clearSelection() {
    selectedMsgs.clear()
  }

  /** 全选 / 取消全选(多选顶栏用):已经在全选状态就清空,否则把整个会话都勾上。 */
  fun toggleSelectAll(chat: Chat) {
    if (selectedMsgs.size == chat.msgs.size) {
      selectedMsgs.clear()
    } else {
      selectedMsgs.clear()
      selectedMsgs.addAll(chat.msgs)
    }
  }

  fun openChatInfo(chat: Chat) {
    chatInfoChat = chat
  }

  fun closeChatInfo() {
    chatInfoChat = null
    // 角色信息页(及其子页)是聊天信息页的下层,父页关了它也得收起来
    closeCharacterInfo()
  }

  /** 删除好友:通讯录和会话一起清掉。内置助手是内置角色,不允许删。 */
  fun deleteFriend(context: Context, user: User) {
    if (DawnAssistant.isLocked(user.id)) return
    val appContext = context.applicationContext
    contacts.removeAll { it.id == user.id }
    FriendsStore.save(appContext, contacts)
    chats.removeAll { it.friend.id == user.id }
    saveChats(appContext)
    // 记忆跟着角色走:角色没了,它的即时记忆也不该以孤儿条目的形式留在盘上
    MemoryStore.remove(appContext, user.id)
    // 新库(memory.db)里的微总结 / 大总结 / 滚动状态同样清掉 —— 它也是「跟着角色走」的数据。
    // 走仓库自己的后台作用域:用户删完角色可能立刻退出页面,这种清理不该被取消。
    MemoryRepository.clearAsync(appContext, user.id)
    summaryRoundPending.remove(user.id)
    // 时钟账本同理:删了角色就把它的时段状态与事务日志一起清掉
    ClockStore.remove(appContext, user.id)
    // 物品目录也不会自己消失:不删的话这些条目会在备份里变成看不了也删不掉的孤儿
    ItemStore.remove(appContext, user.id)
    if (currentChat?.friend?.id == user.id) {
      backProgress = 0f
      openModule = null
      currentChat = null
    }
    chatInfoChat = null
    closeCharacterInfo()
  }

  fun onBackProgress(progress: Float) {
    backProgress = progress
  }

  fun cancelBack() {
    backProgress = 0f
  }

  /** 发一条文字消息并等对方的回复。发出去立刻写盘,重启后还在。 */
  fun send(context: Context, chat: Chat, text: String) {
    val content = text.trim()
    if (content.isEmpty()) return
    val appContext = context.applicationContext
    // 导演时间指令(`/time ...`):这是 T5.1 的入口,直接改时钟、不走网络。
    // 没挂 DevMode 门禁:时间推进是正常玩法能力之一,不是开发者工具。
    if (content.startsWith(DirectorParser.CMD_PREFIX)) {
      runDirectorTime(appContext, chat, content)
      return
    }
    // Self-insert 会话:时间管理器给每轮对话注入本地时间(精确到分钟)
    val injectTime = chat.friend.roleplayMode == RoleplayMode.SELF_INSERT
    val asked = Msg(User.Me, content, time = if (injectTime) TimeManager.nowDateTime() else null)
      .apply { read = true }
    chat.msgs.add(asked)
    saveChats(appContext)
    replyError = null
    // 请求在发出去的那一刻就把历史定下来:回复到的时候会话里可能已经多了别的消息
    val history = chat.msgs.toList()
    // 请求上下文在发出去的这一刻定下来:回来时可能已经换过 KEY 或切过会话
    val apiKey = apiKeyOf(chat)
    // 物品管理器:把这个会话对方名下的物品交给请求层,用户消息提到名称就注入名称与简介
    val items = ItemStore.load(appContext, chat.friend.id)
    // 时间子系统:轮次类型由这里(编排层)在构造请求时确定,不交给模型。
    // 普通轮只带稳定规则块(进 system);刚生成过新总结的这一轮算总结轮(时钟推进时段、
    // 吸收 pending 导演补丁,但**不再**往报文里塞一条总结消息);时钟里有 pending 导演补丁
    // 则改走导演事件轮(导演面板的 `<旧变更新>` 就在这一轮注入)。
    val atRound = MemoryRounds.group(history).size
    val clockSession = ClockSession(ClockStore.load(appContext, chat.friend.id))
    val summaryReady = summaryRoundPending.contains(chat.friend.id)
    val timePlan = chatTimePlan(clockSession, summaryReady, atRound)
    val summaryRound = timePlan.roundType == RoundType.SUMMARY
    val directorEventRound = timePlan.roundType == RoundType.DIRECTOR_EVENT && timePlan.hasTimeFact()
    replyingCount++
    viewModelScope.launch {
      try {
        // 状态快照的两个数据源都在 memory.db 里(room 自己切线程,这里只等结果):
        // 元信息取 rolling_meta(没有行时用设置页那份播种),记忆取最新大总结 + 最近 3 条微总结。
        // 读库失败不让这一轮发不出去:降级成「没有记忆 + 设置页的元信息」照常请求。
        val repository = MemoryRepository.get(appContext)
        val liveMeta = MetaInfoStore.load(appContext, chat.friend.id)
        val baseMeta = runCatching { repository.loadMeta(chat.friend.id, liveMeta) }.getOrDefault(liveMeta)
        // 套时钟:本体代入用真实时间 / 角色代入用剧情时钟或手填值
        val metaInfo = SceneTime.applyTo(baseMeta, chat.friend.roleplayMode)
        val snapshot = runCatching { repository.loadSnapshot(chat.friend.id) }.getOrNull()
        // 用这个会话关联的 API KEY:模型、地址、KEY 都跟着它走;角色设定取自会话的对方
        when (val result = ChatService.reply(apiKey, chat.friend, history, items, snapshot, timePlan, metaInfo)) {
          is ChatReply.Ok -> {
            // 消耗流水:密钥组 = 这条 API KEY,聊天窗口 = 这个会话的对方。
            // 失败的请求不计费,所以只有成功分支落库;没有 usage 时 record 内部会跳过。
            // 写库放到 IO 线程:这里是主线程,不该为一条插入顿一下界面。
            // 放在下面的锚点判断**之前**:请求已经发出去了就已经计费,
            // 即使结果因为消息被删而被丢弃,这笔钱也要在统计里看得到。
            withContext(Dispatchers.IO) {
              UsageLog.record(
                context = appContext,
                keyGroupId = apiKey?.id.orEmpty(),
                model = apiKey?.model.orEmpty(),
                chatWindowId = chat.friend.id,
                usage = result.usage
              )
            }
            // 这一轮提问还在对话里,回复才算数:等待期间用户可能已经删掉或回滚掉了它
            // (回滚的承诺是「回到那条之前」,把回复接到截断后的尾巴上就违背了这个承诺)
            if (chat.msgs.none { it === asked }) return@launch
            // T6.2 / T6.3 后处理:模型自报时间就删掉那一句,复述系统标签就把标签洗掉。
            // 洗完之后一个字不剩 ⇒ 这条回复整个都是违规内容,丢弃并把原因告诉用户
            // (不重发请求:本工程的网络层不重试)。
            val guarded = OutputGuard.sanitize(result.text)
            if (guarded.hits.isNotEmpty()) {
              TimeLogs.log(
                category = com.reimei.intersect.data.time.TimeLogCategory.INJECT,
                message = "回复后处理:移除 ${guarded.hits.size} 处违规",
                context = mapOf(
                  "friend" to chat.friend.id,
                  "findings" to guarded.hits.joinToString(",") { it.finding.name },
                ),
              )
            }
            if (guarded.text.isBlank()) {
              replyError = "这条回复只包含报时或系统标签,已按时间规则移除"
              return@launch
            }
            // 记忆索引:这一轮「用户一句 + AI 一句」只记一份,挂在回复上。
            // 角色号跟着角色记录走(intersect 固定 0),我是固定 1;
            // 时间是这条记忆马上就要写进设备的秒级时间,所以现在就取
            val memory = MemoryStamp(
              characterUid = characterUidOf(appContext, chat),
              userUid = UidStore.ME_UID,
              storedAt = System.currentTimeMillis() / 1000L,
            )
            chat.msgs.add(
              Msg(
                chat.friend,
                guarded.text,
                time = if (injectTime) TimeManager.nowDateTime() else null,
                memory = memory,
              ).apply { read = true }
            )
            saveChats(appContext)
            // 记忆流水线:轮数攒够 20 的倍数就在后台跑一次总结(独立请求,不阻塞这条回复)。
            // 旧 MemoryStore 不再写入 —— 新的 memory.db 是唯一写入方。
            val rounds = MemoryRounds.group(chat.msgs.toList())
            summarizeMemory(appContext, chat, rounds)
            // 总结轮成功发出去:推进 last_summary_period 并消费掉 pending 导演补丁
            // (T1.6 消费规则:补丁只被下一次总结吸收一次),账本落盘。
            if (summaryRound) {
              clockSession.markSummary(atRound = atRound)
              ClockStore.save(appContext, chat.friend.id, clockSession.ledger())
              // 这一轮的新总结已经随快照发出去了,标记消费掉,下一轮回到普通轮
              summaryRoundPending.remove(chat.friend.id)
            } else if (directorEventRound) {
              // 导演面板的场景变更已经随这一轮注入过了:清掉变更明细(补丁本身留着等总结吸收),
              // 所以 `<旧变更新>` 只出现一次。请求失败时不清,下一轮还能再带上。
              if (clockSession.consumeSceneChanges(atRound = atRound) != null) {
                ClockStore.save(appContext, chat.friend.id, clockSession.ledger())
              }
            }
          }
          // 失败不写进聊天记录:原因属于这一轮请求的状态,不是谁说过的话
          is ChatReply.Fail -> replyError = result.message
        }
      } finally {
        replyingCount--
      }
    }
  }

  /** 清掉上一次请求的失败提示(用户重新发一条时也会自动清)。 */
  fun clearReplyError() {
    replyError = null
  }

  /** 清掉导演 / 时钟的提示行(点一下「知道了」)。 */
  fun clearClockNotice() {
    clockNotice = null
  }

  /**
   * 这一轮的注入计划(**编排层**的工作,不交给模型)。
   *
   * 优先级与 [TimeRounds.of] 一致:刚有新的微总结 ⇒ 总结轮;否则时钟里有 pending 导演补丁
   * ⇒ 导演事件轮;都没有 ⇒ 普通轮(只带稳定规则块)。
   *
   * 总结轮**不再带总结块与段落元数据**:记忆只走状态快照(见 [ChatService.snapshotBlock]),
   * 旧版「记忆总结块直接充当时间总结块」的特殊报文形态已废弃。轮次类型留下来只用于时钟记账
   * (`last_summary_period` 推进 + pending 导演补丁的消费时机)。
   */
  private fun chatTimePlan(
    session: ClockSession,
    summaryReady: Boolean,
    atRound: Int,
  ): InjectionPlan {
    val clock = session.state
    val event = TimeRounds.of(
      summaryReady = summaryReady,
      directorPatchPending = clock.pendingDirectorPatch != null,
    )
    return TimeRounds.plan(
      event = event,
      directorPatch = clock.pendingDirectorPatch,
      atRound = atRound,
    )
  }

  /**
   * T5.1–T5.5 导演时间指令入口:解析 → 执行 → 快照/事务日志 → 落盘,并把回显交给状态行。
   *
   * 不传网络、不碰 AI 输出:时钟只能由这里改。低置信说法一律不动时钟(T5.2)。
   */
  fun runDirectorTime(context: Context, chat: Chat, text: String): String {
    val appContext = context.applicationContext
    val session = ClockSession(ClockStore.load(appContext, chat.friend.id))
    val atRound = MemoryRounds.group(chat.msgs.toList()).size
    val outcome = session.command(text, atRound = atRound)
    if (outcome.applied) {
      ClockStore.save(appContext, chat.friend.id, session.ledger())
    }
    replyError = null
    clockNotice = outcome.echo
    return outcome.echo
  }

  /** 时钟当前状态(UI / 导演面板 / 调试用)。精确时刻不进模型上下文。 */
  fun clockState(context: Context, friendId: String): ClockState =
    ClockStore.load(context.applicationContext, friendId).state

  /** 导演面板打开时的初始值:时间 / 地点 / 天气(取自当前时钟状态)。 */
  fun sceneDraft(context: Context, friendId: String): SceneDraft =
    SceneEditor.draftOf(clockState(context, friendId))

  /** 基准时间的人读文本(角色信息页那一行用):含纪年名与显式正负号。 */
  fun baseTimeLabel(context: Context, friendId: String): String =
    SceneCalendar.label(clockState(context, friendId))

  /**
   * 导演面板保存:时间 / 地点 / 天气一次落账(走 [ClockSession.editScene] → [TimeAuthority])。
   *
   * 返回 **null 表示保存成功**(包括「改成了原样、本来就没变化」这种情况:没有写时钟,
   * 也不会重注入);非 null 是失败原因(格式看不懂 / 字段太长),面板据此留在原地让用户改。
   *
   * 成功后下一轮的注入计划会变成导演事件轮,把 `<旧值变更新值>` 那几行发给模型,且只发一次。
   */
  fun saveScene(context: Context, chat: Chat, draft: SceneDraft): String? {
    val appContext = context.applicationContext
    val session = ClockSession(ClockStore.load(appContext, chat.friend.id))
    val atRound = MemoryRounds.group(chat.msgs.toList()).size
    val outcome = session.editScene(draft, atRound = atRound)
    if (outcome.applied) ClockStore.save(appContext, chat.friend.id, session.ledger())
    replyError = null
    clockNotice = outcome.echo
    return outcome.rejection
  }

  /** 按 `tx_id` 回滚一次时钟写操作(T5.4),返回新的事务日志摘要;失败返回原因。 */
  fun rollbackClock(context: Context, friendId: String, txId: String): String {
    val appContext = context.applicationContext
    val session = ClockSession(ClockStore.load(appContext, friendId))
    val atRound = 0
    val tx = session.rollback(txId, atRound = atRound)
    if (tx == null) {
      clockNotice = "没有可回滚的事务:$txId"
      return clockNotice!!
    }
    ClockStore.save(appContext, friendId, session.ledger())
    clockNotice = "已回滚 $txId:${TimeFallback.exactLabel(session.state)}"
    return clockNotice!!
  }

  /**
   * 删除若干条消息并落盘。
   *
   * 按对象身份匹配(而不是 equals):两条内容相同、发送者相同的消息是各自独立的,
   * 删一条不该把另一条一起带走。
   */
  fun deleteMsgs(context: Context, chat: Chat, targets: Collection<Msg>) {
    if (targets.isEmpty()) return
    val identitySet = Collections.newSetFromMap(IdentityHashMap<Msg, Boolean>())
    identitySet.addAll(targets)
    val removed = chat.msgs.removeAll { msg -> identitySet.contains(msg) }
    if (removed) {
      saveChats(context)
      pruneSelection(chat)
      val rounds = MemoryRounds.group(chat.msgs.toList()).size
      // 消息少了,记忆层的轮数 / 覆盖轮次也跟着校正,不然「距下次总结」会一直停在 0
      MemoryStore.reconcile(context.applicationContext, chat.friend.id, rounds)
      // 新库同理:覆盖范围超出现有轮数的总结已经描述不了当前对话,必须一起裁掉
      MemoryRepository.trimBeyondAsync(context.applicationContext, chat.friend.id, rounds)
    }
  }

  /**
   * 回滚到某条消息之前:这条以及它之后的全部消息一起删掉。
   *
   * 与控制台的 `rollbackTo` 同一套语义,只是对象是主聊天的会话:
   * 按对象身份定位(不依赖 equals,内容相同的两条不会串),落盘后校正记忆层轮数。
   * 记忆索引、物品、通讯录都不动 —— 回滚的是对话本身。
   */
  fun rollbackTo(context: Context, chat: Chat, msg: Msg) {
    if (truncateAt(chat.msgs, msg) < 0) return
    saveChats(context)
    pruneSelection(chat)
    val rounds = MemoryRounds.group(chat.msgs.toList()).size
    // 轮数变小了:不校正的话「距下次总结」会一直停在 0,已删掉的那段总结还会被注入
    MemoryStore.reconcile(context.applicationContext, chat.friend.id, rounds)
    // 新库的总结同样按轮次裁剪:否则回滚之后还会把已删掉的剧情注入,写水位也会卡住
    MemoryRepository.trimBeyondAsync(context.applicationContext, chat.friend.id, rounds)
  }

  /**
   * 把多选里指向「已经不在这个会话里」的引用清掉。
   *
   * 多选状态活在 ViewModel 里,删除/回滚之后那些对象已经离开会话:留着的话
   * 多选顶栏会一直显示、复制出来是空串、全选的 `size == msgs.size` 判断也会错位。
   */
  private fun pruneSelection(chat: Chat) {
    if (selectedMsgs.isEmpty()) return
    selectedMsgs.removeAll { picked -> chat.msgs.none { it === picked } }
  }

  /** 标记已读并落盘;没有未读时不写盘。 */
  fun read(context: Context, chat: Chat) {
    var changed = false
    for (msg in chat.msgs) {
      if (!msg.read) {
        msg.read = true
        changed = true
      }
    }
    if (changed) saveChats(context)
  }

  private companion object {
    /**
     * 压测注入一次最多多少轮(400 条消息)。
     *
     * 上限存在的意义只是别让 chat_store 里那份 JSON 无限膨胀 —— 超过这个量级,
     * 想测的总结链路早就测完了。
     */
    const val MAX_FAKE_ROUNDS = 200
  }
}
