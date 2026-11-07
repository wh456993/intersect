package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reimei.intersect.R
import com.reimei.intersect.data.ConfigUsage
import com.reimei.intersect.data.ModelPrice
import com.reimei.intersect.data.PriceConfig
import com.reimei.intersect.data.PriceConfigStore
import com.reimei.intersect.data.Pricing
import com.reimei.intersect.data.TokenSplit
import com.reimei.intersect.data.UsageBucket
import com.reimei.intersect.data.UsageBuckets
import com.reimei.intersect.data.UsageDimension
import com.reimei.intersect.data.UsageExport
import com.reimei.intersect.data.UsageExportFiles
import com.reimei.intersect.data.UsageFilter
import com.reimei.intersect.data.UsageGroup
import com.reimei.intersect.data.UsagePeriod
import com.reimei.intersect.data.UsageQueries
import com.reimei.intersect.data.UsageRange
import com.reimei.intersect.data.UsageSnapshot
import com.reimei.intersect.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「Token 统计」页。
 *
 * 数据全部来自本地两张表(见 [com.reimei.intersect.data.UsageDb]),没有后端接口:
 * 顶部的时间范围 / 维度筛选一变,就把所有查询按同一个条件重跑一遍(SQL 里的 GROUP BY),
 * 然后整页刷新——等价于 Web 版「改筛选 → 重新执行 SQL → setOption」。
 *
 * 三种时间粒度(每日 / 每周 / 累计)只影响图表的横轴,换粒度不重新查库:
 * 按天的聚合结果一次查回来,分桶是纯内存计算(见 [UsageBuckets])。
 *
 * [keyGroupName] / [windowName] 把库里的 id 翻成给人看的名字(KEY 名称、角色名);
 * 找不到时(那条 KEY 已被删除)原样显示 id,不做猜测。
 */
