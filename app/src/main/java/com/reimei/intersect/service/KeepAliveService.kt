package com.reimei.intersect.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.reimei.intersect.MainActivity
import com.reimei.intersect.R
import com.reimei.intersect.data.BackgroundSetting
import com.reimei.intersect.data.LangSetting
import com.reimei.intersect.i18n.t
import com.reimei.intersect.receiver.KeepAliveReceiver

/**
 * 后台常驻服务(前台服务 + 常驻通知)。
 *
 * 保活分三路,逐路弱化以保证「尽力而为」而不是硬撑:
 * 1. [onStartCommand] 返回 START_STICKY:进程被系统回收后由系统择机拉回;
 * 2. [onTaskRemoved]:用户从「最近任务」划掉时,挂一个几秒后的一次性闹钟把自己唤醒;
 * 3. [KeepAliveReceiver] 在开机 / 应用更新后重新启动。
 *
 * 设置里关掉「后台常驻」后不再做任何拉起,服务自己停掉,所以不会出现关不掉的情况。
 */
class KeepAliveService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    BackgroundSetting.init(this)
    // 常驻通知的文案跟着界面语言走;没有打开过界面(开机拉起)时也要先读出来
    LangSetting.init(this)
    startAsForeground()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 开关已经关掉,却又被残留的闹钟 / 系统重启唤起:直接退出
    if (!BackgroundSetting.keepAlive) {
      stopSelf()
      return START_NOT_STICKY
    }
    startAsForeground()
    return START_STICKY
  }

  /** 从最近任务里被划掉:短时间内自己再起来一次。 */
  override fun onTaskRemoved(rootIntent: Intent?) {
    if (BackgroundSetting.keepAlive) scheduleRestart()
    super.onTaskRemoved(rootIntent)
  }

  /** 常驻通知:低优先级、不发声、不可手动划掉。 */
  private fun startAsForeground() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java)
      if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, t("后台常驻"), NotificationManager.IMPORTANCE_MIN).apply {
            description = t("保持应用在后台运行")
            setShowBadge(false)
          }
        )
      }
    }
    val contentIntent = PendingIntent.getActivity(
      this,
      REQUEST_CONTENT,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ui_tab_chat_on)
      .setContentTitle(t("intersect 正在后台运行"))
      .setContentText(t("保持在线,及时接收消息"))
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setShowWhen(false)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .build()
    startForeground(NOTIFICATION_ID, notification)
  }

  /**
   * 挂一个 [RESTART_DELAY_MS] 之后触发的一次性闹钟。
   *
   * 用 RTC_WAKEUP 而不是 ELAPSED_REALTIME:即使期间设备睡下去也要按真实时间唤醒。
   * Android 12+ 的精确闹钟需要额外授权,被拒时退回普通闹钟(可能晚几秒到几分钟)。
   */
  private fun scheduleRestart() {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(this, KeepAliveReceiver::class.java)
      .setAction(KeepAliveReceiver.ACTION_RESTART)
    val pendingIntent = PendingIntent.getBroadcast(
      this,
      REQUEST_RESTART,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    // 从当前时间算起,而不是 uptime:闹钟用的是 RTC 时基
    val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
      return
    }
    try {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    } catch (e: SecurityException) {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }
  }

  private companion object {
    const val CHANNEL_ID = "keep_alive"
    const val NOTIFICATION_ID = 1001

    /** 被划掉后多久再起来:够短才像「没被处理掉」,太长用户会以为已经退出。 */
    const val RESTART_DELAY_MS = 3_000L

    const val REQUEST_CONTENT = 1
    const val REQUEST_RESTART = 2
  }
}

/** 启动常驻服务。系统若拒绝在后台启动前台服务,这里静默放弃,等下次回到前台再试。 */
fun Context.startKeepAlive() {
  val intent = Intent(this, KeepAliveService::class.java)
  try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(intent)
    } else {
      startService(intent)
    }
  } catch (e: Exception) {
    // Android 12+ 对后台启动前台服务有限制;targetSdk 30 一般不受影响,兜底不抛出到界面
  }
}

fun Context.stopKeepAlive() {
  stopService(Intent(this, KeepAliveService::class.java))
}
