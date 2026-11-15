package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.1–T5.5 验收 —— 导演事务。
 *
 * 1. 结构化指令解析(T5.1);
 * 2. 自然语言**只认受限模板**,模板外一律低置信、不写时钟(T5.2 / T1.6);
 * 3. 执行:校验 → 更新 → 快照 → 轻量事件 → pending 标记(T5.3);
 * 4. 事务日志与回滚(T5.4);
 * 5. 写权限隔离:AI 与其他模块一律被拒(T5.5)。
 */
class DirectorTxTest {

  private fun authority(at: Long = 5_000L) = TimeAuthority(logger = InMemoryTimeLog(), clock = { at })

  @Test
  fun `结构化指令 align`() {
    val parsed = DirectorParser.parse("/time align 黄昏")
    val ok = parsed as DirectorParse.Ok
    assertEquals(ParseConfidence.HIGH, ok.confidence)
    val command = ok.command as DirectorCommand.Align
    assertEquals(Period.DUSK, command.period)
    assertTrue(ok.echo.contains("黄昏"))

    // 枚举名 / 别名 / 显式日期都认
    assertEquals(Period.DUSK, (DirectorParser.parse("/time align dusk").let { (it as DirectorParse.Ok).command } as DirectorCommand.Align).period)
    assertEquals(
      2,
      ((DirectorParser.parse("/time align 傍晚 day 3") as DirectorParse.Ok).command as DirectorCommand.Align).explicitDateAnchor,
    )
  }

  @Test
  fun `结构化指令 advance 与 set`() {
    val advance = (DirectorParser.parse("/time advance 90") as DirectorParse.Ok).command as DirectorCommand.Advance
    assertEquals(90, advance.minutes)
    val advanceUnit = (DirectorParser.parse("/time advance 2小时") as DirectorParse.Ok).command as DirectorCommand.Advance
    assertEquals(120, advanceUnit.minutes)

    val set = (DirectorParser.parse("/time set 14:30") as DirectorParse.Ok).command as DirectorCommand.SetTime
    assertEquals(14 * 60 + 30, set.minuteOfDay)
    val setDay = (DirectorParser.parse("/time set 24:00 day 2") as DirectorParse.Ok).command as DirectorCommand.SetTime
    assertEquals(0, setDay.minuteOfDay)
    assertEquals(1, setDay.explicitDateAnchor)
  }

  @Test
  fun `结构化指令 pause resume rate 与非法输入`() {
    assertTrue(((DirectorParser.parse("/time pause") as DirectorParse.Ok).command as DirectorCommand.Pause).paused)
    assertFalse(((DirectorParser.parse("/time resume") as DirectorParse.Ok).command as DirectorCommand.Pause).paused)
    assertEquals(2.0, ((DirectorParser.parse("/time rate 2") as DirectorParse.Ok).command as DirectorCommand.Rate).rate, 0.0001)

    listOf(
      "/time", // 缺动作
      "/time align", // 缺时段
      "/time align 火星时间", // 不认识
      "/time set 25:00", // 非法时刻
      "/time advance", // 缺时长
      "/time advance -5",
      "/time rate 0", // 倍率非正
      "/time frobnicate 3", // 不认识的指令
    ).forEach { input ->
      assertTrue("应当低置信:$input", DirectorParser.parse(input) is DirectorParse.Low)
    }
  }

  @Test
  fun `自然语言只认受限模板`() {
    val align = DirectorParser.parse("时间来到黄昏") as DirectorParse.Ok
    assertEquals(Period.DUSK, (align.command as DirectorCommand.Align).period)
    assertEquals(ParseConfidence.HIGH, align.confidence)

    val viaAlias = DirectorParser.parse("到了傍晚") as DirectorParse.Ok
    assertEquals(Period.DUSK, (viaAlias.command as DirectorCommand.Align).period)

    val advance = DirectorParser.parse("过了三十分钟") as DirectorParse.Ok
    assertEquals(30, (advance.command as DirectorCommand.Advance).minutes)

    val hours = DirectorParser.parse("两小时后") as DirectorParse.Ok
    assertEquals(120, (hours.command as DirectorCommand.Advance).minutes)

    val quarter = DirectorParser.parse("又过了一刻钟") as DirectorParse.Ok
    assertEquals(15, (quarter.command as DirectorCommand.Advance).minutes)
  }

