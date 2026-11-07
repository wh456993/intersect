package com.reimei.intersect.data

/**
 * 角色卡的扮演模式。
 *
 * - [AVATAR_BASED]:扮演一个虚构角色,行为和既有逻辑完全一致。
 * - [SELF_INSERT]:以自己身份代入,时间管理器([TimeManager])会给每轮对话注入本地时间。
 */
enum class RoleplayMode(val label: String) {
  AVATAR_BASED("Avatar-based Role-Play"),
  SELF_INSERT("Self-insert Role-Play");

  companion object {
    /** 从存盘的枚举名还原;不认识的(或老数据里没有)一律按默认的 Avatar-based 处理。 */
    fun fromName(name: String?): RoleplayMode =
      entries.firstOrNull { it.name == name } ?: AVATAR_BASED
  }
}
