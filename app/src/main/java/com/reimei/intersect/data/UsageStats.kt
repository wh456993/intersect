package com.reimei.intersect.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.TreeSet

/** 统计的切分维度。「全部」不切分,但可以再带一个具体取值把范围收窄。 */
enum class UsageDimension(val label: String, val column: String?) {
  All("全部", null),
  KeyGroup("密钥组", "key_group_id"),
  Model("模型", "model_name"),
  Window("聊天窗口", "chat_window_id"),
}

/** 时间粒度:图表按天分桶、按周分桶,或画累计曲线。 */
enum class UsagePeriod(val label: String) {
  Day("每日"),
  Week("每周"),
  Total("累计"),
}

/**
 * 时间区间(统计页顶部的日期范围)。
 *
 * [fromMs] / [toMs] 为 null 表示该端不设限;[presetDays] 只用来显示「近 N 天」这样的标签,
 * 不参与查询。区间条件会同时作用到流水与心跳两张表(两边的列名都是 `timestamp`)。
 */
data class UsageRange(
  val fromMs: Long? = null,
  val toMs: Long? = null,
  val presetDays: Int? = null
) {
  val active: Boolean get() = fromMs != null || toMs != null

  /** 顶部显示的范围文本。 */
  val label: String get() = if (presetDays != null) "近 $presetDays 天" else "全部时间"

  companion object {
    val All = UsageRange()

    /** 含今天在内的最近 [days] 天,从那天本地零点算起。 */
    fun lastDays(days: Int, now: Long = System.currentTimeMillis()): UsageRange {
      val zone = TimeZone.getDefault()
      val startDay = UsageDays.today(now, zone) - (days - 1).coerceAtLeast(0)
      return UsageRange(
        fromMs = UsageDays.localDayStart(startDay, zone),
        presetDays = days
      )
    }
  }
}

/**
 * 筛选器:按哪个维度、取哪个值、跨哪段时间。
 *
 * 所有查询共用同一个实例,所以「改一下筛选条件」就等于把所有 SQL 重跑一遍。
 */
data class UsageFilter(
  val dimension: UsageDimension = UsageDimension.All,
  val value: String? = null,
  val range: UsageRange = UsageRange.All
) {
  val active: Boolean get() = dimension.column != null && !value.isNullOrBlank()

  private fun conditions(extra: List<String>): List<String> {
    val list = extra.toMutableList()
    val column = dimension.column
    if (column != null && !value.isNullOrBlank()) list.add("$column = ?")
    // 毫秒时间戳来自本地状态,直接拼进 SQL 是安全的;用字符串绑定反而会被当成文本比较
    range.fromMs?.let { list.add("timestamp >= $it") }
    range.toMs?.let { list.add("timestamp <= $it") }
    return list
  }

  /**
   * 只带时间区间的条件。
   *
   * 心跳表(user_heartbeats)只有 timestamp / app_state 两列,**没有**维度列,
   * 拿 [sqlWhere] 去查它会在「按密钥组筛选」时直接报 `no such column`。
   */
  fun rangeWhere(extra: List<String> = emptyList()): String {
    val list = extra.toMutableList()
    range.fromMs?.let { list.add("timestamp >= $it") }
    range.toMs?.let { list.add("timestamp <= $it") }
    return if (list.isEmpty()) "" else " WHERE " + list.joinToString(" AND ")
  }

  fun sqlWhere(extra: List<String> = emptyList()): String {
    val list = conditions(extra)
    return if (list.isEmpty()) "" else " WHERE " + list.joinToString(" AND ")
  }

  fun sqlArgs(): Array<String> = if (active) arrayOf(value!!) else emptyArray()
}

/** 一组聚合结果:某个密钥组 / 模型 / 窗口的累计用量。 */
data class UsageGroup(
  val key: String,
  val tokens: Long,
  val requests: Long,
  val cachedTokens: Long,
  val cost: Double
)

/** Token 构成(构成条形图数据源)。[uncached] 是「输入里没命中缓存的那部分」。 */
data class TokenSplit(val cached: Long, val uncached: Long, val output: Long) {
  val total: Long get() = cached + uncached + output

  /** 缓存命中率 = 命中 / (命中 + 未命中)。 */
  val cacheRate: Double get() = if (cached + uncached == 0L) 0.0 else cached.toDouble() / (cached + uncached)
}

/** 某一天的用量(SQL 按本地日期聚合的结果)。[dayIndex] 是这一天对应的天序号。 */
data class DayUsage(
  val day: String,
  val dayIndex: Long,
  val tokens: Long,
  val cost: Double,
  val requests: Long
) {
  /** 折线/柱状图的 x 轴标签:去掉年份,只留 MM-dd。 */
  val shortLabel: String get() = day.takeLast(5)
}

