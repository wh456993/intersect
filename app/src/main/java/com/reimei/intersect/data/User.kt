package com.reimei.intersect.data

import androidx.annotation.DrawableRes
import com.reimei.intersect.R

data class User(
  val id: String,
  val name: String,
  @DrawableRes val avatar: Int,
  /** 自定义头像文件的绝对路径;为空时用 [avatar] 里的内置图。 */
  val avatarPath: String? = null,
  /** 个性签名,联系人才有。 */
  val signature: String? = null,
  /**
   * 角色设定里的「身份」:这个角色是谁(干什么的),会写进每轮请求的系统提示。
   *
   * null / 空串表示没设置,请求里就不提这一项。
   */
  val role: String? = null,
  /** 角色设定里的「性格」:说话方式与脾气,同样写进每轮请求。null / 空串表示没设置。 */
  val personality: String? = null,
  /** 角色卡的扮演模式;默认 Avatar-based(行为与既有逻辑一致)。 */
  val roleplayMode: RoleplayMode = RoleplayMode.AVATAR_BASED,
  /**
   * 唯一 ID:只在存储层用(每轮记忆索引里记它),对用户不可见。
   *
   * 和昵称 / 创建时间 / 签名都无关,创建角色时由 [UidStore] 按顺序发下来,之后就跟着这个
   * 角色走 —— 连备份一起带走,恢复 / 换机都不变。null 表示老数据还没发号,读入时会补上。
   */
  val uid: Long? = null,
) {
  companion object {
    /** 「我」:UID 固定 1,由 [UidStore.ME_UID] 定义。 */
    val Me: User = User(
      "self",
      MeProfile.DEFAULT_NAME,
      R.drawable.ui_avatar_me,
      uid = UidStore.ME_UID,
    )
  }
}
