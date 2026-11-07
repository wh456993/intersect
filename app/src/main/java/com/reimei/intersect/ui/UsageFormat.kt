package com.reimei.intersect.ui

import com.reimei.intersect.data.PriceConfig
import java.util.Locale

/**
 * 统计页的数字格式。
 *
 * 统一放一处:卡片上的大数字、图表轴标签、分组行里的零碎数字必须是同一套口径,
 * 否则同一笔费用在图里与在列表里会显示成两个样子。
 */
internal object UsageFmt {
  /** 金额。[config] 决定币种与汇率换算(库里存的是人民币)。 */
  fun money(cny: Double, config: PriceConfig): String =
    config.currencySymbol + fixed(config.displayAmount(sanitize(cny)), 2)

  /** 只带符号的金额(图表轴标签用,位数更少)。 */
  fun moneyAxis(cny: Double, config: PriceConfig): String {
    val amount = config.displayAmount(sanitize(cny))
    return config.currencySymbol + when {
      amount >= 1000 -> compact(amount)
      amount >= 1 -> fixed(amount, if (amount >= 100) 0 else 1)
      amount <= 0 -> "0"
      else -> fixed(amount, 2)
    }
  }

  /** token 数:K / M 缩写,和图里的 5.6M 一个写法。 */
  fun tokens(value: Long): String = when {
    value >= 1_000_000_000L -> fixed(value / 1_000_000_000.0, 2) + "B"
    value >= 1_000_000L -> fixed(value / 1_000_000.0, 1) + "M"
    value >= 1_000L -> fixed(value / 1_000.0, 1) + "K"
    else -> value.toString()
  }

  /** 百分比,保留一位小数(缓存率 83.2% 这种精度)。 */
  fun percent(part: Long, total: Long): String =
    if (total <= 0L) "—" else fixed(part * 100.0 / total, 1) + "%"

  fun percent(ratio: Double): String =
    if (ratio.isNaN() || ratio <= 0.0) "0%" else fixed(ratio * 100.0, 1) + "%"

  /** 时长:天 / 小时 / 分钟。 */
  fun duration(millis: Long): String {
    val minutes = millis / 60_000L
    if (minutes <= 0L) return "不足 1 分钟"
    val days = minutes / (60 * 24)
    val hours = (minutes % (60 * 24)) / 60
    val rest = minutes % 60
    return when {
      days > 0 -> "$days 天 $hours 小时"
      hours > 0 -> "$hours 小时 $rest 分钟"
      else -> "$rest 分钟"
    }
  }

  /** 图表纵轴刻度:从 0 到 max 的三个标签(上 / 中 / 下)。 */
  fun axis(max: Long): List<String> =
    listOf(tokens(max), tokens(max / 2), "0")

  fun axis(max: Double, config: PriceConfig): List<String> =
    listOf(moneyAxis(max, config), moneyAxis(max / 2, config), config.currencySymbol + "0")

  /** 编辑框里的初始文本:整数不拖 ".0",小数最多留 4 位。 */
  fun numberText(value: Double): String {
    if (value <= 0.0 || value.isNaN() || value.isInfinite()) return "0"
    return fixed(value, 4).trimEnd('0').trimEnd('.').ifEmpty { "0" }
  }

  fun parseNumber(text: String): Double =
    text.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && !it.isInfinite() && !it.isNaN() } ?: 0.0

  private fun sanitize(value: Double): Double =
    if (value.isNaN() || value.isInfinite() || value < 0.0) 0.0 else value

  private fun fixed(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

  private fun compact(value: Double): String =
    if (value >= 1_000_000) fixed(value / 1_000_000, 1) + "M" else fixed(value / 1_000, 1) + "k"
}
