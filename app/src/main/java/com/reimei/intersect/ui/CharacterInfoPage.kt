package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.data.CharacterModule
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.data.User
import com.reimei.intersect.data.Worldview
import com.reimei.intersect.data.time.BaseTime
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** 角色名称的字数上限:和添加角色页的昵称一致。 */
private const val NAME_MAX = 20

/** 性格可以写几句(语气、脾气、忌讳),但要能塞进系统提示里。 */
private const val PERSONALITY_MAX = 200

/** 身份一句话说清它是谁,同样按多行给足空间。 */
private const val ROLE_MAX = 200

/**
 * 角色信息页:从聊天信息页点角色名片进来,也是创建角色时点选扮演模式后进入的那一页(草稿形态)。
 *
 * 入口清单**逐项照抄**扮演模式自己的 [com.reimei.intersect.data.RoleplayMode.modules]:
 * 角色代入与本体代入是两套独立的界面,谁也不从谁推导出来 —— 页面上出现哪几行,只由这个角色
 * 的模式声明决定。
 *
 * [baseTime] 是这个角色的基准时间(程序设定的开局时刻)的人读文本,只有声明了
 * [CharacterModule.BASE_TIME] 的模式才用得上;声明了 [CharacterModule.LOCAL_TIME] 的模式
 * (本体代入)那一行显示的是实时本地时间,每 30 秒重取一次。
 *
 * [draft] 是「添加角色」流程里的草稿形态:此时角色还没落库,记忆库还没有内容可看,
 * 所以那一行只标一句「创建后可用」;身份 / 物品 / 世界观 / 背景 / 基准时间都能直接填,
 * 填的内容由创建页带着一起落盘。草稿形态下标题会带上模式名([title]),
 * 让人看得出现在进的是哪一套界面。
 *
 * 开发者控制台的「角色信息」页([DevCharacterInfoPage])用的是同一套行样式,
 * 所以下面这些条目组件是 internal 的。
 */
@Composable
fun CharacterInfoPage(
  modifier: Modifier = Modifier,
  friend: User,
  baseTime: String = "",
  draft: Boolean = false,
  title: String = "角色信息",
  onOpenIdentity: () -> Unit,
  onOpenMemory: () -> Unit,
  onOpenItemManager: () -> Unit,
  onOpenWorldview: () -> Unit,
  onOpenBackground: () -> Unit,
  onOpenTimeManager: () -> Unit,
  onBack: () -> Unit,
) {
  BackHandler { onBack() }

  // 实时时间管理器(本体代入)每 30 秒重取一次本地时间,精确到分钟;别的模式不跑这个循环
  var now by remember(friend.id) { mutableStateOf(TimeManager.nowDateTime()) }
  if (friend.roleplayMode.has(CharacterModule.LOCAL_TIME)) {
    LaunchedEffect(friend.id) {
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
      title = title,
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
      // 名片:只是展示,点它自己没意义 —— 入口在下面的列表里(所以不再带签名副标题)
      CharacterProfileCard(
        avatar = friend,
        name = friend.name,
      )
      Spacer(Modifier.height(12.dp))

      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        // 入口清单来自模式自己声明的 modules:这里只是逐项翻译成一行,不做任何模式判断
        friend.roleplayMode.modules.forEachIndexed { index, module ->
          if (index > 0) CharacterDivider()
          when (module) {
            CharacterModule.IDENTITY -> CharacterEntryRow(
              title = "角色身份",
              detail = identitySummary(friend.name, friend.personality, friend.role),
              onClick = onOpenIdentity
            )

            // 记忆库的内容来自聊天记录:草稿形态还没有记录,先标一句创建后可用
            CharacterModule.MEMORY -> if (draft) {
              ComingSoonRow("记忆库", DRAFT_ONLY_NOTE)
            } else {
              CharacterEntryRow(
                title = "记忆库",
                detail = "即时记忆 / 引导线索 / 长期记忆",
                onClick = onOpenMemory
              )
            }

            CharacterModule.SPACE -> ComingSoonRow("空间管理器")

            CharacterModule.ITEMS -> CharacterEntryRow(
              title = "物品管理器",
              detail = "登记物品,对话提到名称时自动补充简介",
              onClick = onOpenItemManager
            )

            CharacterModule.WORLDVIEW -> CharacterEntryRow(
              title = "世界观",
              detail = worldviewSummary(friend.worldview),
              onClick = onOpenWorldview
            )

            CharacterModule.BACKGROUND -> CharacterEntryRow(
              title = "背景",
              detail = backgroundSummary(friend.background),
              onClick = onOpenBackground
            )

            // 基准时间是程序设定的开局时刻,可改:点这一行进 [BaseTimePage]。
            // 始终显示具体时刻(草稿里没改就是出厂基准 2026.1.1 8:00):同一行在创建前后看到的值一致,
            // 不会让人以为「没填却被记了值」
            CharacterModule.BASE_TIME -> CharacterEntryRow(
              title = "时间管理器",
              detail = if (baseTime.isBlank()) BASE_TIME_NOTE else "$BASE_TIME_LABEL · $baseTime",
              onClick = onOpenTimeManager
            )

            // 本体代入没有可改的时间:直接显示本机当前时刻
            CharacterModule.LOCAL_TIME -> TimeManagerRow(now)
          }
        }
      }
    }
  }
}

