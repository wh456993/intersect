package com.reimei.intersect.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把统计结果摊成一份**自描述的 JSON**,给用户的 AI 读。
 *
 * 刻意做成「机器优先」:键名、枚举值、说明全部英文,数值用毫秒时间戳 / 定点小数,
 * 不含任何只有界面才知道的格式化结果(千分位、K/M 缩写、货币符号)。
 * 唯一会带中文的是用户自己的数据本身 —— 密钥备注名、角色名,那是内容而不是格式。
 *
 * 生成过程是纯函数(除了取一句版本号),所以能在 instrumented test 里逐字段校验。
 */
object UsageExport {

  /** 导出格式的版本号:字段只增不改,增字段时 +1。 */
  const val SCHEMA_VERSION = 1

  const val SCHEMA = "intersect.usage.export"

  /**
   * 拼出导出内容。
   *
   * @param period 用户当前看的时间粒度,只写进 `query` 里说明这份数据是怎么切的,
   *   不改变 `daily`(永远是按天)与 `totals`(永远是区间合计)。
   * @param keyGroupLabel / windowLabel 把库里的 id 翻成可读名字,取不到时由调用方原样返回。
   */
  fun build(
    snapshot: UsageSnapshot,
    period: UsagePeriod,
    priceConfig: PriceConfig,
    appVersion: String,
    keyGroupLabel: (String) -> String = { it },
    windowLabel: (String) -> String = { it },
    exportedAtMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault()
  ): String {
    val root = JSONObject()

    root.put("schema", SCHEMA)
    root.put("schema_version", SCHEMA_VERSION)
    root.put("exported_at", isoTime(exportedAtMs, zone))
    root.put("exported_at_epoch_ms", exportedAtMs)
    root.put("timezone", zone.id)

    root.put(
      "app",
      JSONObject()
        .put("package", "com.reimei.intersect")
        .put("version_name", appVersion)
    )

    // ---- 这份数据是怎么筛出来的:AI 需要它才能正确解读下面的数字 ----
    val range = snapshot.filter.range
    root.put(
      "query",
      JSONObject()
        .put(
          "range",
          JSONObject()
            .put("preset_days", range.presetDays ?: JSONObject.NULL)
            .put("from_epoch_ms", range.fromMs ?: JSONObject.NULL)
            .put("to_epoch_ms", range.toMs ?: JSONObject.NULL)
        )
        .put(
          "filter",
          JSONObject()
            .put("dimension", snapshot.filter.dimension.name.lowercase(Locale.US))
            .put("value", snapshot.filter.value ?: JSONObject.NULL)
        )
        .put("display_period", period.name.lowercase(Locale.US))
        // 「使用时长」不是时钟时长,而是采样次数 × 采样周期,这里把周期告诉对方
        .put("foreground_sample_interval_ms", Heartbeat.SAMPLE_INTERVAL_MS)
    )

    // ---- 区间合计 ----
    val tokens = snapshot.split
    root.put(
      "totals",
      JSONObject()
        .put("requests", snapshot.requests)
        .put("cost_cny", round(snapshot.cost))
        .put(
          "tokens",
          JSONObject()
            // prompt = cached + uncached,三个数是同一层级的拆分,不要相加 prompt 与 total
            .put("prompt_cached", tokens.cached)
            .put("prompt_uncached", tokens.uncached)
            .put("prompt", tokens.cached + tokens.uncached)
            .put("completion", tokens.output)
            .put("total", tokens.total)
        )
        .put("cache_hit_rate", round(tokens.cacheRate, 4))
    )

    root.put("by_key_group", groupsJson(snapshot.byKeyGroup, keyGroupLabel))
    root.put("by_model", groupsJson(snapshot.byModel) { it })
    root.put("by_chat_window", groupsJson(snapshot.byWindow, windowLabel))

    // ---- 按天:永远是原始粒度,不随 period 折叠 ----
    val daily = JSONArray()
    snapshot.trend.forEach { day ->
      daily.put(
        JSONObject()
          .put("date", day.day)
          .put("requests", day.requests)
          .put("tokens", day.tokens)
          .put("cost_cny", round(day.cost))
      )
    }
    root.put("daily", daily)

    // ---- 活跃情况:连续天数是按「有前台采样的本地日期」算的 ----
    val activeDates = JSONArray()
    snapshot.activeDayIndexes.forEach { index ->
      activeDates.put(localDate(index, zone))
    }
    root.put(
      "activity",
      JSONObject()
        .put("active_days", snapshot.activeDayIndexes.size)
        .put("current_streak_days", snapshot.streak.current)
        .put("longest_streak_days", snapshot.streak.longest)
        .put("foreground_samples", snapshot.foregroundSamples)
        .put("foreground_estimated_ms", UsageQueries.foregroundDurationMs(snapshot.foregroundSamples))
        .put("active_dates", activeDates)
    )

    // ---- 每个密钥组(一条 API KEY)在这段时间的用量 ----
    val configs = JSONArray()
    snapshot.configs.forEach { config ->
      configs.put(
        JSONObject()
          .put("key_group_id", config.key)
          .put("label", keyGroupLabel(config.key))
          .put("requests", config.requests)
          .put("tokens", config.tokens)
          .put("cost_cny", round(config.cost))
          .put("last_used_at", if (config.lastUsedMs > 0) isoTime(config.lastUsedMs, zone) else JSONObject.NULL)
      )
    }
    root.put("configs", configs)

    // ---- 单价:费用是可以被复算的,把口径一起交出去 ----
    val models = JSONObject()
    (Pricing.DEFAULT_PRICES.keys + snapshot.byModel.map { it.key } + priceConfig.overrides.keys)
      .filter { it.isNotBlank() }
      .distinct()
      .forEach { name ->
        val price = priceConfig.priceOf(name)
        models.put(
          name,
          JSONObject()
            .put("input", price.input)
            .put("cache_read", price.cacheRead)
            .put("output", price.output)
            .put("currency", price.currency.lowercase(Locale.US))
        )
      }
    root.put(
      "pricing",
      JSONObject()
        .put("unit", "currency per 1_000_000 tokens")
        .put("cost_unit", "CNY")
        .put("usd_to_cny", priceConfig.usdToCny)
        .put("models", models)
    )

    root.put(
      "notes",
      JSONArray()
        .put("cost_cny = (prompt_uncached * input + prompt_cached * cache_read + completion * output) / 1e6, converted to CNY with usd_to_cny when the model is priced in USD.")
        .put("A model missing from pricing.models is priced 0, so its cost is 0 rather than an estimate.")
        .put("token counts come from the API response usage object; failed calls are not logged at all.")
        .put("daily contains local-time calendar days; activity.foreground_estimated_ms is samples * foreground_sample_interval_ms, an estimate, not wall-clock usage.")
        .put("label fields are user-provided names (API key nickname, character name) and are the only values that may be non-English.")
    )

    return root.toString(2)
  }