@Composable
fun UsagePage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  keyGroupName: (String) -> String = { it },
  windowName: (String) -> String = { it }
) {
  BackHandler { onBack() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var period by remember { mutableStateOf(UsagePeriod.Total) }
  var range by remember { mutableStateOf(UsageRange.All) }
  var dimension by remember { mutableStateOf(UsageDimension.All) }
  var value by remember { mutableStateOf<String?>(null) }
  var metric by remember { mutableStateOf(TrendMetric.Tokens) }
  var selected by remember { mutableStateOf<Int?>(null) }
  var configExpanded by remember { mutableStateOf(false) }
  var sheet by remember { mutableStateOf<UsageSheet?>(null) }
  var snapshot by remember { mutableStateOf<UsageSnapshot?>(null) }
  var options by remember { mutableStateOf<List<String>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var priceConfig by remember { mutableStateOf(PriceConfigStore.load(context)) }
  var notice by remember { mutableStateOf<String?>(null) }
  // 导出状态:进行中 / 完成后的结果(路径或失败原因)
  var exporting by remember { mutableStateOf(false) }
  var exportOutcome by remember { mutableStateOf<ExportOutcome?>(null) }
  // 价格改过 / 重算过之后 +1,触发重新查询
  var refresh by remember { mutableStateOf(0) }

  val filter = remember(dimension, value, range) { UsageFilter(dimension, value, range) }

  LaunchedEffect(filter, refresh) {
    loading = true
    val (loaded, loadedOptions) = withContext(Dispatchers.IO) {
      UsageQueries.load(context, filter) to UsageQueries.options(context, dimension, range)
    }
    snapshot = loaded
    options = loadedOptions
    loading = false
  }

  // 换粒度 / 换范围后之前选中的那一桶已经不存在了
  LaunchedEffect(period, range, dimension, value) { selected = null }

  val current = snapshot
  val trend = current?.trend ?: emptyList()
  // 周期总览的折线跟着粒度走(每日 / 每周 / 累计曲线)
  val buckets = remember(trend, period) { UsageBuckets.of(trend, period) }
  // 趋势分析的柱状图在「累计」下仍按天分桶:累计曲线本身单调递增,拿来做"趋势"看不出任何信息
  val barBuckets = remember(buckets, trend, period) {
    if (period == UsagePeriod.Total) UsageBuckets.of(trend, UsagePeriod.Day) else buckets
  }
  val activeDays = current?.activeDayIndexes ?: emptyList()
  val activeSeries = remember(buckets, activeDays) { UsageBuckets.activeSeries(buckets, activeDays) }
  val peak = remember(trend, period) { UsageBuckets.peak(trend, period) }

  fun labelOf(dim: UsageDimension, key: String): String = when (dim) {
    UsageDimension.KeyGroup -> keyGroupName(key)
    UsageDimension.Window -> windowName(key)
    else -> key
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    UsageHeader(
      title = "Token 使用统计",
      onBack = onBack,
      exporting = exporting,
      exportEnabled = current != null && !current.isEmpty,
      onExport = {
        val target = current ?: return@UsageHeader
        if (exporting) return@UsageHeader
        exporting = true
        scope.launch {
          exportOutcome = runCatching {
            withContext(Dispatchers.IO) {
              val json = UsageExport.build(
                snapshot = target,
                period = period,
                priceConfig = priceConfig,
                appVersion = UsageExportFiles.appVersion(context),
                keyGroupLabel = keyGroupName,
                windowLabel = windowName
              )
              UsageExportFiles.save(context, json)
            }
          }.let { result ->
            result.fold(
              onSuccess = { file -> ExportOutcome(path = file.absolutePath, bytes = file.length()) },
              onFailure = { error -> ExportOutcome(path = null, error = error.message ?: "保存失败") }
            )
          }
          exporting = false
        }
      }
    )

    LazyColumn(
      Modifier
        .fillMaxSize()
        .navigationBarsPadding(),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    ) {
      item(key = "period") {
        PeriodRow(
          period = period,
          rangeLabel = range.label,
          onPeriod = { period = it },
          onRangeClick = { sheet = UsageSheet.Range }
        )
      }

      if (current == null || (current.isEmpty && !loading)) {
        item(key = "empty") {
          EmptyHint(
            loading = loading,
            filtered = filter.active || range.active,
            onReset = {
              dimension = UsageDimension.All
              value = null
              range = UsageRange.All
            }
          )
        }
      }

      if (current != null) {
        item(key = "overview") {
          OverviewCard(
            snapshot = current,
            buckets = buckets,
            peak = peak,
            period = period,
            priceConfig = priceConfig
          )
        }
        item(key = "stats") {
          MetricGrid(
            peak = peak,
            requests = current.requests,
            cacheRate = current.split.cacheRate,
            output = current.split.output
          )
        }
        item(key = "active") {
          ActiveCard(
            series = activeSeries,
            currentStreak = current.streak.current,
            longestStreak = current.streak.longest
          )
        }
        item(key = "composition") {
          CompositionCard(current.split)
        }
        item(key = "models") {
          ModelRankCard(current.byModel, priceConfig)
        }
        item(key = "scope") {
          ScopeCard(
            label = scopeLabel(dimension, value) { dim, key -> labelOf(dim, key) },
            onClick = { sheet = UsageSheet.Scope }
          )
        }
        item(key = "trend") {
          TrendCard(
            buckets = barBuckets,
            period = period,
            metric = metric,
            onMetric = { metric = it },
            selected = selected,
            onSelect = { selected = it },
            priceConfig = priceConfig
          )
        }
        item(key = "configs") {
          ConfigDetailCard(
            configs = current.configs,
            nameOf = keyGroupName,
            expanded = configExpanded,
            onToggle = { configExpanded = !configExpanded },
            priceConfig = priceConfig
          )
        }
        item(key = "settings") {
          StatSettingsCard(
            priceConfig = priceConfig,
            notice = notice,
            onToggleCurrency = {
              val next = priceConfig.copy(
                displayCurrency = if (priceConfig.displayCurrency == Pricing.CURRENCY_USD) {
                  Pricing.CURRENCY_CNY
                } else {
                  Pricing.CURRENCY_USD
                }
              )
              PriceConfigStore.save(context, next)
              priceConfig = next
            },
            onOpenPrice = { sheet = UsageSheet.Price }
          )
        }
      }
    }
  }

  when (sheet) {
    UsageSheet.Range -> RangeSheet(
      current = range,
      onPick = {
        range = it
        sheet = null
      },
      onDismiss = { sheet = null }
    )

    UsageSheet.Scope -> ScopeSheet(
      dimension = dimension,
      value = value,
      options = options,
      labelOf = { labelOf(dimension, it) },
      onPick = { dim, picked ->
        dimension = dim
        value = picked
      },
      onDismiss = { sheet = null }
    )

    UsageSheet.Price -> PriceSheet(
      config = priceConfig,
      models = (
        Pricing.DEFAULT_PRICES.keys +
          (current?.byModel?.map { it.key } ?: emptyList())
        ).filter { it.isNotBlank() }.distinct(),
      onDismiss = { sheet = null },
      onSave = { config, recompute ->
        PriceConfigStore.save(context, config)
        priceConfig = config
        sheet = null
        notice = null
        if (recompute) {
          scope.launch {
            val count = withContext(Dispatchers.IO) { PriceConfigStore.recompute(context) }
            notice = "已按新单价重算 $count 条历史流水"
            refresh++
          }
        } else {
          refresh++
        }
      }
    )

    null -> Unit
  }

  exportOutcome?.let { outcome ->
    ExportResultDialog(outcome = outcome, onDismiss = { exportOutcome = null })
  }
}

/* ------------------------------ 顶部 ------------------------------ */

/**
 * 渐变标题栏。
 *
 * 统计页是「数据看板」,和聊天列表那种纯文字页不是一类东西,所以这里没有复用 [TitleBar]:
 * 用主色调的竖向渐变把标题栏与内容分开,标题与返回键统一用白字。
 *
 * 右侧那个「保存」是导出按钮:把当前筛选下的全部统计写成一份 JSON 存到本机,
 * 给用户的 AI 读(格式是机器优化的,不需要人看懂)。
 */
@Composable
private fun UsageHeader(
  title: String,
  onBack: () -> Unit,
  exporting: Boolean,
  exportEnabled: Boolean,
  onExport: () -> Unit
) {
  val accent = AppTheme.colors.iconCurrent
  val deep = lerp(accent, Color.Black, 0.32f)
  Row(
    Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(accent, deep)))
      .statusBarsPadding()
      .height(52.dp)
      .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painterResource(R.drawable.ui_chevron_left),
      contentDescription = "返回",
      Modifier
        .clickable(onClick = onBack)
        .size(40.dp)
        .padding(8.dp),
      tint = Color.White
    )
    Spacer(Modifier.width(4.dp))
    Text(
      title,
      Modifier.weight(1f),
      fontSize = 17.sp,
      fontWeight = FontWeight.Medium,
      color = Color.White,
      maxLines = 1
    )
    val enabled = exportEnabled && !exporting
    Row(
      Modifier
        .clip(RoundedCornerShape(50))
        .background(Color.White.copy(alpha = if (enabled) 0.20f else 0.08f))
        .clickable(enabled = enabled, onClick = onExport)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        painterResource(R.drawable.ui_download_tray),
        contentDescription = null,
        Modifier.size(13.dp),
        tint = Color.White.copy(alpha = if (enabled) 1f else 0.5f)
      )
      Spacer(Modifier.width(5.dp))
      Text(
        if (exporting) "保存中" else "保存",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = if (enabled) 1f else 0.5f)
      )
    }
    Spacer(Modifier.width(4.dp))
  }
}

