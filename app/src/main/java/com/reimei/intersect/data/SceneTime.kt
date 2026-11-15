package com.reimei.intersect.data

/**
 * 场景时间来源:角色代入([RoleplayMode.AVATAR_BASED])下「此刻是剧情里的什么时候」的算法接口。
 *
 * **这是一个待实现的接口** —— 目前没有任何实现返回非 null 值,调用方总是回落到用户手填的
 * [MetaInfo.time]。留它的目的是把「时间值怎么来」和「谁来读时间」分开:
 * 将来无论是按轮数推进、按剧情锚点推进,还是从地图/事件线取时间,都只实现这一个接口,
 * 上层 [SceneTime] 与元信息块格式都不用改。
 *
 * 约定:
 * - 返回 `null` 表示「这个角色没有可用的剧情时钟」→ 回落到手填值;
 * - 返回空串 `""` 表示「明确没有时间」(如失忆、梦境)→ 该行整行省略;
 * - 返回值是**给人看的文本**(如「黄昏」「第三天清晨」),不含数值,直接进请求体;
 * - 实现必须是纯函数式行为:同一 [nowMillis] 应当给出同一结果,否则历史记忆会前后打架。
 * - 不要在这里做持久化:状态属于实现方,`SceneTime` 每次调用都是无状态的一问一答。
 */
fun interface FictionClock {
  /** [friendId] 是角色 id;[nowMillis] 是真实时间戳,供实现方做种子或偏移参考。 */
  fun timeAt(friendId: String, nowMillis: Long): String?
}

/**
 * 场景时间的统一出口 —— 时间在元信息里是**唯一特殊**的字段:
 *
 * - **本体代入**:时间管理器([TimeManager])直接输出本地真实年月日时刻,不许算法干预;
 * - **角色代入**:由 [FictionClock] 按剧情算法生成。**该算法尚未实现**,
 *   [DefaultFictionClock] 恒返回 null,因此现阶段表现为「回落到用户手填的 [MetaInfo.time]」。
 *
 * 与 [MetaInfo] 其余字段隔离:时间**不参与**人工登记的关系/地点那套语义,
 * 也不由 AI 生成;AI 只在总结时读到已经定好的时间名词(见 [MetaInfo.block])。
 */
object SceneTime {
  /**
   * 默认的剧情时钟:**未实现**。
   *
   * 保持恒 null 是有意的 —— 让「角色代入下时间由算法生成」这件事在数据上表现为
   * 「暂时没有算法」,而不是偷偷塞一个假时间进请求体。
   */
  object DefaultFictionClock : FictionClock {
    override fun timeAt(friendId: String, nowMillis: Long): String? = null
  }

  /**
   * 解析这个角色此刻的时间文本。
   *
   * 返回空串表示「没有时间」,调用方据此整行省略(见 [MetaInfo.block] 的 `timeOverride`)。
   *
   * [fiction] 只在角色代入下被调用;本体代入永远走真实时钟,[fiction] 传了也不生效 ——
   * 这是「本体代入 = 真实时间」这条规则的硬保证,不给算法留后门。
   */
  fun of(
    mode: RoleplayMode,
    friendId: String,
    fallback: String = "",
    nowMillis: Long = System.currentTimeMillis(),
    fiction: FictionClock = DefaultFictionClock,
  ): String = when (mode) {
    // 本体代入:时间管理器直接输出年月日时刻,精确到分钟(与聊天页注入的是同一条口径)
    RoleplayMode.SELF_INSERT -> TimeManager.formatMinuteAt(nowMillis)
    // 角色代入:算法优先;还没有算法(或该角色不适用)就用手填值
    RoleplayMode.AVATAR_BASED -> fiction.timeAt(friendId, nowMillis) ?: fallback.trim()
  }

  /**
   * 把解析出的时间套回元信息,得到「这一轮真正会进请求体」的那份。
   *
   * 上层用它把时钟结果与手填字段拼在一起,再调 [MetaInfo.block] —— 这样时间来源变了
   * (手填 → 算法 → 真实时钟)时,只有本函数变,块格式与总结链路都不用动。
   */
  fun applyTo(meta: MetaInfo, mode: RoleplayMode, nowMillis: Long = System.currentTimeMillis()): MetaInfo =
    meta.copy(time = of(mode, meta.friendId, fallback = meta.time, nowMillis = nowMillis))
}
