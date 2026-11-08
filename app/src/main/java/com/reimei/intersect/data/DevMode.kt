package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 开发者模式开关(设置 → 开发者模式)。
 *
 * 关着的时候应用就是个普通聊天软件:角色卡、聊天页上都看不到任何存储层的东西。
 * 打开之后,主聊天的附件按钮里才会多出「全部显示」——点开把每一轮的记忆索引
 * ([MemoryStamp]:角色 UID / 用户 UID / 落盘时间)显示在 AI 回复那一侧。
 *
 * 与 [BackgroundSetting] 同一套做法:改完即生效并落盘,界面状态由 [enabled] 这个
 * Compose 状态驱动,所以正在看的聊天页会立刻跟着变。
 */
object DevMode {
  private const val PREFS = "dev_mode"
  private const val KEY_ENABLED = "enabled"

  var enabled by mutableStateOf(false)
    private set

  private var prefs: SharedPreferences? = null

  /** 在主界面组合之前调用一次即可,重复调用无副作用。 */
  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    enabled = p.getBoolean(KEY_ENABLED, false)
  }

  fun setEnabled(context: Context, value: Boolean) {
    enabled = value
    prefsOf(context).edit().putBoolean(KEY_ENABLED, value).apply()
  }

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
