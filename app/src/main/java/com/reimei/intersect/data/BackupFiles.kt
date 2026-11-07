package com.reimei.intersect.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件的读写。
 *
 * 备份必须落在用户自己挑的位置:导出走系统的「保存到」选择器,恢复走「打开文件」选择器。
 * 之前是靠猜目录(用户主目录 → 应用外部目录 → 应用私有目录)硬写,一旦没拿到全部文件权限
 * 就会静默退回到应用私有目录,卸载应用时备份跟着一起没了 —— 所以写入只保留 SAF 这一条路。
 *
 * [legacyDirs] 只用于「扫描旧版本留下的备份」:那些文件还在,得让用户能找回来。
 */
object BackupFiles {
  const val DIR_NAME = "intersect-backup"
  const val EXTENSION = ".ibk"

  /** .ibk 没有注册过的 MIME,交给选择器按二进制存。 */
  const val MIME_TYPE = "application/octet-stream"

  fun fileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "intersect-backup-$stamp$EXTENSION"
  }

  // ------------------------------------------------------------ 选择器给的 Uri

  /**
   * 把内容写到用户选的位置;目标已存在时整份覆盖。
   *
   * 写完要回头确认真的写进去了:选择器在我们返回之前就把文件建好了(0 字节),
   * 如果写入静默失败,用户手上拿到的就是一个“看似成功”的空文件。
   *
   * @throws IllegalStateException 选的位置打不开、写不进去或写完仍为空
   */
  fun write(context: Context, uri: Uri, content: String) {
    val resolver = context.contentResolver
    val stream = openForWrite(context, uri) ?: error("无法写入所选位置")
    stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }

    // 写法不同的提供方对 "wt" 的支持不一样:写完后一律按文件的真实大小核对一遍
    val written = sizeOnDisk(context, uri)
    if (written != null && written == 0L) {
      error("写入后文件仍是空的")
    }
    if (written != null && written < content.toByteArray(Charsets.UTF_8).size) {
      error("只写入了 $written 字节")
    }
  }

  /**
   * 先试截断写("wt"),提供方不认就退回普通写("w")。
   * 有的 DocumentsProvider 只实现了后者,直接用 "wt" 会直接报错。
   */
  private fun openForWrite(context: Context, uri: Uri): java.io.OutputStream? {
    val resolver = context.contentResolver
    return runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
      ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
  }

  /** 文件在磁盘上的真实大小;提供方查不到时返回 null(不做判断,不误报失败)。 */
  private fun sizeOnDisk(context: Context, uri: Uri): Long? =
    runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }
      .getOrNull()
      ?.takeIf { it >= 0L }

  /**
   * 删掉一个自己刚创建、仍是 0 字节的文档。
   *
   * 用于「选择器已经把文件建好了,但我们没能写出内容」的补救:留着只会让用户以为备份成功。
   * 只在长度为 0 时才删,碰不到任何有内容的文件。
   */
  fun deleteIfEmpty(context: Context, uri: Uri): Boolean = runCatching {
    if (sizeOnDisk(context, uri) != 0L) return@runCatching false
    android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
  }.getOrDefault(false)

  fun read(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
  }

  /** 选择器里的显示名;查不到就退回到 Uri 末段。 */
  fun displayName(context: Context, uri: Uri): String =
    query(context, uri, OpenableColumns.DISPLAY_NAME)
      ?: uri.lastPathSegment?.substringAfterLast('/')
      ?: uri.toString()

  /** 选择器给出的字节数;有的提供方不给,由 [BackupDocument] 兜底。 */
  fun sizeOf(context: Context, uri: Uri): Long =
    query(context, uri, OpenableColumns.SIZE)?.toLongOrNull() ?: 0L

  fun document(context: Context, uri: Uri): BackupDocument =
    BackupDocument(displayName(context, uri), sizeOf(context, uri)) { read(context, uri) }

  private fun query(context: Context, uri: Uri, column: String): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
  }.getOrNull()

  // ------------------------------------------------------------ 本机文件

  fun read(file: File): String = file.readText(Charsets.UTF_8)

  /** 直接写本机文件:测试里替身「用户选了某个位置」用。 */
  fun writeTo(file: File, content: String): File = file.apply {
    parentFile?.mkdirs()
    writeText(content, Charsets.UTF_8)
  }

  /** 旧版本硬写过的那几个目录里还剩的备份,按修改时间从新到旧。 */
  fun list(context: Context): List<File> {
    val seen = mutableMapOf<String, File>()
    legacyDirs(context).forEach { dir ->
      dir.listFiles().orEmpty().forEach { file ->
        if (file.isFile && file.name.endsWith(EXTENSION) && file.length() > 0L) {
          seen.putIfAbsent(file.absolutePath, file)
        }
      }
    }
    return seen.values.sortedByDescending { it.lastModified() }
  }

  private fun legacyDirs(context: Context): List<File> {
    val dirs = mutableListOf<File>()
    @Suppress("DEPRECATION")
    val shared = Environment.getExternalStorageDirectory()
    if (shared != null) dirs.add(File(shared, DIR_NAME))
    context.getExternalFilesDir(null)?.let { dirs.add(File(it, DIR_NAME)) }
    dirs.add(File(context.filesDir, DIR_NAME))
    return dirs
  }
}

/**
 * 一份备份文件的内容入口:本机扫描到的 File 与选择器给的 Uri 都先读成文本,
 * 解析、解密、写回共用同一套逻辑。文本只读一次,预览与恢复复用同一份。
 */
class BackupDocument(
  val name: String,
  private val declaredSize: Long,
  private val loader: () -> String,
) {
  private val cached: String by lazy(loader)

  fun text(): String = cached

  /** 字节数:提供方没给(或给 0)时按读出来的长度兜底。 */
  val size: Long get() = if (declaredSize > 0L) declaredSize else text().length.toLong()

  companion object {
    fun of(file: File): BackupDocument =
      BackupDocument(file.name, file.length()) { BackupFiles.read(file) }
  }
}
