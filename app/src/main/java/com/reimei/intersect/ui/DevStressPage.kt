package com.reimei.intersect.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.MainViewModel
import com.reimei.intersect.data.memory.MemoryPipeline
import com.reimei.intersect.data.memory.MemoryRepository
import com.reimei.intersect.data.memory.NodeCleanPlan
import com.reimei.intersect.data.memory.RollingMetaCodec
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * 记忆压测面板(TASK-04):给非技术测试员用的三个「上帝按钮」。
 *
 * 三件事都作用在**主聊天的真实数据**上(chat_store 的会话 + memory.db),不是控制台自己那份 ——
 * 只有这样才能测到用户真正会遇到的报文与总结行为。因此注入与触发都交给
 * [MainViewModel] 做(它持有内存里活着的会话列表;绕过它直接改盘上的 JSON,
 * 会在下一次落盘时被整体覆盖回去)。
 *
 * 1. **一键注入假数据** —— 纯本地追加 N 轮「测试语句 / 测试回复」,瞬间把轮数拉满;
 * 2. **强制触发记忆总结** —— 无视 `rounds % 20 == 0`,立刻跑一次真实的网络总结;
 * 3. **当前记忆状态看板** —— 纯文本摊开 rolling_meta / 大总结 / 最近 3 条微总结,肉眼看数据对不对;
 * 4. **清洗脏大总结** —— 把已经落库、正文里混着英文思考过程的大总结就地重洗(洗不出来就删)。
 *
 * 注入只写会话表,不往 memory.db 塞假总结:直接塞会推高微总结的水位,
 * 让之后真实的总结被误判成「这段已经总结过」而整段吞掉。假数据要进记忆库,
 * 就接着点第二个按钮,让流水线按真实数据流走一遍。
 */
