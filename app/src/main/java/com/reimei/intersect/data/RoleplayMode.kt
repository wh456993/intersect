package com.reimei.intersect.data

/**
 * 角色信息页里的一类功能入口。
 *
 * 这里只声明「有哪些模块」,**不管它们长什么样**:每一行的标题、副标题与点击行为都在
 * 界面层([com.reimei.intersect.ui.CharacterInfoPage])。这样数据层能单独测「某个模式有哪些功能」,
 * 也不用把文字搬进枚举。
 */
enum class CharacterModule {
  /** 角色身份:名称 / 性格 / 身份。 */
  IDENTITY,

  /** 记忆库:即时记忆 / 引导线索 / 长期记忆。 */
  MEMORY,

  /** 空间管理器(开发中)。 */
  SPACE,

  /** 物品管理器。 */
  ITEMS,

  /** 世界观:法则体系 / 势力分布 / 经济架构 / 历史与重大事件。 */
  WORLDVIEW,

  /** 背景。 */
  BACKGROUND,

  /** 时间管理器(基准时间):编辑这个角色被程序设定的开局时刻。 */
  BASE_TIME,

  /** 时间管理器(实时):显示本机当前的年月日时分,不写任何状态。 */
  LOCAL_TIME,
}

/**
 * 角色卡的扮演模式。
 *
 * - [AVATAR_BASED]:扮演一个虚构角色,行为和既有逻辑完全一致。
 * - [SELF_INSERT]:以自己身份代入,时间管理器([TimeManager])会给每轮对话注入本地时间。
 *
 * **[modules] 是两种模式各自的入口清单,彼此没有任何推导关系** —— 两份列表都手写在这里,
 * 谁也不从谁算出来。看着像「角色代入多几项」是巧合,不是规则:以后任一侧增删功能,
 * 只动它自己那一份就够了,另一侧不会被带着变。
 */
enum class RoleplayMode(val label: String, val modules: List<CharacterModule>) {
  /**
   * 角色代入:有一套自己的世界,所以有空间 / 物品管理器、世界观与背景;
   * 时间由基准时间起算,所以时间管理器是**可编辑的基准时间**。
   */
  AVATAR_BASED(
    "角色代入",
    listOf(
      CharacterModule.IDENTITY,
      CharacterModule.MEMORY,
      CharacterModule.SPACE,
      CharacterModule.ITEMS,
      CharacterModule.WORLDVIEW,
      CharacterModule.BACKGROUND,
      CharacterModule.BASE_TIME,
    )
  ),

  /**
   * 本体代入:没有可管理的世界(故事发生在真实时间里),所以没有空间 / 物品管理器、
   * 世界观与背景;时间管理器直接显示本机真实时间,不可编辑。
   */
  SELF_INSERT(
    "本体代入",
    listOf(
      CharacterModule.IDENTITY,
      CharacterModule.MEMORY,
      CharacterModule.LOCAL_TIME,
    )
  );

  /** 这个模式是否有这个模块(创建流程里按它决定草稿要落哪些数据)。 */
  fun has(module: CharacterModule): Boolean = modules.contains(module)

  companion object {
    /** 从存盘的枚举名还原;不认识的(或老数据里没有)一律按默认的 Avatar-based 处理。 */
    fun fromName(name: String?): RoleplayMode =
      entries.firstOrNull { it.name == name } ?: AVATAR_BASED
  }
}
