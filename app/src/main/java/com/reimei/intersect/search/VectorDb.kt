package com.reimei.intersect.search

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 向量库:混合检索的本地存储,只有一张 vectors 表。
 *
 * schema 版本升级采用破坏性迁移(fallbackToDestructiveMigration):表里存的向量随时
 * 可以从原文重算出来,不值得为它写迁移 SQL;真正的「版本迁移」按 [VectorEntity.modelVersion]
 * 做全量重算,与 schema 版本无关。
 */
@Database(entities = [VectorEntity::class], version = 1, exportSchema = false)
abstract class VectorDb : RoomDatabase() {

  abstract fun vectors(): VectorDao

  companion object {
    private const val DB_NAME = "hybrid_search.db"

    @Volatile
    private var instance: VectorDb? = null

    fun getInstance(context: Context): VectorDb =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          VectorDb::class.java,
          DB_NAME,
        )
          .fallbackToDestructiveMigration()
          .build()
          .also { instance = it }
      }
  }
}
