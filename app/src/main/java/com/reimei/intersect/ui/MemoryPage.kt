package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.memory.MemoryPipeline
import com.reimei.intersect.data.memory.MemoryRepository
import com.reimei.intersect.data.memory.MicroSummaryEntity
import com.reimei.intersect.data.memory.NodeSummaryEntity
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.search.EmbeddingEngine
import com.reimei.intersect.search.HybridSearchEngine
import com.reimei.intersect.search.SearchChatUseCase
import com.reimei.intersect.search.UiSearchHit
import com.reimei.intersect.search.VectorDb
import com.reimei.intersect.search.toAnnotatedString
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 记忆库的三个页签:一个页签对应一个datasource,页面上不放任何配置项。 */
private enum class MemoryLayer(val title: String) {
  Immediate("即时记忆层"),
  Clue("引导线索层"),
  LongTerm("长期记忆层"),
}

/**
 * 记忆库页:角色信息页里「记忆库」那一行点进来的地方。
 *
 * **这一页只展示数据**,不放任何配置项(总结提示词开关、长度滑块在**这个角色自己的聊天页
 * → 附件 → 记忆设置**里,见 [MemorySettingsSheet]),也不放开发者面板 ——
 * 配置与数据混在一条列表里极易误触。
 *
 * 三个页签严格对齐三个数据源:
 * - **即时记忆层** → memory.db 的 `micro_summary` 表:每 [MemoryPipeline.MICRO_EVERY_ROUNDS] 轮
 *   自动生成一条微总结,按时间倒序展示(轮次区间 / 评分 / 正文);
 * - **引导线索层** → memory.db 的 `node_summary` 表:每 [MemoryPipeline.MICRO_PER_NODE] 条微总结
 *   压成一条 10~50 词的主线大总结,卡片只给轮次跨度与正文;
 * - **长期记忆层** → `search/` 那套本地混合检索(精确 + 模糊 + 向量 RRF 融合):一个搜索测试框,
 *   手动输关键字看向量选择器能召回什么。
 *
 * 每个页签都支持下拉刷新:直接重读数据库(总结可能在聊天页后台跑完,不用退出重进)。
 *
 * [version] 由调用方给:主聊天取 ViewModel 的记忆版本号,它一变(自动总结跑完)就重读一次。
 */
