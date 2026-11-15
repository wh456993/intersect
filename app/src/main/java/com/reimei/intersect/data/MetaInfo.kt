package com.reimei.intersect.data

/**
 * 角色的即时元信息:对话场景的数值状态 + 文本状态,按角色存(见 [MetaInfoStore])。
 *
 * 三件事把它和普通表单数据区分开:
 *
 * 1. **数值一律 0~100**([favorability] 好感度、[health] 生命值、[hunger] 饥饿值)。
 *    越界值在映射时会被夹回范围内,存的时候不保证夹过 —— 读的一侧自己兜。
 * 2. **数值不进请求体**:每个数值阶段在代码里**确定性映射成一个名词**
 *    (见 [MetaPhrases],不走 AI),请求体里只出现「陌生」「亚健康」「六分饱」这类词,
 *    永远不出现具体数字。接口方拼报文时只能用 [block] / `*Noun()`,不要直接读数值。
 * 3. **buff 词条由 AI 按剧情定**:程序只维护词表([BUFF_VOCAB])与「数值阶段 ↔ 词条」的
 *    对应认知,**不**根据数值自动往 [buffs] 里塞词条 —— 饥饿 <20、重病 <30 这类规则也由
 *    AI 在 20 轮总结时按剧情决定写不写。[buffs] 是当前生效词条的人工登记(设置页手动改)。
 *
 * 文本字段(时间 / 关系 / 地点 / 角色 / 气象)留空表示「没登记」,[block] 会整行省略;
 * 数值三项永远有值(有默认),总是出现在 [block] 里。
 *
 * [location] 预留给未来的空间管理器,[characters] 是当前对话场景里在场的角色名。
 */
