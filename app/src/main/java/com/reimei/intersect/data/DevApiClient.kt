package com.reimei.intersect.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次调用服务端回执里的用量,token 数都由服务商给出,本地只负责折算成钱(见 [Pricing])。
 *
 * [cachedTokens] 是输入里命中缓存的那部分(它已经包含在 [promptTokens] 里,不是额外的一份):
 * 计费时要先把这部分从输入里拆出来按缓存价算。
 */
data class ApiUsage(
  val promptTokens: Int,
  val completionTokens: Int,
  val cachedTokens: Int,
  val totalTokens: Int,
)

/** 开发者控制台一次请求的结果;[raw] 是服务商的原始响应体,原样展示在「报文」里。 */
sealed class DevApiResult {
  abstract val statusCode: Int
  abstract val raw: String

  data class Ok(
    val text: String,
    override val statusCode: Int,
    override val raw: String,
    /** 响应体里的 usage;服务商没回就是 null(消耗统计据此决定要不要记一条流水)。 */
    val usage: ApiUsage? = null,
  ) : DevApiResult()

  data class Fail(
    val message: String,
    override val statusCode: Int,
    override val raw: String,
  ) : DevApiResult()
}

/**
 * 极简的 OpenAI 协议客户端。
 *
 * 不引第三方库(HttpURLConnection 就够),也不做重试/流式:链路越直白越好,
 * 出错时把状态码与服务端原文带回去。
 *
 * 用在两处:开发者控制台的对话([com.reimei.intersect.ui.DevConsole]),
 * 以及 API KEY 管理页的「测试连接」([ApiProbe.test])。
 */
object DevApiClient {
  private const val CONNECT_TIMEOUT = 30_000
  private const val READ_TIMEOUT = 120_000

  suspend fun chat(
    apiKey: String,
    payload: String,
    endpoint: String,
    /** 拿到连接(以及结束时传 null)的通知:[ApiProbe] 用它来在取消/超时时把请求提前抡断。 */
    onConnection: (HttpURLConnection?) -> Unit = {},
    /**
     * 连接 / 读取阶段的 socket 超时。
     *
     * 默认值给开发者控制台用(愿意等模型)。[ApiProbe] 会传更短的连接上限:
     * 地址根本不可达时,等 30 秒才知道「连不上」太慢,占掉的还是整次探测的总时限。
     */
    connectTimeoutMs: Int = CONNECT_TIMEOUT,
    readTimeoutMs: Int = READ_TIMEOUT
  ): DevApiResult =
    withContext(Dispatchers.IO) {
      var conn: HttpURLConnection? = null
      try {
        conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = connectTimeoutMs
          readTimeout = readTimeoutMs
          doOutput = true
          setRequestProperty("Content-Type", "application/json; charset=utf-8")
          setRequestProperty("Accept", "application/json")
          if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        onConnection(conn)
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.let { input ->
          input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }.orEmpty()

        if (code !in 200..299) {
          return@withContext DevApiResult.Fail(errorMessage(raw, code), code, raw)
        }
        val obj = runCatching { JSONObject(raw) }.getOrNull()
          ?: return@withContext DevApiResult.Fail("响应不是合法 JSON:${raw.take(300)}", code, raw)
        val usage = usageOf(obj)
        val message = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        // 推理模型的正文可能只在 reasoning_content 里(未开启思维链展开时)
        val reasoning = message?.optString("reasoning_content").orEmpty()
        val text = content.ifBlank { reasoning }
        if (text.isBlank()) {
          DevApiResult.Fail("响应里没有正文:${raw.take(300)}", code, raw)
        } else {
          DevApiResult.Ok(text, code, raw, usage)
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        // 页面关掉时的取消要往上抛,不能当成「请求失败」吞掉
        throw e
      } catch (e: Exception) {
        // 同上:混淆后 simpleName 会变成 a.b,不作为面向用户的兜底文案
        DevApiResult.Fail(e.message ?: "请求失败(原因未知)", 0, "")
      } finally {
        conn?.disconnect()
        onConnection(null)
      }
    }

  /**
   * 从响应体里取 usage;取不到就返回 null(不编造 token 数)。
   *
   * 缓存命中数有两种写法:OpenAI 系的 `prompt_tokens_details.cached_tokens`,
   * 以及 DeepSeek 的 `prompt_cache_hit_tokens`,两种都认。
   * [ApiUsage.totalTokens] 缺失时退回 prompt + completion —— 有些网关就是不给这个字段。
   */
  internal fun usageOf(body: JSONObject): ApiUsage? {
    val usage = body.optJSONObject("usage") ?: return null
    val prompt = usage.optInt("prompt_tokens", 0)
    val completion = usage.optInt("completion_tokens", 0)
    val cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0)
      ?: usage.optInt("prompt_cache_hit_tokens", 0)
    val total = usage.optInt("total_tokens", prompt + completion)
    if (prompt == 0 && completion == 0 && total == 0) return null
    return ApiUsage(
      promptTokens = prompt.coerceAtLeast(0),
      completionTokens = completion.coerceAtLeast(0),
      cachedTokens = cached.coerceAtLeast(0),
      totalTokens = total.coerceAtLeast(0),
    )
  }

  /** 服务端错误体形如 {"error":{"message":"..."}},取不到就把原文截一段。 */
  internal fun errorMessage(raw: String, code: Int): String {
    val parsed = runCatching {
      JSONObject(raw).opt("error")?.let { error ->
        when (error) {
          is JSONObject -> error.optString("message")
          else -> error.toString()
        }
      }
    }.getOrNull()
    return if (!parsed.isNullOrBlank()) {
      "HTTP $code: $parsed"
    } else {
      "HTTP $code: ${raw.take(300).ifBlank { "没有响应体" }}"
    }
  }
}