@Composable
fun MemoryLibraryPage(
  modifier: Modifier = Modifier,
  friendId: String,
  version: Int = 0,
  /** 顶部一行说明(控制台用它标一句「这份记忆挂在哪个数据源上」);null 不显示。 */
  note: String? = null,
  onBack: () -> Unit,
) {
  BackHandler { onBack() }

  val context = LocalContext.current

  var layer by remember(friendId) { mutableStateOf(MemoryLayer.Immediate) }
  // 新的在前:轮次越靠后越新(同一轮次的再按 id 排)
  var micros by remember(friendId) { mutableStateOf<List<MicroSummaryEntity>>(emptyList()) }
  var nodes by remember(friendId) { mutableStateOf<List<NodeSummaryEntity>>(emptyList()) }
  /** 卡片的展开态:两类卡片 id 来自不同表,key 带前缀防撞号。 */
  var expanded by remember(friendId) { mutableStateOf(setOf<String>()) }
  /** 重读信号:下拉刷新 / 总结跑完(version)/ 换角色都重读一次库。 */
  var reloadTick by remember(friendId) { mutableStateOf(0) }
  /** 下拉刷新的转圈:数据落位后由 LaunchedEffect 收起。 */
  var refreshing by remember(friendId) { mutableStateOf(false) }

  // —— 长期记忆层:搜索测试框 ——
  var query by remember(friendId) { mutableStateOf("") }
  var searchTick by remember(friendId) { mutableStateOf(0) }
  /** null = 还没搜过;空列表 = 搜过但没有召回。 */
  var hits by remember(friendId) { mutableStateOf<List<UiSearchHit>?>(null) }
  var searching by remember(friendId) { mutableStateOf(false) }
  var searchError by remember(friendId) { mutableStateOf<String?>(null) }

  val repository = remember(context) { MemoryRepository.get(context) }
  val searchUseCase = remember(context) {
    SearchChatUseCase(HybridSearchEngine(VectorDb.getInstance(context).vectors()))
  }

  LaunchedEffect(friendId, version, reloadTick) {
    micros = loadOrElse({ repository.allMicro(friendId) }, emptyList())
      .sortedWith(compareByDescending<MicroSummaryEntity> { it.toRound }.thenByDescending { it.id })
    nodes = loadOrElse({ repository.allNode(friendId) }, emptyList())
      .sortedWith(compareByDescending<NodeSummaryEntity> { it.toRound }.thenByDescending { it.id })
    refreshing = false
  }

  // 手动搜索(不在输入时触发):每次点「搜索」把 searchTick +1
  LaunchedEffect(friendId, searchTick) {
    val q = query.trim()
    if (q.isEmpty()) {
      hits = null
      searching = false
      refreshing = false
      return@LaunchedEffect
    }
    searching = true
    searchError = null
    // 向量路要引擎:init 幂等,失败只会让这次检索退化成纯字面召回(红线,不崩)
    try {
      EmbeddingEngine.init(context)
      hits = searchUseCase(q)
    } catch (cancelled: CancellationException) {
      // 又搜了一次:这是旧协程被取消,不是检索失败 —— 不能写错误也不能提前解禁按钮
      throw cancelled
    } catch (t: Throwable) {
      hits = null
      searchError = t.javaClass.simpleName
    }
    searching = false
    refreshing = false
  }

  /** 即时记忆层 / 引导线索层的刷新:只重读数据库。 */
  fun refreshData() {
    refreshing = true
    reloadTick++
  }

  /** 长期记忆层的刷新:顺带重跑一次当前的搜索。 */
  fun refreshLongTerm() {
    refreshing = true
    reloadTick++
    if (query.isNotBlank()) searchTick++
  }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "记忆库",
      actionText = "",
      actionEnabled = false,
      onBack = onBack,
      onAction = {}
    )
    MemoryLayerTabs(current = layer, onPick = { layer = it })
    if (note != null) {
      Text(
        note,
        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        fontSize = 10.sp,
        color = AppTheme.colors.more
      )
    }
    when (layer) {
      MemoryLayer.Immediate -> ImmediateLayer(
        modifier = Modifier.weight(1f),
        micros = micros,
        expanded = expanded,
        refreshing = refreshing,
        onRefresh = { refreshData() },
        onToggle = { id -> expanded = expanded.toggle(id) },
      )

      MemoryLayer.Clue -> ClueLayer(
        modifier = Modifier.weight(1f),
        nodes = nodes,
        expanded = expanded,
        refreshing = refreshing,
        onRefresh = { refreshData() },
        onToggle = { id -> expanded = expanded.toggle(id) },
      )

      MemoryLayer.LongTerm -> LongTermLayer(
        modifier = Modifier.weight(1f),
        query = query,
        onQueryChange = { query = it },
        onSearch = { searchTick++ },
        searchTick = searchTick,
        searching = searching,
        hits = hits,
        error = searchError,
        refreshing = refreshing,
        onRefresh = { refreshLongTerm() },
      )
    }
  }
}

/** 三个页签的切换行:选中项用主题色 + 一条短底线,和设置页的分段选择一致。 */
@Composable
private fun MemoryLayerTabs(current: MemoryLayer, onPick: (MemoryLayer) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    MemoryLayer.entries.forEach { layer ->
      val selected = layer == current
      Column(
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(8.dp))
          .clickable { onPick(layer) }
          .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          layer.title,
          fontSize = 14.sp,
          fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
          color = if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.textSecondary,
          maxLines = 1
        )
        Spacer(Modifier.height(5.dp))
        Box(
          Modifier
            .width(20.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(if (selected) AppTheme.colors.iconCurrent else AppTheme.colors.background)
        )
      }
    }
  }
}