/** 一次导出的结果:成功给路径与字节数,失败给原因。 */
private data class ExportOutcome(val path: String?, val bytes: Long = 0L, val error: String? = null)

/**
 * 导出结果弹窗。
 *
 * 路径必须原样展示:导出到哪儿取决于应用有没有拿到全部文件权限,
 * 用户得知道该去哪个目录找,也要能一键复制去别的 App 里用。
 */
@Composable
private fun ExportResultDialog(outcome: ExportOutcome, onDismiss: () -> Unit) {
  val clipboard = LocalClipboardManager.current
  val ok = outcome.path != null
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color(0x66000000))
        .clickable(onClick = onDismiss),
      contentAlignment = Alignment.Center
    ) {
      Column(
        Modifier
          .fillMaxWidth(0.86f)
          .clip(RoundedCornerShape(16.dp))
          .background(AppTheme.colors.listItem)
          .clickable(enabled = false) {}
          .padding(horizontal = 16.dp, vertical = 16.dp)
      ) {
        Text(
          if (ok) "已保存到本地" else "保存失败",
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        if (ok) {
          Spacer(Modifier.height(10.dp))
          Text(
            outcome.path.orEmpty(),
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(AppTheme.colors.background)
              .padding(horizontal = 10.dp, vertical = 9.dp),
            fontSize = 11.sp,
            color = AppTheme.colors.textPrimary
          )
          Spacer(Modifier.height(6.dp))
          Text(
            "${outcome.bytes} 字节",
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary
          )
        } else {
          Text(
            outcome.error.orEmpty(),
            fontSize = 12.sp,
            color = AppTheme.colors.badge
          )
        }
        Spacer(Modifier.height(12.dp))
        Row {
          Box(
            Modifier
              .weight(1f)
              .clip(RoundedCornerShape(10.dp))
              .background(AppTheme.colors.background)
              .clickable(onClick = onDismiss)
              .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("关闭", fontSize = 13.sp, color = AppTheme.colors.textPrimary)
          }
          if (ok) {
            Spacer(Modifier.width(10.dp))
            Box(
              Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(AppTheme.colors.iconCurrent)
                .clickable {
                  clipboard.setText(AnnotatedString(outcome.path.orEmpty()))
                  onDismiss()
                }
                .padding(vertical = 11.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                "复制路径",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.listItem
              )
            }
          }
        }
      }
    }
  }
}

/** 时间粒度分段控件 + 右侧范围选择。 */
@Composable
private fun PeriodRow(
  period: UsagePeriod,
  rangeLabel: String,
  onPeriod: (UsagePeriod) -> Unit,
  onRangeClick: () -> Unit
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    SegmentedControl(
      options = UsagePeriod.entries.map { it.label },
      selectedIndex = UsagePeriod.entries.indexOf(period),
      onSelect = { index -> onPeriod(UsagePeriod.entries[index]) },
      modifier = Modifier.weight(1f)
    )
    Spacer(Modifier.width(10.dp))
    Row(
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onRangeClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(rangeLabel, fontSize = 12.sp, color = AppTheme.colors.textPrimary, maxLines = 1)
      Spacer(Modifier.width(4.dp))
      Icon(
        painterResource(R.drawable.ui_chevron_right),
        contentDescription = null,
        Modifier
          .size(12.dp)
          .rotate(90f),
        tint = AppTheme.colors.more
      )
    }
  }
}

/**
 * 分段控件:一个浅底容器里一排等宽选项,选中项换成卡片底色。
 *
 * 选中项不填充主色:这一页已经有图表和进度条在用主色,控件再用会抢视线。
 */
@Composable
private fun SegmentedControl(
  options: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier
      .clip(RoundedCornerShape(10.dp))
      .background(AppTheme.colors.background)
      .padding(3.dp)
  ) {
    options.forEachIndexed { index, label ->
      val active = index == selectedIndex
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(8.dp))
          .background(if (active) AppTheme.colors.listItem else Color.Transparent)
          .clickable { onSelect(index) }
          .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          label,
          fontSize = 13.sp,
          fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
          color = if (active) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary,
          maxLines = 1
        )
      }
    }
  }
}

/* ------------------------------ 卡片 ------------------------------ */

/** 统一的卡片外壳:白底(暗色下是深灰)+ 细边 + 圆角。 */
@Composable
private fun UsageCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit
) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    modifier
      .fillMaxWidth()
      .padding(top = 10.dp)
      .clip(shape)
      .background(AppTheme.colors.listItem)
      .border(1.dp, AppTheme.colors.chatListDivider, shape)
      .padding(horizontal = 14.dp, vertical = 13.dp),
    content = content
  )
}

@Composable
private fun CardTitle(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      title,
      Modifier.weight(1f),
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary
    )
    trailing()
  }
}

/** 小胶囊:连续天数这类附注信息。 */
@Composable
private fun InfoChip(text: String) {
  Text(
    text,
    Modifier
      .clip(RoundedCornerShape(50))
      .background(AppTheme.colors.background)
      .padding(horizontal = 8.dp, vertical = 3.dp),
    fontSize = 10.sp,
    color = AppTheme.colors.textSecondary,
    maxLines = 1
  )
}

