package com.reimei.intersect.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地向量化引擎:单例持有 OrtEnvironment/OrtSession,把文本变成 L2 归一化的 384 维向量。
 *
 * 模型:assets/models/bge-micro-v2-int8.onnx(INT8 量化,~17MB,随 APK 打包,纯离线)。
 * 输入张量:input_ids / attention_mask / token_type_ids [1, seq];
 * 输出张量:last_hidden_state [1, seq, 384]。
 * 池化:Mean Pooling(与模型卡 SentenceTransformers 配置 pooling_mode_mean_tokens=True 一致):
 *
 *   pooled_j = Σ_t (h_tj · m_t) / max(Σ_t m_t, ε)      (ε = 1e-9,防全零掩码除零)
 *
 * 归一化:L2 归一化使向量落在单位球面 ‖v‖2 = 1,此后点积即余弦相似度:
 *
 *   v̂ = v / max(‖v‖2, 1e-12),  ‖v‖2 = sqrt(Σ_j v_j2)
 *   cos(a, b) = (a·b) / (‖a‖‖b‖) = â·b̂      (两向量均归一化后)
 *
 * 红线:
 * 1. 初始化/推理任何异常都必须捕获,静默降级为"仅字面检索"([embed] 返回 null),严禁崩溃;
 * 2. session.run() 用 [inferMutex] 串行化 —— 并发推理会成倍放大 native 内存峰值,
 *    低内存设备上直接 OOM;串行后单次峰值 = 单条推理的内存;
 * 3. 分词/推理/向量运算一律在 Dispatchers.IO 或 Default,严禁主线程。
 */
object EmbeddingEngine {

  private const val TAG = "EmbeddingEngine"

  /** 当前模型版本号,写进 [VectorEntity.modelVersion];换模型必须 +1(触发全量重算)。 */
  const val MODEL_VERSION = 1

  private const val MODEL_ASSET = "models/bge-micro-v2-int8.onnx"

  /** bge-micro-v2 输出维度(sentence-transformers: 384 维稠密向量)。 */
  const val DIM = 384

  private val initMutex = Mutex()
  private val inferMutex = Mutex()

  @Volatile private var env: OrtEnvironment? = null
  @Volatile private var session: OrtSession? = null
  @Volatile private var tokenizer: WordPieceTokenizer? = null

  /** 任一步失败即 false:调用方据此跳过向量路,只走字面检索。 */
  @Volatile var isAvailable: Boolean = false
    private set

  /**
   * 初始化(幂等):加载词表、创建 OrtSession。失败只置 isAvailable=false,不抛出。
   * 在 Dispatchers.IO 执行(读 17MB 资产 + native 会话创建都是 I/O/重操作)。
   */
  suspend fun init(context: Context) {
    if (isAvailable) return
    initMutex.withLock {
      if (isAvailable) return
      try {
        withContext(Dispatchers.IO) {
          val tok = WordPieceTokenizer.loadFromAssets(context)
          val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
          val environment = OrtEnvironment.getEnvironment()
          // intra-op 线程限 2:够用且不吃满 CPU/内存,手机上大线程池只会互相抢核
          val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
          val s = environment.createSession(modelBytes, options)
          options.close()
          tokenizer = tok
          env = environment
          session = s
          isAvailable = true
          Log.i(TAG, "init ok: modelVersion=$MODEL_VERSION dim=$DIM")
        }
      } catch (t: Throwable) {
        // 红线:初始化异常一律吞掉,降级为仅字面检索
        Log.w(TAG, "init failed, degrade to literal-only search: ${t.javaClass.simpleName}")
        isAvailable = false
      }
    }
  }

  /**
   * 文本向量化(已 L2 归一化,点积即余弦)。
   * 返回 null 表示引擎不可用或推理失败 —— 调用方必须降级为仅字面检索,不得崩溃。
   */
  suspend fun embed(text: String): FloatArray? {
    val tok = tokenizer ?: return null
    val s = session ?: return null
    val e = env ?: return null
    return try {
      // 1) 分词(CPU 密集,Default)
      val input = withContext(Dispatchers.Default) { tok.encode(text) }

      // 2) 推理(阻塞 + native 内存密集,IO;Mutex 串行防并发 OOM)
      val hidden: Array<FloatArray> = withContext(Dispatchers.IO) {
        inferMutex.withLock {
          // 形状 [1, seq]:包一层单样本维度
          OnnxTensor.createTensor(e, arrayOf(input.inputIds)).use { idsTensor ->
            OnnxTensor.createTensor(e, arrayOf(input.attentionMask)).use { maskTensor ->
              OnnxTensor.createTensor(e, arrayOf(input.tokenTypeIds)).use { typesTensor ->
                val feeds = mapOf(
                  "input_ids" to idsTensor,
                  "attention_mask" to maskTensor,
                  "token_type_ids" to typesTensor,
                )
                s.run(feeds).use { result ->
                  // last_hidden_state: float32 [1, seq, DIM] → 取掉 batch 维
                  @Suppress("UNCHECKED_CAST")
                  val batch = result[0].value as Array<Array<FloatArray>>
                  batch[0]
                }
              }
            }
          }
        }
      }

      // 3) Mean Pooling + L2 归一化(CPU 密集,Default)
      withContext(Dispatchers.Default) {
        meanPoolThenNormalize(hidden, input.attentionMask)
      }
    } catch (t: Throwable) {
      // 红线:推理异常一律吞掉,返回 null 由调用方降级
      Log.w(TAG, "embed failed, degrade to literal-only search: ${t.javaClass.simpleName}")
      null
    }
  }

  /**
   * Mean Pooling + L2 归一化,公式见类注释。
   * @param tokens last_hidden_state 去掉 batch 维:tokens[t] 是第 t 个 token 的 DIM 维向量
   * @param mask attention_mask,1=真实 token,0=padding(单句推理恒全 1,仍写通用实现)
   */
  private fun meanPoolThenNormalize(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
    val dim = tokens.firstOrNull()?.size ?: return FloatArray(0)
    val pooled = FloatArray(dim)
    var maskSum = 0.0
    for (t in tokens.indices) {
      val m = if (t < mask.size) mask[t].toFloat() else 0f
      if (m == 0f) continue
      maskSum += m
      val vec = tokens[t]
      for (j in 0 until dim) pooled[j] += vec[j] * m
    }
    // max(Σ m, ε) 防除零:ε=1e-9
    val denom = maxOf(maskSum, 1e-9).toFloat()
    for (j in 0 until dim) pooled[j] /= denom

    // L2 归一化:v̂ = v / max(‖v‖2, 1e-12)
    var sumSq = 0.0
    for (j in 0 until dim) sumSq += pooled[j].toDouble() * pooled[j]
    val norm = maxOf(Math.sqrt(sumSq), 1e-12).toFloat()
    for (j in 0 until dim) pooled[j] /= norm
    return pooled
  }
}
