package com.reimei.intersect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.ImmediateMemoryState
import com.reimei.intersect.data.MemoryPromptStore
import com.reimei.intersect.data.MemoryStore
import com.reimei.intersect.data.MemorySummarizer
import com.reimei.intersect.data.memory.MemoryRepository
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * 记忆总结的设置面板:自定义总结提示词 + 总结长度上限 + 清空即时记忆。
 *
 * 入口在这个角色**自己的聊天页 → 附件 → 记忆设置**,不再是全局的「设置 → 记忆设置」:
 * 这几项调的都是「这个角色的记忆怎么写」,跟着角色走才不会串味。
 *
 * 隔离规则(本面板只有一份数据能改):
 * - 面板**不提供角色切换**:[friendId] 由调用方(聊天页)传当前会话的角色,
 *   长度上限写 [MemoryStore] 里这个角色那一份,提示词写 [MemoryPromptStore] 里这个角色那一条;
 * - 因此这里改任何一项,其他角色的记忆口径与长度都不受影响;
 * - 「清空」也只清这个角色的两套库(旧归档 + memory.db)。
 */
@Composable
fun MemorySettingsSheet(
  modifier: Modifier = Modifier,
  viewModel: MainViewModel,
  friendId: String,
  characterName: String,
  onBack: () -> Unit,
) {
  val context = LocalContext.current

  // 面板只在「这个角色」上工作:所有初值都按 friendId 读一次,换角色/重开面板就重读
  var promptEnabled by remember(friendId) {
    mutableStateOf(MemoryPromptStore.enabled(context, friendId))
  }
  var savedPrompt by remember(friendId) { mutableStateOf(MemoryPromptStore.prompt(context, friendId)) }
  // 提示词编辑态放在面板这一层:滚动时被回收也不至于把改了一半的文本丢掉
  var promptText by remember(friendId) { mutableStateOf(MemoryPromptStore.draft(context, friendId)) }
  var promptNotice by remember(friendId) { mutableStateOf<String?>(null) }
  var target by remember(friendId) { mutableStateOf(MemoryStore.load(context, friendId).targetTokens) }
  var notice by remember(friendId) { mutableStateOf<String?>(null) }
  var confirmClear by remember(friendId) { mutableStateOf(false) }

  Column(
    modifier
      .fillMaxWidth()
      .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "记忆设置",
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Spacer(Modifier.width(8.dp))
      Text(
        characterName.ifBlank { "未命名角色" },
        Modifier.weight(1f),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        "返回",
        Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable(onClick = onBack)
          .padding(horizontal = 8.dp, vertical = 4.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.iconCurrent
      )
    }
    Text(
      "只影响这个角色的记忆,其他角色各自独立。",
      Modifier.padding(top = 2.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary
    )
    Spacer(Modifier.height(6.dp))

    // 面板里三块内容可能比屏幕高(还可能要露出键盘):限高 + 内部滚动,
    // 保证「保存 / 清空」不会因为内容变长而滑出可视区
    Column(
      Modifier
        .fillMaxWidth()
        .heightIn(max = 340.dp)
        .verticalScroll(rememberScrollState())
    ) {
      PromptSection(
        enabled = promptEnabled,
        text = promptText,
        activeCustom = MemoryPromptStore.isCustom(promptEnabled, savedPrompt),
        notice = promptNotice,
        onEnabledChange = { value ->
          promptEnabled = value
          MemoryPromptStore.setEnabled(context, friendId, value)
          promptNotice = if (value) "已改用自定义提示词" else "已回到内置模板"
        },
        onTextChange = {
          // 超长粘贴在这里就裁住,不然请求的 max_tokens 会被模板自己吃满
          promptText = MemoryPromptStore.cap(it)
          promptNotice = null
        },
        onSave = {
          MemoryPromptStore.setPrompt(context, friendId, promptText)
          savedPrompt = MemoryPromptStore.prompt(context, friendId)
          promptNotice = "已保存,下次总结生效"
        },
        onReset = {
          // 清空写过的文本 = 回到内置模板:以后内置模板升级了也能跟着变
          MemoryPromptStore.setPrompt(context, friendId, "")
          savedPrompt = ""
          promptText = MemoryPromptStore.draft(context, friendId)
          promptNotice = "已恢复内置模板"
        }
      )

      Spacer(Modifier.height(8.dp))
      TargetSliderSection(
        target = target,
        onChange = { target = it },
        onCommit = {
          target = MemoryStore.setTarget(context, friendId, it).targetTokens
          notice = "已保存:之后的微总结按 $target token 封顶"
        }
      )

      if (notice != null) {
        Text(
          notice!!,
          Modifier.padding(start = 4.dp, top = 6.dp),
          fontSize = 11.sp,
          color = AppTheme.colors.iconCurrent
        )
      }

      Spacer(Modifier.height(10.dp))
      Box(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.textFieldBackground)
          .clickable { confirmClear = true }
          .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "清空这个角色的即时记忆",
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.badge
        )
      }
      Text(
        "只清这个角色:总结与错误日志全删(滑块值与当前进度保留),删除后无法恢复。",
        Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        fontSize = 10.sp,
        color = AppTheme.colors.more
      )
    }
  }

  if (confirmClear && friendId.isNotBlank()) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("清空这些记忆?", color = AppTheme.colors.textPrimary, fontSize = 16.sp) },
      text = {
        Text(
          "这个角色的两层记忆(旧归档 + 新记忆库)里的全部总结、错误日志都会删掉," +
            "滑块值与当前进度保留;删除后无法恢复,之后新聊的轮次从头开始攒。",
          fontSize = 13.sp,
          color = AppTheme.colors.textSecondary
        )
      },
      confirmButton = {
        Text(
          "清空",
          Modifier
            .clickable {
              confirmClear = false
              val rounds = viewModel.roundsOf(friendId).size
              MemoryStore.clear(context, friendId, rounds)
              // 新库也要一起清:它是唯一写入方,不清就会「旧库清空了、报文里还在注入」
              MemoryRepository.clearAsync(context, friendId)
              notice = "已清空,之后新聊的轮次从头开始攒"
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
            .clickable { confirmClear = false }
            .padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }
}

/**
 * 总结记忆提示词的开关与编辑框(这个角色一份)。
 *
 * 开关打开后用这里写的文本当总结请求的 system(见 [MemoryPromptStore.template]);
 * 关着(或文本为空)时一律用内置模板 —— 这样以后内置模板升级,用着内置的人能跟着变。
 * `{target}` / `{min}` 在发请求前换成长度上限与固定下限(250 token)。
 */
@Composable
private fun PromptSection(
  enabled: Boolean,
  text: String,
  activeCustom: Boolean,
  notice: String?,
  onEnabledChange: (Boolean) -> Unit,
  onTextChange: (String) -> Unit,
  onSave: () -> Unit,
  onReset: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 14.dp, vertical = 10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("自定义总结提示词", fontSize = 15.sp, color = AppTheme.colors.textPrimary)
        Text(
          if (activeCustom) "当前:自定义" else "当前:内置模板",
          fontSize = 11.sp,
          color = AppTheme.colors.textSecondary
        )
        // 诚实标注:新流水线(memory.db)的总结格式是固定的(正文 + 评分 + 状态三段),
        // 换模板会把解析需要的标签弄丢,所以自定义模板只作用于旧版归档总结。
        Text(
          "只作用于旧版归档总结;新记忆库的总结固定用内置模板。",
          Modifier.padding(top = 2.dp),
          fontSize = 10.sp,
          color = AppTheme.colors.more
        )
      }
      Switch(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        colors = SwitchDefaults.colors(
          checkedThumbColor = AppTheme.colors.onFab,
          checkedTrackColor = AppTheme.colors.iconCurrent,
          uncheckedThumbColor = AppTheme.colors.more,
          uncheckedTrackColor = AppTheme.colors.textFieldBackground
        )
      )
    }

    // 状态提示放在分支之前:关掉开关时也要能看到「已回到内置模板」
    if (notice != null) {
      Text(
        notice,
        Modifier.padding(top = 4.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.iconCurrent
      )
    }

    if (!enabled) {
      Text(
        "打开后可以把总结请求的 system 换成自己写的一段(比如要求按时间线写、保留情绪变化)。",
        Modifier.padding(top = 6.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
      return@Column
    }

    if (text.isBlank()) {
      Text(
        "还没写内容,现在用的是内置模板;写一段再点保存。",
        Modifier.padding(top = 6.dp),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(6.dp))
    // 限高 + 内部滚动:粘长文时「保存」按钮不会被顶出可视区
    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 96.dp, max = 160.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(AppTheme.colors.textFieldBackground)
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
      BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
          .fillMaxWidth()
          .fillMaxHeight(),
        textStyle = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = AppTheme.colors.textPrimary
        ),
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent)
      )
    }
    Text(
      "占位符:" +
        "${MemorySummarizer.TARGET_SLOT} 长度上限、${MemorySummarizer.MIN_SLOT} 长度下限(固定 ${ImmediateMemoryState.TARGET_MIN})。" +
        "写成空文本会退回内置模板。",
      Modifier.padding(top = 4.dp),
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary
    )
    // 字数与上限:粘长文时能一眼看出是不是已经贴到上限了
    Text(
      "已用 ${text.length} / ${MemoryPromptStore.MAX_LENGTH} 字符",
      Modifier
        .fillMaxWidth()
        .padding(top = 2.dp),
      fontSize = 10.sp,
      color = if (text.length >= MemoryPromptStore.MAX_LENGTH) AppTheme.colors.badge
      else AppTheme.colors.textSecondary,
      textAlign = TextAlign.End
    )
    Spacer(Modifier.height(6.dp))
    Row {
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.textFieldBackground)
          .clickable(onClick = onReset)
          .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("恢复默认", fontSize = 12.sp, color = AppTheme.colors.textPrimary)
      }
      Spacer(Modifier.width(8.dp))
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.iconCurrent)
          .clickable(onClick = onSave)
          .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          "保存",
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.listItem
        )
      }
    }
  }
}

