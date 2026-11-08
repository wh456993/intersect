package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 总结记忆提示词(即时记忆层的 system prompt)的自定义开关与文本。
 *
 * 默认用 [MemorySummarizer.DEFAULT_TEMPLATE];开发者模式下可以在记忆库页把它换成自己的版本
 * (例如要求按时间线写、要求保留情绪变化)。文本里的 `{target}` / `{min}` 在发请求时会被
 * 换成当前滑块的目标长度,所以换模板之后滑块仍然起作用。
 *
 * 全局一份(不按角色分):它是「怎么总结」的规则,与具体某个角色无关;
 * 想给单个角色换口径时改完总结完再改回来即可。
 */
object MemoryPromptStore {
  private const val PREFS = "memory_prompt"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_PROMPT = "prompt"

  /**
   * 模板长度上限。
   *
   * 不限制的话,一次超长粘贴会把请求的 `max_tokens` 预算吃满(模板本身也占上下文),
   * 表现为总结每次都超限、被压缩再截断。4000 个字符对「按时间线写 + 保留情绪变化」这种
   * 要求绰绰有余,界面上也有计数提示。
   */
  const val MAX_LENGTH = 4000

  /** 开关:关着时一律用内置模板(这样内置模板升级了能跟着变)。 */
  var enabled by mutableStateOf(false)
    private set

  /** 用户写的模板;空串表示还没写过。 */
  var prompt by mutableStateOf("")
    private set

  private var prefs: SharedPreferences? = null

  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    enabled = p.getBoolean(KEY_ENABLED, false)
    // 老版本存下的超长文本也要在读取时收进上限:否则编辑框一敲字就会从尾部剪掉一大段,
    // 而实际发出去的模板又是没收过的原文 —— 三处必须同源
    prompt = cap(p.getString(KEY_PROMPT, "").orEmpty())
  }

  fun setEnabled(context: Context, value: Boolean) {
    enabled = value
    prefsOf(context).edit().putBoolean(KEY_ENABLED, value).apply()
  }

  fun setPrompt(context: Context, text: String) {
    // 两边(编辑框与落盘)用同一个上限:粘贴进来就会被裁,不会出现「写进去的比看到的多」
    val capped = cap(text)
    prompt = capped
    prefsOf(context).edit().putString(KEY_PROMPT, capped).apply()
  }

  /**
   * 把模板收进 [MAX_LENGTH]。
   *
   * 不用 `take(MAX_LENGTH)`:它按 UTF-16 code unit 切,第 4000 个位置正好落在 emoji 的
   * 代理对中间时会留下一个不成对的 surrogate,写进 JSON 请求体后部分服务端会直接报错。
   * 遇到这种情况就少留一位。
   */
  fun cap(text: String): String {
    if (text.length <= MAX_LENGTH) return text
    val end = if (text[MAX_LENGTH - 1].isHighSurrogate() && text[MAX_LENGTH].isLowSurrogate()) {
      MAX_LENGTH - 1
    } else {
      MAX_LENGTH
    }
    return text.substring(0, end)
  }

  /** 编辑框里的初值:写过就用写过的,没写过就从内置模板开始改。 */
  fun draft(): String = cap(prompt.ifBlank { MemorySummarizer.DEFAULT_TEMPLATE })

  /**
   * 给某组开关 + 文本判断「实际生效的是不是自定义模板」。
   *
   * 界面与 [template] 用同一把尺子:开关开着但文本为空时,实际用的还是内置模板,
   * 界面上就不能写「当前:自定义」。
   */
  fun isCustom(enabled: Boolean, prompt: String): Boolean = enabled && prompt.isNotBlank()

  /** 实际生效的模板:开关关着、或者写空了,都回到内置模板。 */
  fun template(): String =
    if (isCustom(enabled, prompt)) cap(prompt) else MemorySummarizer.DEFAULT_TEMPLATE

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
