package com.reimei.intersect.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 混合检索的向量条目:一条原文 + 它的向量 + 结构化状态标签。
 *
 * - [id] 用来源数据自己的稳定 id(如消息 id),重复写入即覆盖(REPLACE),天然幂等;
 * - [embedding] 是 LITTLE_ENDIAN 编码的 FloatArray(见 [toBytesLE]),已做 L2 归一化;
 * - [modelVersion] 标记产生该向量的模型版本:模型换了向量空间就变了,
 *   不同版本的向量不可比,App 启动时据此做全量重算(见后台索引管线);
 * - [metadataTags] 是 JSON 对象(如 {"favorability":"72"}),检索时按 key/value 过滤。
 */
@Entity(tableName = "vectors")
data class VectorEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "content") val content: String,
  @ColumnInfo(name = "embedding") val embedding: ByteArray,
  @ColumnInfo(name = "model_version") val modelVersion: Int,
  @ColumnInfo(name = "metadata_tags") val metadataTags: String,
)
