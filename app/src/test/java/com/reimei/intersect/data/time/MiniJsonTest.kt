package com.reimei.intersect.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自带 JSON 编解码的单测。
 *
 * 它是整个子系统可测的前提:落盘状态、快照、事务日志、契约校验都走它。所以只钉最基本、
 * 但一点都不能错的那几条:往返相等、转义、数字形态、坏输入抛错而不是返回半个对象。
 */
class MiniJsonTest {

  @Test
  fun `对象与数组往返相等`() {
    val value = jobj(
      "a" to jnum(1),
      "b" to jstr("黄昏"),
      "c" to jbool(true),
      "d" to jarrOf(jnum(1), jnum(2), jstr("x")),
      "e" to jobj("f" to JsonValue.Null),
    )
    val roundTripped = Json.parse(value.encode())
    assertEquals(value, roundTripped)
  }

  @Test
  fun `值为null的键会被省略`() {
    val value = jobj("a" to jnum(1), "b" to null)
    assertEquals(setOf("a"), value.keys())
  }

  @Test
  fun `字符串转义往返`() {
    val text = "引号\"反斜线\\换行\n制表\t控制\u0001"
    val encoded = jstr(text).encode()
    assertEquals(text, (Json.parse(encoded) as JsonValue.Str).value)
  }

  @Test
  fun `小数与整数的原始文本不会被改写`() {
    val decimal = JsonValue.Num.of(2.5)
    assertEquals("2.5", decimal.encode())
    assertEquals("2.0", JsonValue.Num.of(2.0).encode())
    assertEquals("2", JsonValue.Num.of(2).encode())
    assertEquals(2.0, (Json.parse("2.0") as JsonValue.Num).asDouble(), 0.0001)
    assertEquals(2L, (Json.parse("2") as JsonValue.Num).asLong())
  }

  @Test
  fun `坏输入抛异常而不是返回半个对象`() {
    val bad = listOf("", "{", "{\"a\":}", "{'a':1}", "[1,]", "nul", "\"未闭合")
    bad.forEach { text ->
      var thrown = false
      try {
        Json.parse(text)
      } catch (e: IllegalArgumentException) {
        thrown = true
      }
      assertTrue("应当拒绝:$text", thrown)
    }
  }

  @Test
  fun `字段读取对坏数据一律返回null`() {
    val obj = jobj("a" to jstr("x"), "b" to jnum(3))
    assertEquals("x", obj.strField("a"))
    assertEquals(3, obj.intField("b"))
    assertNull(obj.strField("b"))
    assertNull(obj.intField("a"))
    assertNull(obj.strField("missing"))
    assertNull(null.strField("a"))
    assertTrue(null.arrItems().isEmpty())
  }
}