/** 创建流程里还没有角色的那些入口写的那句话。 */
internal const val DRAFT_ONLY_NOTE = "创建后可用"

/**
 * 角色信息页顶上的名片:头像 + 名字 + 可选的一行副标题。
 *
 * 角色信息页不传 [detail]:那里只放得出「这个角色是谁」的入口,再挂一行签名既填不出来
 * (创建角色时不再填签名)也没法点,不如留白。开发者控制台会把这份设定拼出的 system 当副标题传进来,
 * 那边仍然照常显示。
 */
@Composable
internal fun CharacterProfileCard(
  avatar: User,
  name: String,
  detail: String = "",
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    UserAvatar(avatar, size = 52.dp)
    Column(
      Modifier
        .weight(1f)
        .padding(start = 12.dp)
    ) {
      Text(
        name,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (detail.isNotBlank()) {
        Text(
          detail,
          Modifier.padding(top = 4.dp),
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/** 角色身份这一行的副标题:说清它是哪三项,顺便提示已经填了没有。 */
internal fun identitySummary(name: String, personality: String?, role: String?): String {
  val filled = listOf(name.isNotBlank(), !personality.isNullOrBlank(), !role.isNullOrBlank())
  val count = filled.count { it }
  return if (count == 3) "名称、性格、身份都已填写" else "名称、性格、身份 · 已填写 $count 项"
}

/**
 * 世界观这一行的副标题:计数放在最前面。
 *
 * 四项名点出来能说清这一行是什么,但整行只有一行宽、超出会省略 —— 所以「已填几项」必须排在最前,
 * 否则被截掉的正好是唯一有信息量的那一截。
 */
internal fun worldviewSummary(worldview: Worldview?): String {
  val names = Worldview.LABELS.joinToString(" / ")
  val count = worldview?.filledCount ?: 0
  return if (count == 0) "还没填写 · $names" else "已填 $count/${Worldview.LABELS.size} · $names"
}

/** 背景这一行的副标题:没填时提示可以填,填了就给首行摘要(长文本由行尾省略兜住)。 */
internal fun backgroundSummary(background: String?): String {
  val text = background?.trim().orEmpty()
  if (text.isEmpty()) return "填写这个角色的来历与处境(可留空)"
  return text.lineSequence().first().trim()
}

/** 可进入的条目:标题 + 副标题 + 右侧箭头。 */
@Composable
internal fun CharacterEntryRow(
  title: String,
  detail: String,
  onClick: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      Text(
        detail,
        Modifier.padding(top = 4.dp),
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

/** 还没做的条目:整行不可点,右侧标一句状态,文字也要比别的行淡。 */
@Composable
internal fun ComingSoonRow(title: String, note: String = "开发中") {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(title, Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.more)
    Text(note, fontSize = 12.sp, color = AppTheme.colors.more)
  }
}

/**
 * 角色代入的时间管理器右侧那句话:开局时刻是**程序设定的基准**。
 *
 * 这一行是可点入口,点进去是 [BaseTimePage](创建角色时是同一个页面,只是草稿形态)。
 * 基准时间不是导演事务:保存后直接作为时钟账本的初始状态落盘,事务日志从空开始。
 */
internal const val BASE_TIME_NOTE = "基准时间(程序设定的)"

/** 有具体基准时刻时的前缀(`基准时间 · 2026.1.1 8:00`)。 */
internal const val BASE_TIME_LABEL = "基准时间"

/** 基准时间的输入上限:带纪年的写法也就二十来个字符,留足余量。 */
private const val BASE_TIME_MAX = 40

/** 基准时间写得看不懂时的那句话(与导演面板同一条纪律:看不懂就拦住,什么都不写)。 */
internal const val BASE_TIME_UNREADABLE = "看不懂这个时间:年份可带正负号,纪年名不能含数字"

/** 基准时间页底部的说明:它只定当前时刻,不是一次导演改动,也不删已有记录。 */
internal const val BASE_TIME_NOTE_HINT =
  "基准时间是程序设定的开局时刻;改它只改当前的时刻,以前的时间记录不会被动。清空就是回到出厂基准 2026.1.1 8:00。"

/** Self-insert 角色的时间管理器:右侧实时显示本地年月日时分,不再是「开发中」。 */
@Composable
internal fun TimeManagerRow(time: String) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text("时间管理器", Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    Text(time, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
  }
}

@Composable
internal fun CharacterDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

/**
 * 时间管理器页(角色代入):编辑这个角色的**基准时间**。
 *
 * 和创建角色页原来那个时间管理器是同一件事,只是挪到了角色信息页下面:一个输入框,
 * 写法与导演面板同源([BaseTime.HINT]),解析交给 [BaseTime.parseOrEmpty] —— 看不懂就保留输入、
 * 拦下「完成」,什么都不写(空白等于用出厂基准)。
 *
 * 交回去的是**文本**而不是解析好的状态:已有角色要把时间**合进**当前时钟(保住地点 / 天气 /
 * 待注入的导演补丁),那一层由 ViewModel 用 [BaseTime.mergeInto] 做;刚创建的角色则直接把
 * 它当作账本初始状态。在这一页里先解析一遍只是为了判「能不能点完成」。
 */
@Composable
fun BaseTimePage(
  modifier: Modifier = Modifier,
  stateKey: String,
  initialText: String,
  onBack: () -> Unit,
  onSave: (String) -> Unit,
) {
  BackHandler { onBack() }

  // 用 rememberSaveable:写上几十个字再转个屏不至于白写
  var text by rememberSaveable(stateKey) { mutableStateOf(initialText) }
  // 空白 = 出厂基准;非空但解析不出 = null,这时候「完成」不可点
  val readable = BaseTime.parseOrEmpty(text) != null

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "时间管理器",
      actionText = "完成",
      actionEnabled = readable,
      onBack = onBack,
      onAction = { if (readable) onSave(text.trim()) }
    )

    Column(
      Modifier
        .weight(1f)
        .fillMaxWidth()
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
          label = "基准时间",
          value = text,
          maxLength = BASE_TIME_MAX,
          onValueChange = { text = it },
          hint = BaseTime.HINT,
          hintColor = if (readable) AppTheme.colors.textSecondary else AppTheme.colors.badge
        )
      }
      // 输入框下的提示只在聚焦时出现,格式错误必须一直看得见,所以单独一行
      if (!readable) {
        Text(
          BASE_TIME_UNREADABLE,
          Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
          fontSize = 11.sp,
          color = AppTheme.colors.badge
        )
      }
      Text(
        BASE_TIME_NOTE_HINT,
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
  }
}

/**
 * 角色身份页:角色名称 / 角色性格 / 角色身份。
 *
 * 这三项就是每轮请求里的角色设定 —— 名称是它叫什么,性格决定它怎么说话,
 * 身份说明它是谁。保存后立刻进 [onSave],由 ViewModel 同步到通讯录与所有会话。
 *
 * [original] 是改之前的样子:进来的字段全是它的现值,没点「完成」就返回等于放弃修改。
 */
@Composable
fun CharacterIdentityPage(
  modifier: Modifier = Modifier,
  original: User,
  onBack: () -> Unit,
  onSave: (name: String, personality: String?, role: String?) -> Unit,
) = CharacterIdentityEditor(
  modifier = modifier,
  stateKey = original.id,
  title = "角色身份",
  initialName = original.name,
  initialPersonality = original.personality,
  initialRole = original.role,
  onBack = onBack,
  onSave = onSave,
)

/**
 * 角色身份页本体:名称 / 性格 / 身份三项的编辑框。
 *
 * 主聊天的角色身份页与开发者控制台的「角色」页都是它,区别只在标题与初值来源;
 * [stateKey] 变了才把草稿重置成初值(重进同一个角色时回到现值)。
 *
 * [minNameLength] 是名称至少几个字:默认 1(只说「不能为空」);创建角色流程传 3,
 * 否则它能存下表单不接受的名字,回去后右上角「完成」会莫名变灰。
 */
@Composable
fun CharacterIdentityEditor(
  modifier: Modifier = Modifier,
  stateKey: String,
  title: String,
  initialName: String,
  initialPersonality: String?,
  initialRole: String?,
  minNameLength: Int = 1,
  onBack: () -> Unit,
  onSave: (name: String, personality: String?, role: String?) -> Unit,
) {
  BackHandler { onBack() }

  var name by remember(stateKey) { mutableStateOf(initialName) }
  var personality by remember(stateKey) { mutableStateOf(initialPersonality.orEmpty()) }
  var role by remember(stateKey) { mutableStateOf(initialRole.orEmpty()) }

  val trimmedName = name.trim()
  val canSave = trimmedName.length >= minNameLength

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = title,
      actionText = "完成",
      actionEnabled = canSave,
      onBack = onBack,
      onAction = {
        if (!canSave) return@EditTopBar
        onSave(
          trimmedName,
          personality.trim().ifEmpty { null },
          role.trim().ifEmpty { null },
        )
      }
    )

    Column(
      Modifier
        .weight(1f)
        .fillMaxWidth()
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
          hint = if (trimmedName.isEmpty()) "不能为空"
          else if (trimmedName.length < minNameLength) "至少 $minNameLength 个字符"
          else "$NAME_MAX 个字符以内",
          hintColor = if (canSave) AppTheme.colors.textSecondary else AppTheme.colors.badge,
          maxLength = NAME_MAX,
          onValueChange = { name = it }
        )
        CharacterDivider()
        MultiLineField(
          label = "性格",
          value = personality,
          maxLength = PERSONALITY_MAX,
          placeholder = "可留空,比如:说话简短直接,偶尔吐槽,不喜欢寒暄",
          onValueChange = { personality = it }
        )
        CharacterDivider()
        MultiLineField(
          label = "身份",
          value = role,
          maxLength = ROLE_MAX,
          placeholder = "可留空,比如:本软件的向导,负责带新用户上手",
          onValueChange = { role = it }
        )
      }
    }
  }
}

/** 多行输入:标题与字数在上一行,下面是撑开的输入区,适合性格、身份这种能写几句的字段。 */
@Composable
internal fun MultiLineField(
  label: String,
  value: String,
  maxLength: Int,
  placeholder: String,
  onValueChange: (String) -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      Text(
        "${value.length}/$maxLength",
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(8.dp))
    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 84.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(AppTheme.colors.textFieldBackground)
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
      if (value.isEmpty()) {
        Text(placeholder, fontSize = 14.sp, color = AppTheme.colors.more)
      }
      BasicTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 14.sp, color = AppTheme.colors.textPrimary),
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
      )
    }
  }
}
