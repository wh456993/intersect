package com.reimei.intersect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal const val PREFS_NAME = "wecompose"

/** 协议页是否已经确认过,确认过后再启动就直接停在权限页。 */
private const val KEY_AGREEMENT_ACCEPTED = "agreement_accepted"

/** 自启动没有任何可查询的授权状态,只能记录「已经引导过」。 */
private const val KEY_AUTOSTART_DONE = "autostart_done"

/**
 * 用户手动确认过「电池无限制」。MIUI / 澎湃 OS 的省电策略写在自己的
 * powerkeeper 里,标准的 isIgnoringBatteryOptimizations() 读不到,只能靠用户确认。
 */
private const val KEY_BATTERY_CONFIRMED = "battery_unrestricted_confirmed"

/** 用户在激活页选了「稍后再说」,之后启动不再拦在激活页。 */
private const val KEY_ACTIVATION_SKIPPED = "activation_skipped"

/** MIUI / 澎湃 OS 的「省电策略」页(隐藏应用配置),用来把应用改成「无限制」。 */
private val MIUI_BATTERY_COMPONENTS = listOf(
  ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
  ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerSettingsActivity"),
)

/**
 * 进入主界面前需要完成的四项授权,顺序即激活页任务表从上到下的顺序。
 */
internal enum class PermTask(val title: String, val summary: String) {
  AllFiles("访问所有文件", "读写手机中的图片、视频与文档"),
  Battery("电池无限制", "后台常驻,避免被系统省电策略清理"),
  AutoStart("自启动", "开机或被清理后台后能自动恢复"),
  Notifications("信息通知", "收到新消息时及时提醒"),
}

/** 各厂商的自启动管理页,没有统一 API,只能逐个试。 */
private val AUTO_START_COMPONENTS = listOf(
  ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
  ComponentName(
    "com.huawei.systemmanager",
    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
  ),
  ComponentName(
    "com.coloros.safecenter",
    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
  ),
  ComponentName(
    "com.vivo.permissionmanager",
    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
  ),
  ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
  ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
)

internal fun Context.hasNotificationPermission(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
  } else {
    true
  }

internal fun Context.notificationsEnabled(): Boolean =
  hasNotificationPermission() && NotificationManagerCompat.from(this).areNotificationsEnabled()

internal fun Context.hasAllFilesAccess(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
  } else {
    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
  }

internal fun Context.isIgnoringBatteryOptimizations(): Boolean {
  // API 23 以下没有电池优化白名单,视为不受限
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
  val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
  return powerManager.isIgnoringBatteryOptimizations(packageName)
}

/** 用户手动确认过「电池无限制」(MIUI 等厂商策略读不到时的兜底)。 */
internal fun Context.isBatteryConfirmed(prefs: SharedPreferences): Boolean =
  prefs.getBoolean(KEY_BATTERY_CONFIRMED, false)

internal fun Context.confirmBatteryUnrestricted(prefs: SharedPreferences) {
  prefs.edit().putBoolean(KEY_BATTERY_CONFIRMED, true).apply()
}

/**
 * 电池是否已不受限:先看系统白名单,再看用户手动确认。
 * 澎湃 OS / MIUI 的「无限制」写在厂商自己的省电策略里,系统白名单是空的,
 * 只靠 isIgnoringBatteryOptimizations() 会一直判定为未授权,所以要带上用户确认。
 */
internal fun Context.isBatteryUnrestricted(prefs: SharedPreferences): Boolean =
  isIgnoringBatteryOptimizations() || isBatteryConfirmed(prefs)

/** 某项授权当前是否已经拿到(从系统页面返回后会重新算一遍)。 */
internal fun Context.isTaskDone(prefs: SharedPreferences, task: PermTask): Boolean = when (task) {
  PermTask.Notifications -> notificationsEnabled()
  PermTask.Battery -> isBatteryUnrestricted(prefs)
  PermTask.AllFiles -> hasAllFilesAccess()
  // 自启动没有任何可查询的接口,引导过就算完成
  PermTask.AutoStart -> prefs.getBoolean(KEY_AUTOSTART_DONE, false)
}

internal fun Context.allTasksDone(prefs: SharedPreferences): Boolean =
  PermTask.entries.all { isTaskDone(prefs, it) }

internal fun Context.unfinishedTaskCount(prefs: SharedPreferences): Int =
  PermTask.entries.count { !isTaskDone(prefs, it) }

