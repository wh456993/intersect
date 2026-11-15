package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 总结记忆提示词(即时记忆层的 system prompt)的自定义开关与文本,**按角色隔离**。
 *
 * 默认用 [MemorySummarizer.DEFAULT_TEMPLATE];在**这个角色自己的聊天页 → 附件 → 记忆设置**里
 * 可以把它换成自己的版本(例如要求按时间线写、要求保留情绪变化)。文本里的 `{target}` /
 * `{min}` 在发请求时会被换成长度上限与固定下限(250 token),所以换模板之后滑块仍然起作用。
 *
 * 为什么按角色分(以前的实现是全局一份):
 * 这条规则挂在界面上是「这个角色的记忆怎么写」,用户在 A 的聊天里改它,不该把 B 的记忆
 * 口径一起改掉 —— 一旦全局,两个角色就会互相串味,而且改完还没地方看出是谁的规则。
 * 现在键名带上 friendId,读写都必须带角色 id,`template(context, friendId)` 里的 friendId
 * 由总结任务传入([MemoryJobs.run] 手上就是触发这次总结的那个角色)。
 *
 * 老版本存下的全局键(`enabled` / `prompt`)不再读取:那是一个全局值,分不清属于哪个角色,
 * 硬塞给所有角色等于让它们共享同一条自定义口径。升级后每个角色都从内置模板开始,
 * 各自写各自的。
 */
object MemoryPromptStore {
  private const val PREFS = "memory_prompt"
  private const val KEY_ENABLED_PREFIX = "enabled_"
  private const val KEY_PROMPT_PREFIX = "prompt_"

  /**
   * 模板长度上限。
   *
   * 不限制的话,一次超长粘贴会把请求的 `max_tokens` 预算吃满(模板本身也占上下文),
   * 表现为总结每次都超限、被压缩再截断。4000 个字符对「按时间线写 + 保留情绪变化」这种
   * 要求绰绰有余,界面上也有计数提示。
   */
  const val MAX_LENGTH = 4000

  private var prefs: SharedPreferences? = null

  fun init(context: Context) {
    if (prefs != null) return
    prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  }

  /** 这个角色是否在用自定义模板。从没设过 / 角色 id 为空时为 false(用内置模板)。 */
  fun enabled(context: Context, friendId: String): Boolean {
    if (friendId.isBlank()) return false
    return prefsOf(context).getBoolean(KEY_ENABLED_PREFIX + friendId, false)
  }

  /** 这个角色写的模板;空串表示还没写过。 */
  fun prompt(context: Context, friendId: String): String {
    if (friendId.isBlank()) return ""
    // 老版本存下的超长文本也要在读取时收进上限:否则编辑框一敲字就会从尾部剪掉一大段,
    // 而实际发出去的模板又是没收过的原文 —— 三处必须同源
    return cap(prefsOf(context).getString(KEY_PROMPT_PREFIX + friendId, "").orEmpty())
  }

  fun setEnabled(context: Context, friendId: String, value: Boolean) {
    if (friendId.isBlank()) return
    prefsOf(context).edit().putBoolean(KEY_ENABLED_PREFIX + friendId, value).apply()
  }

  fun setPrompt(context: Context, friendId: String, text: String) {
    if (friendId.isBlank()) return
    // 两边(编辑框与落盘)用同一个上限:粘贴进来就会被裁,不会出现「写进去的比看到的多」
    prefsOf(context).edit().putString(KEY_PROMPT_PREFIX + friendId, cap(text)).apply()
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
  fun draft(context: Context, friendId: String): String =
    cap(prompt(context, friendId).ifBlank { MemorySummarizer.DEFAULT_TEMPLATE })

  /**
   * 给某组开关 + 文本判断「实际生效的是不是自定义模板」。
   *
   * 界面与 [template] 用同一把尺子:开关开着但文本为空时,实际用的还是内置模板,
   * 界面上就不能写「当前:自定义」。
   */
  fun isCustom(enabled: Boolean, prompt: String): Boolean = enabled && prompt.isNotBlank()

  /** 这个角色实际生效的模板:开关关着、或者写空了,都回到内置模板。 */
  fun template(context: Context, friendId: String): String {
    val text = prompt(context, friendId)
    return if (isCustom(enabled(context, friendId), text)) cap(text) else MemorySummarizer.DEFAULT_TEMPLATE
  }

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
