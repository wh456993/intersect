package com.reimei.intersect.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者日志落盘。
 *
 * 优先写到用户目录(/sdcard)下的「intersect日志」文件夹,和相册、下载并列,方便用文件管理器找;
 * 应用没有被授予全部文件权限(或者外部存储不可挂载)时,退回本应用的外部目录,
 * 那里不需要任何权限。返回值就是真正写成功的那个文件。
 */
object DevLogFiles {
  const val DIR_NAME = "intersect日志"

  fun save(context: Context, lines: List<String>): File {
    val content = lines.joinToString("\n") + "\n"
    for (dir in candidateDirs(context)) {
      val file = runCatching {
        dir.mkdirs()
        File(dir, fileName()).apply { writeText(content, Charsets.UTF_8) }
      }.getOrNull()
      if (file != null && file.length() > 0) return file
    }
    // 两处都写不进去:抛出给调用方,界面上会显示失败
    error("没有可写的外部目录")
  }

  private fun candidateDirs(context: Context): List<File> {
    val dirs = mutableListOf<File>()
    @Suppress("DEPRECATION")
    val shared = Environment.getExternalStorageDirectory()
    if (shared != null && shared.canWrite()) dirs.add(File(shared, DIR_NAME))
    context.getExternalFilesDir(null)?.let { dirs.add(File(it, DIR_NAME)) }
    dirs.add(File(File(context.filesDir, "logs"), DIR_NAME))
    return dirs
  }

  private fun fileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    // 文件夹本身就叫 intersect日志,里面的文件同名加时间戳,方便区分哪一次
    return "$DIR_NAME-$stamp.log"
  }
}
