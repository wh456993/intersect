package com.reimei.intersect.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * memory.db 的读写入口。全部是 suspend:调用方一律在 IO 上跑(Room 自己切线程),
 * 主线程拿不到阻塞句柄,发消息那条高频路径不会被数据库顿住。
 *
 * 只实现这套轻量架构真正用到的查询 —— 不做 task 表、不做租约、不做内容哈希。
 */
@Dao
interface MemoryDao {

  // —— 微总结 ——

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMicro(row: MicroSummaryEntity): Long

  /** 最近 N 条微总结(新的在前)。状态快照里取 3 条,流水线判断「够不够 3 条」也用它。 */
  @Query(
    "SELECT * FROM micro_summary WHERE friend_id = :friendId " +
      "ORDER BY to_round DESC, id DESC LIMIT :limit"
  )
  suspend fun recentMicro(friendId: String, limit: Int): List<MicroSummaryEntity>

  /** 全部微总结(旧的在前),给记忆库页/调试用。 */
  @Query("SELECT * FROM micro_summary WHERE friend_id = :friendId ORDER BY from_round ASC, id ASC")
  suspend fun allMicro(friendId: String): List<MicroSummaryEntity>

  /**
   * 还没并进大总结的微总结(旧的在前,最多 [limit] 条)。
   *
   * [nodedToRound] 是已并进大总结的水位(最新大总结的 to_round):只取水位之后的,
   * 所以每 3 条微总结只会被压缩一次,不会每次都在滑动窗口里重复压同几段。
   */
  @Query(
    "SELECT * FROM micro_summary WHERE friend_id = :friendId AND from_round > :nodedToRound " +
      "ORDER BY from_round ASC, id ASC LIMIT :limit"
  )
  suspend fun pendingMicro(friendId: String, nodedToRound: Int, limit: Int): List<MicroSummaryEntity>

  @Query("SELECT COUNT(*) FROM micro_summary WHERE friend_id = :friendId")
  suspend fun microCount(friendId: String): Int

  /** 删消息 / 回滚之后,把覆盖范围超出当前轮数的微总结清掉(它们描述的是已经不存在的对话)。 */
  @Query("DELETE FROM micro_summary WHERE friend_id = :friendId AND to_round > :rounds")
  suspend fun deleteMicroBeyond(friendId: String, rounds: Int)

  // —— 大总结 ——

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNode(row: NodeSummaryEntity): Long

  /** 最新 1 条大总结(状态快照只用这一条)。 */
  @Query(
    "SELECT * FROM node_summary WHERE friend_id = :friendId " +
      "ORDER BY to_round DESC, id DESC LIMIT 1"
  )
  suspend fun latestNode(friendId: String): NodeSummaryEntity?

  /** 这个轮次区间是否已经有大总结了(3 条微总结只该压出一条,重复触发时据此跳过)。 */
  @Query(
    "SELECT COUNT(*) FROM node_summary " +
      "WHERE friend_id = :friendId AND from_round = :fromRound AND to_round = :toRound"
  )
  suspend fun nodeCount(friendId: String, fromRound: Int, toRound: Int): Int

  /** 与 [deleteMicroBeyond] 同理:回滚之后超出现有轮数的大总结不该再注入。 */
  @Query("DELETE FROM node_summary WHERE friend_id = :friendId AND to_round > :rounds")
  suspend fun deleteNodeBeyond(friendId: String, rounds: Int)

  /** 这个角色的全部大总结(旧的在前),给「清洗脏大总结」扫描用。 */
  @Query("SELECT * FROM node_summary WHERE friend_id = :friendId ORDER BY from_round ASC, id ASC")
  suspend fun allNode(friendId: String): List<NodeSummaryEntity>

  /** 就地重写一条大总结的正文:轮次区间与生成时间都保持原样(它描述的还是那一段剧情)。 */
  @Query("UPDATE node_summary SET text = :text WHERE id = :id")
  suspend fun updateNodeText(id: Long, text: String)

  /** 清洗后仍不可用的那一行直接删掉(留着会一直污染后面每一轮的请求体)。 */
  @Query("DELETE FROM node_summary WHERE id = :id")
  suspend fun deleteNodeById(id: Long)

  // —— 滚动状态 ——

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertRollingMeta(row: RollingMetaEntity)

  @Query("SELECT * FROM rolling_meta WHERE friend_id = :friendId")
  suspend fun rollingMeta(friendId: String): RollingMetaEntity?

  // —— 清理 ——

  @Query("DELETE FROM micro_summary WHERE friend_id = :friendId")
  suspend fun deleteMicro(friendId: String)

  @Query("DELETE FROM node_summary WHERE friend_id = :friendId")
  suspend fun deleteNode(friendId: String)

  @Query("DELETE FROM rolling_meta WHERE friend_id = :friendId")
  suspend fun deleteRollingMeta(friendId: String)
}
