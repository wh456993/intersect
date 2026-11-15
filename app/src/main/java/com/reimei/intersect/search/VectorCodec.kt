package com.reimei.intersect.search

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 向量的字节序列化:[FloatArray] <-> [ByteArray],供 Room 以 BLOB 存储。
 *
 * 布局:第 i 个浮点数占据字节区间 [4i, 4i+4),总长 4 * size 字节;
 * 每个 float 按 IEEE-754 单精度编码(1 位符号 + 8 位指数 + 23 位尾数)。
 *
 * 字节序必须显式钉死为 LITTLE_ENDIAN,不能依赖默认值:
 * 1. ONNX Runtime 在 Android 支持的 ABI(arm64-v8a 等)上输出的张量是主机序,即小端;
 *    用 LITTLE_ENDIAN 写盘,与推理输出逐字节一致。
 * 2. java.nio.ByteBuffer 的默认序是 BIG_ENDIAN —— 不写死的话,存进 Room 再读回来的
 *    向量会每个浮点都被位翻转,余弦相似度直接失效,而且不报错、只是结果全错。
 *
 * 往返恒等: `arr.toBytesLE().toFloatsLE() contentEquals arr`(逐位相等,含 NaN/Inf)。
 */

/** 把向量编码为 LITTLE_ENDIAN 字节串。 */
fun FloatArray.toBytesLE(): ByteArray {
  // 布局公式:字节长度 = 4 * 向量维度
  val bytes = ByteArray(size * 4)
  val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  for (value in this) buffer.putFloat(value)
  return bytes
}

/** 把 [toBytesLE] 写出的字节串还原为向量;长度不是 4 的倍数说明数据损坏,直接拒绝。 */
fun ByteArray.toFloatsLE(): FloatArray {
  require(size % 4 == 0) { "向量字节长度必须是 4 的倍数,实际 ${size}" }
  val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  val out = FloatArray(size / 4)
  buffer.get(out)
  return out
}