/** 周期总览:大数字 + 总费用 + 走势折线。 */
@Composable
private fun OverviewCard(
  snapshot: UsageSnapshot,
  buckets: List<UsageBucket>,
  peak: Long,
  period: UsagePeriod,
  priceConfig: PriceConfig
) {
  val total = snapshot.split.total
  val axisMax = buckets.maxOfOrNull { it.tokens } ?: 0L
  UsageCard {
    CardTitle("周期总览") {
      InfoChip("当前连续 ${snapshot.streak.current} 天")
      Spacer(Modifier.width(6.dp))
      InfoChip("最长连续 ${snapshot.streak.longest} 天")
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        UsageFmt.tokens(total),
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.textPrimary,
        maxLines = 1
      )
      Spacer(Modifier.width(4.dp))
      Text(
        "Token",
        Modifier.padding(bottom = 5.dp),
        fontSize = 13.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.Bottom) {
      Text("总费用", Modifier.padding(bottom = 3.dp), fontSize = 12.sp, color = AppTheme.colors.textSecondary)
      Spacer(Modifier.width(6.dp))
      Text(
        UsageFmt.money(snapshot.cost, priceConfig),
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
    }
    Spacer(Modifier.height(12.dp))
    UsageLineChart(
      values = buckets.map { it.tokens.toFloat() },
      yLabels = UsageFmt.axis(axisMax),
      accent = AppTheme.colors.iconCurrent
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        buckets.firstOrNull()?.label ?: "—",
        fontSize = 10.sp,
        color = AppTheme.colors.textSecondary
      )
      Text(
        if (buckets.isEmpty()) "" else buckets.last().label,
        fontSize = 10.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
      when (period) {
        UsagePeriod.Day -> "按天统计 · 峰值 ${UsageFmt.tokens(peak)} Token"
        UsagePeriod.Week -> "按周统计 · 峰值 ${UsageFmt.tokens(peak)} Token"
        UsagePeriod.Total -> "累计到每天为止的合计 · 日峰值 ${UsageFmt.tokens(peak)} Token"
      },
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}

/** 四个指标卡:峰值 Token / 总请求次数 / 缓存率 / 输出。 */
@Composable
private fun MetricGrid(peak: Long, requests: Long, cacheRate: Double, output: Long) {
  Row(Modifier.fillMaxWidth()) {
    MetricCard(
      icon = MetricIcon.Trend,
      label = "峰值 Token",
      value = UsageFmt.tokens(peak),
      modifier = Modifier.weight(1f)
    )
    Spacer(Modifier.width(10.dp))
    MetricCard(
      icon = MetricIcon.Bars,
      label = "总请求次数",
      value = requests.toString(),
      modifier = Modifier.weight(1f)
    )
  }
  Spacer(Modifier.height(10.dp))
  Row(Modifier.fillMaxWidth()) {
    MetricCard(
      icon = MetricIcon.Percent,
      label = "缓存率",
      value = UsageFmt.percent(cacheRate),
      modifier = Modifier.weight(1f)
    )
    Spacer(Modifier.width(10.dp))
    MetricCard(
      icon = MetricIcon.Bubble,
      label = "输出",
      value = UsageFmt.tokens(output),
      modifier = Modifier.weight(1f)
    )
  }
}

private enum class MetricIcon { Trend, Bars, Percent, Bubble }

@Composable
private fun MetricCard(
  icon: MetricIcon,
  label: String,
  value: String,
  modifier: Modifier = Modifier
) {
  val shape = RoundedCornerShape(14.dp)
  val accent = AppTheme.colors.iconCurrent
  Row(
    modifier
      .clip(shape)
      .background(AppTheme.colors.listItem)
      .border(1.dp, AppTheme.colors.chatListDivider, shape)
      .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      Modifier
        .size(34.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(accent.copy(alpha = 0.14f)),
      contentAlignment = Alignment.Center
    ) {
      MetricIconView(icon, accent, Modifier.size(18.dp))
    }
    Spacer(Modifier.width(10.dp))
    Column {
      Text(label, fontSize = 10.sp, color = AppTheme.colors.textSecondary, maxLines = 1)
      Spacer(Modifier.height(2.dp))
      Text(
        value,
        fontSize = 19.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary,
        maxLines = 1
      )
    }
  }
}

/**
 * 指标卡上的小图标。
 *
 * 直接用 Canvas 画四个几何图形,不为这几个 18dp 的图标往仓库里塞矢量资源。
 */
@Composable
private fun MetricIconView(kind: MetricIcon, tint: Color, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
    when (kind) {
      MetricIcon.Trend -> {
        val path = Path().apply {
          moveTo(w * 0.12f, h * 0.74f)
          lineTo(w * 0.38f, h * 0.44f)
          lineTo(w * 0.58f, h * 0.62f)
          lineTo(w * 0.88f, h * 0.24f)
        }
        drawPath(path, tint, style = stroke)
      }

      MetricIcon.Bars -> {
        val barWidth = w * 0.2f
        listOf(0.62f to 0, 0.4f to 1, 0.2f to 2).forEach { (top, index) ->
          drawRoundRect(
            color = tint,
            topLeft = Offset(w * (0.1f + index * 0.32f), h * top),
            size = Size(barWidth, h * (0.9f - top)),
            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
          )
        }
      }

      MetricIcon.Percent -> {
        drawCircle(tint, radius = w * 0.38f, center = center, style = stroke)
        drawLine(
          color = tint,
          start = Offset(w * 0.3f, h * 0.7f),
          end = Offset(w * 0.7f, h * 0.3f),
          strokeWidth = 1.6.dp.toPx(),
          cap = StrokeCap.Round
        )
      }

      MetricIcon.Bubble -> {
        drawRoundRect(
          color = tint,
          topLeft = Offset(w * 0.12f, h * 0.18f),
          size = Size(w * 0.76f, h * 0.52f),
          cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
          style = stroke
        )
        drawLine(
          color = tint,
          start = Offset(w * 0.32f, h * 0.86f),
          end = Offset(w * 0.46f, h * 0.7f),
          strokeWidth = 1.6.dp.toPx(),
          cap = StrokeCap.Round
        )
      }
    }
  }
}

/** 活跃记录:累计活跃天数曲线 + 前台采样时长。 */
@Composable
private fun ActiveCard(
  series: List<Int>,
  currentStreak: Int,
  longestStreak: Int
) {
  val maxActive = series.maxOrNull() ?: 0
  UsageCard {
    CardTitle("活跃记录") {
      InfoChip("当前连续 $currentStreak 天")
      Spacer(Modifier.width(6.dp))
      InfoChip("最长连续 $longestStreak 天")
    }
    Spacer(Modifier.height(10.dp))
    UsageLineChart(
      values = series.map { it.toFloat() },
      yLabels = listOf(maxActive.toString(), (maxActive / 2).toString(), "0"),
      accent = AppTheme.colors.iconCurrent
    )
  }
}

/** Token 构成:三条横向进度条(缓存读取 / 未缓存输入 / 输出)。 */
@Composable
private fun CompositionCard(split: TokenSplit) {
  val accent = AppTheme.colors.iconCurrent
  val total = split.total
  UsageCard {
    CardTitle("Token 构成") {
      Text(
        UsageFmt.tokens(total),
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(10.dp))
    CompositionRow("缓存读取", split.cached, total, accent)
    Spacer(Modifier.height(8.dp))
    CompositionRow("未缓存输入", split.uncached, total, accent.copy(alpha = 0.62f))
    Spacer(Modifier.height(8.dp))
    CompositionRow("输出", split.output, total, accent.copy(alpha = 0.34f))
  }
}

@Composable
private fun CompositionRow(label: String, amount: Long, total: Long, color: Color) {
  val ratio = if (total <= 0L) 0f else (amount.toFloat() / total.toFloat()).coerceIn(0f, 1f)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      label,
      Modifier.width(64.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textSecondary,
      maxLines = 1
    )
    Box(
      Modifier
        .weight(1f)
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(AppTheme.colors.background)
    ) {
      Box(
        Modifier
          .fillMaxWidth(ratio)
          .fillMaxHeight()
          .clip(RoundedCornerShape(3.dp))
          .background(color)
      )
    }
    Spacer(Modifier.width(8.dp))
    Text(
      UsageFmt.tokens(amount),
      Modifier.width(50.dp),
      fontSize = 11.sp,
      color = AppTheme.colors.textPrimary,
      textAlign = TextAlign.End,
      maxLines = 1
    )
    Text(
      UsageFmt.percent(amount, total),
      Modifier.width(46.dp),
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary,
      textAlign = TextAlign.End,
      maxLines = 1
    )
  }
}

/** 模型累计:排行 + 占比条 + 费用。 */
@Composable
private fun ModelRankCard(groups: List<UsageGroup>, priceConfig: PriceConfig) {
  val accent = AppTheme.colors.iconCurrent
  val totalTokens = groups.sumOf { it.tokens }.coerceAtLeast(1L)
  UsageCard {
    CardTitle("模型累计") {
      Text("${groups.size} 个模型", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
    }
    if (groups.isEmpty()) {
      Spacer(Modifier.height(8.dp))
      Text("还没有调用记录", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
      return@UsageCard
    }
    Spacer(Modifier.height(10.dp))
    groups.take(MODEL_RANK_LIMIT).forEachIndexed { index, group ->
      if (index > 0) Spacer(Modifier.height(12.dp))
      Row(verticalAlignment = Alignment.Top) {
        Box(
          Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.background),
          contentAlignment = Alignment.Center
        ) {
          Text(
            "${index + 1}",
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary
          )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              group.key.ifEmpty { "(未知模型)" },
              Modifier.weight(1f),
              fontSize = 12.sp,
              color = AppTheme.colors.textPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              "${UsageFmt.tokens(group.tokens)} · ${UsageFmt.percent(group.tokens, totalTokens)}",
              fontSize = 10.sp,
              color = AppTheme.colors.textSecondary,
              maxLines = 1
            )
          }
          Spacer(Modifier.height(5.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AppTheme.colors.background)
            ) {
              Box(
                Modifier
                  .fillMaxWidth((group.tokens.toFloat() / totalTokens).coerceIn(0.02f, 1f))
                  .fillMaxHeight()
                  .clip(RoundedCornerShape(3.dp))
                  .background(if (index == 0) accent else accent.copy(alpha = 0.45f))
              )
            }
            Spacer(Modifier.width(8.dp))
            Text(
              UsageFmt.money(group.cost, priceConfig),
              fontSize = 10.sp,
              color = AppTheme.colors.textSecondary
            )
          }
        }
      }
    }
  }
}

