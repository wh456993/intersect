package com.reimei.intersect.data

/**
 * 模型 ID 与界面上那个名字的对照。
 *
 * 接口只认严格的模型 ID(如 `deepseek-flash`),不接受带空格、带大写字母的展示名;
 * 但 ID 摆在界面上不好认。所以两边分开:存下来、发出去的一律是 ID,界面显示走 [display]。
 *
 * 特意不放在 [ApiModels] 里:那个对象初始化时要建 JSON(org.json 在纯 JVM 单测里跑不了),
 * 而单价计算是纯计算、要有单测,这张表必须零依赖。
 */
object ModelNames {
  /** 模型 ID → 界面上的名字;没有对应名字的模型界面上直接显示 ID。 */
  private val ID_TO_DISPLAY: Map<String, String> = mapOf(
    "deepseek-flash" to "DeepSeek V4.1 Flash",
    "deepseek-v4-pro" to "DeepSeek V4 Pro",
  )

  /**
   * 老名字 → 现在的模型 ID(键一律小写比对)。
   *
   * 含两批:更早的官方叫法(`deepseek-chat` / `deepseek-reasoner`),
   * 以及曾经误当成模型 ID 存下来的展示名 —— 都有用户存过,读出来时顺手换回 ID。
   */
  private val ALIASES: Map<String, String> = mapOf(
    "deepseek-chat" to "deepseek-flash",
    "deepseek-reasoner" to "deepseek-v4-pro",
    "deepseek v4.1 flash" to "deepseek-flash",
    "deepseek v4 pro" to "deepseek-v4-pro",
  )

  /** 把老名字 / 展示名换成真正的模型 ID;不认识的模型名原样返回。 */
  fun normalize(model: String): String = ALIASES[model.trim().lowercase()] ?: model

  /** 界面上显示的名字;没有对应展示名的模型原样返回它的 ID。 */
  fun display(model: String): String = ID_TO_DISPLAY[model.trim().lowercase()] ?: model
}
