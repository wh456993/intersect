package com.reimei.intersect.data

import androidx.annotation.DrawableRes
import androidx.compose.runtime.mutableStateListOf
import com.reimei.intersect.R

/**
 * 应用内置的唯一默认角色:intersect 助手。
 *
 * 存盘时只记 id,不记 drawable,读回来用 [builtInAvatarFor] 按 id 找回内置头像。
 */
object DawnAssistant {
  const val ID = "dawn_assistant"
  const val NAME = "intersect"

  /** 与内置助手的会话里,第一条消息永远是这句欢迎语。 */
  const val GREETING = "欢迎加入 intersect"

  fun assistant(): User = User(
    id = ID,
    name = NAME,
    avatar = R.drawable.ui_avatar_helper,
    signature = "intersect",
    roleplayMode = RoleplayMode.SELF_INSERT,
  )

  /** 内置角色不允许删除:界面上隐藏入口,业务层也会拦住。 */
  fun isLocked(userId: String): Boolean = userId == ID

  /** 首次启动时给内置助手建的会话,第一条消息是欢迎语(未读,列表上带小红点)。 */
  fun greetingChat(): Chat =
    Chat(assistant(), mutableStateListOf(Msg(assistant(), GREETING)))

  /** 按 id 找回内置角色的头像资源;非内置角色用占位图。 */
  @DrawableRes
  fun builtInAvatarFor(id: String): Int =
    if (id == ID) R.drawable.ui_avatar_helper else R.drawable.ui_avatar_blank
}
