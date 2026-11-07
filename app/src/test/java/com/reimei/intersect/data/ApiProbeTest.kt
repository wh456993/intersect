package com.reimei.intersect.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「拉取模型」与「测试连接」拼地址的规则。
 *
 * 只覆盖纯字符串逻辑:真正发请求要靠网络,而 org.json 在 JVM 单测里是空实现。
 */
class ApiProbeTest {
  @Test
  fun modelsEndpoint_appendsToBaseUrl() {
    assertEquals(
      "https://api.deepseek.com/v1/models",
      ApiProbe.modelsEndpoint("https://api.deepseek.com/v1")
    )
  }

  @Test
  fun modelsEndpoint_stripsTrailingSlashAndChatPath() {
    assertEquals(
      "https://api.deepseek.com/v1/models",
      ApiProbe.modelsEndpoint("https://api.deepseek.com/v1/")
    )
    assertEquals(
      "https://api.deepseek.com/v1/models",
      ApiProbe.modelsEndpoint("https://api.deepseek.com/v1/chat/completions")
    )
    assertEquals(
      "https://api.deepseek.com/v1/models",
      ApiProbe.modelsEndpoint("https://api.deepseek.com/v1/models")
    )
  }

  @Test
  fun chatEndpoint_keepsFullPath() {
    assertEquals(
      "https://api.deepseek.com/v1/chat/completions",
      ApiProbe.chatEndpoint("https://api.deepseek.com/v1")
    )
    assertEquals(
      "https://api.deepseek.com/v1/chat/completions",
      ApiProbe.chatEndpoint("https://api.deepseek.com/v1/chat/completions")
    )
  }

  // 「地址不通」要能和「模型回得慢」分开说,否则用户只看到一句「超时」,判断不了该改什么。

  @Test
  fun failureMessage_unknownHostPointsAtTypo() {
    val msg = ApiProbe.failureMessage(
      UnknownHostException("api.example.com"),
      "https://api.example.com/v1"
    )
    assertTrue(msg, msg.contains("解析不到域名 api.example.com"))
  }

  @Test
  fun failureMessage_connectRefusedPointsAtNetwork() {
    val msg = ApiProbe.failureMessage(
      ConnectException("failed to connect to api.openai.com/1.2.3.4 (port 443)"),
      "https://api.openai.com/v1"
    )
    assertTrue(msg, msg.contains("连不上 api.openai.com"))
  }

  @Test
  fun failureMessage_connectTimeoutIsNotBlamedOnModelSpeed() {
    val msg = ApiProbe.failureMessage(
      SocketTimeoutException("connect timed out"),
      "https://api.openai.com/v1"
    )
    assertTrue(msg, msg.contains("建连超时"))
  }

  @Test
  fun failureMessage_readTimeoutBlamesSlowResponse() {
    val msg = ApiProbe.failureMessage(
      SocketTimeoutException("Read timed out"),
      "https://api.deepseek.com/v1"
    )
    assertTrue(msg, msg.contains("已连上 api.deepseek.com"))
  }

  @Test
  fun failureMessage_fallsBackToRawMessage() {
    val msg = ApiProbe.failureMessage(
      IllegalStateException("boom"),
      "https://api.deepseek.com/v1"
    )
    assertEquals("boom", msg)
  }
}