/** 范围分析:一行「维度:取值」,点开选。 */
@Composable
private fun ScopeCard(label: String, onClick: () -> Unit) {
  UsageCard {
    CardTitle("范围分析")
    Spacer(Modifier.height(10.dp))
    val shape = RoundedCornerShape(8.dp)
    Row(
      Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(AppTheme.colors.background)
        .border(1.dp, AppTheme.colors.chatListDivider, shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        label,
        Modifier.weight(1f),
        fontSize = 12.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Icon(
        painterResource(R.drawable.ui_chevron_right),
        contentDescription = null,
        Modifier
          .size(12.dp)
          .rotate(90f),
        tint = AppTheme.colors.more
      )
    }
  }
}

/** 趋势分析:费用 / 请求 / Token 三个口径的柱状图,点柱子看单日数据。 */
@Composable
private fun TrendCard(
  buckets: List<UsageBucket>,
  period: UsagePeriod,
  metric: TrendMetric,
  onMetric: (TrendMetric) -> Unit,
  selected: Int?,
  onSelect: (Int) -> Unit,
  priceConfig: PriceConfig
) {
  val values = buckets.map { it.metricValue(metric).toFloat() }
  val selectedIndex = selected?.coerceIn(0, (buckets.size - 1).coerceAtLeast(0))
  val highlight = selectedIndex ?: buckets.lastIndex
  val total = buckets.sumOf { it.metricValue(metric) }
  val axisMax = values.maxOrNull()?.toDouble() ?: 0.0

  UsageCard {
    CardTitle("趋势分析")
    Spacer(Modifier.height(10.dp))
    SegmentedControl(
      options = TrendMetric.entries.map { it.label },
      selectedIndex = TrendMetric.entries.indexOf(metric),
      onSelect = { onMetric(TrendMetric.entries[it]) }
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        "${metric.label}趋势",
        Modifier.weight(1f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Text(
        formatMetric(total, metric, priceConfig),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
    }
    Spacer(Modifier.height(8.dp))
    if (buckets.isEmpty()) {
      Text("还没有调用记录", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
      return@UsageCard
    }
    UsageBarChart(
      values = values,
      yLabels = if (metric == TrendMetric.Cost) {
        UsageFmt.axis(axisMax, priceConfig)
      } else {
        UsageFmt.axis(axisMax.toLong())
      },
      labels = buckets.map { it.label },
      accent = AppTheme.colors.iconCurrent,
      selectedIndex = selectedIndex,
      onSelect = onSelect
    )
    Spacer(Modifier.height(8.dp))
    if (highlight >= 0 && highlight < buckets.size) {
      val bucket = buckets[highlight]
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
          bucket.label,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = AppTheme.colors.textPrimary
        )
        Spacer(Modifier.weight(1f))
        Text(
          formatMetric(bucket.metricValue(metric), metric, priceConfig),
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Text(
      if (selectedIndex == null) {
        "点击柱子查看单个${if (period == UsagePeriod.Week) "周" else "日"}的数据"
      } else {
        "已选中一${if (period == UsagePeriod.Week) "周" else "天"},再点一次其它柱子可切换"
      },
      fontSize = 10.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}

/** 配置详情:每个密钥组(一条 API KEY)在这段时间的用量,默认收起。 */
@Composable
private fun ConfigDetailCard(
  configs: List<ConfigUsage>,
  nameOf: (String) -> String,
  expanded: Boolean,
  onToggle: () -> Unit,
  priceConfig: PriceConfig
) {
  UsageCard {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onToggle),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "配置详情",
        Modifier.weight(1f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Text("${configs.size} 个配置", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
      Spacer(Modifier.width(6.dp))
      Icon(
        painterResource(R.drawable.ui_chevron_right),
        contentDescription = if (expanded) "收起" else "展开",
        Modifier
          .size(12.dp)
          .rotate(if (expanded) 90f else 0f),
        tint = AppTheme.colors.more
      )
    }
    if (!expanded) return@UsageCard
    if (configs.isEmpty()) {
      Spacer(Modifier.height(10.dp))
      Text("这个范围内没有调用记录", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
      return@UsageCard
    }
    configs.forEach { config ->
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            nameOf(config.key).ifEmpty { config.key },
            fontSize = 12.sp,
            color = AppTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.height(2.dp))
          Text(
            "${config.requests} 次 · ${UsageFmt.tokens(config.tokens)} tokens · 最后 ${shortTime(config.lastUsedMs)}",
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(Modifier.width(8.dp))
        Text(
          UsageFmt.money(config.cost, priceConfig),
          fontSize = 12.sp,
          color = AppTheme.colors.textPrimary
        )
      }
    }
  }
}

/** 统计设置:显示币种、汇率、模型单价。 */
@Composable
private fun StatSettingsCard(
  priceConfig: PriceConfig,
  notice: String?,
  onToggleCurrency: () -> Unit,
  onOpenPrice: () -> Unit
) {
  UsageCard {
    CardTitle("统计设置")
    Spacer(Modifier.height(4.dp))
    SettingsRow(
      title = "总计币种",
      value = priceConfig.displayCurrency,
      onClick = onToggleCurrency
    )
    RowDivider()
    SettingsRow(
      title = "汇率设置",
      value = "1 USD = ${UsageFmt.numberText(priceConfig.usdToCny)} CNY",
      hint = if (priceConfig.usdToCny == Pricing.DEFAULT_USD_TO_CNY) "默认" else "已手动设置",
      onClick = onOpenPrice
    )
    RowDivider()
    SettingsRow(
      title = "模型单价",
      value = "每百万 token",
      onClick = onOpenPrice
    )
    if (notice != null) {
      Spacer(Modifier.height(8.dp))
      Text(notice, fontSize = 10.sp, color = AppTheme.colors.iconCurrent)
    }
  }
}

@Composable
private fun SettingsRow(
  title: String,
  value: String,
  hint: String? = null,
  onClick: () -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(title, Modifier.weight(1f), fontSize = 13.sp, color = AppTheme.colors.textPrimary)
    if (hint != null) {
      Text(hint, fontSize = 10.sp, color = AppTheme.colors.textSecondary)
      Spacer(Modifier.width(6.dp))
    }
    Text(
      value,
      fontSize = 12.sp,
      color = AppTheme.colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.width(6.dp))
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(12.dp),
      tint = AppTheme.colors.more
    )
  }
}

@Composable
private fun RowDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

@Composable
private fun EmptyHint(loading: Boolean, filtered: Boolean, onReset: () -> Unit) {
  UsageCard {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        if (loading) "正在统计..." else "还没有消耗记录",
        fontSize = 14.sp,
        color = AppTheme.colors.textPrimary
      )
      if (filtered) {
        Spacer(Modifier.height(10.dp))
        Text(
          "清除筛选条件",
          Modifier
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.background)
            .clickable(onClick = onReset)
            .padding(horizontal = 12.dp, vertical = 5.dp),
          fontSize = 11.sp,
          color = AppTheme.colors.iconCurrent
        )
      }
    }
  }
}

/* ------------------------------ 选择面板 ------------------------------ */

private enum class UsageSheet { Range, Scope, Price }

private enum class TrendMetric(val label: String) {
  Tokens("Token"),
  Cost("费用"),
  Requests("请求")
}

/** 底部弹出的面板外壳,三个选择面板共用一个壳。 */
@Composable
private fun UsageSheetShell(
  title: String,
  onDismiss: () -> Unit,
  content: @Composable ColumnScope.() -> Unit
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color(0x66000000))
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
          .imePadding()
          .padding(horizontal = 16.dp, vertical = 16.dp)
      ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        content()
      }
    }
  }
}