/**
 * 即时记忆层:只有微总结卡片,没有滑块也没有提示词编辑框。
 *
 * 每张卡片三样东西 —— 轮次区间、评分、正文;正文默认折到 4 行,点一下展开。
 */
@Composable
private fun ImmediateLayer(
  modifier: Modifier = Modifier,
  micros: List<MicroSummaryEntity>,
  expanded: Set<String>,
  refreshing: Boolean,
  onRefresh: () -> Unit,
  onToggle: (String) -> Unit,
) {
  RefreshableList(
    modifier = modifier,
    refreshing = refreshing,
    onRefresh = onRefresh,
  ) {
    item {
      LayerCaption(
        "每 ${MemoryPipeline.MICRO_EVERY_ROUNDS} 轮对话自动压一条微总结,共 ${micros.size} 条(新的在前)。"
      )
    }
    if (micros.isEmpty()) {
      item { EmptyHint(title = "还没有即时记忆", detail = emptyImmediateDetail()) }
      return@RefreshableList
    }
    items(micros, key = { microKey(it.id) }) { micro ->
      MicroCard(
        micro = micro,
        expanded = microKey(micro.id) in expanded,
        onClick = { onToggle(microKey(micro.id)) }
      )
      Spacer(Modifier.height(8.dp))
    }
  }
}

/**
 * 引导线索层:每 [MemoryPipeline.MICRO_PER_NODE] 条微总结压出来的主线大总结。
 *
 * 卡片只放两样东西 —— 轮次跨度(如「第 1-60 轮」)与正文。
 */
@Composable
private fun ClueLayer(
  modifier: Modifier = Modifier,
  nodes: List<NodeSummaryEntity>,
  expanded: Set<String>,
  refreshing: Boolean,
  onRefresh: () -> Unit,
  onToggle: (String) -> Unit,
) {
  RefreshableList(
    modifier = modifier,
    refreshing = refreshing,
    onRefresh = onRefresh,
  ) {
    item {
      LayerCaption(
        "每 ${MemoryPipeline.MICRO_PER_NODE} 条微总结压成一条主线大总结(10~50 词),共 ${nodes.size} 条(新的在前)。"
      )
    }
    if (nodes.isEmpty()) {
      item {
        EmptyHint(
          title = "还没有引导线索",
          detail = "攒够 ${MemoryPipeline.MICRO_EVERY_ROUNDS * MemoryPipeline.MICRO_PER_NODE} 轮对话才会压出第一条主线大总结。"
        )
      }
      return@RefreshableList
    }
    items(nodes, key = { nodeKey(it.id) }) { node ->
      ClueCard(
        node = node,
        expanded = nodeKey(node.id) in expanded,
        onClick = { onToggle(nodeKey(node.id)) }
      )
      Spacer(Modifier.height(8.dp))
    }
  }
}

/**
 * 长期记忆层:一个搜索测试框,背后是 `search/` 那套本地混合检索。
 *
 * 召回源是 vectors 表(由 SearchIndexer 在充电 + 息屏时回填:聊天消息记作 chat、
 * 即时记忆总结记作 memory),所以这里能搜到的就是向量选择器真正会拿给模型的那批片段。
 * 向量命中没有字符级对应,不会被高亮;字面命中(精确 / 模糊)会标黄。
 */
