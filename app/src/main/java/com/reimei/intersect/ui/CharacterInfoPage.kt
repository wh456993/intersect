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
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.TimeManager
import com.reimei.intersect.data.User
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** 角色名称的字数上限:和添加角色页的昵称一致。 */
private const val NAME_MAX = 20

/** 性格可以写几句(语气、脾气、忌讳),但要能塞进系统提示里。 */
private const val PERSONALITY_MAX = 200

/** 身份一句话说清它是谁,同样按多行给足空间。 */
private const val ROLE_MAX = 200

/**
 * 角色信息页:从聊天信息页点角色名片进来。
 *
 * 这里是一层入口列表 —— 角色身份(已实现)及其它几项系统模块(开发中)。
 * 角色身份是本轮唯一的实页,其余先占位,下一轮再逐项做。
 */
@Composable
fun CharacterInfoPage(
  modifier: Modifier = Modifier,
  friend: User,
  onOpenIdentity: () -> Unit,
  onOpenItemManager: () -> Unit,
  onBack: () -> Unit,
) {
  BackHandler { onBack() }

  // Self-insert 会话的时间管理器会实时刷新:精确到分钟,每 30 秒重取一次本地时间
  var now by remember(friend.id) { mutableStateOf(TimeManager.nowDateTime()) }
  if (friend.roleplayMode == RoleplayMode.SELF_INSERT) {
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
      // 名片:只是展示,点它自己没意义 —— 入口在下面的列表里
      Row(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        UserAvatar(friend, size = 52.dp, shape = RoundedCornerShape(8.dp))
        Column(
          Modifier
            .weight(1f)
            .padding(start = 12.dp)
        ) {
          Text(
            friend.name,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            friend.signature?.takeIf { it.isNotBlank() } ?: "这个人很懒,什么都没写",
            Modifier.padding(top = 4.dp),
            fontSize = 12.sp,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        // 已实现的只有角色身份;其余几项是规划中的系统模块,先标开发中
        CharacterEntryRow(
          title = "角色身份",
          detail = identitySummary(friend),
          onClick = onOpenIdentity
        )
        CharacterDivider()
        ComingSoonRow("事件线")
        CharacterDivider()
        ComingSoonRow("记忆库")
        CharacterDivider()
        ComingSoonRow("地图管理器")
        CharacterDivider()
        CharacterEntryRow(
          title = "物品管理器",
          detail = "登记物品,对话提到名称时自动补充简介",
          onClick = onOpenItemManager
        )
        CharacterDivider()
        if (friend.roleplayMode == RoleplayMode.SELF_INSERT) {
          TimeManagerRow(now)
        } else {
          ComingSoonRow("时间管理器")
        }
      }
    }
  }
}

/** 角色身份这一行的副标题:说清它是哪三项,顺便提示已经填了没有。 */
private fun identitySummary(friend: User): String {
  val filled = listOf(friend.name.isNotBlank(), !friend.personality.isNullOrBlank(), !friend.role.isNullOrBlank())
  val count = filled.count { it }
  return if (count == 3) "名称、性格、身份都已填写" else "名称、性格、身份 · 已填写 $count 项"
}

/** 可进入的条目:标题 + 副标题 + 右侧箭头。 */
@Composable
private fun CharacterEntryRow(
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

/** 还没做的条目:整行不可点,右侧标「开发中」,文字也要比别的行淡。 */
@Composable
private fun ComingSoonRow(title: String) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(title, Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.more)
    Text("开发中", fontSize = 12.sp, color = AppTheme.colors.more)
  }
}

/** Self-insert 角色的时间管理器:右侧实时显示本地年月日时分,不再是「开发中」。 */
@Composable
private fun TimeManagerRow(time: String) {
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
private fun CharacterDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
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
 */
@Composable
fun CharacterIdentityEditor(
  modifier: Modifier = Modifier,
  stateKey: String,
  title: String,
  initialName: String,
  initialPersonality: String?,
  initialRole: String?,
  onBack: () -> Unit,
  onSave: (name: String, personality: String?, role: String?) -> Unit,
) {
  BackHandler { onBack() }

  var name by remember(stateKey) { mutableStateOf(initialName) }
  var personality by remember(stateKey) { mutableStateOf(initialPersonality.orEmpty()) }
  var role by remember(stateKey) { mutableStateOf(initialRole.orEmpty()) }

  val trimmedName = name.trim()
  val canSave = trimmedName.isNotEmpty()

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
          hint = if (trimmedName.isEmpty()) "不能为空" else "$NAME_MAX 个字符以内",
          hintColor = if (trimmedName.isEmpty()) AppTheme.colors.badge else AppTheme.colors.textSecondary,
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
private fun MultiLineField(
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
