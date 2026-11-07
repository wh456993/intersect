package com.reimei.intersect.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 开发者控制台里的一条消息;[error] 为 true 时界面按错误样式显示(也不参与后续请求的上下文)。 */
data class DevMsg(
  val role: String,
  val text: String,
  val error: Boolean = false,
  /** Self-insert 模式下由时间管理器注入的本地时间(精确到分钟);其余情况为 null。 */
  val time: String? = null,
)

/**
 * 一轮往返的完整记录:发出去的请求体(最终形态,含真实对话)与服务商的原始响应。
 *
 * 只存在内存里,给「报文」页逐轮查看用;不写盘(日志文件另有入口)。
 */
data class DevExchange(
  val index: Int,
  val time: String,
  val provider: String,
  val endpoint: String,
  val status: Int,
  val request: String,
  val response: String,
)

/**
 * 开发者控制台里的「角色」设定:昵称 / 身份 / 性格。
 *
 * 和主聊天里的角色身份页填的是同一类东西,只是这份只属于控制台,不写进通讯录。
 * 发请求时它会拼成一条 system 消息插进 messages(见 [DevRequestConfig.payload])。
 */
data class DevCharacter(
  val name: String = "",
  val role: String? = null,
  val personality: String? = null,
) {
  /** 三项全空等于没设角色:请求里就不加这条 system。 */
  val isBlank: Boolean
    get() = name.isBlank() && role.isNullOrBlank() && personality.isNullOrBlank()

  /** 拼成 system 消息的内容;没设角色时是空串。复用主聊天同一套拼法,两边措辞保持一致。 */
  fun systemPrompt(): String =
    if (isBlank) "" else ChatService.systemPrompt(name, role, personality)
}

/**
 * 一次对话请求的配置。
 *
 * 请求体不再拆成一个个字段,而是一整段 JSON([body]):界面上就是一个代码框,
 * 直接改整段文本,没有「只能点着改某一项」的限制。
 *
 * 发请求时只做两件事:
 * 1. 把 body 里的 `messages` 换成「body 里现成的 system 条目 + 角色设定 + 本轮的对话」;
 * 2. 把 JSON 文本原样 POST 到 [endpoint]。
 */
data class DevRequestConfig(
  val provider: String,
  val url: String,
  val body: String,
) {
  /** base_url 补成 chat/completions 的完整地址;已经写全的不再重复拼。 */
  fun endpoint(): String {
    val base = url.trim().trimEnd('/')
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
  }

  /** 从 body 里读模型名,给顶栏与日志用;读不到就显示占位。 */
  fun model(): String =
    runCatching { JSONObject(body).optString("model") }.getOrDefault("").ifEmpty { "未设置模型" }

  /**
   * 拼出真正要发的请求体文本。
   *
   * body 里自带的 `messages` 只保留 role=system 的条目(相当于系统提示),后面接
   * [character] 拼出的角色设定(没设就不加),再接本轮对话;
   * 其余字段原样保留,所以模型特有参数(如 MiMo 的 thinking)也能直接在代码框里写。
   * body 不是合法 JSON 对象时退化成「只有对话」的最小请求体。
   *
   * 角色设定排在 body 自带的 system 之后:代码框里写的是底子,角色是这一轮要演的那个人。
   */
  fun payload(messages: List<DevMsg>, character: DevCharacter = DevCharacter()): String {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
    val array = JSONArray()
    runCatching { root.optJSONArray("messages") }.getOrNull()?.let { original ->
      for (i in 0 until original.length()) {
        val item = original.optJSONObject(i) ?: continue
        if (item.optString("role") == "system") array.put(item)
      }
    }
    val persona = character.systemPrompt()
    if (persona.isNotEmpty()) {
      array.put(JSONObject().put("role", "system").put("content", persona))
    }
    messages.forEach { msg ->
      if (!msg.error && msg.text.isNotBlank()) {
        array.put(
          JSONObject()
            .put("role", msg.role)
            // Self-insert 的消息带着时间:和主聊天一致,拼成 "[yyyy-MM-dd HH:mm] 文本" 交给模型
            .put("content", msg.time?.let { "[$it] ${msg.text}" } ?: msg.text)
        )
      }
    }
    root.put("messages", array)
    return root.toString(2)
  }

  companion object {
    fun defaultFor(provider: ApiProvider): DevRequestConfig = DevRequestConfig(
      provider = provider.label,
      url = provider.url,
      body = provider.defaultBody.toString(2),
    )

    /** 默认停在第一个服务商(千问)上。 */
    fun default(): DevRequestConfig = defaultFor(ApiModels.PROVIDERS.first())
  }
}

/** 开发者控制台配置的本地存储:按服务商各存一份,另外记住上次用的那家。 */
object DevConsoleStore {
  private const val PREFS = "dev_console"
  private const val KEY_LAST_PROVIDER = "last_provider"
  private const val KEY_PREFIX = "cfg_"
  private const val KEY_CHAR_NAME = "character_name"
  private const val KEY_CHAR_ROLE = "character_role"
  private const val KEY_CHAR_PERSONALITY = "character_personality"

  /** 读取上次用的那套配置;没有记录或解析失败就回到默认值。 */
  fun load(context: Context): DevRequestConfig {
    val prefs = prefsOf(context)
    val provider = prefs.getString(KEY_LAST_PROVIDER, null)
      ?.let { label -> ApiModels.PROVIDERS.firstOrNull { it.label == label } }
      ?: ApiModels.PROVIDERS.first()
    val raw = prefs.getString(KEY_PREFIX + provider.label, null)
      ?: return DevRequestConfig.defaultFor(provider)
    return runCatching { fromJson(JSONObject(raw), provider) }
      .getOrElse { DevRequestConfig.defaultFor(provider) }
  }

  fun save(context: Context, config: DevRequestConfig) {
    prefsOf(context).edit()
      .putString(KEY_LAST_PROVIDER, config.provider)
      .putString(KEY_PREFIX + config.provider, toJson(config).toString())
      .apply()
  }

  /**
   * 读控制台的角色设定。
   *
   * 这一份是全局的,不按服务商各存一套:换模型调试时角色还是同一个人,没必要重填。
   */
  fun loadCharacter(context: Context): DevCharacter {
    val prefs = prefsOf(context)
    return DevCharacter(
      name = prefs.getString(KEY_CHAR_NAME, "").orEmpty(),
      role = prefs.getString(KEY_CHAR_ROLE, null),
      personality = prefs.getString(KEY_CHAR_PERSONALITY, null),
    )
  }

  fun saveCharacter(context: Context, character: DevCharacter) {
    prefsOf(context).edit()
      .putString(KEY_CHAR_NAME, character.name)
      .putString(KEY_CHAR_ROLE, character.role)
      .putString(KEY_CHAR_PERSONALITY, character.personality)
      .apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun toJson(config: DevRequestConfig): JSONObject = JSONObject().apply {
    put("provider", config.provider)
    put("url", config.url)
    put("body", config.body)
  }

  private fun fromJson(obj: JSONObject, provider: ApiProvider): DevRequestConfig {
    val fallback = DevRequestConfig.defaultFor(provider)
    return DevRequestConfig(
      provider = obj.optString("provider", provider.label),
      url = obj.optString("url", fallback.url),
      body = obj.optString("body", fallback.body).ifBlank { fallback.body },
    )
  }
}
