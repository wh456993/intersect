package com.reimei.intersect.data

import android.content.Context
import java.io.File

/**
 * 「已经点过开始备份、正在等用户在选择器里挑位置」的那份备份。
 *
 * 为什么必须落盘而不能只放在内存里(两种写法都试过、都被真机打过脸):
 * 1. 放在界面的 remember 里:选择器把应用推到后台,回到应用时组合可能已重建,内容跟着消失;
 * 2. 放在进程级单例里:MIUI 等厂商的省电策略会在选择器占据前台的这段时间直接回收我们的进程,
 *    结果回传时进程已经是新起的,单例是空的。
 * 两种情况下选择器都已经把目标文件建好了(0 字节),于是用户拿到的就是「无效的空文件」。
 *
 * 缓存目录里的暂存文件活过进程重建:打包、加密、序列化都在选位置之前做完,
 * 回传后只需要「把暂存文件的内容抄到用户选的位置」。抄完(或用户取消)就删掉。
 */
object BackupDrafts {
  private const val DIR_NAME = "backup-draft"

  private fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

  /** 把打包好的备份暂存到磁盘,等选择器回传 Uri 后再抄过去。文件名即建议的文件名。 */
  fun stage(context: Context, archive: BackupArchive): File {
    val dir = dir(context)
    dir.deleteRecursively()
    dir.mkdirs()
    return File(dir, archive.fileName).apply { writeText(archive.content, Charsets.UTF_8) }
  }

  /** 暂存的那份备份;没有(或已被取走)时返回 null。 */
  fun take(context: Context): File? =
    dir(context).listFiles().orEmpty().firstOrNull { it.isFile && it.length() > 0L }

  fun clear(context: Context) {
    dir(context).deleteRecursively()
  }
}
