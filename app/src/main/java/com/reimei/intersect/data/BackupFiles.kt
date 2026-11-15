package com.reimei.intersect.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件的读写。
 *
 * 备份必须落在用户自己挑的位置:导出走系统的「保存到」选择器,导入走「打开文件」选择器。
 * 之前是靠猜目录(用户主目录 → 应用外部目录 → 应用私有目录)硬写,一旦没拿到全部文件权限
 * 就会静默退回到应用私有目录,卸载应用时备份跟着一起没了 —— 所以写入只保留 SAF 这一条路。
 *
 * 备份是**明文 JSON**(`.json`),不再有 `.ibk` 那种加密容器:文件名直接说明这是谁、什么内容,
 * 用户在自己的文件管理器里一眼就能看出哪份是什么、也能直接用文本编辑器打开。
 */
object BackupFiles {
  const val EXTENSION = ".json"

  /** 导出的是纯文本 JSON:给选择器一个真实存在的 MIME,文件管理器里才会正常关联。 */
  const val MIME_TYPE = "application/json"

  /** 「打开文件」选择器的过滤条件:各家文件管理器给 .json 报的 MIME 不完全一致,都给上。 */
  val PICKER_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

  /** 文件名里的软件正式名:只有模型配置与整包备份用得上(角色卡 / 聊天记录只看角色名)。 */
  const val APP_NAME = "Intersect"

  /** 角色名在文件名里最多占多少字:再长会被截断,免得撑爆文件管理器的显示。 */
  private const val MAX_SUBJECT = 40

  /** 各文件系统都不接受的字符:角色名是用户随手写的,不能原样进文件名。 */
  private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

  /**
   * 导出文件名:**角色名 + 内容类别 + 导出时间**,不带应用名。
   *
   * - 聊天记录 / 角色卡 → `小红-聊天记录-20250101-120000.json`、`小红-角色卡-20250101-120000.json`
   * - 模型配置 → `Intersect-API_Key-20250101-120000.json`
   * - 整包 → `Intersect-软件备份-20250101-120000.json`
   *
   * [subject] 是所选角色 / 会话的角色名;为 null(整类导出)时只留类别名。
   */
  fun fileName(
    section: BackupSection,
    subject: String? = null,
    nowMs: Long = System.currentTimeMillis(),
  ): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
    val head = when (section) {
      BackupSection.Chats -> listOfNotNull(safeSubject(subject), "聊天记录")
      BackupSection.Characters -> listOfNotNull(safeSubject(subject), "角色卡")
      BackupSection.Memories -> listOfNotNull(safeSubject(subject), "记忆库")
      BackupSection.ApiKeys -> listOf(APP_NAME, "API_Key")
      BackupSection.Full -> listOf(APP_NAME, "软件备份")
    }.joinToString("-")
    return "$head-$stamp$EXTENSION"
  }

  /** 把角色名收拾成能进文件名的样子:去掉保留字符与控制字符、掐掉首尾空白与点、限长。 */
  private fun safeSubject(raw: String?): String? {
    val cleaned = raw.orEmpty()
      // 控制字符(含 C1)与双向控制符都要去掉:后者能在文件管理器里把 .json 后缀“搬”到前面去
      .filter { !it.isISOControl() && it !in BIDI_CONTROLS }
      // 路径分隔符与各文件系统的保留字符
      .filter { it !in ILLEGAL }
      .trim()
      .trim('.')
    val cut = if (cleaned.length > MAX_SUBJECT) {
      // 截断不能把 emoji 这类代理对切成半个:留下高位半截会变成乱码方块
      val head = cleaned.take(MAX_SUBJECT)
      if (head.last().isHighSurrogate()) head.dropLast(1) else head
    } else {
      cleaned
    }
    return cut.trim().trim('.').ifEmpty { null }
  }

  /** 双向控制符:U+202A–U+202E / U+2066–U+2069。它们不占宽度,却会骗过文件名显示。 */
  private const val BIDI_CONTROLS = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

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
}

/**
 * 一份备份文件的内容入口:选择器给的 Uri 先读成文本,
 * 解析、写回共用同一套逻辑。文本只读一次,预览与恢复复用同一份。
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