@Composable
private fun LongTermLayer(
  modifier: Modifier = Modifier,
  query: String,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  searchTick: Int,
  searching: Boolean,
  hits: List<UiSearchHit>?,
  error: String?,
  refreshing: Boolean,
  onRefresh: () -> Unit,
) {
  RefreshableList(
    modifier = modifier,
    refreshing = refreshing,
    onRefresh = onRefresh,
    resetScrollKey = searchTick,
  ) {
    item {
      SearchBox(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        searching = searching,
      )
    }
    item {
      LayerCaption(
        "检索整个本地索引(全部角色 + 控制台的聊天片段与记忆总结);" +
          "向量路不可用时自动退化成纯字面召回。"
      )
    }
    when {
      error != null -> item { EmptyHint(title = "检索失败", detail = error) }
      searching -> item { EmptyHint(title = "检索中...", detail = "分词 → 向量化 → 三路融合,稍等。") }
      hits == null -> item {
        EmptyHint(title = "输入关键字试试", detail = "比如某个物品名、地点或一句约定,看本地检索能召回哪些片段。")
      }
      hits.isEmpty() -> item {
        EmptyHint(
          title = "没有召回任何内容",
          detail = "索引里的内容要跟关键字对得上;新聊的对话会在下次后台回填后才进索引。"
        )
      }
      else -> {
        item { LayerCaption("召回 ${hits.size} 条(按 RRF 融合分排序)。") }
        items(hits, key = { it.id }) { hit ->
          HitCard(hit = hit)
          Spacer(Modifier.height(8.dp))
        }
      }
    }
  }
}

/** 搜索测试框:输入框 + 「搜索」按钮。只在点按钮时检索,不做逐键触发(每次都可能带一次推理)。 */
@Composable
private fun SearchBox(
  query: String,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  searching: Boolean,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      Modifier
        .weight(1f)
        .clip(RoundedCornerShape(8.dp))
        .background(AppTheme.colors.textFieldBackground)
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
      if (query.isEmpty()) {
        Text("输入关键字", fontSize = 13.sp, color = AppTheme.colors.more)
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = AppTheme.colors.textPrimary),
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() })
      )
    }
    Spacer(Modifier.width(8.dp))
    Box(
      Modifier
        .clip(RoundedCornerShape(10.dp))
        .background(if (searching) AppTheme.colors.textFieldBackground else AppTheme.colors.iconCurrent)
        .clickable(enabled = !searching, onClick = onSearch)
        .padding(horizontal = 16.dp, vertical = 9.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        if (searching) "检索中" else "搜索",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = if (searching) AppTheme.colors.more else AppTheme.colors.listItem
      )
    }
  }
}

/** 一条召回结果:来源标签 + 融合分 + 正文(字面命中高亮)。 */
@Composable
private fun HitCard(hit: UiSearchHit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        sourceLabel(hit.sourceType),
        Modifier.weight(1f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.iconCurrent
      )
      Text(
        "融合分 " + String.format(Locale.US, "%.4f", hit.rrfScore),
        fontSize = 11.sp,
        color = AppTheme.colors.textSecondary
      )
    }
    Spacer(Modifier.height(8.dp))
    Text(
      hit.toAnnotatedString(SpanStyle(background = AppTheme.colors.iconCurrent.copy(alpha = 0.28f))),
      fontSize = 13.sp,
      color = AppTheme.colors.textPrimary,
      maxLines = 8,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/** 来源标签:vectors 表里的 source 字段(chat / memory),没登记的按原样显示。 */
private fun sourceLabel(sourceType: String): String = when (sourceType) {
  "chat" -> "聊天片段"
  "memory" -> "记忆总结"
  else -> sourceType.ifBlank { "未标注来源" }
}

/**
 * 一条微总结:轮次区间 + 评分 + 正文。
 *
 * 点一下展开全文 —— 折叠时正文最多 4 行,展开后不截断。
 */
@Composable
private fun MicroCard(micro: MicroSummaryEntity, expanded: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        micro.roundLabel(),
        Modifier.weight(1f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Text("评分 ${micro.score}", fontSize = 12.sp, color = AppTheme.colors.iconCurrent)
    }
    Spacer(Modifier.height(8.dp))
    Text(
      micro.text,
      fontSize = 13.sp,
      color = AppTheme.colors.textPrimary,
      maxLines = if (expanded) Int.MAX_VALUE else 4,
      overflow = TextOverflow.Ellipsis
    )
    ExpandHint(expanded = expanded, text = micro.text)
  }
}

