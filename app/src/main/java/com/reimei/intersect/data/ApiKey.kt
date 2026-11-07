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

/** 一家模型服务商:列表里按它分组,选中组内模型时顺手带出 [url]。 */
data class ApiProvider(
  val label: String,
  val url: String,
  val models: List<String>,
  /** 开发者控制台里这家默认的请求体;其中的 messages 只放 system 占位,真正的对话发送时再填。 */
  val defaultBody: org.json.JSONObject,
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
    ),
    ApiProvider(
      label = "DeepSeek",
      url = "https://api.deepseek.com/v1",
      models = listOf("deepseek-chat", "deepseek-reasoner"),
      defaultBody = body("deepseek-chat"),
    ),
    ApiProvider(
      label = "小米 MiMo",
      url = "https://api.xiaomimimo.com/v1",
      models = listOf("mimo-v2.6-pro", "mimo-v2.6-flash"),
      defaultBody = body("mimo-v2.6-pro"),
    ),
  )

  /** 三家内置的服务地址:用来判断 URL 还是「没被手动改过」的状态。 */
  val PRESET_URLS: List<String> = PROVIDERS.map { it.url }

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
