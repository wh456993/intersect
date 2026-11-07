package com.reimei.intersect.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** 拉取模型列表的结果;[models] 已经去重排序,空列表也算成功(说明接口通了但没返回模型)。 */
sealed class ApiModelListResult {
  data class Ok(val models: List<String>, val statusCode: Int) : ApiModelListResult()
  data class Fail(val message: String, val statusCode: Int) : ApiModelListResult()
}

/** 一次「测试连接」的结果;[ok] 只看 HTTP 状态码,响应没有正文也算连通。 */
data class ApiTestResult(
  val ok: Boolean,
  val statusCode: Int,
  val message: String,
)

/**
 * API KEY 管理页的探测工具:拉模型列表 + 测试连通性。
 *
 * 和 [DevApiClient] 一样走 OpenAI 兼容协议、只用 HttpURLConnection:
 * 地址与 KEY 都是用户刚填的,链路上不要有额外中间层,出错时把状态码与服务端原文带回去。
 *
 * 两个入口都可能被用户中途关掉页面打断,所以取消异常要原样抛出(不能当成「请求失败」吞掉)。
 */
object ApiProbe {
  /** 取消后显示的结论。 */
  const val CANCELLED = "已取消"

  /**
   * 拉模型的连接上限。
   *
   * 取 6 秒而不是 10 秒:GET 是幂等请求,HttpURLConnection 建连失败会自己重试一次,
   * 2×6 秒仍落在 20 秒总时限内 —— 这样能拿到真实的「连不上」原因,而不是干等到墙钟报一个笼统的超时。
   */
  private const val CONNECT_TIMEOUT = 6_000

  /** 要比 [LIST_TIMEOUT_MS] 小:socket 先报错,墙钟兜底,免得所有的「慢」都被说成「超时」。 */
  private const val READ_TIMEOUT = 15_000

  /**
   * 测试连接的连接上限。
   *
   * 必须小于 [TEST_TIMEOUT_MS]:地址不通时要在总时限内就说「连不上」,
   * 而不是把 3 秒全等成一句笼统的超时。
   */
  private const val TEST_CONNECT_TIMEOUT = 2_500

  /** 测试连接的读取上限:也压在总时限内,让 socket 先报错、墙钟傅底。 */
  private const val TEST_READ_TIMEOUT = 2_500

  /**
   * 整次探测的墙钟上限。
   *
   * 只靠 socket 的 connect/read 超时是不够的:HttpURLConnection 会对一个域名解析出的多个 IP
   * 逐个建连(失败还会整条重试一次),连不通的地址每个都要等到 connect 超时,一次请求能拖到好几分钟,
   * 界面上就是「一直转圈」。这里给一个总时限,到点必须给结论。
   *
   * 测试连接只发一句「请仅回复数字1」,该秒回:3 秒内拿不到结论就是没有结论,
   * 没必要让用户盯着按钮耗 45 秒。
   */
  private const val LIST_TIMEOUT_MS = 20_000L
  private const val TEST_TIMEOUT_MS = 3_000L

  /** 等结果时每 200ms 醒一次:既能守住总时限,也能让页面上的「取消」立刻生效。 */
  private const val POLL_MS = 200L

