package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导演模式面板的**解析层**验收:
 *
 * 1. 日历换算(锚点 ↔ `2026.5.6 8:22`)是纯整数、无时区,往返相等;
 * 2. 三个输入框 → [SceneEdit]:格式不对 / 太长一律**拒绝**(不猜、不兜底);
 * 3. 差异清单决定注入什么,没差异就是空(「没改就不重注入」)。
 */
class SceneFieldsTest {

  @Test
  fun `纪元锚点 0 就是配置里的那一天`() {
    val (y, m, d) = SceneCalendar.civilOf(
      SceneCalendar.daysFromCivil(SceneConfig.EPOCH_YEAR, SceneConfig.EPOCH_MONTH, SceneConfig.EPOCH_DAY)
    )
    assertEquals(SceneConfig.EPOCH_YEAR, y)
    assertEquals(SceneConfig.EPOCH_MONTH, m)
    assertEquals(SceneConfig.EPOCH_DAY, d)
  }

  @Test
  fun `日期锚点与分钟数渲染成人读文本`() {
    // 锚点 0 = 纪元当天,8:22 不补零(与面板输入框的写法一致)
    assertEquals("2026.1.1 8:22", SceneCalendar.label(0, 8 * 60 + 22))
    assertEquals("2026.1.1 0:05", SceneCalendar.label(0, 5))
    // 跨日:锚点 1 就是第二天
    assertEquals("2026.1.2 0:00", SceneCalendar.label(1, 0))
    // 一个月后的边界(1 月 31 天)
    assertEquals("2026.2.1 9:40", SceneCalendar.label(31, 9 * 60 + 40))
  }

  @Test
  fun `人读文本解析回锚点与分钟 往返相等`() {
    listOf(0 to 0, 0 to 8 * 60 + 22, 1 to 9 * 60 + 40, 31 to 12 * 60, 400 to 23 * 60 + 59).forEach { (anchor, minute) ->
      val text = SceneCalendar.label(anchor, minute)
      val parsed = requireNotNull(SceneCalendar.parse(text)) { "解析失败:$text" }
      assertEquals(text, anchor, parsed.dateAnchor)
      assertEquals(text, minute, parsed.minuteOfDay)
    }
  }

  @Test
  fun `解析认多种日期分隔符与全角冒号`() {
    val expected = SceneCalendar.parse("2026.5.6 8:22")!!
    assertEquals(expected, SceneCalendar.parse("2026/5/6 8:22"))
    assertEquals(expected, SceneCalendar.parse("2026-5-6 8:22"))
    assertEquals(expected, SceneCalendar.parse("2026.5.6 8:22"))
    assertEquals(expected, SceneCalendar.parse("  2026.5.6   8:22  "))
  }

  @Test
  fun `格式不对或日期越界一律返回 null`() {
    listOf(
      "",
      "2026.5.6",              // 没有时刻
      "8:22",                  // 没有日期
      "2026.13.6 8:22",        // 月份越界
      "2026.5.32 8:22",        // 日期越界(5 月 31 天)
      "2025.2.29 8:22",        // 不是闰年
      "2026.5.6 25:00",        // 小时越界
      "2026.5.6 8:60",         // 分钟越界
      "10000.5.6 8:22",        // 年份超上限(下限已是 -9999,支持非公元纪年)
      "2026.5.6 8:22 傍晚",     // 多了一段
    ).forEach { assertNull("不该解析成功:$it", SceneCalendar.parse(it)) }
  }

  @Test
  fun `闰年二月二十九可以解析`() {
    val parsed = requireNotNull(SceneCalendar.parse("2024.2.29 8:00"))
    assertEquals("2024.2.29 8:00", SceneCalendar.label(parsed.dateAnchor, parsed.minuteOfDay))
  }

  @Test
  fun `24点整归一到第二天零点`() {
    val midnight = requireNotNull(SceneCalendar.parse("2026.5.6 24:00"))
    val nextDay = requireNotNull(SceneCalendar.parse("2026.5.7 0:00"))
    assertEquals(nextDay, midnight)
  }

  @Test
  fun `三个输入框解析成编辑内容`() {
    val result = SceneEditor.parse(SceneDraft(time = "2026.5.7 9:40", place = " 北京 ", weather = "小雨"))
    assertTrue(result is SceneEditResult.Ok)
    val edit = (result as SceneEditResult.Ok).edit
    assertEquals("2026.5.7 9:40", edit.timeLabel)
    assertEquals("北京", edit.place)     // 首尾空白被去掉
    assertEquals("小雨", edit.weather)
    assertEquals(Period.MORNING, edit.period)
  }

