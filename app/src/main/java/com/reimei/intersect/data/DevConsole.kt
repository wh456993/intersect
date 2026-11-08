package com.reimei.intersect.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 开发者控制台里的一条消息;[error] 为 true 时界面按错误样式显示(也不参与后续请求的上下文)。
 *
 * 每条消息都单独带一份编码([at] + [fromUid] + [toUid]):按「发送方 UID v 接收方 UID:
 * 精确到秒的时间」的规则生成,例 `(1v0:2026-11-07 20:42:31)` 表示用户(UID 1)发给内置角色
 * intersect(UID 0)的一条。号来自 [UidStore]:intersect 固定 0、用户固定 1,与记忆索引同一套。
 * 编码只用于本地展示与留存,不进请求报文。
 */
data class DevMsg(
  val role: String,
  val text: String,
  val error: Boolean = false,
  /** Self-insert 模式下由时间管理器注入的本地时间(精确到分钟);其余情况为 null。 */
  val time: String? = null,
  /**
   * 这条消息的编码时间:发出那一刻的本地时间戳,单位秒(epoch second)。
   *
   * 0 表示「没有编码」——编码规则上线前存下的老消息,界面上就不显示那一行。
   */
  val at: Long = 0L,
  /** 编码里的发送方 UID:用户发的固定 [UidStore.ME_UID],模型回的是 [UidStore.INTERSECT_UID]。 */
  val fromUid: Long = UidStore.INTERSECT_UID,
  /** 编码里的接收方 UID。 */
  val toUid: Long = UidStore.ME_UID,
) {
  /** 界面上显示的那一行编码;没有编码时间时为 null(老消息)。 */
  fun code(): String? =
    if (at <= 0L) null else "(${fromUid}v${toUid}:${TimeManager.formatSecond(at)})"

  companion object {
    /** 用户发的一条:用户(UID 1)→ 内置角色 intersect(UID 0)。 */
    fun me(text: String, time: String? = null, error: Boolean = false): DevMsg = DevMsg(
      role = "user",
      text = text,
      error = error,
      time = time,
      at = nowSecond(),
      fromUid = UidStore.ME_UID,
      toUid = UidStore.INTERSECT_UID,
    )

    /** 模型回的一条:内置角色 intersect(UID 0)→ 用户(UID 1);出错时的提示也走这一侧。 */
    fun intersect(text: String, time: String? = null, error: Boolean = false): DevMsg = DevMsg(
      role = "assistant",
      text = text,
      error = error,
      time = time,
      at = nowSecond(),
      fromUid = UidStore.INTERSECT_UID,
      toUid = UidStore.ME_UID,
    )

    /** 编码用的时间戳:秒级,和 [MemoryStamp.storedAt] 同一套单位。 */
    private fun nowSecond(): Long = System.currentTimeMillis() / 1000L
  }
}

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
   * [character] 拼出的角色设定(没设就不加),再接对话;
   * 其余字段原样保留,所以模型特有参数(如 MiMo 的 thinking)也能直接在代码框里写。
   * body 不是合法 JSON 对象时退化成「只有对话」的最小请求体。
   *
   * 角色设定排在 body 自带的 system 之后:代码框里写的是底子,角色是这一轮要演的那个人。
   * 记忆使用规则接在角色设定后面,与主聊天([ChatService.systemWithMemory])同口径。
   *
   * [items] 是控制台自己的物品目录:和主聊天一样,用户消息里提到某件物品的名称时,
   * 就把它的名称与简介拼到话尾(见 [ChatService.injectItems]),模型才知道那是什么。
   *
   * [memory] 是这个角色(控制台用保留 id)的即时记忆状态:已总结的轮次不再注入原文,
   * 最近几条总结作为**单独一条 user 记忆块**插在当前输入之前 —— 与主聊天完全同一套规则,
   * 这样在控制台的「本次请求体」里看到的记忆注入就是真实发生的事情。
   */
  fun payload(
    messages: List<DevMsg>,
    character: DevCharacter = DevCharacter(),
    items: List<Item> = emptyList(),
    memory: ImmediateMemoryState? = null,
  ): String {
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
      array.put(
        JSONObject()
          .put("role", "system")
          .put("content", "$persona\n\n${MemoryInject.RULES}")
      )
    }
    // 只留「还没总结」的这几轮原文;已总结的由记忆块代替
    val usable = messages.filter { !it.error && it.text.isNotBlank() }
    val covered = (memory?.coveredRounds ?: 0).coerceIn(0, roundCount(usable))
    val kept = usable.drop(countMessages(usable, covered))
    // 当前输入单列:它必须排在记忆块之后
    val current = kept.lastOrNull()?.takeIf { it.role == "user" }
    val past = if (current == null) kept else kept.dropLast(1)
    past.forEach { msg -> array.put(messageOf(msg, items)) }

    val summaries = memory?.latest.orEmpty()
    if (summaries.isNotEmpty()) {
      array.put(
        JSONObject()
          .put("role", "user")
          .put("content", MemoryInject.block(summaries))
      )
    }

    current?.let { msg -> array.put(messageOf(msg, items)) }
    root.put("messages", array)
    return root.toString(2)
  }

  /** 一条消息转成请求里的那一项;用户消息先过物品注入,Self-insert 的消息带上时间。 */
  private fun messageOf(msg: DevMsg, items: List<Item>): JSONObject {
    val text = if (msg.role == "user") ChatService.injectItems(msg.text, items) else msg.text
    return JSONObject()
      .put("role", msg.role)
      .put("content", msg.time?.let { "[$it] $text" } ?: text)
  }

  /**
   * 这批消息按「一轮 = 用户一句 + 角色一句」能分成几轮。
   *
   * 控制台的聊天记录是 [DevMsg] 而不是 [Msg],但分组规则与主聊天一致(见 [MemoryRounds]).
   */
  fun roundCount(messages: List<DevMsg>): Int {
    var count = 0
    messages.forEach { msg ->
      if (msg.role == "user") count++ else if (count == 0) count = 1
    }
    return count
  }

  /** 前 [rounds] 轮一共占了多少条消息:用来跳过已总结的整轮。 */
  fun countMessages(messages: List<DevMsg>, rounds: Int): Int {
    if (rounds <= 0) return 0
    var seenRounds = 0
    messages.forEachIndexed { index, msg ->
      if (msg.role == "user") {
        seenRounds++
        if (seenRounds > rounds) return index
      }
    }
    return messages.size
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
  private const val KEY_MESSAGES = "messages"

  /** 控制台聊天最多留多少条:调试记录,超出丢最旧的,不让 SharedPreferences 无限涨。 */
  private const val MAX_STORED_MESSAGES = 200

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

  /**
   * 读上一次留下的控制台聊天记录;从没保存过时返回空列表。
   *
   * 以前这些消息只活在内存里,退出控制台就全没了(等于每次进来都从零开始调试);
   * 现在每加一条就落盘,退出页面 / 重启进程后接着聊。
   */
  fun loadMessages(context: Context): List<DevMsg> {
    val raw = prefsOf(context).getString(KEY_MESSAGES, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { msgFromJson(it) }
      }
    }.getOrElse {
      // 记录坏了就当没有:宁可从头开始,也不要让控制台打不开
      emptyList()
    }
  }

  /** 整体写盘,只留最近 [MAX_STORED_MESSAGES] 条;列表为空时顺手把旧记录删掉。 */
  fun saveMessages(context: Context, messages: List<DevMsg>) {
    if (messages.isEmpty()) {
      prefsOf(context).edit().remove(KEY_MESSAGES).apply()
      return
    }
    val array = JSONArray()
    messages.takeLast(MAX_STORED_MESSAGES).forEach { array.put(msgToJson(it)) }
    prefsOf(context).edit().putString(KEY_MESSAGES, array.toString()).apply()
  }

  /** 一条控制台消息的 JSON 表示:文本 / 错误标记 / 注入时间 + 它自己的编码。 */
  private fun msgToJson(msg: DevMsg): JSONObject = JSONObject().apply {
    put("role", msg.role)
    put("text", msg.text)
    put("error", msg.error)
    msg.time?.let { put("time", it) }
    put("at", msg.at)
    put("fromUid", msg.fromUid)
    put("toUid", msg.toUid)
  }

  /** 从 JSON 还原一条消息;role 缺失或不可解析时返回 null。 */
  private fun msgFromJson(obj: JSONObject): DevMsg? {
    val role = obj.optString("role")
    if (role.isEmpty()) return null
    return DevMsg(
      role = role,
      text = obj.optString("text"),
      error = obj.optBoolean("error", false),
      time = obj.optString("time").takeIf { it.isNotEmpty() },
      // 没有 at 的就是编码规则之前存下的(其实不会有,留个兜底):at=0 时界面不显示编码
      at = obj.optLong("at", 0L),
      fromUid = obj.optLong("fromUid", UidStore.INTERSECT_UID),
      toUid = obj.optLong("toUid", UidStore.ME_UID),
    )
  }

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