/**
 * 图表上的一个桶:一天、一周,或累计曲线上的某一天。
 *
 * [startDay] / [endDay] 是天序号,用来算「这条桶之前累计活跃了几天」。
 */
data class UsageBucket(
  val label: String,
  val startDay: Long,
  val endDay: Long,
  val tokens: Long,
  val cost: Double,
  val requests: Long
)

/** 连续使用天数。 */
data class UsageStreak(val current: Int, val longest: Int)

/** 配置详情里的一行:某个密钥组(一条 API KEY)在这段时间的用量。 */
data class ConfigUsage(
  val key: String,
  val requests: Long,
  val tokens: Long,
  val cost: Double,
  val lastUsedMs: Long
)

/** 一次筛选下的全部统计结果,统计页只读这一份快照。 */
data class UsageSnapshot(
  val filter: UsageFilter,
  val split: TokenSplit,
  val requests: Long,
  val cost: Double,
  val byKeyGroup: List<UsageGroup>,
  val byModel: List<UsageGroup>,
  val byWindow: List<UsageGroup>,
  val trend: List<DayUsage>,
  val streak: UsageStreak,
  val foregroundSamples: Long,
  /** 区间内有前台采样的天序号(升序),用来画活跃记录曲线。 */
  val activeDayIndexes: List<Long>,
  /** 区间内各个密钥组的用量,给「配置详情」用。 */
  val configs: List<ConfigUsage>
) {
  val isEmpty: Boolean get() = requests == 0L

  /** 区间内活跃过的天数。 */
  val activeDays: Int get() = activeDayIndexes.size
}

/**
 * 日期的纯计算:把时间戳折成「本地天序号」再比差值。
 *
 * 不用 java.time(minSdk 21 上没有);也不做 DST 精细处理 —— 统计口径是天,
 * 一年两次的一小时偏移不影响「今天有没有用」。
 */
object UsageDays {
  const val MILLIS_PER_DAY = 86_400_000L

  /** 本地天序号(同一天得到同一个数,相邻两天差 1)。 */
  fun dayIndex(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val local = epochMillis + zone.getOffset(epochMillis)
    return if (local >= 0) local / MILLIS_PER_DAY else (local - (MILLIS_PER_DAY - 1)) / MILLIS_PER_DAY
  }

  /** 今天的本地天序号。 */
  fun today(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Long =
    dayIndex(now, zone)

  /** 某个本地天序号的零点时间戳(近似:偏移按当天估算,DST 当天可能差一小时)。 */
  fun localDayStart(dayIndex: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val approx = dayIndex * MILLIS_PER_DAY
    return approx - zone.getOffset(approx)
  }

  /** `yyyy-MM-dd` → 天序号(按 UTC 解析,得到的是「这一天」这个数,与本地时区无关)。 */
  fun civilDayIndex(date: String): Long? = runCatching {
    dateFormatter().parse(date)?.time?.let { it / MILLIS_PER_DAY }
  }.getOrNull()

  /**
   * 由「活跃天序号列表(降序、去重)」算当前连续与历史最长连续。
   *
   * 今天还没用不算断:昨天有活跃,连续天数照样从那天往前往后数,
   * 只有「最近一次活跃早于昨天」才算断了(今天才过了一半,不该判人断签)。
   */
  fun streakOf(daysDesc: List<Long>, today: Long): UsageStreak {
    if (daysDesc.isEmpty()) return UsageStreak(0, 0)
    var longest = 1
    var run = 1
    for (index in 1 until daysDesc.size) {
      run = if (daysDesc[index - 1] - daysDesc[index] == 1L) run + 1 else 1
      if (run > longest) longest = run
    }
    val first = daysDesc.first()
    if (first != today && first != today - 1) return UsageStreak(0, longest)
    var current = 1
    for (index in 1 until daysDesc.size) {
      if (daysDesc[index - 1] - daysDesc[index] == 1L) current++ else break
    }
    return UsageStreak(current, longest)
  }

  private fun dateFormatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }
}

/**
 * 分桶:把「按天」的聚合结果折成图表要的序列。
 *
 * 纯计算,不碰数据库,所以按天 / 按周 / 累计三种粒度都由单元测试盯着。
 */
object UsageBuckets {
  /** 天序号 → 所在周的周一(1970-01-01 是周四,所以 +3 再取模把周一移到边界)。 */
  fun weekStart(dayIndex: Long): Long = dayIndex - Math.floorMod(dayIndex + 3L, 7L)

