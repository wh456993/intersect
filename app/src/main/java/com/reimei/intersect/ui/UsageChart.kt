package com.reimei.intersect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.ui.theme.AppTheme

/** 纵轴标签占的宽度:三张图共用,横轴标签才能和图区对齐。 */
private val Y_AXIS_WIDTH = 40.dp

/**
 * 折线图(周期总览 / 活跃记录用)。
 *
 * 纵轴三条虚线刻度 + 左侧标签;数据点画小圆点,点多了就不画(会糊成一条粗线)。
 * 画法全部在 Canvas 里手写,不引图表库:这里只需要「一条折线 + 网格」。
 */
@Composable
internal fun UsageLineChart(
  values: List<Float>,
  yLabels: List<String>,
  accent: Color,
  modifier: Modifier = Modifier,
  height: Dp = 108.dp
) {
  Row(modifier.fillMaxWidth().height(height)) {
    AxisLabels(yLabels)
    // 配色在组合阶段读好:Canvas 的绘制 lambda 不是 @Composable,里面拿不到 AppTheme
    val gridColor = AppTheme.colors.chatListDivider
    Canvas(
      Modifier
        .weight(1f)
        .fillMaxHeight()
        .padding(vertical = 2.dp)
    ) {
      drawGrid(height = size.height, color = gridColor)
      val max = values.maxOrNull() ?: 0f
      if (max <= 0f || values.isEmpty()) return@Canvas
      val plotHeight = size.height
      val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f
      val points = values.mapIndexed { index, value ->
        Offset(
          x = if (values.size > 1) index * stepX else size.width / 2f,
          y = plotHeight - (value / max).coerceIn(0f, 1f) * plotHeight
        )
      }
      if (points.size > 1) {
        val path = Path().apply {
          moveTo(points.first().x, points.first().y)
          points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
          path = path,
          color = accent,
          style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )
      }
      if (points.size <= DOTS_LIMIT) {
        points.forEach { drawCircle(color = accent, radius = 2.dp.toPx(), center = it) }
      } else {
        // 点太多只标最后一个,保证「最新一条」可读
        drawCircle(color = accent, radius = 2.4.dp.toPx(), center = points.last())
      }
    }
  }
}

/**
 * 柱状图(趋势分析用):点哪根柱子就高亮哪根。
 *
 * [labels] 与 [values] 一一对应;横轴只画首 / 中 / 尾三个标签,十几个日期挤一行没人看得清。
 */
@Composable
internal fun UsageBarChart(
  values: List<Float>,
  yLabels: List<String>,
  labels: List<String>,
  accent: Color,
  selectedIndex: Int?,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  height: Dp = 128.dp
) {
  Column(modifier.fillMaxWidth()) {
    val gridColor = AppTheme.colors.chatListDivider
    Row(Modifier.fillMaxWidth().height(height)) {
      AxisLabels(yLabels)
      Canvas(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(vertical = 2.dp)
          .pointerInput(values.size) {
            detectTapGestures { offset ->
              if (values.isNotEmpty() && size.width > 0) {
                val step = size.width.toFloat() / values.size
                onSelect((offset.x / step).toInt().coerceIn(0, values.lastIndex))
              }
            }
          }
      ) {
        drawGrid(height = size.height, color = gridColor)
        val max = values.maxOrNull() ?: 0f
        if (max <= 0f || values.isEmpty()) return@Canvas
        val step = size.width / values.size
        val barWidth = (step * 0.52f).coerceAtLeast(2.dp.toPx())
        values.forEachIndexed { index, value ->
          val ratio = (value / max).coerceIn(0f, 1f)
          val barHeight = (ratio * size.height).coerceAtLeast(if (value > 0f) 2.dp.toPx() else 0f)
          if (barHeight <= 0f) return@forEachIndexed
          val left = index * step + (step - barWidth) / 2f
          val selected = selectedIndex == index
          drawRoundRect(
            color = if (selected || selectedIndex == null) accent else accent.copy(alpha = 0.32f),
            topLeft = Offset(left, size.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
          )
        }
      }
    }
    if (labels.isNotEmpty()) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        // 与图区左对齐:先让出纵轴那一条
        Row(Modifier.width(Y_AXIS_WIDTH)) {}
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
          val shown = listOfNotNull(
            labels.firstOrNull(),
            labels.getOrNull(labels.size / 2).takeIf { labels.size > 2 },
            labels.lastOrNull()
          )
          shown.forEach { label ->
            Text(
              label,
              fontSize = 9.sp,
              color = AppTheme.colors.textSecondary,
              maxLines = 1,
              overflow = TextOverflow.Clip
            )
          }
        }
      }
    }
  }
}

/** 纵轴标签:上 / 中 / 下,与三条虚线对齐。 */
@Composable
private fun AxisLabels(labels: List<String>) {
  Column(
    Modifier
      .width(Y_AXIS_WIDTH)
      .fillMaxHeight()
      .padding(vertical = 2.dp),
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    labels.forEach { label ->
      Text(
        label,
        fontSize = 9.sp,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Clip
      )
    }
  }
}

/** 三条横向虚线(顶 / 中 / 底)。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(height: Float, color: Color) {
  val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
  listOf(0f, height / 2f, height).forEach { y ->
    drawLine(
      color = color,
      start = Offset(0f, y),
      end = Offset(size.width, y),
      strokeWidth = 1f,
      pathEffect = dash
    )
  }
}

/** 超过这个点数就不画数据点了(点数 × 2dp 会连成一条粗线)。 */
private const val DOTS_LIMIT = 45
