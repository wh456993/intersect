package com.reimei.intersect.search

/**
 * AI 上下文构建用例:上层两个用例之一(另一个见 [SearchChatUseCase])。
 *
 * 职责:以「当前用户输入」为查询词,按 [metadataFilters](如 {"favorability":"72"})
 * 检索相关记忆,再用固定 Markdown 模板拼出一段上下文:
 *
 * ```
 * ## 系统状态
 * 当前时刻 14:05;场景:咖啡馆
 *
 * ## 相关记忆
 * 1. [memory] 昨天一起去了水族馆,她说很喜欢海豚......
 * 2. [chat] 「下次再一起出来玩吧」
 *
 * ## 用户输入
 * 明天有空吗?
 * ```
 *
 * 定位:纯本地拼装,产物是给上层(主聊天 / 开发者控制台)塞进 Prompt 的一段文本;
 * 与既有 ChatService 四段式(system → 未总结历史 → 记忆块 → 当前输入)互不干扰 ——
 * 本用例的输出怎么进请求体由调用方决定,这里只保证模板结构稳定。
 */
class BuildAIContextUseCase(
  private val engine: SearchEngine,
) {

  /**
   * 拼上下文。
   *
   * @param userInput 当前用户输入;既是模板末段的「用户输入」,也是检索的查询词
   * @param systemStatus 系统状态描述(时间/场景/世界状态等),空串时模板段保留但写「(无)」
   * @param metadataFilters 记忆过滤,如 {"favorability":"72"};null 不过滤
   * @param maxMemories 最多拼几条相关记忆(按 RRF 分数降序取)
   * @param maxCharsPerMemory 单条记忆的截断长度,超出部分以「......」收尾
   */
  suspend operator fun invoke(
    userInput: String,
    systemStatus: String = "",
    metadataFilters: Map<String, String>? = null,
    maxMemories: Int = 5,
    maxCharsPerMemory: Int = 240,
  ): String {
    val memories = engine.search(userInput, metadataFilters).take(maxMemories)
    return buildString {
      append(HEADER_STATUS)
      append('\n')
      append(systemStatus.trim().ifEmpty { "(无)" })

      append("\n\n")
      append(HEADER_MEMORIES)
      if (memories.isEmpty()) {
        append('\n')
        append("(暂无相关记忆)")
      } else {
        memories.forEachIndexed { index, hit ->
          append('\n')
          append(index + 1)
          append(". [")
          append(hit.sourceType)
          append("] ")
          append(oneLine(hit.content, maxCharsPerMemory))
        }
      }

      append("\n\n")
      append(HEADER_INPUT)
      append('\n')
      append(userInput.trim())
    }
  }

  /**
   * 记忆正文压成单行再截断:
   * 模板里一条记忆占一行,正文里的换行/连续空白全部折叠,避免把「## 用户输入」之类的
   * 结构行冲乱;超长截断加省略号,控制注入 token 量。
   */
  private fun oneLine(text: String, maxChars: Int): String {
    val flat = text.trim().replace(WHITESPACE, " ")
    return if (flat.length <= maxChars) flat else flat.substring(0, maxChars) + "......"
  }

  companion object {
    /** 模板三段的标题;固定措辞,便于上层按段解析或肉眼检查。 */
    const val HEADER_STATUS = "## 系统状态"
    const val HEADER_MEMORIES = "## 相关记忆"
    const val HEADER_INPUT = "## 用户输入"

    private val WHITESPACE = Regex("\\s+")
  }
}