/** 长度上限滑块:250~600 token,每 25 token 一档,松手即落盘(只写这个角色那一份)。 */
@Composable
private fun TargetSliderSection(
  target: Int,
  onChange: (Int) -> Unit,
  onCommit: (Int) -> Unit,
) {
  // 松手要提交的是滑块最后给出的那一档:最后一次 onValueChange 引发的重组还没跑完时,
  // 外面的 target 可能仍是上一档,直接提交 target 会让滑块视觉上回跳一格。
  // 以 target 为 key:落盘后 store 可能把值夹回合法区间,这里跟着它对齐。
  var latest by remember(target) { mutableStateOf(target) }
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 14.dp, vertical = 8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("总结长度上限", Modifier.weight(1f), fontSize = 15.sp, color = AppTheme.colors.textPrimary)
      Text(
        "最大 $target token",
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.iconCurrent
      )
    }
    val steps = (ImmediateMemoryState.TARGET_MAX - ImmediateMemoryState.TARGET_MIN) /
      ImmediateMemoryState.TARGET_STEP - 1
    Slider(
      value = target.toFloat(),
      onValueChange = {
        latest = it.roundToInt()
        onChange(latest)
      },
      onValueChangeFinished = { onCommit(latest) },
      valueRange = ImmediateMemoryState.TARGET_MIN.toFloat()..ImmediateMemoryState.TARGET_MAX.toFloat(),
      steps = steps,
      colors = SliderDefaults.colors(
        thumbColor = AppTheme.colors.iconCurrent,
        activeTrackColor = AppTheme.colors.iconCurrent,
        inactiveTrackColor = AppTheme.colors.textFieldBackground
      )
    )
    Row {
      Text(
        "${ImmediateMemoryState.TARGET_MIN}",
        Modifier.weight(1f),
        fontSize = 10.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(
        "${ImmediateMemoryState.TARGET_MAX}",
        fontSize = 10.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Text(
      "每 ${ImmediateMemoryState.SUMMARY_EVERY} 轮自动总结一次;实际长度在 ${ImmediateMemoryState.TARGET_MIN} token 到该值之间按内容浮动。",
      Modifier.padding(top = 4.dp),
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary
    )
    Text(
      "只对这个角色生效(其他角色各自独立)。",
      Modifier.padding(top = 2.dp),
      fontSize = 10.sp,
      color = AppTheme.colors.more
    )
  }
}