  fun of(trend: List<DayUsage>, period: UsagePeriod): List<UsageBucket> = when (period) {
    UsagePeriod.Day -> trend.map { day ->
      UsageBucket(day.shortLabel, day.dayIndex, day.dayIndex, day.tokens, day.cost, day.requests)
    }

    UsagePeriod.Week -> trend
      .groupBy { weekStart(it.dayIndex) }
      .toSortedMap()
      .map { (start, days) ->
        UsageBucket(
          label = days.first().shortLabel,
          startDay = start,
          endDay = start + 6,
          tokens = days.sumOf { it.tokens },
          cost = days.sumOf { it.cost },
          requests = days.sumOf { it.requests }
        )
      }

    UsagePeriod.Total -> {
      var tokens = 0L
      var cost = 0.0
      var requests = 0L
      trend.map { day ->
        tokens += day.tokens
        cost += day.cost
        requests += day.requests
        // 累计曲线:每个点都是「到这天为止」的合计,所以 x 轴仍是日期
        UsageBucket(day.shortLabel, day.dayIndex, day.dayIndex, tokens, cost, requests)
      }
    }
  }

  /**
   * 峰值。
   *
   * 按天 / 按周取桶里的最大值;累计曲线本身就是单调递增的,「峰值」没意义,
   * 于是退回日峰值 —— 用户想看的是「哪天用得最狠」。
   */
  fun peak(trend: List<DayUsage>, period: UsagePeriod): Long = when (period) {
    UsagePeriod.Week -> of(trend, UsagePeriod.Week).maxOfOrNull { it.tokens } ?: 0L
    else -> trend.maxOfOrNull { it.tokens } ?: 0L
  }

  /**
   * 活跃记录曲线:每个桶对应的「到这天为止累计活跃天数」。
   *
   * 只升不降,和签到式的连续记录一个形状:能直观看出哪天开始用、哪段时间荒了。
   */
  fun activeSeries(buckets: List<UsageBucket>, activeDays: Collection<Long>): List<Int> {
    if (buckets.isEmpty()) return emptyList()
    val sorted = activeDays.toSortedSet().toList()
    return buckets.map { bucket -> sorted.count { it <= bucket.endDay } }
  }
}

/**
 * 统计查询:全部在 SQL 里聚合,不在内存里捞全表。
 *
 * 每个方法都接受同一个 [UsageFilter],所以「筛选条件改一下、所有图表一起刷新」
 * 只是重新调一次 [load]。
 */
object UsageQueries {
  /** 一次把这页要的所有聚合做完;查询本身很轻(单表 + 索引),放在 IO 线程调用即可。 */
  fun load(context: Context, filter: UsageFilter, now: Long = System.currentTimeMillis()): UsageSnapshot {
    val db = UsageDb.of(context).readableDatabase
    return UsageSnapshot(
      filter = filter,
      split = tokenSplit(db, filter),
      requests = scalarLong(db, "SELECT COUNT(*) FROM api_call_logs" + filter.sqlWhere(), filter.sqlArgs()),
      cost = scalarDouble(db, "SELECT SUM(cost) FROM api_call_logs" + filter.sqlWhere(), filter.sqlArgs()),
      byKeyGroup = groups(db, UsageDimension.KeyGroup, filter),
      byModel = groups(db, UsageDimension.Model, filter),
      byWindow = groups(db, UsageDimension.Window, filter),
      trend = trend(db, filter),
      streak = streak(context, now),
      foregroundSamples = scalarLong(
        db,
        // 心跳表没有维度列:这里只能用年龄区间条件
        "SELECT COUNT(*) FROM user_heartbeats" + filter.rangeWhere(listOf("app_state = 'foreground'")),
        emptyArray()
      ),
      activeDayIndexes = activeDayIndexes(context, filter.range),
      configs = configs(db, filter)
    )
  }

  /** 筛选器下拉里可选的取值(该维度下真实出现过的,按用量降序)。 */
  fun options(context: Context, dimension: UsageDimension, range: UsageRange = UsageRange.All): List<String> {
    val column = dimension.column ?: return emptyList()
    val db = UsageDb.of(context).readableDatabase
    val filter = UsageFilter(range = range)
    val result = mutableListOf<String>()
    db.rawQuery(
      "SELECT $column, SUM(total_tokens) AS tokens FROM api_call_logs" +
        filter.sqlWhere(listOf("$column <> ''")) +
        " GROUP BY $column ORDER BY tokens DESC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) result.add(cursor.getString(0).orEmpty())
    }
    return result
  }

  /** 流水里出现过的模型名(单价设置页要按它列出来)。 */
  fun usedModels(context: Context): List<String> {
    val db = UsageDb.of(context).readableDatabase
    val result = mutableListOf<String>()
    db.rawQuery(
      "SELECT model_name, SUM(total_tokens) AS tokens FROM api_call_logs " +
        "WHERE model_name <> '' GROUP BY model_name ORDER BY tokens DESC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) result.add(cursor.getString(0).orEmpty())
    }
    return result
  }

