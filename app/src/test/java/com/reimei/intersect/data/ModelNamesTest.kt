package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型 ID 与界面展示名的对照。
 *
 * 接口只认严格的模型 ID,界面上显示好认的名字:两边弄混了就是「调用报错」或「满屏 ID」,
 * 所以对应关系、老名字迁移、以及单价表的键都钉在这里。
 */
class ModelNamesTest {
  @Test
  fun `展示名映射到接口认的模型 ID`() {
    assertEquals("DeepSeek V4.1 Flash", ModelNames.display("deepseek-flash"))
    assertEquals("DeepSeek V4 Pro", ModelNames.display("deepseek-v4-pro"))
  }

  @Test
  fun `没有展示名的模型原样显示自己的 ID`() {
    assertEquals("qwen-plus", ModelNames.display("qwen-plus"))
    assertEquals("", ModelNames.display(""))
  }

  @Test
  fun `老的官方模型名迁移到新的 ID`() {
    assertEquals("deepseek-flash", ModelNames.normalize("deepseek-chat"))
    assertEquals("deepseek-v4-pro", ModelNames.normalize("deepseek-reasoner"))
  }

  @Test
  fun `曾经被当成 ID 存下来的展示名也换回 ID`() {
    assertEquals("deepseek-flash", ModelNames.normalize("DeepSeek V4.1 Flash"))
    assertEquals("deepseek-v4-pro", ModelNames.normalize("DeepSeek V4 Pro"))
    // 前后空格不该影响判断
    assertEquals("deepseek-v4-pro", ModelNames.normalize("  DeepSeek V4 Pro  "))
  }

  @Test
  fun `不认识的模型名原样返回`() {
    assertEquals("qwen-plus", ModelNames.normalize("qwen-plus"))
  }

  @Test
  fun `单价表按模型 ID 建键,老名字照样算得出价`() {
    val config = PriceConfig()
    assertEquals(
      Pricing.DEFAULT_PRICES.getValue("deepseek-flash").input,
      config.priceOf("deepseek-flash").input,
      0.0
    )
    // 改名之前记下的流水里是 deepseek-chat,重算费用不能掉成 0
    assertEquals(config.priceOf("deepseek-flash"), config.priceOf("deepseek-chat"))
  }
}