/** 时间范围:只需要几个常用档位,不做一个完整的日历控件。 */
@Composable
private fun RangeSheet(current: UsageRange, onPick: (UsageRange) -> Unit, onDismiss: () -> Unit) {
  val presets = listOf(
    UsageRange.All,
    UsageRange.lastDays(7),
    UsageRange.lastDays(30),
    UsageRange.lastDays(90)
  )
  UsageSheetShell(title = "统计范围", onDismiss = onDismiss) {
    presets.forEach { preset ->
      val active = preset.presetDays == current.presetDays
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { onPick(preset) }
          .background(if (active) AppTheme.colors.background else Color.Transparent)
          .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          preset.label,
          Modifier.weight(1f),
          fontSize = 14.sp,
          color = if (active) AppTheme.colors.iconCurrent else AppTheme.colors.textPrimary
        )
        if (active) Text("当前", fontSize = 11.sp, color = AppTheme.colors.iconCurrent)
      }
    }
  }
}

/** 范围分析:先选维度(全部 / 密钥组 / 模型 / 聊天窗口),再选该维度下的取值。 */
@Composable
private fun ScopeSheet(
  dimension: UsageDimension,
  value: String?,
  options: List<String>,
  labelOf: (String) -> String,
  onPick: (UsageDimension, String?) -> Unit,
  onDismiss: () -> Unit
) {
  val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.36f).dp
  UsageSheetShell(title = "范围分析", onDismiss = onDismiss) {
    SegmentedControl(
      options = UsageDimension.entries.map { it.label },
      selectedIndex = UsageDimension.entries.indexOf(dimension),
      onSelect = { index -> onPick(UsageDimension.entries[index], null) }
    )
    if (dimension != UsageDimension.All) {
      Spacer(Modifier.height(8.dp))
      LazyColumn(Modifier.heightIn(max = maxListHeight)) {
        item(key = "all") {
          ScopeOption(
            text = "全部${dimension.label}",
            active = value.isNullOrBlank(),
            onClick = { onPick(dimension, null) }
          )
        }
        items(options, key = { it }) { option ->
          ScopeOption(
            text = labelOf(option).ifEmpty { option },
            active = option == value,
            onClick = { onPick(dimension, option) }
          )
        }
      }
      if (options.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
          "这段时间里没有${dimension.label}维度的流水",
          fontSize = 11.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    Box(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(AppTheme.colors.background)
        .clickable(onClick = onDismiss)
        .padding(vertical = 12.dp),
      contentAlignment = Alignment.Center
    ) {
      Text("完成", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary)
    }
  }
}

@Composable
private fun ScopeOption(text: String, active: Boolean, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text,
      Modifier.weight(1f),
      fontSize = 13.sp,
      color = if (active) AppTheme.colors.iconCurrent else AppTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    if (active) Text("当前", fontSize = 11.sp, color = AppTheme.colors.iconCurrent)
  }
}