/** 一条主线大总结:只显示轮次跨度与正文。 */
@Composable
private fun ClueCard(node: NodeSummaryEntity, expanded: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(
      node.roundLabel(),
      Modifier.fillMaxWidth(),
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary
    )
    Spacer(Modifier.height(8.dp))
    Text(
      node.text,
      fontSize = 13.sp,
      color = AppTheme.colors.textPrimary,
      maxLines = if (expanded) Int.MAX_VALUE else 4,
      overflow = TextOverflow.Ellipsis
    )
    ExpandHint(expanded = expanded, text = node.text)
  }
}

/** 卡片右下角的「展开 / 收起」:正文本来就短时不显示(没什么可展开的)。 */
@Composable
private fun ExpandHint(expanded: Boolean, text: String) {
  if (text.length <= EXPAND_HINT_MIN_LENGTH) return
  Text(
    if (expanded) "收起" else "展开",
    Modifier
      .fillMaxWidth()
      .padding(top = 6.dp),
    fontSize = 11.sp,
    color = AppTheme.colors.more,
    textAlign = TextAlign.End
  )
}

/** 每个页签顶部的一行说明:数据从哪来、一共多少条。 */
@Composable
private fun LayerCaption(text: String) {
  Text(
    text,
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 8.dp),
    fontSize = 11.sp,
    color = AppTheme.colors.textSecondary
  )
}

/** 空状态 / 出错 / 检索中的统一提示块。 */
@Composable
private fun EmptyHint(title: String, detail: String) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 40.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(title, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    Text(
      detail,
      Modifier.padding(top = 8.dp),
      fontSize = 12.sp,
      color = AppTheme.colors.textSecondary,
      textAlign = TextAlign.Center
    )
  }
}

private fun emptyImmediateDetail(): String =
  "聊满 ${MemoryPipeline.MICRO_EVERY_ROUNDS} 轮会自动总结一次;总结之后这些轮的原文不再发给模型,只发总结。"

/** 列表卡片折叠时会显示「展开」的最小正文长度(比 4 行短的基本没必要展开)。 */
private const val EXPAND_HINT_MIN_LENGTH = 80

/** 微总结卡片在 [expanded] 集合里的 key:两类卡片 id 来自不同表,加前缀避免撞号。 */
private fun microKey(id: Long): String = "micro_$id"

private fun nodeKey(id: Long): String = "node_$id"

private fun Set<String>.toggle(key: String): Set<String> =
  if (key in this) this - key else this + key

/**
 * 读库失败当作「没有数据」,但**协程取消必须继续抛**:
 *
 * 刷新 / 搜索的信号连点两次时,旧协程会被取消;把 CancellationException 一起吞掉的话,
 * 旧协程会把空列表写回界面(明明有总结却显示「还没有」),或把取消当成「检索失败」。
 */
private suspend fun <T> loadOrElse(block: suspend () -> T, fallback: T): T =
  try {
    block()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Throwable) {
    fallback
  }

/**
 * 页签内容容器:上下拉一下即刷新(重读数据库)。
 *
 * 三个页签共用:同一个转圈、同一套内边距,列表内容由各层自己给。
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun RefreshableList(
  modifier: Modifier = Modifier,
  refreshing: Boolean,
  onRefresh: () -> Unit,
  resetScrollKey: Any? = null,
  content: LazyListScope.() -> Unit,
) {
  val state = rememberPullRefreshState(refreshing = refreshing, onRefresh = onRefresh)
  val listState = rememberLazyListState()
  // 换了一批结果(比如又搜了一次)就回到顶部:停在上一批的位置会让人以为「没反应」
  LaunchedEffect(resetScrollKey) {
    if (resetScrollKey != null && listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
  }
  Box(modifier.pullRefresh(state)) {
    LazyColumn(
      Modifier
        .fillMaxSize()
        .navigationBarsPadding(),
      state = listState,
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.Top,
      content = content,
    )
    PullRefreshIndicator(
      refreshing = refreshing,
      state = state,
      Modifier.align(Alignment.TopCenter),
      backgroundColor = AppTheme.colors.listItem,
      contentColor = AppTheme.colors.iconCurrent,
    )
  }
}