@Composable
fun DevStressPanel(
  modifier: Modifier = Modifier,
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  BackHandler { onBack() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboardManager.current
  val repository = remember { MemoryRepository.get(context) }

  val chats = viewModel.chats
  var friendId by remember { mutableStateOf(chats.firstOrNull()?.friend?.id) }
  // 会话可能被删 / 换过:目标失效时退回到第一个,避免面板停在一个不存在的会话上
  LaunchedEffect(chats.size) {
    if (chats.none { it.friend.id == friendId }) friendId = chats.firstOrNull()?.friend?.id
  }

  /** 看板文本;null 表示还没打开。 */
  var dashboard by remember { mutableStateOf<String?>(null) }
  var injectOpen by remember { mutableStateOf(false) }
  /** 重读信号:注入 / 总结完成后 +1,页面上的数字跟着刷新。 */
  var tick by remember { mutableStateOf(0) }
  var rounds by remember { mutableStateOf(0) }
  var microCount by remember { mutableStateOf(0) }
  var nodeLabel by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }
  /** 非 null 表示清洗预览弹窗开着:里面是「按下去到底会怎么改」的计划。 */
  var cleanPlan by remember { mutableStateOf<NodeCleanPlan?>(null) }

  fun toast(text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
  }

  val targetName = chats.firstOrNull { it.friend.id == friendId }?.friend?.name.orEmpty()

  // 轮数 / 库里的条数 / 流水线状态:换角色、注入完、总结完都重读一次
  LaunchedEffect(friendId, tick, viewModel.memoryVersion) {
    val id = friendId ?: return@LaunchedEffect
    rounds = viewModel.roundsOf(id).size
    microCount = runCatching { repository.microCount(id) }.getOrDefault(0)
    nodeLabel = runCatching { repository.latestNode(id) }.getOrNull()?.roundLabel()
    running = MemoryPipeline.isRunning(id)
  }

  /** 第二个按钮:强制跑一次总结(不看轮数整除 20)。 */
  fun forceSummary() {
    val id = friendId ?: return
    val launched = viewModel.forceSummary(context, id) { ok ->
      running = false
      tick++
      toast(if (ok) "总结已写入记忆库" else "这次没有写入:可能这段已总结过,或请求失败")
    }
    if (launched) {
      running = true
      tick++
      toast("已强制触发总结")
      return
    }
    toast(
      when {
        MemoryPipeline.isRunning(id) -> "这个角色已经有一次总结在跑,稍等"
        viewModel.apiKeyForFriend(id) == null -> "没有可用的 API KEY"
        viewModel.roundsOf(id).isEmpty() -> "会话里还没有可以总结的轮次"
        else -> "强制触发失败"
      }
    )
  }

  /** 第三个按钮:把当前记忆库状态摊成一段纯文本。 */
  fun openDashboard() {
    val id = friendId ?: return
    scope.launch {
      val meta = runCatching { repository.rollingMeta(id) }.getOrNull()
      val node = runCatching { repository.latestNode(id) }.getOrNull()
      // 最近 3 条,新的在前(与请求体快照同一批数据)
      val micros = runCatching { repository.recentMicro(id, MemoryRepository.SNAPSHOT_MICRO_COUNT) }
        .getOrDefault(emptyList())
      dashboard = dashboardText(
        title = "$targetName ($id)",
        rounds = viewModel.roundsOf(id).size,
        meta = meta,
        node = node,
        micros = micros,
      )
    }
  }

  /** 第四个按钮:扫出正文里混进英文思考过程的大总结。只读,先让测试员看一眼要改什么。 */
  fun scanDirtyNodes() {
    val id = friendId ?: return
    scope.launch {
      val plan = runCatching { repository.scanDirtyNodes(id) }.getOrNull()
      if (plan == null) {
        toast("扫描失败:读不到 memory.db")
        return@launch
      }
      if (plan.isEmpty) {
        toast("扫描了 ${plan.scanned} 条大总结,没有英文思考过程残留")
        return@launch
      }
      cleanPlan = plan
    }
  }

  /** 预览里点「执行清洗」才真的落库:能洗的改写正文,洗不出中文主线的整行删掉。 */
  fun applyClean(plan: NodeCleanPlan) {
    cleanPlan = null
    scope.launch {
      val ok = runCatching { repository.applyNodeClean(plan) }.isSuccess
      if (!ok) {
        toast("清洗失败:写库出错")
        return@launch
      }
      // 记忆库页按 memoryVersion 重读:不通知的话它还会显示清洗前的那条脏大总结
      viewModel.notifyMemoryChanged()
      tick++
      toast("已重写 ${plan.rewrites.size} 条,删除 ${plan.deletes.size} 条")
    }
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "记忆压测",
      actionText = "",
      actionEnabled = false,
      onBack = onBack,
      onAction = {}
    )
    Column(
      Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
      SectionTitle("压测目标")
      if (chats.isEmpty()) {
        Note("还没有任何会话,先去主界面和角色聊两句,或者添加一个角色。")
      } else {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
          chats.forEach { chat ->
            TargetChip(
              name = chat.friend.name,
              selected = chat.friend.id == friendId,
              onClick = { friendId = chat.friend.id }
            )
            Spacer(Modifier.width(8.dp))
          }
        }
        Spacer(Modifier.height(10.dp))
        // 一行概览:注入多少、库里有几条 —— 三个按钮的效果都在这行体现
        Text(
          "当前 $rounds 轮 · 微总结 $microCount 条 · 大总结 ${nodeLabel ?: "还没有"}" +
            if (running) " · 总结进行中" else "",
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary
        )
      }

      Spacer(Modifier.height(16.dp))
      SectionTitle("上帝按钮")
      Column(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        StressRow(
          title = "一键注入假数据",
          detail = "纯本地追加 N 轮测试对话,不发网络请求,瞬间拉满轮数",
          enabled = friendId != null
        ) { injectOpen = true }
        RowDivider()
        StressRow(
          title = "强制触发记忆总结",
          detail = "无视 20 轮整除限制,立刻跑一次真实的网络总结并写入 memory.db",
          enabled = friendId != null && !running
        ) { forceSummary() }
        RowDivider()
        StressRow(
          title = "当前记忆状态看板",
          detail = "纯文本查看 rolling_meta / 最新大总结 / 最近 3 条微总结",
          enabled = friendId != null
        ) { openDashboard() }
        RowDivider()
        StressRow(
          title = "清洗脏大总结",
          detail = "扫出正文里混着英文思考过程的大总结:能洗的就地重写,洗不出中文主线的直接删除",
          enabled = friendId != null
        ) { scanDirtyNodes() }
      }

      Spacer(Modifier.height(12.dp))
      Note(
        "注入只往会话表(chat_store)里写假对话,不会往 memory.db 塞假总结 —— " +
          "直接塞会推高水位,把之后真实的总结整段吞掉。假数据要进记忆库,就接着点「强制触发记忆总结」。"
      )
      Spacer(Modifier.height(20.dp))
    }
  }

  if (injectOpen) {
    var input by remember { mutableStateOf("40") }
    AlertDialog(
      onDismissRequest = { injectOpen = false },
      title = { Text("一键注入假数据", fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
      text = {
        Column {
          Text(
            "向「$targetName」追加 N 轮对话:用户「测试语句1..N」/ 角色「测试回复1..N」。",
            fontSize = 13.sp,
            color = AppTheme.colors.textSecondary
          )
          Spacer(Modifier.height(10.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("轮数", fontSize = 14.sp, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.width(12.dp))
            Box(
              Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.background)
                .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
              BasicTextField(
                value = input,
                // 只收数字:测试员乱填字母也不会让按钮变成「点了没反应」
                onValueChange = { text -> input = text.filter { it.isDigit() }.take(3) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = AppTheme.colors.textPrimary),
                cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
              )
            }
          }
          Spacer(Modifier.height(8.dp))
          Text(
            "一次最多 200 轮(400 条消息)。",
            fontSize = 11.sp,
            color = AppTheme.colors.textSecondary
          )
        }
      },
      confirmButton = {
        Text(
          "注入",
          Modifier
            .clickable {
              val count = input.toIntOrNull() ?: 0
              val id = friendId
              injectOpen = false
              if (id == null) {
                toast("找不到目标会话")
                return@clickable
              }
              val injected = viewModel.injectFakeRounds(context, id, count)
              if (injected > 0) {
                tick++
                toast("已注入 $injected 轮假对话")
              } else {
                toast("注入失败:找不到这个会话")
              }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.iconCurrent
        )
      },
      dismissButton = {
        Text(
          "取消",
          Modifier
            .clickable { injectOpen = false }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }

  val text = dashboard
  if (text != null) {
    AlertDialog(
      onDismissRequest = { dashboard = null },
      title = { Text("当前记忆状态", fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
      text = {
        // 等宽 + 可滚动:总结正文可能很长,一屏放不下
        Box(
          Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
        ) {
          Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = AppTheme.colors.textSecondary
          )
        }
      },
      confirmButton = {
        Text(
          "好",
          Modifier
            .clickable { dashboard = null }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.iconCurrent
        )
      },
      dismissButton = {
        Text(
          "复制",
          Modifier
            .clickable {
              clipboard.setText(AnnotatedString(text))
              toast("已复制到剪贴板")
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }

  val plan = cleanPlan
  if (plan != null) {
    AlertDialog(
      onDismissRequest = { cleanPlan = null },
      title = { Text("清洗脏大总结", fontSize = 16.sp, color = AppTheme.colors.textPrimary) },
      text = {
        Column(
          Modifier
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
        ) {
          Text(
            "共扫描 ${plan.scanned} 条,要动 ${plan.changed} 条:" +
              "重写 ${plan.rewrites.size} 条,删除 ${plan.deletes.size} 条。",
            fontSize = 13.sp,
            color = AppTheme.colors.textPrimary
          )
          Spacer(Modifier.height(10.dp))
          // 逐条摊开「原文 → 改后」:测试员按下确认之前就能看出这条会不会被误伤
          plan.changes.forEach { item ->
            Text(
              "${item.row.roundLabel()} · ${item.row.timeLabel()}",
              fontSize = 12.sp,
              color = AppTheme.colors.textSecondary
            )
            Text(
              "原文:${item.row.text.take(60)}",
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              color = AppTheme.colors.more
            )
            Text(
              if (item.removed) "→ 删除(洗不出中文主线)" else "→ 改后:${item.after.take(60)}",
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              color = if (item.removed) AppTheme.colors.more else AppTheme.colors.iconCurrent
            )
            Spacer(Modifier.height(10.dp))
          }
          Text(
            "删除的行会留一条黑匣子日志;删掉之后这一轮的「最新大总结」退回到上一条," +
              "之后流水线会重新压一段干净的出来。",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = AppTheme.colors.textSecondary
          )
        }
      },
      confirmButton = {
        Text(
          "执行清洗",
          Modifier
            .clickable { applyClean(plan) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.iconCurrent
        )
      },
      dismissButton = {
        Text(
          "取消",
          Modifier
            .clickable { cleanPlan = null }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }
}

/**
 * 看板正文:三段纯文本,顺序与请求体状态快照一致(状态 → 大总结 → 微总结)。
 *
 * 明确写出「还没有」而不是留空:测试员据这一行就能判断是「没跑到」还是「跑了但没写进去」。
 */
private fun dashboardText(
  title: String,
  rounds: Int,
  meta: com.reimei.intersect.data.memory.RollingMetaEntity?,
  node: com.reimei.intersect.data.memory.NodeSummaryEntity?,
  micros: List<com.reimei.intersect.data.memory.MicroSummaryEntity>,
): String = buildString {
  appendLine("=== 目标 ===")
  appendLine(title)
  appendLine("当前轮次: $rounds")
  appendLine()
  appendLine("=== rolling_meta(滚动状态)===")
  if (meta == null) {
    appendLine("还没有落库:第一次总结写入之后才会有这一行")
  } else {
    // 走与请求体同一套解码:看板里显示的就是报文里会用的那份状态
    val info = RollingMetaCodec.toMetaInfo(meta)
    appendLine("好感: ${info.favorability}   健康: ${info.health}   饥饿: ${info.hunger}")
    appendLine("时间: ${info.time.ifEmpty { "(未登记)" }}")
    appendLine("地点: ${info.location.ifEmpty { "(未登记)" }}")
    appendLine("关系: ${info.relationship.ifEmpty { "(未登记)" }}")
    appendLine("在场: ${info.characters.ifEmpty { "(未登记)" }}")
    appendLine("天气: ${info.weather.ifEmpty { "(未登记)" }}")
    appendLine("Buff: ${if (info.buffs.isEmpty()) "(无)" else info.buffs.joinToString("、")}")
    appendLine("更新时间: ${com.reimei.intersect.data.TimeManager.formatMinute(meta.updatedAt)}")
  }
  appendLine()
  appendLine("=== node_summary(最新大总结)===")
  if (node == null) {
    appendLine("还没有:攒够 3 条连续微总结才会压一条")
  } else {
    appendLine("${node.roundLabel()} · ${node.timeLabel()}")
    appendLine(node.text)
  }
  appendLine()
  appendLine("=== micro_summary(最近 ${micros.size} 条,新的在前)===")
  if (micros.isEmpty()) {
    appendLine("还没有:每 20 轮自动生成一条(也可以点「强制触发」立刻生成)")
  } else {
    micros.forEachIndexed { index, micro ->
      appendLine("[${index + 1}] ${micro.roundLabel()} · 评分 ${micro.score} · ${micro.timeLabel()}")
      appendLine(micro.text)
      if (index != micros.lastIndex) appendLine()
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text,
    Modifier.padding(start = 4.dp, bottom = 8.dp),
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    color = AppTheme.colors.textSecondary
  )
}

@Composable
private fun Note(text: String) {
  Text(
    text,
    Modifier.padding(horizontal = 4.dp),
    fontSize = 11.sp,
    lineHeight = 16.sp,
    color = AppTheme.colors.textSecondary
  )
}

@Composable
private fun TargetChip(name: String, selected: Boolean, onClick: () -> Unit) {
  Text(
    name,
    Modifier
      .clip(RoundedCornerShape(16.dp))
      .background(if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.listItem)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 6.dp),
    fontSize = 13.sp,
    color = if (selected) AppTheme.colors.onFab else AppTheme.colors.textPrimary
  )
}

@Composable
private fun StressRow(
  title: String,
  detail: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(
      title,
      fontSize = 15.sp,
      color = if (enabled) AppTheme.colors.textPrimary else AppTheme.colors.more
    )
    Spacer(Modifier.height(2.dp))
    Text(
      detail,
      fontSize = 11.sp,
      lineHeight = 15.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}

@Composable
private fun RowDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}
