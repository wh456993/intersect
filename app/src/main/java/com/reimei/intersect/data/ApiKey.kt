package com.reimei.intersect.data

/**
 * 一条对话服务的 API KEY 配置。
 *
 * [name] 只用于在列表里认出这一条,[model] / [url] / [key] 是真正调用服务时需要的三项。
 * 全部只存在本机,不会上传。
 */
data class ApiKey(
  val id: String,
  val name: String,
  val model: String,
  val url: String,
  val key: String,
) {
  companion object {
    const val NAME_MAX = 20

    /** KEY 太短基本是误填,不给保存。 */
    const val KEY_MIN = 8

    /** 地址必须带协议头,否则调用时拼不出可用 URL。 */
    fun isUrlOk(url: String): Boolean =
      url.startsWith("http://") || url.startsWith("https://")
  }
}

/**
 * 编辑页的目标。
 *
 * [id] 为 null 表示新增;否则是正在修改的那条(列表里按 id 找原文)。
 */
data class ApiKeyEditTarget(val id: String?)

/**
 * 「深度思考 / 联网搜索」这类开关在某家服务商上的写法。
 *
 * 各家的做法不一样,所以这里两种形态都支持:
 * - **加一个请求体参数**:如千问的 `enable_thinking` / `enable_search`;
 * - **只能靠换模型**:如 DeepSeek 的 `deepseek-v4-pro` 就是思考模式,没有开关参数。
 *
 * [key] 与 [onModel] 都为 null 时表示这家没内置这个开关:界面上按钮置灰,
 * 想试只能自己在请求体里手写。服务商文档变了只改 [ApiModels] 那张表,界面跟着走。
 */
data class DevParamSpec(
  /** 请求体里的参数名;null 表示这家不靠参数开关。 */
  val key: String? = null,
  /** 打开时写进去的值(Boolean / String / JSONObject 都可以)。 */
  val on: Any? = null,
  /** 关闭时写进去的值;null 表示关闭时把这个参数整条删掉。 */
  val off: Any? = null,
  /** 打开时顺手切到的模型:靠换模型开关思考的服务商用这个。 */
  val onModel: String? = null,
  /** 关闭时顺手切回的模型。 */
  val offModel: String? = null,
) {
  /** 有没有内置这个开关;没有的话界面上的按钮置灰,只能手改请求体。 */
  val supported: Boolean
    get() = key != null || onModel != null
}

/** 一家模型服务商:列表里按它分组,选中组内模型时顺手带出 [url]。 */
data class ApiProvider(
  val label: String,
  val url: String,
  val models: List<String>,
  /** 开发者控制台里这家默认的请求体;其中的 messages 只放 system 占位,真正的对话发送时再填。 */
  val defaultBody: org.json.JSONObject,
  /** 深度思考开关在这家的写法;默认「没内置」。 */
  val thinking: DevParamSpec = DevParamSpec(),
  /** 联网搜索开关在这家的写法;默认「没内置」。 */
  val search: DevParamSpec = DevParamSpec(),
)

/**
 * 目前支持的三家服务商与各自的模型。
 *
 * 模型名就是调用时 model 字段要填的值;三家都兼容 OpenAI 协议,
 * 所以只有接口地址和模型名不同,KEY 各用各的。
 */
object ApiModels {
  val PROVIDERS = listOf(
    ApiProvider(
      label = "千问 Qwen",
      url = "https://dashscope.aliyuncs.com/compatible-mode/v1",
      models = listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen3-max", "qwen3-coder-plus"),
      defaultBody = body("qwen-max"),
      // 百炼的 OpenAI 兼容模式:思考模式与联网搜索都是请求体里的布尔开关
      thinking = DevParamSpec("enable_thinking", true, false),
      search = DevParamSpec("enable_search", true, false),
    ),
    ApiProvider(
      label = "DeepSeek",
      url = "https://api.deepseek.com/v1",
      models = listOf("deepseek-flash", "deepseek-v4-pro"),
      defaultBody = body("deepseek-flash"),
      // 这家的思考模式靠换模型:deepseek-v4-pro 会先思考再回答,没有开关参数
      thinking = DevParamSpec(onModel = "deepseek-v4-pro", offModel = "deepseek-flash"),
      // 官方 OpenAI 兼容接口没有联网搜索参数:按钮置灰,要试只能手写请求体
      search = DevParamSpec(),
    ),
    ApiProvider(
      label = "小米 MiMo",
      url = "https://api.xiaomimimo.com/v1",
      models = listOf("mimo-v2.6-pro", "mimo-v2.6-flash"),
      defaultBody = body("mimo-v2.6-pro"),
      // MiMo 的思考开关是 thinking.type = enabled / disabled
      thinking = DevParamSpec(
        key = "thinking",
        on = org.json.JSONObject().put("type", "enabled"),
        off = org.json.JSONObject().put("type", "disabled"),
      ),
      search = DevParamSpec(),
    ),
  )

  /** 三家内置的服务地址:用来判断 URL 还是「没被手动改过」的状态。 */
  val PRESET_URLS: List<String> = PROVIDERS.map { it.url }

  /** 按名字(label)找回一家服务商;找不到时回落到第一家——界面上「当前是哪家」总得有个归属。 */
  fun providerOf(label: String): ApiProvider =
    PROVIDERS.firstOrNull { it.label == label } ?: PROVIDERS.first()

  /**
   * 这个地址是不是某个内置服务商的地址(允许大小写与末尾斜杠不同)。
   *
   * 用判定「用户没主动填 URL」:真的手写了别的地址就不归内置预设管了。
   */
  fun isPresetUrl(url: String): Boolean {
    val target = url.trim().trimEnd('/')
    return PRESET_URLS.any { it.trimEnd('/').equals(target, ignoreCase = true) }
  }

  /** 按模型名找回它属于哪家;手填的模型名找不到时返回 null。 */
  fun urlFor(model: String): String? {
    val name = model.trim()
    return PROVIDERS.firstOrNull { provider ->
      provider.models.any { it.equals(name, ignoreCase = true) }
    }?.url
  }

  /** 默认请求体:三家都按 OpenAI 协议给一份最常用的参数组合。 */
  private fun body(model: String): org.json.JSONObject = org.json.JSONObject().apply {
    put("model", model)
    put("messages", org.json.JSONArray())
    put("temperature", 0.7)
    put("top_p", 0.8)
    put("max_tokens", 1024)
    put("stream", false)
  }
}