  private fun groupsJson(groups: List<UsageGroup>, label: (String) -> String): JSONArray {
    val array = JSONArray()
    groups.forEach { group ->
      array.put(
        JSONObject()
          .put("id", group.key)
          .put("label", label(group.key))
          .put("requests", group.requests)
          .put("tokens", group.tokens)
          .put("cached_tokens", group.cachedTokens)
          .put("cost_cny", round(group.cost))
      )
    }
    return array
  }

  /** 金额留 6 位小数:一次调用的费用常常在 1e-4 量级,四舍五入到分会把它们全抹成 0。 */
  private fun round(value: Double): Double = round(value, 6)

  private fun round(value: Double, digits: Int): Double {
    if (value.isNaN() || value.isInfinite()) return 0.0
    val factor = Math.pow(10.0, digits.toDouble())
    return Math.round(value * factor) / factor
  }

  private fun isoTime(epochMillis: Long, zone: TimeZone): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    format.timeZone = zone
    return format.format(Date(epochMillis))
  }

  private fun localDate(dayIndex: Long, zone: TimeZone): String {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    format.timeZone = zone
    return format.format(Date(UsageDays.localDayStart(dayIndex, zone)))
  }
}

/**
 * 导出文件的落盘。
 *
 * 和开发者日志一样:优先写到用户目录下的文件夹(有全部文件权限时,文件管理器里直接能看到),
 * 没有权限就退回本应用的外部目录 —— 那里不需要任何权限,路径会原样告诉用户。
 */
object UsageExportFiles {
  const val DIR_NAME = "intersect-usage"

  /** 真正写成功的那个文件。 */
  fun save(context: Context, content: String): File {
    for (dir in candidateDirs(context)) {
      val file = runCatching {
        dir.mkdirs()
        File(dir, fileName()).apply { writeText(content, Charsets.UTF_8) }
      }.getOrNull()
      if (file != null && file.length() > 0L) return file
    }
    error("没有可写的目录")
  }

  /** 应用版本号,写进导出文件里方便对方判断 schema 是否匹配。 */
  fun appVersion(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName.orEmpty()
  }.getOrDefault("")

  private fun candidateDirs(context: Context): List<File> {
    val dirs = mutableListOf<File>()
    @Suppress("DEPRECATION")
    val shared = Environment.getExternalStorageDirectory()
    if (shared != null && shared.canWrite()) dirs.add(File(shared, DIR_NAME))
    context.getExternalFilesDir(null)?.let { dirs.add(File(it, DIR_NAME)) }
    dirs.add(File(context.filesDir, DIR_NAME))
    return dirs
  }

  private fun fileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "usage-$stamp.json"
  }
}
