package com.reimei.intersect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.time.SceneDraft
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 输入栏底部面板里的一项。
 *
 * 图标两种来源:[iconRes] 是既有的 PNG 资源,也可以给一段 [icon] 自己画
 * (导演模式那个折线图就是用 [LineChartIcon] 现画的,不额外添一张图)。
 */
class AttachEntry(
  val label: String,
  val iconRes: Int? = null,
  val icon: (@Composable () -> Unit)? = null,
  val onClick: () -> Unit,
)

/** 面板里每行放几个:4 个是这类底栏面板的常规密度(微信 / Telegram 都是 4)。 */
private const val SHEET_COLUMNS = 4

/**
 * 底部附件面板:点加号后从屏幕最底部升起的图标网格。
 *
 * 位置关键是**在输入栏下方**而不是上方:面板展开时把输入栏顶上去、自己占住屏幕底边,
 * 也就是键盘原本待的那块地方(微信 / QQ / Telegram 的「+」都是这个交互)。
 * 只负责摆放,不知道每一项是干什么的 —— 放哪些入口由调用方决定。
 *
 * 没给图标的项按 [LineChartIcon] 画(导演模式就是这么来的,不额外添一张 PNG)。
 * 底部让位(键盘 / 导航栏)由调用方 [com.reimei.intersect.ui.ChatBottomBar] 统一处理,
 * 这里只管内容,这样导演模式表单也能装进同一个容器。
 */
@Composable
fun AttachSheet(entries: List<AttachEntry>) {
  Column(Modifier.fillMaxWidth()) {
    // 顶部那道小横条:一眼看出「这是从底下拉上来的面板」,也是各家底栏面板的共同观感
    Box(
      Modifier
        .fillMaxWidth()
        .padding(top = 8.dp, bottom = 4.dp),
      contentAlignment = Alignment.Center
    ) {
      Box(
        Modifier
          .size(width = 36.dp, height = 4.dp)
          .clip(CircleShape)
          .background(AppTheme.colors.textSecondary.copy(alpha = 0.35f))
      )
    }
    entries.chunked(SHEET_COLUMNS).forEach { row ->
      Row(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp)
      ) {
        row.forEach { entry -> AttachCell(entry, Modifier.weight(1f)) }
        // 补齐最后一行的空位:不补的话 1~3 个入口会被拉宽成整行(和翻牌一样难看)
        repeat(SHEET_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
    Spacer(Modifier.height(10.dp))
  }
}

/** 面板里的一格:圆角方块图标 + 下方标签。整格可点,不必对准图标。 */
@Composable
private fun AttachCell(entry: AttachEntry, modifier: Modifier = Modifier) {
  Column(
    modifier
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = entry.onClick)
      .padding(vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      Modifier
        .size(52.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(AppTheme.colors.textFieldBackground),
      contentAlignment = Alignment.Center
    ) {
      val custom = entry.icon
      val res = entry.iconRes
      when {
        custom != null -> custom()
        res != null -> Icon(
          painterResource(res),
          contentDescription = entry.label,
          Modifier.size(24.dp),
          tint = AppTheme.colors.textPrimary
        )

        else -> LineChartIcon(Modifier.size(26.dp))
      }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      entry.label,
      fontSize = 12.sp,
      color = AppTheme.colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * 导演模式图标:一段简易折线图(左下 → 抬升 → 回落 → 右上),用 Canvas 画。
 *
 * 为什么不加一张 PNG:图标的形状只由几个坐标点决定,画出来和资源的观感一致,
 * 又不用为两种主题各存一份图。
 *
 * 默认用 [AppTheme] 的正文色(标准黑 / 暗色下为白):面板里的图标是**可用入口**,
 * 不该用未激活的灰色(`colors.icon`)—— 那看起来像点不动。
 */
@Composable
fun LineChartIcon(modifier: Modifier = Modifier, tint: Color = AppTheme.colors.textPrimary) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val inset = w * 0.14f
    val spanX = w - inset * 2
    val spanY = h - inset * 2
    // 折线的四个拐点(0..1 的相对坐标,坐标系向下,所以 y 小的在上面)
    val points = listOf(
      0.00f to 0.86f,
      0.34f to 0.42f,
      0.60f to 0.68f,
      1.00f to 0.12f,
    ).map { (x, y) -> Offset(inset + x * spanX, inset + y * spanY) }

    val path = Path().apply {
      moveTo(points.first().x, points.first().y)
      points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
      path = path,
      color = tint,
      style = Stroke(
        width = w * 0.10f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
      )
    )
    // 末尾那个点稍大一点,读起来像「涨到这儿」
    drawCircle(tint, radius = w * 0.09f, center = points.last())
  }
}

/**
 * 导演模式面板:时间 / 地点 / 天气三个输入框 + 右下角保存。
 *
 * 它只是**填写界面**:解析与落账在 [com.reimei.intersect.data.time.SceneEditor] 与
 * [com.reimei.intersect.data.time.TimeAuthority] 那一侧,这里不判断合法性,
 * 只把错误文本显示出来。
 *
 * [error] 非空时贴在输入框下面(格式不对 / 太长),保存成功则由调用方收起面板。
 */
@Composable
fun DirectorScenePanel(
  draft: SceneDraft,
  onDraftChange: (SceneDraft) -> Unit,
  error: String?,
  onSave: () -> Unit,
  onBack: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "导演模式",
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.textPrimary
      )
      Spacer(Modifier.weight(1f))
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
    Spacer(Modifier.height(4.dp))
    SceneFieldRow("时间", draft.time, SceneTimeHint) { onDraftChange(draft.copy(time = it)) }
    SceneFieldRow("地点", draft.place, "未设定") { onDraftChange(draft.copy(place = it)) }
    SceneFieldRow("天气", draft.weather, "未设定") { onDraftChange(draft.copy(weather = it)) }
    if (error != null) {
      Text(
        error,
        Modifier.padding(top = 6.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.badge
      )
    }
    Row(
      Modifier
        .fillMaxWidth()
        .padding(top = 8.dp),
      horizontalArrangement = Arrangement.End
    ) {
      Box(
        Modifier
          .clip(RoundedCornerShape(8.dp))
          .background(AppTheme.colors.fab)
          .clickable(onClick = onSave)
          .padding(horizontal = 20.dp, vertical = 8.dp)
      ) {
        Text("保存", fontSize = 13.sp, color = AppTheme.colors.onFab)
      }
    }
  }
}

/** 时间输入框的占位提示(与面板的实际输入格式一致)。 */
private const val SceneTimeHint = "2026.5.6 8:22"

/** 一行「标签 + 输入框」。占位提示只在内容为空时显示,不用 Material 的 TextField(样式太重)。 */
@Composable
private fun SceneFieldRow(
  label: String,
  value: String,
  hint: String,
  onValueChange: (String) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      label,
      Modifier.width(40.dp),
      fontSize = 13.sp,
      color = AppTheme.colors.textSecondary
    )
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier
        .weight(1f)
        .height(36.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(AppTheme.colors.textFieldBackground)
        .padding(horizontal = 8.dp, vertical = 9.dp),
      cursorBrush = SolidColor(AppTheme.colors.textPrimary),
      textStyle = TextStyle(fontSize = 13.sp, color = AppTheme.colors.textPrimary),
      singleLine = true,
      decorationBox = { inner ->
        if (value.isEmpty()) {
          Box {
            Text(hint, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
            inner()
          }
        } else {
          inner()
        }
      }
    )
  }
}