  /** 真正把请求发出去的线程。 */
  private val probeExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "api-probe").apply { isDaemon = true }
  }

  /**
   * 给一次探测套上墙钟硬上限,并让「取消」能真正掐断请求。
   *
   * 不用 withTimeout 是因为协程的取消是协作式的:请求卡在 socket 上时它不会提前返回,
   * 只会等阻塞调用自己结束 —— 那就是用户看到的「一直转圈」。所以把请求丢到独立线程,
   * 这里只管等结果 / 到点 / 被取消三件事。
   *
   * [abort] 用来把连接掐掉:阻塞在 socket 上的读线程,只有 disconnect 才能让它提前退出。
   */
  internal suspend fun <T> withDeadline(
    ms: Long,
    onTimeout: (Long) -> T,
    onError: (Exception) -> T,
    abort: () -> Unit = {},
    block: suspend () -> T
  ): T = withContext(Dispatchers.IO) {
    val future = probeExecutor.submit(Callable { runBlocking { block() } })
    try {
      await(future, ms, this, onTimeout, abort)
    } catch (e: InterruptedException) {
      future.cancel(true)
      abort()
      Thread.currentThread().interrupt()
      throw CancellationException(CANCELLED)
    } catch (e: ExecutionException) {
      when (val cause = e.cause) {
        // 页面关掉 / 取消:原样往上抛,不能当成「请求失败」
        is CancellationException -> throw cause
        is Exception -> onError(cause)
        else -> throw e
      }
    }
  }

  /** 一边等结果一边盯着两件事:总时限到了、或者页面把这次探测取消了。 */
  private fun <T> await(
    future: Future<T>,
    ms: Long,
    scope: CoroutineScope,
    onTimeout: (Long) -> T,
    abort: () -> Unit
  ): T {
    val start = System.currentTimeMillis()
    while (true) {
      try {
        return future.get(POLL_MS, TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        if (System.currentTimeMillis() - start >= ms) {
          future.cancel(true)
          abort()
          return onTimeout(ms)
        }
        if (!scope.isActive) {
          future.cancel(true)
          abort()
          throw CancellationException(CANCELLED)
        }
      }
    }
  }

  /** 掐断连接:被遗弃的请求不该继续占着 socket、流量和电量。 */
  private fun abortConnection(ref: AtomicReference<HttpURLConnection?>) {
    ref.getAndSet(null)?.let { conn -> runCatching { conn.disconnect() } }
  }

  /** 响应最多读这么多:地址是用户手填的,对方返回一大坨 HTML 时不能把内存全吃进去。 */
  private const val MAX_BODY = 512 * 1024

  /**
   * 测试时发给模型的那句话。
   *
   * 只要一个字:测试只为判断链路是否通,回执正文界面上一个字符都不展示,
   * 没必要让模型写一段话(既慢又多耗 token)。
   */
  const val TEST_PROMPT = "请仅回复数字1"

  /** 测试用的最小请求体:一句「请仅回复数字1」+ 极小的 max_tokens,只看链路通不通。 */
  fun testPayload(model: String): String = JSONObject().apply {
    put("model", model)
    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", TEST_PROMPT)))
    // 回执只需要一个「1」:留够 token 又逼模型别啰嗦
    put("max_tokens", 16)
    put("stream", false)
  }.toString()

  /** chat/completions 的完整地址:base_url 补全,已经写全的不再重复拼。 */
  fun chatEndpoint(url: String): String {
    val base = normalize(url)
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
  }

  /** GET /models 的地址:用户可能把接口路径写全了,先削掉再拼 /models。 */
  fun modelsEndpoint(url: String): String {
    var base = normalize(url)
    if (base.endsWith("/chat/completions")) {
      base = base.removeSuffix("/chat/completions")
    } else if (base.endsWith("/completions")) {
      base = base.removeSuffix("/completions")
    } else if (base.endsWith("/models")) {
      base = base.removeSuffix("/models")
    }
    return base.trimEnd('/') + "/models"
  }

  /** 发一句「请仅回复数字1」做连通性测试。2xx 就算通过,回执正文不带给界面。 */
  suspend fun test(apiKey: String, model: String, url: String): ApiTestResult {
    val payload = testPayload(model)
    val endpoint = chatEndpoint(url)
    val connRef = AtomicReference<HttpURLConnection?>(null)
    return withDeadline(
      ms = TEST_TIMEOUT_MS,
      onTimeout = { ms -> ApiTestResult(false, 0, slowMessage(ms)) },
      onError = { e -> ApiTestResult(false, 0, failureMessage(e, endpoint)) },
      abort = { abortConnection(connRef) }
    ) {
      val result = DevApiClient.chat(
        apiKey = apiKey,
        payload = payload,
        endpoint = endpoint,
        onConnection = { conn -> connRef.set(conn) },
        connectTimeoutMs = TEST_CONNECT_TIMEOUT,
        readTimeoutMs = TEST_READ_TIMEOUT
      )
      val ok = result.statusCode in 200..299
      ApiTestResult(ok, result.statusCode, failureDetail(result))
    }
  }

  /**
   * 拉取模型列表(GET /models)。
   *
   * 带查询参数或 # 的地址(Azure 那种)拼不出正确的 /models,直接说明而不是发一个必然失败的请求。
   */
  suspend fun listModels(apiKey: String, url: String): ApiModelListResult {
    val target = url.trim()
    if (target.contains('?') || target.contains('#')) {
      return ApiModelListResult.Fail("接口地址不要带 ? 或 # 参数,请只填到 /v1 这一级", 0)
    }
    val connRef = AtomicReference<HttpURLConnection?>(null)
    return withDeadline(
      ms = LIST_TIMEOUT_MS,
      onTimeout = { ms -> ApiModelListResult.Fail(timeoutMessage(ms), 0) },
      onError = { e -> ApiModelListResult.Fail(failureMessage(e, modelsEndpoint(target)), 0) },
      abort = { abortConnection(connRef) }
    ) {
      requestModels(apiKey, target, connRef)
    }
  }

  /** 真正发 GET /models 的那一段(阻塞式,跑在 probe 线程上)。 */
  private fun requestModels(
    apiKey: String,
    target: String,
    connRef: AtomicReference<HttpURLConnection?>
  ): ApiModelListResult {
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(modelsEndpoint(target)).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = CONNECT_TIMEOUT
        readTimeout = READ_TIMEOUT
        setRequestProperty("Accept", "application/json")
        if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
      }
      connRef.set(conn)
      val code = conn.responseCode
      val raw = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)

      if (code !in 200..299) {
        return ApiModelListResult.Fail(DevApiClient.errorMessage(raw, code), code)
      }
      if (raw.isBlank()) {
        return ApiModelListResult.Fail("接口返回了空响应(HTTP $code)", code)
      }
      val models = parseModels(raw)
        ?: return ApiModelListResult.Fail("响应不是可识别的模型列表:${raw.take(300)}", code)
      ApiModelListResult.Ok(models, code)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ApiModelListResult.Fail(failureMessage(e, modelsEndpoint(target)), 0)
    } finally {
      connRef.set(null)
      conn?.disconnect()
    }
  }

  /**
   * 把底层异常翻成用户能判断的一句话。
   *
   * 「一直转圈然后超时」和「地址根本连不上」要分得清:前者可能是模型慢,后者往往是地址写错、
   * 域名在这台设备的网络里不通,或者需要代理 —— 让用户看到 "connect timed out" 这种英文原文帮不上忙。
   */
  internal fun failureMessage(e: Exception, url: String): String {
    val host = runCatching { URL(url).host }.getOrNull().orEmpty()
    val who = host.ifEmpty { "该地址" }
    return when {
      e is java.net.UnknownHostException ->
        "解析不到域名 ${who}:检查地址拼写,或换个网络再试"

      e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
        "连不上 ${who}:这个地址在当前网络下不通,可能需要代理"

      // HttpURLConnection 连接阶段的超时 message 是 "connect timed out",读阶段是 "Read timed out"
      e is java.net.SocketTimeoutException &&
        e.message.orEmpty().startsWith("connect", ignoreCase = true) ->
        "连不上 ${who}:建连超时,该域名可能被网络拦截,或需要代理"

      e is java.net.SocketTimeoutException ->
        "已连上 ${who},但一直没有返回数据:模型可能太慢,或服务端在排队"

      // 别用 e.javaClass.simpleName 兜底:release 包开了混淆,类名会变成 a.b 这种,给用户看毫无意义
      else -> e.message ?: "请求失败(原因未知)"
    }
  }

  /** 拉模型超时:多半是地址不通。 */
  private fun timeoutMessage(ms: Long): String =
    "请求超时(${ms / 1000} 秒没有响应):地址可能不可达,或被网络/代理拦截"

  /** 测试连接超时:3 秒都没回,基本就是地址或网络不通,或者是模型排队/过载。 */
  private fun slowMessage(ms: Long): String =
    "${ms / 1000} 秒内没有返回:可能模型响应慢,也可能是地址或网络不通"

  /**
   * 失败时留给界面的那一句话。
   *
   * 成功一律返回空串:界面只显示「测试成功」,回执正文(模型说了什么)完全不展示。
   * 失败且服务端给了状态码时也返回空串 —— 状态码本身就够判断了,不必把响应体摊给用户。
   * 只有「连响应都没有」(连不上 / 超时)时才把原因带回去,否则用户无从下手。
   */
  private fun failureDetail(result: DevApiResult): String = when (result) {
    is DevApiResult.Ok -> ""
    is DevApiResult.Fail -> if (result.statusCode == 0) result.message else ""
  }

  private fun normalize(url: String): String = url.trim().trimEnd('/')

  /** 逐块读并卡住上限,避免服务端一次吐几 MB 进来。 */
  private fun readBody(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.use { input ->
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(8 * 1024)
      var total = 0
      while (total < MAX_BODY) {
        val read = input.read(buffer)
        if (read <= 0) break
        out.write(buffer, 0, minOf(read, MAX_BODY - total))
        total += read
      }
      String(out.toByteArray(), Charsets.UTF_8)
    }
  }

  /**
   * 从响应体里取模型名。
   *
   * 标准形状是 `{"data":[{"id":"qwen-max"}, ...]}`,也有服务商直接回一个数组,
   * 两种都认;数组里是字符串的也取出来。解析不出结构时返回 null。
   */
  private fun parseModels(raw: String): List<String>? {
    val array = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
      ?: runCatching { JSONArray(raw) }.getOrNull()
      ?: return null
    val names = mutableListOf<String>()
    for (i in 0 until array.length()) {
      val name = when (val item = array.opt(i)) {
        is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
        is String -> item
        else -> ""
      }
      if (name.isNotBlank()) names.add(name)
    }
    return names.distinct().sorted()
  }
}
