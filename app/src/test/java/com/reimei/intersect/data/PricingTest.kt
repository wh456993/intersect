package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 费用折算的单元测试。
 *
 * 这块是纯计算,出错不会崩、只会静静地把账单算错,所以每条规则都钉一遍。
 */
class PricingTest {
  private val cny = ModelPrice(input = 2.0, cacheRead = 0.5, output = 8.0)

  @Test
  fun `未缓存输入按输入价、缓存按缓存价、输出按输出价`() {
    // (600 × 2 + 400 × 0.5 + 500 × 8) / 1e6 = 0.0054
    val cost = Pricing.costInCny(
      price = cny,
      promptTokens = 1000,
      cachedTokens = 400,
      completionTokens = 500
    )
    assertEquals(0.0054, cost, 1e-12)
  }

  @Test
  fun `没有缓存命中时全部输入按输入价`() {
    val cost = Pricing.costInCny(cny, promptTokens = 1_000_000, cachedTokens = 0, completionTokens = 0)
    assertEquals(2.0, cost, 1e-12)
  }

  @Test
  fun `缓存数大于输入总量时按输入总量封顶,不会算出负的未缓存输入`() {
    // 100 个输入全算缓存:100 × 0.5 / 1e6
    val cost = Pricing.costInCny(cny, promptTokens = 100, cachedTokens = 500, completionTokens = 0)
    assertEquals(0.00005, cost, 1e-12)
  }

  @Test
  fun `美元计价的模型乘汇率`() {
    val price = ModelPrice(input = 1.0, cacheRead = 1.0, output = 1.0, currency = Pricing.CURRENCY_USD)
    val cost = Pricing.costInCny(
      price = price,
      promptTokens = 1_000_000,
      cachedTokens = 0,
      completionTokens = 1_000_000,
      usdToCny = 7.2
    )
    // (1 + 1) 美元 → 14.4 元
    assertEquals(14.4, cost, 1e-12)
  }

  @Test
  fun `单价未知的模型算出来是 0`() {
    val cost = Pricing.costInCny(
      Pricing.UNKNOWN_PRICE,
      promptTokens = 123_456,
      cachedTokens = 0,
      completionTokens = 7_890
    )
    assertEquals(0.0, cost, 0.0)
  }

  @Test
  fun `负数 token 不会被算成负费用`() {
    val cost = Pricing.costInCny(cny, promptTokens = -100, cachedTokens = -5, completionTokens = -3)
    assertEquals(0.0, cost, 0.0)
  }

  @Test
  fun `配置里改过的单价优先于内置参考价`() {
    val config = PriceConfig().withPrice("deepseek-chat", ModelPrice(1.0, 0.1, 2.0))
    assertEquals(1.0, config.priceOf("deepseek-chat").input, 0.0)
    // 没改过的模型仍走内置参考价
    assertEquals(Pricing.DEFAULT_PRICES.getValue("qwen-plus").input, config.priceOf("qwen-plus").input, 0.0)
    // 完全不认识的模型单价为 0
    assertEquals(Pricing.UNKNOWN_PRICE, config.priceOf("my-local-model"))
  }
}
