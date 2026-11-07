package com.reimei.intersect.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 10 分钟一次的活跃采样。
 *
 * 和 Web 版的 `setInterval` 一样,采样的内容是**采样那一刻**的前后台状态:
 * 一个横跨「前台 → 后台」的 10 分钟区间,只会在区间结束时留下后台那一条。
 * 因此「前台总时长 = 前台采样数 × 10 分钟」是估算值,精度正是采样周期。
 *
 * 进程被杀后定时器随之消失,这段时间不会有采样——那是真实的「没在用」。
 * 状态由 [com.reimei.intersect.MainActivity] 的 onStart / onStop 推进。
 */
object Heartbeat {
  /** 采样周期:10 分钟。 */
  const val SAMPLE_INTERVAL_MS = 600_000L

  @Volatile
  private var foreground = true

  @Volatile
  private var scope: CoroutineScope? = null

  /**
   * 启动采样循环;重复调用无副作用(已在跑就什么都不做)。
   *
   * 第一次写入发生在**启动后满 10 分钟**,和 `setInterval` 的行为一致:
   * 不足 10 分钟的会话不留采样,也就不会被算成一段使用时长。
   */
  fun start(context: Context) {
    if (scope != null) return
    synchronized(this) {
      if (scope != null) return
      val appContext = context.applicationContext
      val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      scope = created
      created.launch {
        while (true) {
          delay(SAMPLE_INTERVAL_MS)
          UsageLog.sample(appContext, foreground)
        }
      }
    }
  }

  /** 界面可见性变化时调用:之后每次采样都按这个状态记。 */
  fun setForeground(foreground: Boolean) {
    this.foreground = foreground
  }

  /** 当前采样状态,统计页用来说明「最近一次采样记的是什么」。 */
  fun isForeground(): Boolean = foreground
}
