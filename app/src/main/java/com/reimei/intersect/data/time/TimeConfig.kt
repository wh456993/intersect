package com.reimei.intersect.data.time

/**
 * T1.6 —— 冻结的阈值与解析模板(`config/time_thresholds`)。
 *
 * 三条纪律:
 * 1. **阈值只在这里**,业务代码里不许出现魔法数字;改动阈值属于新任务(R6),单测按初值钉住。
 * 2. **总结优先去重** —— 同一时段里「该总结」与「该发保险丝」同时成立时,只留总结;
 *    保险丝是总结做不了时的补位,定位见 [TimeDedupe]。
 * 3. **pending patch 消费规则** —— 导演补丁只被下一次总结消费一次,消费完即清
 *    ([PendingPatchRule])。
 */
object TimeThresholds {
  /** 多少轮攒一次总结(与既有即时记忆层的 20 轮一致,免得两套节奏)。 */
  const val SUMMARY_INTERVAL_ROUNDS = 20

  /** 未总结内容的 token 上限,到这个量也该总结(长文会话按 token 兜)。 */
  const val SUMMARY_INTERVAL_TOKENS = 3000

  /** 距上次总结超过这么多轮,才允许用保险丝注入代替完整总结。 */
  const val FUSE_THRESHOLD_ROUNDS = 30

  /** 冻结快照:单测直接按这张表断言初值。 */
  val FROZEN: Map<String, Int> = linkedMapOf(
    "summary_interval_rounds" to SUMMARY_INTERVAL_ROUNDS,
    "summary_interval_tokens" to SUMMARY_INTERVAL_TOKENS,
    "fuse_threshold_rounds" to FUSE_THRESHOLD_ROUNDS,
  )
}

/**
 * 导演面板(场景字段)的冻结配置。
 *
 * 为什么需要纪元:`ClockState.dateAnchor` 是「从 0 起的天序号」,而面板与注入都要一个人读的
 * 日期(`2026.5.6 8:22`)。把纪元钉在这里,`date_anchor = 0` 就恒等于 [EPOCH_YEAR] 年
 * [EPOCH_MONTH] 月 [EPOCH_DAY] 日 —— 换算不跟着系统时区、也不跟着设备日期走,
 * 单测才能直接断言(见 `SceneFieldsTest`)。
 */
object SceneConfig {
  const val EPOCH_YEAR = 2026
  const val EPOCH_MONTH = 1
  const val EPOCH_DAY = 1

  /** 地点 / 天气输入框的长度上限:超过就拒绝,不让一次注入被撑成一大段。 */
  const val FIELD_MAX_CHARS = 40

  /**
   * 纪年名的长度上限(如「第三纪」「罗历」)。纪年名里不能带数字 ——
   * 解析靠「第一个数字或正负号」分界,带数字就分不清哪一位开始是年份。
   */
  const val ERA_NAME_MAX_CHARS = 12

  /**
   * 年份的合理区间。允许负数与非公元纪年:扮演「第三纪 302 年」「-302 年」都能写;
   * 同时拓住手滑输入的超大数。
   */
  const val MIN_YEAR = -9999
  const val MAX_YEAR = 9999
}

/**
 * T1.6 —— 去重:同一个时段内的两种「告诉模型时间变了」的手段,总结优先。
 *
 * 为什么保险丝不是「另一个注入时机」:跨时段本身就触发总结([SummaryTrigger.PERIOD_CROSS]),
 * 保险丝只在**这一轮没能产出总结**、而且距上次总结已经超过
 * [TimeThresholds.FUSE_THRESHOLD_ROUNDS] 轮时才补位。两者永不并存。
 */
data class PerPeriodEvents(val summary: Boolean, val fuse: Boolean) {
  /** 最多一种事件。 */
  val count: Int get() = (if (summary) 1 else 0) + (if (fuse) 1 else 0)
}

object TimeDedupe {
  /**
   * @param summaryDue 这一轮是否会产出总结(轮数 / token / 场景切换 / 导演手动 / 跨时段任一成立且本轮可总结)
   * @param periodChanged 这一轮是否跨了时段
   * @param roundsSinceSummary 距上次总结的轮数
   */
  fun resolve(summaryDue: Boolean, periodChanged: Boolean, roundsSinceSummary: Int): PerPeriodEvents {
    if (summaryDue) return PerPeriodEvents(summary = true, fuse = false)
    val fuse = periodChanged && roundsSinceSummary >= TimeThresholds.FUSE_THRESHOLD_ROUNDS
    return PerPeriodEvents(summary = false, fuse = fuse)
  }
}

