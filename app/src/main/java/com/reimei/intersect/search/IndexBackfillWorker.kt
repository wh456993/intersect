package com.reimei.intersect.search

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * 后台索引回填的执行体:一次 [SearchIndexer.backfill]。
 *
 * 约束(充电 + 息屏)与排程(周期/单次)都在 [SearchIndexer] 里配置,本类只管执行与结果:
 * - 成功 → success;
 * - 意外异常 → retry(指数退避,WorkManager 跨重启记住),**绝不让异常冒泡** ——
 *   后台任务崩溃只会在系统日志里留一条噪音,重试才有机会把索引补齐;
 * - 协程取消(CancellationException)照常上抛,不吞 —— 那是正常的生命周期信号。
 */
class IndexBackfillWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    return try {
      SearchIndexer.backfill(applicationContext)
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w("SearchIndexer", "backfill failed, will retry", t)
      Result.retry()
    }
  }
}
