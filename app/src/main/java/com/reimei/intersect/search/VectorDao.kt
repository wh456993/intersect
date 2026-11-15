package com.reimei.intersect.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** vectors 表的读写入口。检索打分在内存做(见 HybridSearchEngine),这里只负责存取。 */
@Dao
interface VectorDao {

  /** 写入/覆盖一条向量:同 id 重复写即更新(REPLACE),回填管线可安全重放。 */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(row: VectorEntity)

  /** 批量写入/覆盖,后台回填管线用。 */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(rows: List<VectorEntity>)

  @Query("SELECT COUNT(*) FROM vectors")
  suspend fun count(): Int

  /**
   * 全量读出。向量条目是「记忆」级别(几千条量级),一次读出来在内存做点积足够;
   * 数据量再大时改用 [loadBatch] 分块扫。
   */
  @Query("SELECT * FROM vectors")
  suspend fun loadAll(): List<VectorEntity>

  /** 分块读:LIMIT/OFFSET 翻页,避免低端机一次性反序列化太多 BLOB。 */
  @Query("SELECT * FROM vectors LIMIT :limit OFFSET :offset")
  suspend fun loadBatch(limit: Int, offset: Int): List<VectorEntity>

  /**
   * 盘上向量的最大模型版本;空表返回 null。
   * 启动时与当前 [EmbeddingEngine.MODEL_VERSION] 比较,不一致则全量重算。
   */
  @Query("SELECT MAX(model_version) FROM vectors")
  suspend fun maxModelVersion(): Int?

  /** 清空全部向量:模型版本迁移时用(旧向量与新模型的向量空间不可比,留着只会污染排序)。 */
  @Query("DELETE FROM vectors")
  suspend fun clearAll()

  @Query("DELETE FROM vectors WHERE id = :id")
  suspend fun deleteById(id: String)

  /** 批量删除陈旧行(源数据里已不存在的 id),回填管线收尾时用。 */
  @Query("DELETE FROM vectors WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<String>)
}
