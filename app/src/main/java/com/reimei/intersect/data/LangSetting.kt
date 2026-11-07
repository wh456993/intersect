package com.reimei.intersect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 界面语言:中文 / English / 跟随系统。 */
enum class LangMode {
  System,
  Chinese,
  English,
}

/**
 * 界面语言设置。
 *
 * 与 [ThemeSetting] 一个套路:改完即生效并落盘。界面文案统一走 com.reimei.intersect.i18n.t(),
 * 那里读的是 [english] 这个 Compose 状态,所以切换语言时凡是显示过文案的组合都会自然重组,
 * 不需要重建 Activity。
 *
 * [mode] 是用户的选择,[english] 是「此刻实际生效的语言」——跟随系统时由系统语言决定,
 * 系统语言变了(Activity 因配置变更重建)会重新算一次。
 */
object LangSetting {
  private const val PREFS = "lang_setting"
  private const val KEY_MODE = "mode"
  /** 旧版本的布尔开关(true = 英文),只在迁移时读。 */
  private const val KEY_LEGACY_ENGLISH = "english"

  /** 用户的选择,默认跟随系统。 */
  var mode by mutableStateOf(LangMode.System)
    private set

  /** 此刻是否用英文显示。 */
  var english by mutableStateOf(false)
    private set

  private var prefs: SharedPreferences? = null

  /** 在主界面组合之前调用一次即可,重复调用无副作用。 */
  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    val stored = p.getString(KEY_MODE, null)
    mode = if (stored != null) {
      LangMode.entries.firstOrNull { it.name == stored } ?: LangMode.System
    } else if (p.contains(KEY_LEGACY_ENGLISH)) {
      // 旧版本只有「英文界面」开关:开过的继续用英文,没开过的按中文算
      if (p.getBoolean(KEY_LEGACY_ENGLISH, false)) LangMode.English else LangMode.Chinese
    } else {
      LangMode.System
    }
    english = resolveEnglish(context, mode)
  }

  fun setMode(context: Context, value: LangMode) {
    mode = value
    prefsOf(context).edit()
      .putString(KEY_MODE, value.name)
      .remove(KEY_LEGACY_ENGLISH)
      .apply()
    english = resolveEnglish(context, value)
  }

  /**
   * 系统语言变了之后重新取一次实际语言。
   *
   * 只在跟随系统时才有意义;其它模式直接返回,不必多跑。
   */
  fun refreshFromSystem(context: Context) {
    if (mode != LangMode.System) return
    english = resolveEnglish(context, mode)
  }

  private fun resolveEnglish(context: Context, value: LangMode): Boolean = when (value) {
    LangMode.English -> true
    LangMode.Chinese -> false
    // 跟随系统:不是中文就用英文,与绝大多数中文用户的直觉一致。
    // 用 Locale.getDefault() 而不是 configuration.locales:后者要 API 24,本工程 minSdk 21。
    LangMode.System -> !Locale.getDefault().language.startsWith("zh", ignoreCase = true)
  }

  private fun prefsOf(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }
}
