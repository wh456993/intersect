package com.reimei.intersect.data

import android.content.Context
import org.json.JSONObject
/**
 * 一个模型的单价,单位是「货币 / 百万 token」。缓存读取(命中)通常比未命中便宜得多。
 *
 * [currency] 决定要不要按汇率换算:[Pricing.CURRENCY_USD] 的模型在算费用时乘上美元汇率,
 * 库里 `cost` 一律是人民币。
 */
data class ModelPrice(
  val input: Double,
  val cacheRead: Double,
  val output: Double,
  val currency: String = Pricing.CURRENCY_CNY,
)

/** 费用的纯计算部分:不碰存储,便于单元测试。 */
object Pricing {
  const val CURRENCY_CNY = "CNY"
  const val CURRENCY_USD = "USD"

  /** 单价按「每百万 token」标价,这是各家服务商计费页的通用口径。 */
  const val TOKENS_PER_UNIT = 1_000_000.0

  const val DEFAULT_USD_TO_CNY = 7.20

  /** 单价未知的模型:算出来是 0,不瞎猜。用户在「单价设置」里填了才开始计费。 */
  val UNKNOWN_PRICE = ModelPrice(0.0, 0.0, 0.0)

  /**
   * 参考单价(**元 / 百万 token**,缓存读取列对不支持缓存计费的服务商与输入同价)。
   *
   * 这些是写代码时的公开报价,服务商随时会调价,以各自计费页为准。
   * 用户在「Token 统计 → 单价」里改过的值会覆盖这里。
   */
  val DEFAULT_PRICES: Map<String, ModelPrice> = mapOf(
    // DeepSeek:输入(未命中)/ 输入(命中)/ 输出
    "deepseek-chat" to ModelPrice(2.0, 0.5, 8.0),
    "deepseek-reasoner" to ModelPrice(4.0, 1.0, 16.0),
    // 千问:没有单独的缓存命中价,命中与未命中同价
    "qwen-max" to ModelPrice(2.4, 2.4, 9.6),
    "qwen-plus" to ModelPrice(0.8, 0.8, 2.0),
    "qwen-turbo" to ModelPrice(0.3, 0.3, 0.6),
    "qwen3-max" to ModelPrice(2.4, 2.4, 9.6),
    "qwen3-coder-plus" to ModelPrice(4.0, 4.0, 16.0),
    // 小米 MiMo
    "mimo-v2.6-pro" to ModelPrice(2.0, 2.0, 8.0),
    "mimo-v2.6-flash" to ModelPrice(0.5, 0.5, 2.0),
  )

  /**
   * 一次调用的费用(人民币)。
   *
   * cost = (未缓存输入 × 输入价 + 缓存命中 × 缓存价 + 输出 × 输出价) / 一百万
   * 美元计价的模型再乘汇率。
   *
   * [cachedTokens] 会被收敛到 `0..promptTokens`:某些网关回的缓存数偶尔比输入总量还大,
   * 直接相减会得出负的「未缓存输入」,费用凭空变低。
   */
  fun costInCny(
    price: ModelPrice,
    promptTokens: Int,
    cachedTokens: Int,
    completionTokens: Int,
    usdToCny: Double = DEFAULT_USD_TO_CNY
  ): Double {
    val prompt = promptTokens.coerceAtLeast(0)
    val cached = cachedTokens.coerceIn(0, prompt)
    val uncached = prompt - cached
    val completion = completionTokens.coerceAtLeast(0)
    val raw = uncached / TOKENS_PER_UNIT * price.input +
      cached / TOKENS_PER_UNIT * price.cacheRead +
      completion / TOKENS_PER_UNIT * price.output
    return if (price.currency == CURRENCY_USD) raw * usdToCny.coerceAtLeast(0.0) else raw
  }
}

/**
 * 本地的价格配置(相当于 Web 版的 `price_config.json`)。
 *
 * [overrides] 只存用户改过的模型,没改过的走 [Pricing.DEFAULT_PRICES];
 * 两边都没有的模型单价为 0 —— 宁可显示 0 元,也不编一个不存在的价。
 *
 * 库里 `cost` 一律存人民币(计费口径不变),[displayCurrency] 只决定页面上怎么显示,
 * 切换币种不需要重算任何历史数据。
 */