  @Test
  fun `模板外输入低置信且不写时钟`() {
    val clock = authority()
    val before = clock.state
    listOf(
      "时间好像变慢了",
      "天快亮了吧",
      "我们在这里待了很久",
      "现在几点了",
      "",
    ).forEach { input ->
      val parsed = DirectorParser.parse(input)
      assertTrue("模板外必须低置信:$input", parsed is DirectorParse.Low)
      val low = parsed as DirectorParse.Low
      assertTrue(low.echo.isNotEmpty())
    }
    // 低置信不产生任何事务
    assertEquals(before, clock.state)
    assertTrue(clock.transactions().isEmpty())
  }

  @Test
  fun `执行事务 校验 更新 快照 轻量事件与pending标记`() {
    val clock = authority()
    val tx = clock.apply(DirectorCommand.Align(Period.DUSK, "暮色"), atRound = 12)
    assertEquals(ClockOp.ALIGN, tx.op)
    assertEquals(TxOperator.DIRECTOR, tx.operator)
    assertEquals(Period.DUSK.startMinute, clock.state.exactTime)
    assertTrue("导演改过时间要留 pending 标记", tx.pending)
    assertNotNull("必须生成快照", clock.snapshotOf(tx.snapshotRef))
    // 轻量事件模板能按补丁拼出来
    val event = DirectorEvents.format(requireNotNull(clock.state.pendingDirectorPatch))
    assertTrue(event.contains("暮色"))

    // 非法参数被挡在写入口:推进量必须为正
    assertThrows(IllegalArgumentException::class.java) { clock.apply(DirectorCommand.Advance(0)) }
    assertThrows(IllegalArgumentException::class.java) { clock.apply(DirectorCommand.Rate(-1.0)) }
  }

  @Test
  fun `冲突优先级 导演大于程序基线大于AI`() {
    assertEquals(TxOperator.DIRECTOR, TxOperator.winner(TxOperator.DIRECTOR, TxOperator.PROGRAM))
    assertEquals(TxOperator.PROGRAM, TxOperator.winner(TxOperator.PROGRAM, TxOperator.AI))
    assertTrue(TxOperator.DIRECTOR.priority > TxOperator.PROGRAM.priority)
    assertTrue(TxOperator.PROGRAM.priority > TxOperator.AI.priority)
  }

  @Test
  fun `AI 写入被拒绝`() {
    val clock = authority()
    val error = assertThrows(ClockPermissionError::class.java) {
      clock.apply(DirectorCommand.Align(Period.DAWN), operator = TxOperator.AI)
    }
    assertTrue(error.message!!.contains("AI 无权写入时钟"))
    assertTrue("被拒绝的写不能留下事务", clock.transactions().isEmpty())
    assertEquals(ClockState(), clock.state)
  }

  @Test
  fun `事务日志可查询且可回滚`() {
    val clock = authority()
    val origin = clock.state
    val tx = clock.apply(DirectorCommand.SetTime(3 * 60))
    val found = clock.transactionOf(tx.txId)
    requireNotNull(found)
    assertEquals(TxOperator.DIRECTOR, found.operator)
    assertEquals(origin, found.before)
    assertEquals(clock.state, found.after)

    val rollback = clock.rollback(tx.txId)
    requireNotNull(rollback)
    assertEquals(origin, clock.state)
    assertEquals(2, clock.transactions().size)
  }

  @Test
  fun `AI 只能建议 不能执行`() {
    val command = DirectorCommand.Align(Period.NOON)
    val suggestion = command.toCommandText()
    assertEquals("/time align 正午", suggestion)
    // 建议文本本身不改任何状态(这里没有时钟,只是验证它是一段纯文本)
    assertTrue(suggestion.startsWith("/time"))
    assertNotNull(command.describe())
  }

  @Test
  fun `普通推进生成程序基线事务 暂停时不产生事务`() {
    val clock = authority()
    val tx = requireNotNull(clock.advanceProgram(45, atRound = 3))
    assertEquals(TxOperator.PROGRAM, tx.operator)
    assertEquals(ClockState.DEFAULT_START_MINUTE + 45, clock.state.exactTime)

    clock.apply(DirectorCommand.Pause(true))
    val txCount = clock.transactions().size
    assertNull("暂停中不推进", clock.advanceProgram(60))
    assertEquals(txCount, clock.transactions().size)
    assertNull("推进量为 0 不产生事务", clock.advanceProgram(0))
  }
}