/**
 * T1.6 —— pending 导演补丁的消费规则。
 *
 * 导演跳转 / 对齐后,程序侧记一个补丁([DirectorPatch]);它的去处只有一个:
 * **下一次总结时并进总结元数据**(T4.3 的「运行时数据流」),并**只消费一次** ——
 * 消费后清空,避免同一个补丁在往后每次总结里反复出现(那会让模型以为时间还在跳)。
 */
object PendingPatchRule {
  /**
   * 消费补丁:返回「被并进来的那个补丁」与「消费之后的状态」。
   * 没有补丁时返回 null,状态原样返回。
   */
  fun consume(state: ClockState): ConsumedPatch? {
    val patch = state.pendingDirectorPatch ?: return null
    return ConsumedPatch(patch = patch, consumed = state.copy(pendingDirectorPatch = null))
  }

  data class ConsumedPatch(val patch: DirectorPatch, val consumed: ClockState)
}

/**
 * T1.6 —— 自然语言解析的**受限模板与词表**(阶段一冻结,阶段五只用来匹配)。
 *
 * 模板之外的输入一律低置信、不写时钟(T5.2 验收)。要加说法就改这里,并补一条单测。
 */
object TimeTemplates {
  /** 时段别名表:词 → 枚举。默认名词总在内,另允许少量常见说法。 */
  val PERIOD_ALIASES: Map<String, Period> = linkedMapOf(
    // 深夜
    "深夜" to Period.LATE_NIGHT,
    "凌晨" to Period.LATE_NIGHT,
    "半夜" to Period.LATE_NIGHT,
    // 清晨
    "清晨" to Period.DAWN,
    "早上" to Period.DAWN,
    "早晨" to Period.DAWN,
    "黎明" to Period.DAWN,
    // 上午
    "上午" to Period.MORNING,
    // 正午
    "正午" to Period.NOON,
    "中午" to Period.NOON,
    // 下午
    "下午" to Period.AFTERNOON,
    // 黄昏
    "黄昏" to Period.DUSK,
    "傍晚" to Period.DUSK,
    // 入夜
    "入夜" to Period.EVENING,
    "晚上" to Period.EVENING,
    // 午夜
    "午夜" to Period.MIDNIGHT,
    "夜里" to Period.MIDNIGHT,
  )

  /** 对齐模板:`{noun}` 会被词表里的词逐个替换成候选句。 */
  val ALIGN_TEMPLATES: List<String> = listOf(
    "时间来到{noun}",
    "时间到了{noun}",
    "时间变成{noun}",
    "到了{noun}",
    "现在是{noun}",
    "时间:{noun}",
  )

  /** 推进模板:`{amount}` 是数字(阿拉伯或中文),`{unit}` 是时长单位。 */
  val ADVANCE_TEMPLATES: List<String> = listOf(
    "过了{amount}{unit}",
    "{amount}{unit}后",
    "时间过去{amount}{unit}",
    "又过了{amount}{unit}",
  )

  /** 时长单位 → 分钟数。 */
  val DURATION_UNITS: Map<String, Int> = linkedMapOf(
    "分钟" to 1,
    "分" to 1,
    "刻" to 15,
    "刻钟" to 15,
    "小时" to 60,
    "钟头" to 60,
  )

  /** 中文数字(只到 99,够用且不会误伤别的词)。 */
  val CN_DIGITS: Map<Char, Int> = mapOf(
    '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
  )

  /** [DirectorTx] 的格式模板(T4.5 / T5.3):字段固定、顺序固定、不含任何动态解释。 */
  const val DIRECTOR_EVENT_TEMPLATE: String = "[导演时间事件]\n操作:{op}\n时段:{period}\n名词:{noun}"

  /**
   * 导演面板改完场景字段后,每一行变更的**注入格式**(冻结)。
   *
   * 形如 `<2026.5.6 8:22变更2026.5.7 9:40>`:尖括号里「旧值 变更 新值」,一眼能看出发生了什么,
   * 同时不会和角色台词混在一起(规则块第 3 条要求模型只把它当场景状态,不要复述)。
   */
  const val DIRECTOR_CHANGE_LINE: String = "<{before}变更{after}>"

  /** 事务的执行者标签(日志与契约里用同一套词)。 */
  val OPERATOR_LABELS: Map<String, String> = mapOf(
    "DIRECTOR" to "导演",
    "PROGRAM" to "程序基线",
    "AI" to "AI",
  )
}