data class MetaInfo(
  val friendId: String,
  /** 好感度 0~100;50 = 陌生人。初始值由用户按角色性格填,默认取中立。 */
  val favorability: Int = FAVOR_DEFAULT,
  /** 生命值 0~100;0 = 死亡。 */
  val health: Int = HEALTH_DEFAULT,
  /** 饥饿值 0~100;越高越饱(50 = 六分饱,80 = 正常吃饱,>80 = 吃撑,<20 = 饥饿)。 */
  val hunger: Int = HUNGER_DEFAULT,
  /** 时间,自由文本(如「黄昏」「周三下午」);未来的管理器可接管。 */
  val time: String = "",
  /** 关系,自由文本(如「点头之交」「同门」);与好感度名词并存,一个管数值阶段一个管定性。 */
  val relationship: String = "",
  /** 地点,自由文本;预留给空间管理器。 */
  val location: String = "",
  /** 当前场景在场的角色,多个用顿号分隔。 */
  val characters: String = "",
  /** 气象,自由文本;没登记视为未知天气(由调用方决定要不要补「未知」)。 */
  val weather: String = "",
  /** 当前生效的 buff 词条,只能取自 [BUFF_VOCAB];手动登记,AI 总结时也会按剧情写进记忆正文。 */
  val buffs: List<String> = emptyList(),
) {
  /** 好感度阶段名词,如「陌生」。请求体用它,不要用 [favorability]。 */
  fun favorNoun(): String = MetaPhrases.favor(favorability)

  /** 生命值阶段名词,如「健康」;0 是「死亡」。 */
  fun healthNoun(): String = MetaPhrases.health(health)

  /** 饥饿值阶段名词,如「六分饱」。 */
  fun hungerNoun(): String = MetaPhrases.hunger(hunger)

  /**
   * 拼出元信息块,只含名词与文本、**不含任何数值** —— 这是给请求体 / 总结请求用的唯一出口。
   *
   * 格式:
   * ```
   * [元信息]
   * 时间:黄昏
   * 好感:陌生
   * 关系:点头之交
   * 地点:镇口酒馆
   * 角色:旅人、守卫
   * 天气:小雨
   * 生命:亚健康
   * Buff:重病、中毒
   * 饥饿:六分饱
   * ```
   * 文本字段留空时整行省略;[buffs] 为空时省略 Buff 行;好感 / 生命 / 饥饿三行总是有。
   */
  fun block(): String = buildString {
    append(HEADER)
    time.trim().takeIf { it.isNotEmpty() }?.let { append("\n时间:").append(it) }
    append("\n好感:").append(favorNoun())
    relationship.trim().takeIf { it.isNotEmpty() }?.let { append("\n关系:").append(it) }
    location.trim().takeIf { it.isNotEmpty() }?.let { append("\n地点:").append(it) }
    characters.trim().takeIf { it.isNotEmpty() }?.let { append("\n角色:").append(it) }
    weather.trim().takeIf { it.isNotEmpty() }?.let { append("\n天气:").append(it) }
    append("\n生命:").append(healthNoun())
    buffs.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
      ?.let { list -> append("\nBuff:").append(list.joinToString("、")) }
    append("\n饥饿:").append(hungerNoun())
  }

  companion object {
    /** 元信息块的首行,让模型一眼看出这段是场景状态。 */
    const val HEADER = "[元信息]"

    /** 好感度默认 50 = 陌生人;真实初值由用户按角色性格在设置页改。 */
    const val FAVOR_DEFAULT = 50
    /** 生命值默认 80 = 健康。 */
    const val HEALTH_DEFAULT = 80
    /** 饥饿值默认 80 = 正常吃饱。 */
    const val HUNGER_DEFAULT = 80

    /**
     * buff 词条表:程序侧唯一合法词表。
     *
     * 与数值的对应关系(供 AI 提示词引用,程序不自动套用):
     * 饥饿 ← 饥饿值 <20;重病 ← 生命值 <30;失血 / 中毒 无数值规则,纯看剧情。
     */
    val BUFF_VOCAB: List<String> = listOf("饥饿", "失血", "中毒", "重病")

    /** 某角色还没登记过元信息时的初值。 */
    fun empty(friendId: String): MetaInfo = MetaInfo(friendId = friendId)

    /**
     * 聊天请求体 system 里的元信息规则 + 状态名词表演对照表。
     *
     * 文本来自需求给的请求体模板,逐字照搬;分档名词与 [MetaPhrases] 一致,词表与
     * [BUFF_VOCAB] 一致 —— 改这三处中的任何一处,另外两处都要跟着改。
     *
     * **每轮一字不改**:它拼在 system 里,稳定才能命中服务商的前缀缓存。所以这里只放常量,
     * 不放任何随轮次变化的内容(状态本身走 [block],在独立的 user 快照里)。
     */
    val RULES: String = listOf(
      "【系统元信息规则】",
      "1. 你会在后续对话中收到[元信息]块,包含好感、生命、饥饿、Buff 等状态。",
      "2. 好感、生命、饥饿三个数值字段已转换为纯名词,不含阿拉伯数字;时间字段在部分模式下是真实时刻,可能含日期数字,属正常现象。",
      "3. 请严格依据名词调整你的态度与生理反应,参考下方对照表。",
      "4. 剧情中可体现 Buff 效果,但合法词条仅限饥饿、失血、中毒、重病;不要自行编造新词条,也不要输出数值结算。",
      "5. 元信息中未出现的行表示该字段未登记,不要编造,也不要假设为“无”。",
      "",
      "【状态名词表演对照表】",
      "以下仅为表演参考,不是结算规则;不得据此自行修改状态或编造数值。",
      "",
      "好感:",
      "仇视:敌意、攻击性或拒绝交流",
      "冷淡:疏远、敷衍、不愿多谈",
      "陌生:保持距离、礼貌但谨慎",
      "熟悉:正常互动、愿意搭话",
      "亲密:明显亲近、主动维护或依赖对方",
      "",
      "生命:",
      "死亡:角色已死亡,不再行动或说话",
      "重病:极度虚弱、意识模糊、行动困难",
      "状态不佳:面色差、乏力、反应迟缓",
      "亚健康:轻微不适、体力略差",
      "健康:正常行动与反应",
      "优质:精力充足、动作利落",
      "",
      "饥饿:",
      "饥饿:明显饥饿感、注意力分散、体力下降",
      "微饿:轻微饥饿、容易想到食物",
      "六分饱:不饿,但对食物仍有兴趣",
      "正常吃饱:体力稳定、对食物兴趣低",
      "吃撑:腹部不适、动作迟缓",
      "",
      "常见 Buff(仅限以下四个词条):",
      "饥饿:由饥饿值过低体现,持续乏力、注意力涣散",
      "失血:持续虚弱、面色苍白、动作发软",
      "中毒:间歇眩晕、恶心、反应变慢",
      "重病:持续虚弱、行动困难、恢复缓慢",
      "",
      "未列出的词条不存在;不得自行扩展档位或新增 Buff。",
    ).joinToString("\n")

    /**
     * 总结请求模板里追加的元信息规则(接在总结要求之后)。
     *
     * 总结不是表演:这里要的是「把状态变化写进记忆正文」,而且**由 AI 按剧情决定 buff 词条**
     * —— 程序不根据数值自动塞词条(见 [MetaInfo] 的类型注释第 3 条)。
     */
    val SUMMARY_RULES: String = listOf(
      "5. 用户消息末尾可能带一段由程序生成的「[元信息]」块(好感 / 生命 / 饥饿等纯名词状态,",
      "   以及当前生效的 Buff 词条),它是这一批对话结束时的即时状态:按剧情把状态变化写进记忆正文",
      "   (该亲近的写亲近、该受伤的写受伤、该饿的写饿),并保留仍然生效的异常状态。",
      "6. Buff 词条没有数值规则,由你按剧情判断:只允许「饥饿 / 失血 / 中毒 / 重病」四个词条,",
      "   不要编造新词条。",
      "7. 不要输出元信息块的格式,不要写「好感:xx」这类字段行,也不要写任何数值结算或数字档位。",
    ).joinToString("\n")
  }
}

