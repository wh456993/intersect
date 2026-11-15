package com.reimei.intersect.data

/**
 * 角色代入专属的「世界观」四要素:法则体系 / 势力分布 / 经济架构 / 历史与重大事件。
 *
 * 它只做**本地记录与展示**:填了以后存在角色记录里(随 [FriendsStore] 与备份一起走),
 * 不进请求体 —— 对话的 system 仍只有名称 / 身份 / 性格三项(见 [ChatService.systemPrompt])。
 *
 * 四项都可以留空;四项都空时 [User.worldview] 存 null(老数据里也没有这一项)。
 */
data class Worldview(
  /** 法则体系:这个世界怎么运转(物理 / 魔法 / 契约 / 禁忌)。 */
  val laws: String? = null,
  /** 势力分布:谁在争什么、彼此什么关系。 */
  val factions: String? = null,
  /** 经济架构:钱、物、资源怎么流动与交换。 */
  val economy: String? = null,
  /** 历史与重大事件:时间线上绕不开的那几件事。 */
  val history: String? = null,
) {
  /** 四项全空 = 没填过,不落盘。 */
  val isEmpty: Boolean
    get() = parts.all { it.isNullOrBlank() }

  /** 填了几项:角色信息页那一行的副标题按它计数。 */
  val filledCount: Int
    get() = parts.count { !it.isNullOrBlank() }

  /** 四项按页面顺序排列(计数与序列化都走它,顺序只有这一处)。 */
  internal val parts: List<String?>
    get() = listOf(laws, factions, economy, history)

  companion object {
    /** 四项的标题,顺序与 [parts] 一致。 */
    val LABELS: List<String> = listOf("法则体系", "势力分布", "经济架构", "历史与重大事件")

    /** 四项填写的字数上限:一段话能说清,又不至于塞满整页。 */
    const val FIELD_MAX = 500
  }
}