  /** 有前台采样的本地日期(降序、去重),连续天数由 [UsageDays.streakOf] 算。 */
  fun activeDayIndexes(context: Context, range: UsageRange = UsageRange.All): List<Long> {
    val db = UsageDb.of(context).readableDatabase
    val zone = TimeZone.getDefault()
    val filter = UsageFilter(range = range)
    val days = TreeSet<Long>()
    db.rawQuery(
      "SELECT timestamp FROM user_heartbeats" + filter.rangeWhere(listOf("app_state = 'foreground'")) +
        " ORDER BY timestamp ASC",
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) days.add(UsageDays.dayIndex(cursor.getLong(0), zone))
    }
    return days.toList()
  }

  /** 前台采样数 × 10 分钟 = 估算的前台使用时长。 */
  fun foregroundDurationMs(samples: Long): Long = samples * Heartbeat.SAMPLE_INTERVAL_MS

  private fun streak(context: Context, now: Long): UsageStreak {
    // 连续天数看的是全部历史,不受区间筛选影响
    val days = activeDayIndexes(context, UsageRange.All).sortedDescending()
    return UsageDays.streakOf(days, UsageDays.today(now))
  }

  private fun groups(db: SQLiteDatabase, dimension: UsageDimension, filter: UsageFilter): List<UsageGroup> {
    val column = dimension.column ?: return emptyList()
    val result = mutableListOf<UsageGroup>()
    db.rawQuery(
      "SELECT $column, SUM(total_tokens) AS tokens, COUNT(*) AS requests, " +
        "SUM(cached_tokens) AS cached, SUM(cost) AS cost FROM api_call_logs" +
        filter.sqlWhere(listOf("$column <> ''")) +
        " GROUP BY $column ORDER BY tokens DESC",
      filter.sqlArgs()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        result.add(
          UsageGroup(
            key = cursor.getString(0).orEmpty(),
            tokens = cursor.getLong(1),
            requests = cursor.getLong(2),
            cachedTokens = cursor.getLong(3),
            cost = cursor.getDouble(4)
          )
        )
      }
    }
    return result
  }

  private fun configs(db: SQLiteDatabase, filter: UsageFilter): List<ConfigUsage> {
    val result = mutableListOf<ConfigUsage>()
    db.rawQuery(
      "SELECT key_group_id, COUNT(*) AS requests, SUM(total_tokens) AS tokens, " +
        "SUM(cost) AS cost, MAX(timestamp) AS last FROM api_call_logs" +
        filter.sqlWhere(listOf("key_group_id <> ''")) +
        " GROUP BY key_group_id ORDER BY tokens DESC",
      filter.sqlArgs()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        result.add(
          ConfigUsage(
            key = cursor.getString(0).orEmpty(),
            requests = cursor.getLong(1),
            tokens = cursor.getLong(2),
            cost = cursor.getDouble(3),
            lastUsedMs = cursor.getLong(4)
          )
        )
      }
    }
    return result
  }

  private fun tokenSplit(db: SQLiteDatabase, filter: UsageFilter): TokenSplit {
    db.rawQuery(
      // 用 CASE 而不是 MAX(a,b):后者在 SQLite 里是标量函数,和聚合函数 MAX 同名,
      // 写清楚条件既避免歧义,也顺手把「缓存数比输入总量还大」这种脏数据挡在 0 上
      "SELECT SUM(cached_tokens) AS cached, " +
        "SUM(CASE WHEN prompt_tokens > cached_tokens THEN prompt_tokens - cached_tokens ELSE 0 END) AS uncached, " +
        "SUM(completion_tokens) AS output FROM api_call_logs" + filter.sqlWhere(),
      filter.sqlArgs()
    ).use { cursor ->
      if (!cursor.moveToFirst()) return TokenSplit(0, 0, 0)
      return TokenSplit(
        cached = cursor.getLong(0),
        uncached = cursor.getLong(1),
        output = cursor.getLong(2)
      )
    }
  }

  private fun trend(db: SQLiteDatabase, filter: UsageFilter): List<DayUsage> {
    val result = mutableListOf<DayUsage>()
    db.rawQuery(
      "SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day, " +
        "SUM(total_tokens) AS tokens, SUM(cost) AS cost, COUNT(*) AS requests FROM api_call_logs" +
        filter.sqlWhere(listOf("timestamp > 0")) +
        " GROUP BY day ORDER BY day ASC",
      filter.sqlArgs()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val day = cursor.getString(0).orEmpty()
        // 天序号解析不出来就跳过这一天(理论上不可能,strftime 出来的必定是合法日期)
        val index = UsageDays.civilDayIndex(day) ?: continue
        result.add(
          DayUsage(
            day = day,
            dayIndex = index,
            tokens = cursor.getLong(1),
            cost = cursor.getDouble(2),
            requests = cursor.getLong(3)
          )
        )
      }
    }
    return result
  }

  private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>): Long =
    db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

  private fun scalarDouble(db: SQLiteDatabase, sql: String, args: Array<String>): Double =
    db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0 }
}
