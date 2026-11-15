package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.Worldview
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme

/** 背景的字数上限:一段来历写清即可,不必写成设定集。 */
private const val BACKGROUND_MAX = 500

/**
 * 世界观页:角色代入专属,四项各一个多行输入。
 *
 * 法则体系 / 势力分布 / 经济架构 / 历史与重大事件 —— 顺序由 [Worldview.LABELS] 固定,
 * 四项都可以留空。**只本地保存**:保存后随角色记录落盘(备份也带着),
 * 但不会进请求体,对话的 system 仍只有名称 / 身份 / 性格。
 *
 * [stateKey] 变了才把草稿重置成 [original](重进同一个角色时回到现值)。
 */
@Composable
fun WorldviewPage(
  modifier: Modifier = Modifier,
  stateKey: String,
  original: Worldview?,
  onBack: () -> Unit,
  onSave: (Worldview) -> Unit,
) {
  BackHandler { onBack() }

  // 四个字段都用 rememberSaveable:旋转屏幕重建 Activity 时刚写的一大段不会没
  // 同时 take 一次上限:从备份导入的旧数据可能超长,不裁的话输入框会变成「打字无效」
  var laws by rememberSaveable(stateKey) { mutableStateOf(original?.laws.orEmpty().take(Worldview.FIELD_MAX)) }
  var factions by rememberSaveable(stateKey) { mutableStateOf(original?.factions.orEmpty().take(Worldview.FIELD_MAX)) }
  var economy by rememberSaveable(stateKey) { mutableStateOf(original?.economy.orEmpty().take(Worldview.FIELD_MAX)) }
  var history by rememberSaveable(stateKey) { mutableStateOf(original?.history.orEmpty().take(Worldview.FIELD_MAX)) }

  val draft = Worldview(
    laws = laws.trim().ifEmpty { null },
    factions = factions.trim().ifEmpty { null },
    economy = economy.trim().ifEmpty { null },
    history = history.trim().ifEmpty { null },
  )

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    // 四项都能留空,所以「完成」永远可点;想放弃就返回
    EditTopBar(
      title = "世界观",
      actionText = "完成",
      actionEnabled = true,
      onBack = onBack,
      onAction = { onSave(draft) }
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
        MultiLineField(
          label = Worldview.LABELS[0],
          value = laws,
          maxLength = Worldview.FIELD_MAX,
          placeholder = "可留空,比如:这个世界没有神,一切力量都要以记忆为代价",
          onValueChange = { laws = it }
        )
        CharacterDivider()
        MultiLineField(
          label = Worldview.LABELS[1],
          value = factions,
          maxLength = Worldview.FIELD_MAX,
          placeholder = "可留空,比如:北境军府与商会联盟对峙,教团在两方之间卖消息",
          onValueChange = { factions = it }
        )
        CharacterDivider()
        MultiLineField(
          label = Worldview.LABELS[2],
          value = economy,
          maxLength = Worldview.FIELD_MAX,
          placeholder = "可留空,比如:以盐为硬通货,长途贸易全走河运,税按船算",
          onValueChange = { economy = it }
        )
        CharacterDivider()
        MultiLineField(
          label = Worldview.LABELS[3],
          value = history,
          maxLength = Worldview.FIELD_MAX,
          placeholder = "可留空,比如:十七年前的断河之战之后,旧王族再没出现过",
          onValueChange = { history = it }
        )
      }
      Text(
        "这四项只在本机记录,不会发给模型;你的每一次对话仍只带名称、身份与性格。",
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
  }
}

/**
 * 背景页:角色代入专属,一个多行输入。
 *
 * 写这个角色从哪来、处在什么境地、想要什么。可留空,清空即等于没填。
 * 与世界观同一条纪律:只本地保存,不进请求体。
 */
@Composable
fun BackgroundPage(
  modifier: Modifier = Modifier,
  stateKey: String,
  original: String?,
  onBack: () -> Unit,
  onSave: (String) -> Unit,
) {
  BackHandler { onBack() }

  var text by rememberSaveable(stateKey) { mutableStateOf(original.orEmpty().take(BACKGROUND_MAX)) }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "背景",
      actionText = "完成",
      actionEnabled = true,
      onBack = onBack,
      onAction = { onSave(text.trim()) }
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
        MultiLineField(
          label = "背景",
          value = text,
          maxLength = BACKGROUND_MAX,
          placeholder = "可留空,比如:出身铁匠铺,十六岁进城当学徒,如今欠着一笔还不上的债",
          onValueChange = { text = it }
        )
      }
      Text(
        "只在本机记录,不会发给模型。",
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
  }
}
