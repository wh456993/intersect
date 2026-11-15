package com.reimei.intersect.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 轻量记忆库:micro_summary / node_summary / rolling_meta 三张表,库文件 memory.db。
 *
 * 与向量库([com.reimei.intersect.search.VectorDb])同一套做法:进程内单例,破坏性迁移
 * (fallbackToDestructiveMigration)。本次重构**不做旧数据迁移** —— 新架构只对之后新生成的
 * 内容生效,旧结构(SharedPreferences 里的即时记忆 JSON)留在原地当只读兼容层。
 */
@Database(
  entities = [MicroSummaryEntity::class, NodeSummaryEntity::class, RollingMetaEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class MemoryDb : RoomDatabase() {

  abstract fun memory(): MemoryDao

  companion object {
    private const val DB_NAME = "memory.db"

    @Volatile
    private var instance: MemoryDb? = null

    fun getInstance(context: Context): MemoryDb =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          MemoryDb::class.java,
          DB_NAME,
        )
          .fallbackToDestructiveMigration()
          .build()
          .also { instance = it }
      }
  }
}
