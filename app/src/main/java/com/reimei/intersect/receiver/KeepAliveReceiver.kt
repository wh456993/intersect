package com.reimei.intersect.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reimei.intersect.data.BackgroundSetting
import com.reimei.intersect.service.startKeepAlive

/**
 * 后台常驻的拉起入口,两类广播共用:
 * - 系统广播:开机完成、应用被覆盖安装后重新拉起常驻服务;
 * - [ACTION_RESTART]:被用户从最近任务划掉后,由 [KeepAliveService] 挂的闹钟触发。
 *
 * 只有设置里的「后台常驻」还开着才动手,关掉之后残留的闹钟不起作用。
 */
class KeepAliveReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    BackgroundSetting.init(context)
    if (!BackgroundSetting.keepAlive) return
    context.startKeepAlive()
  }

  companion object {
    const val ACTION_RESTART = "com.reimei.intersect.action.RESTART_KEEP_ALIVE"
  }
}
