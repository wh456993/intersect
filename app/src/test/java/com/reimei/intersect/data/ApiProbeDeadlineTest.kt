package com.reimei.intersect.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApiProbe.withDeadline] 的三条路径:正常返回、到点放弃、内部异常转成结论。
 *
 * 这段逻辑决定了「测试连接 / 拉取模型」会不会一直转圈,所以单独锁住。
 */
class ApiProbeDeadlineTest {
  @Test
  fun returnsResultWhenRequestFinishesInTime() = runBlocking {
    val result = ApiProbe.withDeadline(
      ms = 2_000,
      onTimeout = { "timeout" },
      onError = { "error" }
    ) { "ok" }
    assertEquals("ok", result)
  }

  @Test
  fun givesUpWhenRequestHangs() = runBlocking {
    val start = System.currentTimeMillis()
    val result = ApiProbe.withDeadline(
      ms = 300,
      onTimeout = { "timeout" },
      onError = { "error" }
    ) {
      // 模拟卡在 socket 上的请求:远超总时限
      Thread.sleep(10_000)
      "ok"
    }
    val elapsed = System.currentTimeMillis() - start
    assertEquals("timeout", result)
    assertTrue("应该在总时限附近就返回,实际 ${elapsed}ms", elapsed < 3_000)
  }

  @Test
  fun mapsExceptionToConclusion() = runBlocking {
    val result = ApiProbe.withDeadline<String>(
      ms = 2_000,
      onTimeout = { "timeout" },
      onError = { e -> e.message ?: "error" }
    ) {
      throw IllegalStateException("boom")
    }
    assertEquals("boom", result)
  }
}