/**
 * 单价面板:模型单价(输入 / 缓存读取 / 输出,每百万 token)+ 美元汇率。
 *
 * 只允许改数字,数值合法性在保存时统一收敛(空 / 非法当 0)。
 * 「保存时重算历史费用」会把已有流水的 `cost` 按新单价全部刷新。
 */
@Composable
private fun PriceSheet(
  config: PriceConfig,
  models: List<String>,
  onDismiss: () -> Unit,
  onSave: (config: PriceConfig, recompute: Boolean) -> Unit
) {
  // 汇率与每个模型的三项单价先写成文本再解析:输入过程中 "2." 这种中间态要能打出来
  var rateText by remember { mutableStateOf(UsageFmt.numberText(config.usdToCny)) }
  var drafts by remember {
    mutableStateOf(
      models.associateWith { name ->
        val price = config.priceOf(name)
        PriceDraft(
          UsageFmt.numberText(price.input),
          UsageFmt.numberText(price.cacheRead),
          UsageFmt.numberText(price.output)
        )
      }
    )
  }
  var recompute by remember { mutableStateOf(true) }
  val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.40f).dp

  fun draftOf(name: String): PriceDraft = drafts[name] ?: PriceDraft("0", "0", "0")

  UsageSheetShell(title = "单价设置", onDismiss = onDismiss) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("美元汇率", Modifier.width(72.dp), fontSize = 12.sp, color = AppTheme.colors.textPrimary)
      Text("1$ =", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
      Spacer(Modifier.width(6.dp))
      PriceField(
        value = rateText,
        onValueChange = { rateText = it },
        modifier = Modifier.width(70.dp)
      )
      Spacer(Modifier.width(6.dp))
      Text("元", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
    }
    Spacer(Modifier.height(10.dp))
    LazyColumn(Modifier.heightIn(max = maxListHeight)) {
      items(models, key = { it }) { name ->
        val draft = draftOf(name)
        val currency = config.priceOf(name).currency
        Column(Modifier.padding(bottom = 12.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              name,
              Modifier.weight(1f),
              fontSize = 12.sp,
              color = AppTheme.colors.textPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              if (currency == Pricing.CURRENCY_USD) "$/百万" else "¥/百万",
              fontSize = 10.sp,
              color = AppTheme.colors.textSecondary
            )
          }
          Spacer(Modifier.height(4.dp))
          Row {
            LabeledPriceField("输入", draft.input, Modifier.weight(1f)) {
              drafts = drafts + (name to draft.copy(input = it))
            }
            Spacer(Modifier.width(8.dp))
            LabeledPriceField("缓存读", draft.cacheRead, Modifier.weight(1f)) {
              drafts = drafts + (name to draft.copy(cacheRead = it))
            }
            Spacer(Modifier.width(8.dp))
            LabeledPriceField("输出", draft.output, Modifier.weight(1f)) {
              drafts = drafts + (name to draft.copy(output = it))
            }
          }
        }
      }
    }
    Spacer(Modifier.height(6.dp))
    Row(
      Modifier
        .fillMaxWidth()
        .clickable { recompute = !recompute }
        .padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      CheckBox(checked = recompute, onCheckedChange = { recompute = it })
      Spacer(Modifier.width(8.dp))
      Text("保存时重算历史费用", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
    }
    Spacer(Modifier.height(10.dp))
    Row {
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.background)
          .clickable(onClick = onDismiss)
          .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("取消", fontSize = 14.sp, color = AppTheme.colors.textPrimary)
      }
      Spacer(Modifier.width(10.dp))
      Box(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .background(AppTheme.colors.iconCurrent)
          .clickable {
            var next = config.copy(
              usdToCny = UsageFmt.parseNumber(rateText).takeIf { it > 0.0 }
                ?: Pricing.DEFAULT_USD_TO_CNY
            )
            models.forEach { name ->
              val draft = draftOf(name)
              val old = config.priceOf(name)
              next = next.withPrice(
                name,
                ModelPrice(
                  input = UsageFmt.parseNumber(draft.input),
                  cacheRead = UsageFmt.parseNumber(draft.cacheRead),
                  output = UsageFmt.parseNumber(draft.output),
                  currency = old.currency
                )
              )
            }
            onSave(next, recompute)
          }
          .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("保存", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.listItem)
      }
    }
  }
}

