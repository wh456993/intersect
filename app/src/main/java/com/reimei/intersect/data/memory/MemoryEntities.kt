package com.reimei.intersect.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.reimei.intersect.data.MetaInfo
import com.reimei.intersect.data.TimeManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆库(memory.db)的三张表。
 *
 * 这一层是**新的唯一写入方**:微总结 / 大总结 / 滚动状态都只写这里。
 * 旧的 `ImmediateMemoryState`(SharedPreferences 里的 JSON)降级为只读兼容层,
 * 继续供记忆库页、备份与既有单测读取,新数据不再往里写。
 *
 * 三张表的分工:
 * - [MicroSummaryEntity] 每 20 轮一条,带 AI 给的评分;
 * - [NodeSummaryEntity] 每 3 条微总结压一条主线大总结;
 * - [RollingMetaEntity] 每个角色一行,是请求体状态快照里元信息的来源。
 */

/** 微总结:覆盖 [fromRound]~[toRound] 这一批轮次的压缩正文,[score] 是 AI 给的 0~100 评分。 */
@Entity(
  tableName = "micro_summary",
  // 状态快照每轮都要查「最近 N 条」:没索引就是全表扫描 + 临时排序,行数随轮数线性增长
  indices = [Index(value = ["friend_id", "to_round"])],
)
data class MicroSummaryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "friend_id") val friendId: String,
  @ColumnInfo(name = "from_round") val fromRound: Int,
  @ColumnInfo(name = "to_round") val toRound: Int,
  @ColumnInfo(name = "text") val text: String,
  @ColumnInfo(name = "score") val score: Int,
  @ColumnInfo(name = "created_at") val createdAt: Long,
) {
  /** 列表里那一行「第 1-20 轮」。 */
  fun roundLabel(): String = if (fromRound == toRound) "第 $fromRound 轮" else "第 $fromRound-$toRound 轮"

  fun timeLabel(): String = TimeManager.formatMinute(createdAt)
}

/** 大总结:3 条微总结压缩出来的 10~50 词主线,覆盖同样的轮次范围。 */
@Entity(
  tableName = "node_summary",
  indices = [Index(value = ["friend_id", "to_round"])],
)
data class NodeSummaryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "friend_id") val friendId: String,
  @ColumnInfo(name = "from_round") val fromRound: Int,
  @ColumnInfo(name = "to_round") val toRound: Int,
  @ColumnInfo(name = "text") val text: String,
  @ColumnInfo(name = "created_at") val createdAt: Long,
) {
  /** 列表里那一行「第 1-60 轮」。 */
  fun roundLabel(): String = if (fromRound == toRound) "第 $fromRound 轮" else "第 $fromRound-$toRound 轮"

  fun timeLabel(): String = TimeManager.formatMinute(createdAt)
}

/**
 * 滚动状态:每个角色一行(主键就是 friend_id),是状态快照里元信息的落盘形态。
 *
 * 数值三项仍是 0~100 的语义(与 [MetaInfo] 一致),进请求体前一律经
 * [MetaInfo.block] 转成名词 —— 数字永远不进报文。
 * [others] 是 JSON 字符串,存该表没有专门列的那几项(关系 / 在场角色 / 天气 / Buff)。
 */
@Entity(tableName = "rolling_meta")
data class RollingMetaEntity(
  @PrimaryKey @ColumnInfo(name = "friend_id") val friendId: String,
  @ColumnInfo(name = "favorability") val favorability: Int,
  @ColumnInfo(name = "health") val health: Int,
  @ColumnInfo(name = "hunger") val hunger: Int,
  @ColumnInfo(name = "time_text") val timeText: String,
  @ColumnInfo(name = "location") val location: String,
  @ColumnInfo(name = "others") val others: String,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * [RollingMetaEntity] ↔ [MetaInfo] 的互转。
 *
 * 数值夹到 0~100,Buff 只留词表内的词条 —— 与 [MetaInfoStore] 读盘时同一套纪律,
 * 脏数据不许进请求体。用 `org.json`(平台自带),解析失败按「没登记」处理。
 */
object RollingMetaCodec {

  /** 用一份 [MetaInfo] 造行(首次落库 / 用户改完元信息时同步)。 */
  fun toEntity(meta: MetaInfo, now: Long = System.currentTimeMillis() / 1000L): RollingMetaEntity =
    RollingMetaEntity(
      friendId = meta.friendId,
      favorability = meta.favorability.coerceIn(0, 100),
      health = meta.health.coerceIn(0, 100),
      hunger = meta.hunger.coerceIn(0, 100),
      timeText = meta.time,
      location = meta.location,
      others = encodeOthers(meta),
      updatedAt = now,
    )

  /** 行 → 元信息。数值越界夹回来,Buff 只收词表内的。 */
  fun toMetaInfo(row: RollingMetaEntity): MetaInfo {
    val others = runCatching { JSONObject(row.others) }.getOrNull()
    val buffs = others?.optJSONArray("buffs")?.let { array ->
      (0 until array.length()).mapNotNull { array.optString(it) }
        .filter { it in MetaInfo.BUFF_VOCAB }
    }.orEmpty()
    return MetaInfo(
      friendId = row.friendId,
      favorability = row.favorability.coerceIn(0, 100),
      health = row.health.coerceIn(0, 100),
      hunger = row.hunger.coerceIn(0, 100),
      time = row.timeText,
      relationship = others?.optString("relationship").orEmpty(),
      location = row.location,
      characters = others?.optString("characters").orEmpty(),
      weather = others?.optString("weather").orEmpty(),
      buffs = buffs,
    )
  }

  /** 只改数值三项与时间 / 地点,其余字段沿用旧值(总结回来的状态就用这条路合并)。 */
  fun merge(
    row: RollingMetaEntity,
    favorability: Int? = null,
    health: Int? = null,
    hunger: Int? = null,
    timeText: String? = null,
    location: String? = null,
    now: Long = System.currentTimeMillis() / 1000L,
  ): RollingMetaEntity = row.copy(
    favorability = favorability?.coerceIn(0, 100) ?: row.favorability,
    health = health?.coerceIn(0, 100) ?: row.health,
    hunger = hunger?.coerceIn(0, 100) ?: row.hunger,
    timeText = timeText ?: row.timeText,
    location = location ?: row.location,
    updatedAt = now,
  )

  /** [MetaInfo] 里没有专门列的那几项,序列化成 JSON 字符串。 */
  fun encodeOthers(meta: MetaInfo): String = JSONObject().apply {
    put("relationship", meta.relationship)
    put("characters", meta.characters)
    put("weather", meta.weather)
    put("buffs", JSONArray().apply { meta.buffs.forEach { put(it) } })
  }.toString()
}