/**
 * 数值 → 名词的**确定性**映射(不走 AI,规则写死在这里,单测钉边界)。
 *
 * 全部输入先 [Int.coerceIn] 夹到 0~100,越界不会抛。
 * 分档规格(经确认,5 档好感 / 6 档生命 / 5 档饥饿):
 *
 * - 好感:0-19 仇视 / 20-39 冷淡 / 40-59 陌生(50 居中)/ 60-79 熟悉 / 80-100 亲密;
 * - 生命:0 死亡 / 1-29 重病 / 30-49 状态不佳 / 50-69 亚健康 / 70-90 健康 / 91-100 优质
 *   (规格「70~90=健康」与「90以上=优质」在 90 撞车,按 90 归健康定);
 * - 饥饿:0-19 饥饿 / 20-49 微饿 / 50-79 六分饱 / 80 正常吃饱 / 81-100 吃撑。
 *   (规格只给了锚点:50=六分饱、80=正常吃饱、>80=吃撑、<20=饥饿;20-49 的「微饿」
 *   是实现时补的中间档名词 —— 纯名词选择,要改只动这一行。)
 */
object MetaPhrases {
  fun favor(value: Int): String = value.coerceIn(0, 100).let {
    when {
      it < 20 -> "仇视"
      it < 40 -> "冷淡"
      it < 60 -> "陌生"
      it < 80 -> "熟悉"
      else -> "亲密"
    }
  }

  fun health(value: Int): String = value.coerceIn(0, 100).let {
    when {
      it == 0 -> "死亡"
      it < 30 -> "重病"
      it < 50 -> "状态不佳"
      it < 70 -> "亚健康"
      it <= 90 -> "健康"
      else -> "优质"
    }
  }

  fun hunger(value: Int): String = value.coerceIn(0, 100).let {
    when {
      it < 20 -> "饥饿"
      it < 50 -> "微饿"
      it < 80 -> "六分饱"
      it == 80 -> "正常吃饱"
      else -> "吃撑"
    }
  }
}
