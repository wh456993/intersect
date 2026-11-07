package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 当前登录用户的个人资料(头像 / 昵称 / 签名)。
 *
 * 三个字段都是「改完即生效」:写入即落盘,界面上的状态直接驱动 Me 页面,
 * 没有单独的保存动作。
 */
object MeProfile {
  private const val PREFS = "me_profile"
  private const val KEY_NAME = "name"
  private const val KEY_SIGNATURE = "signature"
  private const val KEY_AVATAR_VERSION = "avatar_version"
  private const val KEY_STATUS = "status"

  const val DEFAULT_NAME = "小先"
  const val DEFAULT_SIGNATURE = "平行宇宙没有交点......"
  const val NAME_MIN = 2
  const val NAME_MAX = 20
  const val SIGNATURE_MIN = 5
  const val SIGNATURE_MAX = 50

  var name by mutableStateOf(DEFAULT_NAME)
    private set
  var signature by mutableStateOf(DEFAULT_SIGNATURE)
    private set

  /** 头像版本号:每换一次头像加一,用来给 Bitmap 缓存失效。 */
  var avatarVersion by mutableStateOf(0)
    private set

  /** 当前在线状态;null 表示还没设过。 */
  var status by mutableStateOf<UserStatus?>(null)
    private set

  private var prefs: SharedPreferences? = null
  private var cachedAvatar: Bitmap? = null
  private var cachedVersion = -1

  /** 在主界面组合之前调用一次即可,重复调用无副作用。 */
  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    name = p.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
    signature = p.getString(KEY_SIGNATURE, DEFAULT_SIGNATURE) ?: DEFAULT_SIGNATURE
    avatarVersion = p.getInt(KEY_AVATAR_VERSION, 0)
    status = UserStatus.fromKey(p.getString(KEY_STATUS, null))
  }

  fun setName(context: Context, value: String) {
    name = value
    prefsOf(context).edit().putString(KEY_NAME, value).apply()
  }

  fun setSignature(context: Context, value: String) {
    signature = value
    prefsOf(context).edit().putString(KEY_SIGNATURE, value).apply()
  }

  fun avatarFile(context: Context): File = File(context.applicationContext.filesDir, "me_avatar.jpg")

  /** 头像文件写入之后调用,让界面重新解码。 */
  fun onAvatarSaved(context: Context) {
    val next = avatarVersion + 1
    avatarVersion = next
    cachedAvatar = null
    cachedVersion = -1
    prefsOf(context).edit().putInt(KEY_AVATAR_VERSION, next).apply()
  }

  /** 自定义头像;没设置过时返回 null,由调用方退回默认头像。 */
  fun avatarBitmap(context: Context): Bitmap? {
    if (avatarVersion <= 0) return null
    if (cachedVersion == avatarVersion) return cachedAvatar
    val file = avatarFile(context)
    val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    cachedAvatar = bitmap
    cachedVersion = avatarVersion
    return bitmap
  }

  fun setStatus(context: Context, value: UserStatus?) {
    status = value
    prefsOf(context).edit().putString(KEY_STATUS, value?.key).apply()
  }

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