  @Test
  fun `时间格式看不懂就拒绝 不动任何值`() {
    val result = SceneEditor.parse(SceneDraft(time = "傍晚", place = "北京", weather = "晴"))
    assertTrue(result is SceneEditResult.Reject)
    val reject = result as SceneEditResult.Reject
    assertEquals(SceneEditor.REJECT_TIME, reject.reason)
    assertTrue(reject.echo.contains(SceneEditor.TIME_HINT))
  }

  @Test
  fun `地点与天气超过上限就拒绝`() {
    val tooLong = "字".repeat(SceneConfig.FIELD_MAX_CHARS + 1)
    val place = SceneEditor.parse(SceneDraft(time = "2026.5.6 8:22", place = tooLong))
    assertEquals(SceneEditor.REJECT_PLACE, (place as SceneEditResult.Reject).reason)
    val weather = SceneEditor.parse(SceneDraft(time = "2026.5.6 8:22", weather = tooLong))
    assertEquals(SceneEditor.REJECT_WEATHER, (weather as SceneEditResult.Reject).reason)
    // 刚好等于上限是可以的
    val exact = SceneEditor.parse(
      SceneDraft(time = "2026.5.6 8:22", place = "字".repeat(SceneConfig.FIELD_MAX_CHARS))
    )
    assertTrue(exact is SceneEditResult.Ok)
  }

  @Test
  fun `差异清单按时间 地点 天气的顺序给出`() {
    val current = ClockState(exactTime = 8 * 60 + 22, dateAnchor = 0, place = "北京", weather = "晴")
    val edit = SceneEdit(
      dateAnchor = SceneCalendar.parse("2026.1.2 9:40")!!.dateAnchor,
      minuteOfDay = 9 * 60 + 40,
      place = "上海",
      weather = "小雨",
    )
    val changes = SceneEditor.changesOf(current, edit)
    assertEquals(listOf(SceneField.TIME, SceneField.PLACE, SceneField.WEATHER), changes.map { it.field })
    assertEquals("2026.1.1 8:22", changes[0].before)
    assertEquals("2026.1.2 9:40", changes[0].after)
    // 注入行格式就是冻结的那一条
    assertEquals("<2026.1.1 8:22变更2026.1.2 9:40>", changes[0].line())
    assertEquals("<北京变更上海>", changes[1].line())
    assertEquals("<晴变更小雨>", changes[2].line())
  }

  @Test
  fun `没改就没有差异`() {
    val current = ClockState(exactTime = 8 * 60, dateAnchor = 3, place = "北京", weather = "晴")
    val edit = SceneEdit(dateAnchor = 3, minuteOfDay = 8 * 60, place = "北京", weather = "晴")
    assertTrue(SceneEditor.changesOf(current, edit).isEmpty())
  }

  @Test
  fun `只改一项时差异清单只有那一项`() {
    val current = ClockState(exactTime = 8 * 60, dateAnchor = 0, place = "北京", weather = "晴")
    val onlyWeather = SceneEdit(dateAnchor = 0, minuteOfDay = 8 * 60, place = "北京", weather = "雪")
    assertEquals(listOf(SceneField.WEATHER), SceneEditor.changesOf(current, onlyWeather).map { it.field })
  }

  @Test
  fun `未设定的字段在变更行里写未设定 而不是空串`() {
    val current = ClockState(exactTime = 8 * 60, dateAnchor = 0)
    val edit = SceneEdit(dateAnchor = 0, minuteOfDay = 8 * 60, place = "北京", weather = "")
    val place = SceneEditor.changesOf(current, edit).single()
    assertEquals(SceneField.PLACE, place.field)
    assertEquals("<${SceneChange.UNSET}变更北京>", place.line())
  }

  @Test
  fun `面板初始值取自当前状态`() {
    val state = ClockState(exactTime = 21 * 60 + 5, dateAnchor = 2, place = "杭州", weather = "多云")
    val draft = SceneEditor.draftOf(state)
    assertEquals("2026.1.3 21:05", draft.time)
    assertEquals("杭州", draft.place)
    assertEquals("多云", draft.weather)
  }

  @Test
  fun `场景字段只有三项`() {
    // R6:本任务冻结三项,加第四项属于新任务 —— 这里钉住表长
    assertEquals(3, SceneField.entries.size)
    assertEquals(SceneField.PLACE, SceneField.fromName("place"))
    assertNull(SceneField.fromName("mood"))
  }
}
