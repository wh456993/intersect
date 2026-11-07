package com.reimei.intersect.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导出 / 恢复的端到端测试(真机跑,读写的是应用自己的 SharedPreferences)。
 *
 * 每个用例前后都会把三份存储的原文备份再还原,所以跑测试不会把设备上的数据搞乱。
 * 覆盖的是「用户真正关心」的三件事:备份能读回来、口令不对打不开、合并与覆盖的区别。
 */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** 三份存储的 prefs 名与 key,和三个 Store 里的私有常量一致。 */
  private val stores = listOf(
    "chat_store" to "chats",
    "friends_store" to "friends",
    "api_key_store" to "keys",
  )
  private val snapshot = mutableMapOf<String, String?>()
  private var exported: File? = null

  private val password = "246810"

  /**
   * 打包并写到缓存目录:生产里用户在选择器里挑位置,测试里用一个文件等价替代。
   */
  private fun exportFile(sections: Set<BackupSection>, nowMs: Long = System.currentTimeMillis()): File {
    val archive = BackupService.prepare(context, password, sections, nowMs)
    return BackupFiles.writeTo(File(context.cacheDir, archive.fileName), archive.content)
  }

  @Before
  fun snapshotStores() {
    stores.forEach { (prefs, key) ->
      snapshot["$prefs/$key"] = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        .getString(key, null)
    }
  }

  @After
  fun restoreStores() {
    stores.forEach { (prefs, key) ->
      val value = snapshot["$prefs/$key"]
      val edit = context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
      if (value == null) edit.remove(key) else edit.putString(key, value)
      edit.commit()
    }
    exported?.delete()
  }

  private fun seed() {
    val friend = User("friend_a", "角色 A", 0, signature = "签名 A", role = "身份 A", personality = "性格 A")
    FriendsStore.save(context, listOf(DawnAssistant.assistant(), friend))
    ChatStore.save(
      context,
      listOf(
        Chat(friend, mutableListOf(Msg(User.Me, "在吗"), Msg(friend, "在"))).apply {
          apiKeyId = "key_1"
        }
      )
    )
    ApiKeyStore.save(
      context,
      listOf(ApiKey("key_1", "我的 KEY", "deepseek-chat", "https://api.deepseek.com/v1", "sk-12345678"))
    )
  }

  @Test
  fun exportRoundTrip() {
    seed()
    val archive = BackupService.prepare(
      context,
      password,
      BackupSection.entries.toSet(),
      nowMs = 1_700_000_000_000L
    )
    val file = BackupFiles.writeTo(File(context.cacheDir, archive.fileName), archive.content)
    exported = file
    assertTrue(file.exists())
    assertTrue(file.length() > 0L)
    assertEquals(listOf(BackupSection.Characters, BackupSection.Chats, BackupSection.ApiKeys), archive.sections)
    assertEquals(2, archive.counts.characters)
    assertEquals(1, archive.counts.chats)
    assertEquals(2, archive.counts.messages)
    assertEquals(1, archive.counts.apiKeys)

    val preview = BackupService.preview(BackupDocument.of(file))
    assertEquals(file.name, preview.name)
    assertEquals(1_700_000_000_000L, preview.epochMs)
    assertEquals(1, preview.counts.chats)

    val payload = BackupService.verify(BackupDocument.of(file), password)
    assertEquals(setOf("dawn_assistant", "friend_a"), payload.characters!!.map { it.id }.toSet())
    assertEquals("身份 A", payload.characters!!.first { it.id == "friend_a" }.role)
    val chat = payload.chats!!.single()
    assertEquals(listOf("在吗", "在"), chat.msgs.map { it.text })
    assertEquals("key_1", chat.apiKeyId)
    assertEquals("sk-12345678", payload.apiKeys!!.single().key)
  }

  @Test
  fun wrongPasswordCannotOpen() {
    seed()
    val file = exportFile(setOf(BackupSection.Chats))
    exported = file
    try {
      BackupService.verify(BackupDocument.of(file), "111111")
      fail("错误口令不应该解出内容")
    } catch (e: BackupCryptoException) {
      assertTrue(e.message.orEmpty().contains("口令"))
    }
  }

  @Test
  fun charactersOnlyBackup() {
    seed()
    val archive = BackupService.prepare(context, password, setOf(BackupSection.Characters))
    val file = BackupFiles.writeTo(File(context.cacheDir, archive.fileName), archive.content)
    exported = file
    assertEquals(listOf(BackupSection.Characters), archive.sections)
    val payload = BackupService.verify(BackupDocument.of(file), password)
    assertNotNull(payload.characters)
    assertEquals(null, payload.chats)
    assertEquals(null, payload.apiKeys)
  }

  @Test
  fun mergeKeepsLocalExtras() {
    seed()
    val file = exportFile(BackupSection.entries.toSet())
    exported = file

    // 导出之后本机又多了一个角色和一条 KEY,恢复时它们不能消失
    val extra = User("friend_b", "角色 B", 0)
    FriendsStore.save(context, FriendsStore.load(context) + extra)
    ApiKeyStore.save(
      context,
      ApiKeyStore.load(context) + ApiKey("key_2", "第二个 KEY", "qwen-max", "https://a/v1", "sk-abcdefgh")
    )

    val restored = BackupService.restore(context, BackupDocument.of(file), password, RestoreMode.Merge)
    assertEquals(RestoreMode.Merge, restored.mode)
    // 备份里的角色都已在,只是被覆盖了一遍
    assertEquals(0, restored.charactersAdded)
    assertEquals(2, restored.charactersUpdated)
    assertEquals(0, restored.apiKeysAdded)
    assertEquals(1, restored.apiKeysUpdated)

    val ids = FriendsStore.load(context).map { it.id }.toSet()
    assertTrue(ids.containsAll(setOf("dawn_assistant", "friend_a", "friend_b")))
    assertEquals(2, ApiKeyStore.load(context).size)
  }

  @Test
  fun mergeAddsMissingMessages() {
    seed()
    val file = exportFile(setOf(BackupSection.Chats))
    exported = file

    // 本机的这个会话只剩一条消息(比如删过),恢复应该把另一条补回来
    val friend = FriendsStore.load(context).first { it.id == "friend_a" }
    ChatStore.save(context, listOf(Chat(friend, mutableListOf(Msg(User.Me, "在吗")))))

    val restored = BackupService.restore(context, BackupDocument.of(file), password, RestoreMode.Merge)
    assertEquals(1, restored.chatsMerged)
    assertEquals(1, restored.messagesAdded)
    assertEquals(listOf("在吗", "在"), ChatStore.load(context)!!.single().msgs.map { it.text })
  }

  @Test
  fun replaceDropsLocalOnly() {
    seed()
    val file = exportFile(BackupSection.entries.toSet())
    exported = file

    FriendsStore.save(context, FriendsStore.load(context) + User("friend_c", "角色 C", 0))
    ApiKeyStore.save(context, ApiKeyStore.load(context) + ApiKey("key_3", "第三个", "m", "https://a/v1", "sk-zzzzzzzz"))

    val restored = BackupService.restore(context, BackupDocument.of(file), password, RestoreMode.Replace)
    assertEquals(RestoreMode.Replace, restored.mode)

    val ids = FriendsStore.load(context).map { it.id }
    assertFalse(ids.contains("friend_c"))
    assertTrue(ids.contains("friend_a"))
    assertEquals(listOf("key_1"), ApiKeyStore.load(context).map { it.id })
  }

  @Test
  fun emptySectionsRejected() {
    seed()
    try {
      BackupService.prepare(context, password, emptySet())
      fail("一个内容都没勾选时不应该产出备份")
    } catch (e: BackupFormatException) {
      assertTrue(e.message.orEmpty().contains("至少"))
    }
  }

  /**
   * 真实的 SAF 写入:选择器给的就是这种 content:// Uri。
   * 这条路径不能被「写到缓存文件」的用例代替—— 曾经的 bug 就是写进 content:// 后文件是空的。
   */
  @Test
  fun exportToContentUri() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    seed()
    val archive = BackupService.prepare(context, password, BackupSection.entries.toSet())
    val resolver = context.contentResolver
    val uri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, archive.fileName)
        put(MediaStore.Downloads.MIME_TYPE, BackupFiles.MIME_TYPE)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    )
    if (uri == null) {
      fail("无法在下载目录创建测试文件")
      return
    }
    try {
      val result = BackupService.export(context, uri, archive.content)
      assertEquals(archive.fileName, result.name)

      val text = BackupFiles.read(context, uri)
      assertEquals(archive.content, text)
      // 写进去的东西必须能被自己读回来
      val preview = BackupService.preview(BackupFiles.document(context, uri))
      assertEquals(2, preview.counts.characters)
      assertNotNull(BackupService.verify(BackupFiles.document(context, uri), password))
    } finally {
      resolver.delete(uri, null, null)
    }
  }

  /** DocumentsUI 保存到下载目录时给的就是这种 externalstorage document Uri。 */
  @Test
  fun exportToDownloadsDocument() {
    if (!Environment.isExternalStorageManager()) return
    seed()
    val name = "intersect-test-${System.currentTimeMillis()}.ibk"
    val uri = Uri.parse(
      "content://com.android.externalstorage.documents/document/primary%3ADownload%2F$name"
    )
    val archive = BackupService.prepare(context, password, setOf(BackupSection.Characters))
    val target = File(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
      name
    )
    try {
      BackupService.export(context, uri, archive.content)
      assertTrue("文件不存在", target.exists())
      assertTrue("文件是空的:${target.length()} 字节", target.length() > 0L)
      assertEquals(archive.content, target.readText())
    } finally {
      target.delete()
    }
  }

  /**
   * 暂存的备份必须活过「进程被重建」:回调里拿到的必须是那份真的内容。
   * 之前放在内存(remember / 单例):MIUI 在选择器占前台时回收进程,
   * 回传后只能默默不写 —— 选择器建出一个 0 字节的空文件。
   */
  @Test
  fun stagedDraftSurvivesProcessDeath() {
    seed()
    val archive = BackupService.prepare(context, password, setOf(BackupSection.Characters))
    val staged = BackupDrafts.stage(context, archive)
    assertEquals(archive.fileName, staged.name)
    assertTrue("暂存的备份不能是空的", staged.length() > 0L)

    // 模拟进程重建:内存里的东西全没了,只能靠缓存目录里这个文件
    val taken = BackupDrafts.take(context)
    assertNotNull("暂存的备份不应该丢", taken)
    assertEquals(archive.content, taken!!.readText())
    // 内容原样可解,说明暂存下来的是完整可用的一份备份
    assertEquals(setOf("dawn_assistant", "friend_a"),
      BackupService.verify(BackupDocument.of(taken), password).characters!!.map { it.id }.toSet())

    BackupDrafts.clear(context)
    assertEquals(null, BackupDrafts.take(context))
  }

  @Test
  fun exportOverwritesLongerFile() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    seed()
    val archive = BackupService.prepare(context, password, setOf(BackupSection.ApiKeys))
    val resolver = context.contentResolver
    val uri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, archive.fileName)
        put(MediaStore.Downloads.MIME_TYPE, BackupFiles.MIME_TYPE)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    )
    if (uri == null) {
      fail("无法在下载目录创建测试文件")
      return
    }
    try {
      // 先塞一段比备份更长的内容
      BackupFiles.write(context, uri, "x".repeat(archive.content.length + 4096))
      BackupService.export(context, uri, archive.content)
      assertEquals(archive.content, BackupFiles.read(context, uri))
    } finally {
      resolver.delete(uri, null, null)
    }
  }
}