private data class PriceDraft(val input: String, val cacheRead: String, val output: String)

@Composable
private fun LabeledPriceField(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  onValueChange: (String) -> Unit
) {
  Column(modifier) {
    Text(label, fontSize = 10.sp, color = AppTheme.colors.textSecondary)
    Spacer(Modifier.height(2.dp))
    PriceField(value = value, onValueChange = onValueChange)
  }
}

@Composable
private fun PriceField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier
      .clip(RoundedCornerShape(6.dp))
      .background(AppTheme.colors.background)
      .padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
    BasicTextField(
      value = value,
      onValueChange = { input ->
        // 只放行数字与一个小数点:单价框里输中文没有意义
        if (input.count { it == '.' } <= 1 && input.all { it.isDigit() || it == '.' }) {
          onValueChange(input)
        }
      },
      modifier = Modifier.fillMaxWidth(),
      textStyle = TextStyle(fontSize = 13.sp, color = AppTheme.colors.textPrimary),
      singleLine = true,
      cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
      keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done
      )
    )
  }
}

/** 自绘勾选框:只需要一个小方框,不引 Material 的 Checkbox(它在暗色下对比度偏低)。 */
@Composable
private fun CheckBox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Box(
    Modifier
      .size(18.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(if (checked) AppTheme.colors.iconCurrent else AppTheme.colors.chatListDivider)
      .clickable { onCheckedChange(!checked) },
    contentAlignment = Alignment.Center
  ) {
    if (checked) Text("✓", fontSize = 11.sp, color = AppTheme.colors.listItem)
  }
}

/* ------------------------------ 小工具 ------------------------------ */

/** 模型排行最多列几个(再长就只是把同一个数字重复一遍)。 */
private const val MODEL_RANK_LIMIT = 8

/** 范围分析那一行显示的文本:「模型:全部模型」这种。 */
private fun scopeLabel(
  dimension: UsageDimension,
  value: String?,
  labelOf: (UsageDimension, String) -> String
): String = when {
  dimension == UsageDimension.All -> "全部范围"
  value.isNullOrBlank() -> "${dimension.label}:全部${dimension.label}"
  else -> "${dimension.label}:${labelOf(dimension, value).ifEmpty { value }}"
}

private fun UsageBucket.metricValue(metric: TrendMetric): Double = when (metric) {
  TrendMetric.Tokens -> tokens.toDouble()
  TrendMetric.Cost -> cost
  TrendMetric.Requests -> requests.toDouble()
}

private fun formatMetric(value: Double, metric: TrendMetric, config: PriceConfig): String =
  when (metric) {
    TrendMetric.Tokens -> UsageFmt.tokens(value.toLong())
    TrendMetric.Cost -> UsageFmt.money(value, config)
    TrendMetric.Requests -> value.toLong().toString()
  }

/** 配置详情里的「最后使用」:今年的省掉年份。 */
private fun shortTime(epochMillis: Long): String {
  if (epochMillis <= 0L) return "—"
  val pattern = "yyyy-MM-dd"
  val text = SimpleDateFormat(pattern, Locale.US).format(Date(epochMillis))
  return text.removePrefix(
    SimpleDateFormat("yyyy-", Locale.US).format(Date(System.currentTimeMillis()))
  )
}
