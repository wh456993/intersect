package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 明暗配色:浅色 / 深色 / 跟随系统。 */
enum class ThemeMode {
  System,
  Light,
  Dark,
}

/**
 * 外观设置:浅色模式 / 深色模式 / 跟随系统。
 *
 * 只有「设置」页能改:改完即生效并落盘,界面上的明暗由 [mode] 这个 Compose 状态驱动,
 * 没有单独的保存动作。原来标题栏上那个小太阳 / 小月亮切换按钮已经去掉,
 * 一处入口就够,也不必让每页标题栏都多一个按钮。
 */
object ThemeSetting {
  private const val PREFS = "theme_setting"
  private const val KEY_MODE = "mode"
  /** 旧版本的布尔开关(true = 暗色),只在迁移时读。 */
  private const val KEY_DARK = "dark"
  /** 旧版本「显示标题栏切换按钮」,已废弃,读一次清掉即可。 */
  private const val KEY_SHOW_TOGGLE = "show_toggle"

  /** 当前选择,默认跟随系统。 */
  var mode by mutableStateOf(ThemeMode.System)
    private set

  private var prefs: SharedPreferences? = null

  /** 在主界面组合之前调用一次即可,重复调用无副作用。 */
  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    val stored = p.getString(KEY_MODE, null)
    mode = if (stored != null) {
      // 存的是枚举名,改过名字或读坏了就退回跟随系统
      ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    } else {
      // 旧版本:只有「暗色模式」开关,打开过就是深色,没打开过就是浅色
      when {
        !p.contains(KEY_DARK) -> ThemeMode.System
        p.getBoolean(KEY_DARK, false) -> ThemeMode.Dark
        else -> ThemeMode.Light
      }
    }
  }

  fun setMode(context: Context, value: ThemeMode) {
    mode = value
    prefsOf(context).edit()
      .putString(KEY_MODE, value.name)
      // 旧键不再有意义,顺手清掉,免得下次又被当成「升级前」的数据
      .remove(KEY_DARK)
      .remove(KEY_SHOW_TOGGLE)
      .apply()
  }

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
