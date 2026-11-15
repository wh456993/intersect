package com.reimei.intersect.data.memory

/**
 * 「清洗脏大总结」:把库里**已经落盘**的 node_summary 行用同一套 [NodeSummarizer.sanitize] 重洗一遍。
 *
 * 为什么需要它:提示词硬约束与 [NodeSummarizer.sanitize] 只管「以后写进来的」,
 * 而之前被模型把英文推理当正文吐进库里的那一行会继续作为主线大总结进每一轮请求体 ——
 * 光修提示词救不回脏数据,得就地重写。
 *
 * 两条判据(与写入时完全一致):
 * - 洗得出来的 → 就地重写正文(轮次区间与时间不动);
 * - 洗不出来的(汉字不足 [NodeSummarizer.MIN_CJK_CHARS])→ 整行删掉。留着比没有更糟。
 *
 * 已经干净的行一律不动,哪怕它本身很短 —— 这次清洗不该变成一次「短大总结大屠杀」。
 *
 * 规划([plan])与执行([MemoryRepository.applyNodeClean])分开:面板先扫一遍给测试员看要改什么,
 * 确认之后才落库。规划是纯函数,JVM 单测直接钉。
 */

/** 一行大总结的清洗结果。[after] 为空串表示这一行会被删除。 */
data class NodeCleanItem(
  /** 这一行现在的样子(预览里要显示原文)。 */
  val row: NodeSummaryEntity,
  /** 清洗后的正文;空串 = 删除。 */
  val after: String,
) {
  val removed: Boolean get() = after.isBlank()
}

/** 一次扫描的结果:扫了几行、要改哪几条、要删哪几条。 */
data class NodeCleanPlan(
  val scanned: Int,
  val rewrites: List<NodeCleanItem> = emptyList(),
  val deletes: List<NodeCleanItem> = emptyList(),
) {
  /** 全部有变化的行(先重写、后删除)。 */
  val changes: List<NodeCleanItem> get() = rewrites + deletes

  val changed: Int get() = rewrites.size + deletes.size

  val isEmpty: Boolean get() = changes.isEmpty()
}

object NodeClean {

  /** 扫一遍这些行,给出「哪些要重写、哪些要删」;已经干净的不进结果。 */
  fun plan(rows: List<NodeSummaryEntity>): NodeCleanPlan {
    val rewrites = ArrayList<NodeCleanItem>()
    val deletes = ArrayList<NodeCleanItem>()
    rows.forEach { row ->
      val after = NodeSummarizer.sanitize(row.text)
      when {
        // 本来就干净:不动。注意这一条必须在「可用性」判断之前 ——
        // 否则一条又短又干净的老大总结会被当成脏数据删掉。
        // 判「脏」用的是标记/连排英文,不是「清洗前后是否相同」:纯英文行 sanitize 根本改不动,
        // 但那恰恰是最该删的那种。
        !NodeSummarizer.looksDirty(row.text) -> Unit
        !NodeSummarizer.isBodyUsable(after) -> deletes += NodeCleanItem(row, "")
        else -> rewrites += NodeCleanItem(row, after)
      }
    }
    return NodeCleanPlan(scanned = rows.size, rewrites = rewrites, deletes = deletes)
  }
}