data class PriceConfig(
  val usdToCny: Double = Pricing.DEFAULT_USD_TO_CNY,
  val displayCurrency: String = Pricing.CURRENCY_CNY,
  val overrides: Map<String, ModelPrice> = emptyMap()
) {
  fun priceOf(model: String): ModelPrice {
    val name = model.trim()
    return overrides[name] ?: Pricing.DEFAULT_PRICES[name] ?: Pricing.UNKNOWN_PRICE
  }

  fun withPrice(model: String, price: ModelPrice): PriceConfig =
    copy(overrides = overrides + (model.trim() to price))

  /** 人民币金额换成显示币种。汇率为 0 时原样返回,避免除零把页面刷成 Infinity。 */
  fun displayAmount(cny: Double): Double =
    if (displayCurrency == Pricing.CURRENCY_USD && usdToCny > 0.0) cny / usdToCny else cny

  val currencySymbol: String
    get() = if (displayCurrency == Pricing.CURRENCY_USD) "$" else "¥"

  /** 设置页要列出来的模型:内置预设 ∪ 流水里真实出现过的。 */
  fun knownModels(usedModels: List<String>): List<String> =
    (Pricing.DEFAULT_PRICES.keys + usedModels + overrides.keys).filter { it.isNotBlank() }.distinct()
}

/** 价格配置的读写与「按新单价重算历史」。 */
object PriceConfigStore {
  private const val PREFS = "price_config"
  private const val KEY_CONFIG = "config"

  @Volatile
  private var cached: PriceConfig? = null

  fun load(context: Context): PriceConfig {
    cached?.let { return it }
    val raw = prefsOf(context).getString(KEY_CONFIG, null)
    val parsed = if (raw == null) {
      PriceConfig()
    } else {
      runCatching { fromJson(JSONObject(raw)) }.getOrElse { PriceConfig() }
    }
    cached = parsed
    return parsed
  }

  fun save(context: Context, config: PriceConfig) {
    prefsOf(context).edit().putString(KEY_CONFIG, toJson(config).toString()).apply()
    cached = config
  }

  /** 某个模型的单价(用户改过的优先,其次内置参考价,最后 0)。 */
  fun priceOf(context: Context, model: String): ModelPrice = load(context).priceOf(model)

  /** 这次调用花了多少钱(人民币),写流水时用。 */
  fun costOf(
    context: Context,
    model: String,
    promptTokens: Int,
    cachedTokens: Int,
    completionTokens: Int
  ): Double {
    val config = load(context)
    return Pricing.costInCny(
      price = config.priceOf(model),
      promptTokens = promptTokens,
      cachedTokens = cachedTokens,
      completionTokens = completionTokens,
      usdToCny = config.usdToCny
    )
  }

  /**
   * 用当前单价把历史流水的费用全部重算一遍(只改 `cost`,token 数原样)。
   *
   * 单价改完让用户自己点「重算」,而不是保存就自动跑一遍:
   * 重算是全表扫描,流水多了以后不该在用户只是改个汇率时悄悄发生。
   */
  fun recompute(context: Context): Int {
    val config = load(context)
    val db = UsageDb.of(context).writableDatabase
    val rows = mutableListOf<Pair<Long, Double>>()
    db.rawQuery(
      "SELECT id, model_name, prompt_tokens, cached_tokens, completion_tokens FROM api_call_logs",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.getLong(0)
        val model = cursor.getString(1).orEmpty()
        val cost = Pricing.costInCny(
          price = config.priceOf(model),
          promptTokens = cursor.getInt(2),
          cachedTokens = cursor.getInt(3),
          completionTokens = cursor.getInt(4),
          usdToCny = config.usdToCny
        )
        rows.add(id to cost)
      }
    }
    if (rows.isEmpty()) return 0
    db.beginTransaction()
    try {
      rows.forEach { (id, cost) ->
        db.execSQL("UPDATE api_call_logs SET cost = ? WHERE id = ?", arrayOf<Any>(cost, id))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    return rows.size
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun toJson(config: PriceConfig): JSONObject = JSONObject().apply {
    put("usdToCny", config.usdToCny)
    put("displayCurrency", config.displayCurrency)
    val models = JSONObject()
    config.overrides.forEach { (name, price) ->
      models.put(name, JSONObject().apply {
        put("input", price.input)
        put("cacheRead", price.cacheRead)
        put("output", price.output)
        put("currency", price.currency)
      })
    }
    put("models", models)
  }

  private fun fromJson(obj: JSONObject): PriceConfig {
    val models = mutableMapOf<String, ModelPrice>()
    obj.optJSONObject("models")?.let { root ->
      root.keys().forEach { name ->
        val item = root.optJSONObject(name) ?: return@forEach
        models[name] = ModelPrice(
          input = item.optDouble("input", 0.0),
          cacheRead = item.optDouble("cacheRead", 0.0),
          output = item.optDouble("output", 0.0),
          currency = item.optString("currency").takeIf { it.isNotEmpty() } ?: Pricing.CURRENCY_CNY,
        )
      }
    }
    return PriceConfig(
      usdToCny = obj.optDouble("usdToCny", Pricing.DEFAULT_USD_TO_CNY),
      displayCurrency = obj.optString("displayCurrency")
        .takeIf { it == Pricing.CURRENCY_USD || it == Pricing.CURRENCY_CNY }
        ?: Pricing.CURRENCY_CNY,
      overrides = models
    )
  }
}
