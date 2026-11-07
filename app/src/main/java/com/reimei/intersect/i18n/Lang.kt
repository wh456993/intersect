package com.reimei.intersect.i18n

import com.reimei.intersect.data.LangSetting

/**
 * 取一段界面文案:中文原文就是 key,英文查 [EnStrings] 里的对照表。
 *
 * 用「中文原文当 key」是故意的:调用点的写法与原来完全一样(t("设置")),
 * 对照表里查不到时原样返回中文,所以漏翻一处只会显示中文,不会露出占位符。
 *
 * 函数内部读了 [LangSetting.english] 这个 Compose 状态,在组合里调用即登记依赖,
 * 切换语言时读过文案的组合会自动重组,不需要重建 Activity。
 */
fun t(zh: String): String {
  // 先读状态再判断:英文关着的时候也要登记依赖,否则切到英文不会重组
  if (!LangSetting.english) return zh
  if (zh.isEmpty()) return zh
  EnStrings.exact[zh]?.let { return it }
  return matchPattern(zh)
}

/**
 * 带变量的文案:模板里用 {0} / {1} 占位,中文与英文共用一套占位符。
 *
 * 源码里写 `t("已选中 {0} 条", n)`,对照表里的英文模板也写 `{0}`,
 * 这样英文语序与中文不同也能对得上。
 */
fun t(zh: String, vararg args: Any?): String {
  val pattern = if (LangSetting.english) EnStrings.exact[zh] ?: zh else zh
  if (args.isEmpty()) return pattern
  val sb = StringBuilder(pattern.length + 16)
  var i = 0
  while (i < pattern.length) {
    val c = pattern[i]
    if (c == '{') {
      val end = pattern.indexOf('}', i + 1)
      val index = if (end > i + 1) pattern.substring(i + 1, end).toIntOrNull() else null
      if (index != null && index in args.indices) {
        sb.append(args[index]?.toString() ?: "")
        i = end + 1
        continue
      }
    }
    sb.append(c)
    i++
  }
  return sb.toString()
}

/** 句子里的名字、数字拼出来的字符串(如「找到 3 条记录」)按规则表反查。 */
private fun matchPattern(zh: String): String {
  if (!HAS_CJK.containsMatchIn(zh)) return zh
  val rules = EnStrings.patterns
  for (index in rules.indices) {
    val rule = rules[index]
    val match = rule.first.matchEntire(zh) ?: continue
    return rule.second(match)
  }
  return zh
}

private val HAS_CJK = Regex("[\\u4e00-\\u9fff]")

/** 规则表里取第 n 个捕获组。 */
internal fun MatchResult.g(n: Int): String = groupValues[n]

/** 规则表里对捕获到的中文再查一次对照表(处理「全部 + 维度名」这类拼接)。 */
internal fun inner(zh: String): String = EnStrings.exact[zh] ?: zh
