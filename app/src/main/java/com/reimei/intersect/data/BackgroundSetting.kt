package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.reimei.intersect.service.startKeepAlive
import com.reimei.intersect.service.stopKeepAlive

/**
 * 后台设置:后台常驻(前台服务 + 开机自启 + 被清后台后自动拉起)与「退出后从最近任务隐藏」。
 *
 * 与 [ThemeSetting] 一样是「改完即生效并落盘」:开关直接写 SharedPreferences,
 * 界面上的状态由这两个 Compose 状态驱动;常驻服务在改动的当下就启停,不用等下次启动。
 */
object BackgroundSetting {
  private const val PREFS = "background_setting"
  private const val KEY_KEEP_ALIVE = "keep_alive"
  private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"

  /** 后台常驻:前台服务常开,开机与被动清理后由系统 / 闹钟重新拉起。 */
  var keepAlive by mutableStateOf(false)
    private set

  /** 退出应用后,是否把本应用从「最近任务」列表里去掉。 */
  var hideFromRecents by mutableStateOf(false)
    private set

  private var prefs: SharedPreferences? = null

  /** 在主界面组合之前调用一次即可,重复调用无副作用。 */
  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    keepAlive = p.getBoolean(KEY_KEEP_ALIVE, false)
    hideFromRecents = p.getBoolean(KEY_HIDE_FROM_RECENTS, false)
  }

  fun setKeepAlive(context: Context, value: Boolean) {
    keepAlive = value
    prefsOf(context).edit().putBoolean(KEY_KEEP_ALIVE, value).apply()
    if (value) context.startKeepAlive() else context.stopKeepAlive()
  }

  fun setHideFromRecents(context: Context, value: Boolean) {
    hideFromRecents = value
    prefsOf(context).edit().putBoolean(KEY_HIDE_FROM_RECENTS, value).apply()
  }

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