/** 协议已确认,且四项授权都拿到(或用户明确选了稍后),就不再走激活流程。 */
internal fun Context.isActivationCompleted(prefs: SharedPreferences): Boolean =
  prefs.getBoolean(KEY_AGREEMENT_ACCEPTED, false) &&
      (allTasksDone(prefs) || prefs.getBoolean(KEY_ACTIVATION_SKIPPED, false))

internal fun Context.isAgreementAccepted(prefs: SharedPreferences): Boolean =
  prefs.getBoolean(KEY_AGREEMENT_ACCEPTED, false)

internal fun Context.acceptAgreement(prefs: SharedPreferences) {
  prefs.edit().putBoolean(KEY_AGREEMENT_ACCEPTED, true).apply()
}

/** 用户选择「稍后再说」:记下跳过标记,之后启动直接进主界面。 */
internal fun Context.skipActivation(prefs: SharedPreferences) {
  prefs.edit().putBoolean(KEY_ACTIVATION_SKIPPED, true).apply()
}

/** 从激活页重新进入时清掉跳过标记(供后续入口复用)。 */
internal fun Context.resetActivationSkip(prefs: SharedPreferences) {
  prefs.edit().putBoolean(KEY_ACTIVATION_SKIPPED, false).apply()
}

/** 激活流程从第几页开始:协议没确认过从第 1 页开始,否则直接到权限页。 */
internal fun Context.activationStartPage(prefs: SharedPreferences): Int =
  if (isAgreementAccepted(prefs)) 1 else 0

/** 依次尝试这些 intent,返回是否有任意一个能打开。 */
private fun Context.launchFirstAvailable(intents: List<Intent>): Boolean {
  for (intent in intents) {
    try {
      startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return true
    } catch (e: Exception) {
      // 该机型没有这个页面,试下一个
    }
  }
  return false
}

/** 通知设置页的候选 intent:不同机型/版本支持的 extra 不一样,逐个尝试。 */
internal fun notificationSettingsIntents(context: Context): List<Intent> = buildList {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    add(
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
    add(
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra("android.provider.extra.APP_UID", context.applicationInfo.uid)
    )
  }
  // 宏:"应用通知管理"页面(部分 MIUI / 旧版本只认这个)
  add(
    Intent("android.settings.APP_NOTIFICATION_SETTINGS")
      .putExtra("app_package", context.packageName)
      .putExtra("app_uid", context.applicationInfo.uid)
  )
  add(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
      .setData(Uri.parse("package:${context.packageName}"))
  )
}

/**
 * 能否用运行时权限弹窗直接申请通知权限。
 *
 * Android 13+ 只有 targetSdk >= 33 的应用才能用 requestPermissions() 弹窗;
targetSdk 更低的旧应用系统会忽略这次请求(不弹任何东西),
 * 只能让用户到通知设置页手动开启。
 */
internal fun Context.canRequestNotificationPermission(): Boolean =
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      applicationInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU &&
      !hasNotificationPermission()

/**
 * 打开某个授权对应的系统页面。返回是否成功打开了页面——返回 false 说明该机型
 * 连兜底的应用详情页都打不开,调用方应把它当作「已引导」跳过。
 */
internal fun Context.launchTaskGuide(prefs: SharedPreferences, task: PermTask): Boolean = when (task) {
  PermTask.Notifications -> launchFirstAvailable(notificationSettingsIntents(this))

  PermTask.Battery -> launchFirstAvailable(
    buildList {
      add(
        Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:$packageName")
        )
      )
      add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
      // 澎湃 OS / MIUI:直接落到本应用的「省电策略」页,那里才有「无限制」
      MIUI_BATTERY_COMPONENTS.forEach {
        add(
          Intent().setComponent(it)
            .putExtra("package_name", packageName)
            .putExtra("package_label", applicationInfo.loadLabel(packageManager).toString())
        )
      }
      add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
  )

  PermTask.AllFiles -> launchFirstAvailable(
    buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        add(
          Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
          )
        )
        add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      } else {
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
      }
    }
  )

  PermTask.AutoStart -> {
    // 没有可查询的状态,引导过即视为完成
    prefs.edit().putBoolean(KEY_AUTOSTART_DONE, true).apply()
    launchFirstAvailable(
      buildList {
        AUTO_START_COMPONENTS.forEach { add(Intent().setComponent(it)) }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
      }
    )
  }
}
