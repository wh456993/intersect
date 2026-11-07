package com.reimei.intersect.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Base64 编解码的单元测试。
 *
 * 备份文件的密文、盐、IV 全都要经过它,一个字节都不能错——所以直接钉 RFC 4648 的官方测试向量,
 * 再加上往返与非法输入。
 */
class Base64CodecTest {
  @Test
  fun `RFC 4648 的官方测试向量`() {
    assertEquals("", Base64Codec.encode("".toByteArray()))
    assertEquals("Zg==", Base64Codec.encode("f".toByteArray()))
    assertEquals("Zm8=", Base64Codec.encode("fo".toByteArray()))
    assertEquals("Zm9v", Base64Codec.encode("foo".toByteArray()))
    assertEquals("Zm9vYg==", Base64Codec.encode("foob".toByteArray()))
    assertEquals("Zm9vYmE=", Base64Codec.encode("fooba".toByteArray()))
    assertEquals("Zm9vYmFy", Base64Codec.encode("foobar".toByteArray()))
  }

  @Test
  fun `解码还原原文`() {
    assertEquals("f", String(Base64Codec.decode("Zg==")))
    assertEquals("fo", String(Base64Codec.decode("Zm8=")))
    assertEquals("foo", String(Base64Codec.decode("Zm9v")))
    assertEquals("foobar", String(Base64Codec.decode("Zm9vYmFy")))
  }

  @Test
  fun `任意字节往返不丢信息`() {
    val raw = ByteArray(300) { index -> (index * 37 % 256).toByte() }
    assertArrayEquals(raw, Base64Codec.decode(Base64Codec.encode(raw)))
    // 0x00 与 0xFF 这类边界值最容易在移位运算里出错
    val edges = byteArrayOf(0, -1, 127, -128, 1, -2)
    assertArrayEquals(edges, Base64Codec.decode(Base64Codec.encode(edges)))
  }

  @Test
  fun `补位符与换行可以省略`() {
    assertArrayEquals("foo".toByteArray(), Base64Codec.decode("Zm9v"))
    assertArrayEquals("foob".toByteArray(), Base64Codec.decode("Zm9vYg"))
    assertArrayEquals("foobar".toByteArray(), Base64Codec.decode("Zm9v\nYmFy"))
    assertArrayEquals("foobar".toByteArray(), Base64Codec.decode(" Zm9v YmFy "))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `非法字符直接报错`() {
    Base64Codec.decode("Zm9v!!!")
  }

  @Test(expected = IllegalArgumentException::class)
  fun `长度不合法直接报错`() {
    Base64Codec.decode("Zm9vY")
  }
}
