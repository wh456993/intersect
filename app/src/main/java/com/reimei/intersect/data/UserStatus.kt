package com.reimei.intersect.data

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.reimei.intersect.R

/**
 * 名片上可以切换的在线状态。
 *
 * [tint] 用于图标与文字,[background] 是名片在这种状态下渐变到的底色,
 * 都是浅色系,保证深色文字始终能看清。
 */
enum class UserStatus(
  val key: String,
  val label: String,
  @DrawableRes val icon: Int,
  val tint: Color,
  val background: Color,
) {
  Online("online", "在线", R.drawable.ui_state_active, Color(0xFF3C9A50), Color(0xFFDDF1DF)),
  Busy("busy", "忙碌", R.drawable.ui_state_busy, Color(0xFFC2453F), Color(0xFFFBE0DE)),
  Dnd("dnd", "勿扰", R.drawable.ui_state_silent, Color(0xFF7B4BC4), Color(0xFFEDE4FB)),
  Away("away", "离开", R.drawable.ui_state_away, Color(0xFFB47811), Color(0xFFFAEEDA)),
  Slacking("slacking", "摸鱼中", R.drawable.ui_state_idle, Color(0xFF1B7EB8), Color(0xFFDCEBF7)),
  Working("working", "工作中", R.drawable.ui_state_work, Color(0xFF2C5FC4), Color(0xFFDDE6FA)),
  Studying("studying", "学习中", R.drawable.ui_state_study, Color(0xFF5145B8), Color(0xFFE3E0F8)),
  Commuting("commuting", "通勤中", R.drawable.ui_state_commute, Color(0xFF3C7C86), Color(0xFFDDEDEF)),
  Sport("sport", "运动中", R.drawable.ui_state_sport, Color(0xFFC25A2A), Color(0xFFFAE4D8)),
  Resting("resting", "休息中", R.drawable.ui_state_rest, Color(0xFF7E5AAE), Color(0xFFEDE3F8)),
  Music("music", "听歌中", R.drawable.ui_state_music, Color(0xFFB9438A), Color(0xFFF9DFEE)),
  Chat("chat", "想聊天", R.drawable.ui_state_talk, Color(0xFF1F8C7E), Color(0xFFDBF1ED)),
  ;

  companion object {
    fun fromKey(key: String?): UserStatus? = entries.firstOrNull { it.key == key }
  }
}
