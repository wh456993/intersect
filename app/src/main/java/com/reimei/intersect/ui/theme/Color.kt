package com.reimei.intersect.ui.theme

import androidx.compose.ui.graphics.Color

val white = Color(0xFFFFFFFF)
val white1 = Color(0xFFF7F7F7)
val white2 = Color(0xFFEDEDED)

// 暗色模式:底色一层层比亮色深一档,层次关系与亮色一致(页面 → 列表 → 底栏)
val night1 = Color(0xFF121212)
val night2 = Color(0xFF1A1A1A)
val night3 = Color(0xFF1F1F1F)
val night4 = Color(0xFF262626)

// ---------------------------------------------------------------------------
// 设计规范取色:每个角色浅色 / 深色成对,语义见各行注释。
// 只在这里写色值,调色板(Theme.kt)与各处界面都通过这些语义角色取色。
// ---------------------------------------------------------------------------

/** 主交互 / 激活态:悬浮「+」、底栏当前项、重要确认按钮 —— 星空蓝。 */
val accent = Color(0xFF2563EB)

/** 主交互 / 激活态(深色模式)—— 亮星蓝。 */
val accentNight = Color(0xFF60A5FA)

/** 未激活图标 / 线框:底栏未选中项、右向箭头 `>`、设置项左侧图标 —— 冷灰。 */
val iconIdle = Color(0xFF9CA3AF)

/** 未激活图标 / 线框(深色模式)—— 深灰。 */
val iconIdleNight = Color(0xFF6B7280)

/** 主标题 / 正文:名字、设置项名称 —— 炭黑。 */
val inkPrimary = Color(0xFF111827)

/** 主标题 / 正文(深色模式)—— 极浅灰。 */
val inkPrimaryNight = Color(0xFFF9FAFB)

/** 副标题 / 说明文字 —— 中灰。 */
val inkSecondary = Color(0xFF6B7280)

/** 副标题 / 说明文字(深色模式)—— 冷灰。 */
val inkSecondaryNight = Color(0xFF9CA3AF)

/** 分割线 / 边框线 —— 极浅灰。 */
val line = Color(0xFFF3F4F6)

/** 分割线 / 边框线(深色模式)—— 暗灰。 */
val lineNight = Color(0xFF2D2D2D)

/** 危险 / 退出操作 —— 警示红。 */
val danger = Color(0xFFEF4444)

/** 危险 / 退出操作(深色模式)—— 柔和红。 */
val dangerNight = Color(0xFFF87171)

/** 「我」发出的聊天气泡底色 —— 浅星蓝;字用 [inkPrimary]。 */
val bubbleSurface = Color(0xFFDBEAFE)

/** 「我」发出的聊天气泡底色(深色模式)—— 深星蓝;字用 [bubbleSurface]。 */
val bubbleSurfaceNight = Color(0xFF1E3A8A)
