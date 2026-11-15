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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导出 / 导入的端到端测试(真机跑,读写的是应用自己的 SharedPreferences)。
 *
 * 每个用例前后都会把各份存储的原文备份再还原,所以跑测试不会把设备上的数据搞乱。
 * 覆盖的是「用户真正关心」的几件事:每一类都能原样带走、合并与覆盖的区别、
 * 导入一类不会动到别类、旧版 `.ibk` 会被明确拒绝。
 */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** 参与备份的四份 prefs,和四个 Store 里的私有常量一致。 */
  private val prefsNames = listOf("chat_store", "friends_store", "api_key_store", "immediate_memory")
  private val snapshot = mutableMapOf<String, Map<String, *>>()
  private var exported: File? = null

  /** 打包并写到缓存目录:生产里用户在选择器里挑位置,测试里用一个文件等价替代。 */
  private fun exportFile(section: BackupSection, nowMs: Long = System.currentTimeMillis()): File {
    val archive = BackupService.prepare(context, section, nowMs)
    return BackupFiles.writeTo(File(context.cacheDir, archive.fileName), archive.content)
      .also { exported = it }
  }

  @Before
  fun snapshotStores() {
    prefsNames.forEach { name ->
      snapshot[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
    }
  }

  @After
  fun restoreStores() {
    prefsNames.forEach { name ->
      val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
      val edit = prefs.edit()
      prefs.all.keys.forEach { edit.remove(it) }
      snapshot[name]?.forEach { (key, value) ->
        when (value) {
          is String -> edit.putString(key, value)
          is Int -> edit.putInt(key, value)
          is Long -> edit.putLong(key, value)
          is Boolean -> edit.putBoolean(key, value)
          is Float -> edit.putFloat(key, value)
          else -> Unit
        }
      }
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
      listOf(ApiKey("key_1", "我的 KEY", "deepseek-flash", "https://api.deepseek.com/v1", "sk-12345678"))
    )
    MemoryStore.save(
      context,
      ImmediateMemoryState(
        friendId = friend.id,
        rounds = 20,
        coveredRounds = 20,
        summaries = listOf(
          ImmediateMemory(
            id = "immediate_1",
            fromRound = 1,
            toRound = 20,
            createdAt = 1_700_000_000L,
            targetTokens = 400,
            actualTokens = 380,
            text = "他们聊了很久。",
          )
        ),
      )
    )
  }

  // ---------------------------------------------------------------- 导出

  @Test
  fun charactersExportRoundTrip() {
    seed()
    val file = exportFile(BackupSection.Characters, nowMs = 1_700_000_000_000L)
    assertTrue(file.exists())
    assertTrue(file.length() > 0L)
    assertTrue(file.name.endsWith(".json"))

    val preview = BackupService.preview(BackupDocument.of(file))
    assertEquals(file.name, preview.name)
    assertEquals(BackupSection.Characters, preview.section)
    assertEquals(1_700_000_000_000L, preview.epochMs)
    assertEquals(2, preview.counts.characters)

    val payload = BackupService.payload(BackupDocument.of(file))
    assertEquals(setOf("dawn_assistant", "friend_a"), payload.characters!!.map { it.id }.toSet())
    assertEquals("身份 A", payload.characters!!.first { it.id == "friend_a" }.role)
    // 一份文件只装一类内容
    assertNull(payload.chats)
    assertNull(payload.memories)
    assertNull(payload.apiKeys)
  }

  @Test
  fun chatsExportRoundTrip() {
    seed()
    val file = exportFile(BackupSection.Chats)
    val payload = BackupService.payload(BackupDocument.of(file))
    val chat = payload.chats!!.single()
    assertEquals(listOf("在吗", "在"), chat.msgs.map { it.text })
    assertEquals("key_1", chat.apiKeyId)
    assertNull(payload.characters)
  }

  @Test
  fun memoriesExportRoundTrip() {
    seed()
    val file = exportFile(BackupSection.Memories)
    val payload = BackupService.payload(BackupDocument.of(file))
    val state = payload.memories!!.single()
    assertEquals("friend_a", state.friendId)
    assertEquals(20, state.rounds)
    assertEquals("他们聊了很久。", state.summaries.single().text)
  }

  @Test
  fun apiKeysExportRoundTrip() {
    seed()
    val file = exportFile(BackupSection.ApiKeys)
    val payload = BackupService.payload(BackupDocument.of(file))
    assertEquals("sk-12345678", payload.apiKeys!!.single().key)
  }

  /** 导出的文件是可直接打开的明文 JSON:没有加密段、内容段是对象。 */
  @Test
  fun exportedFileIsPlainJson() {
    seed()
    val file = exportFile(BackupSection.ApiKeys)
    val text = BackupFiles.read(file)
    assertFalse(text.contains("encryption"))
    assertTrue(text.contains("\"payload\""))
  }

  // ---------------------------------------------------------------- 导入

  @Test
  fun mergeChatsAddsOnlyMissingMessages() {
    seed()
    val file = exportFile(BackupSection.Chats)

    // 本机在这之后又聊了一句:合并只补备份里多出来的,本机的新消息不能被冲掉也不会重复
    val friend = FriendsStore.load(context).first { it.id == "friend_a" }
    val current = ChatStore.load(context)!!.single()
    current.msgs.add(Msg(friend, "本机新消息"))
    ChatStore.save(context, listOf(current))

    val result = BackupService.restore(context, BackupDocument.of(file), RestoreMode.Merge)
    // 备份里的两条消息本机都有,合并不该多出来
    assertEquals(0, result.messagesAdded)
    assertEquals(3, ChatStore.load(context)!!.single().msgs.size)
  }

  @Test
  fun replaceChatsDropsExtraSessions() {
    seed()
    val file = exportFile(BackupSection.Chats)

    val other = User("friend_b", "角色 B", 0)
    ChatStore.save(
      context,
      ChatStore.load(context)!! + Chat(other, mutableListOf(Msg(User.Me, "另一段")))
    )
    assertEquals(2, ChatStore.load(context)!!.size)

    val result = BackupService.restore(context, BackupDocument.of(file), RestoreMode.Replace)
    assertEquals(1, result.chatsAdded)
    val restored = ChatStore.load(context)!!
    assertEquals(1, restored.size)
    assertEquals("friend_a", restored.single().friend.id)
  }

  @Test
  fun memoriesMergeKeepsLocalAndReplaceOverwrites() {
    seed()
    val file = exportFile(BackupSection.Memories)

    // 本机已经有了这个角色的记忆:合并应当保持不动
    val before = MemoryStore.load(context, "friend_a")
    val mergeResult = BackupService.restore(context, BackupDocument.of(file), RestoreMode.Merge)
    assertEquals(0, mergeResult.memoriesAdded)
    assertEquals(before.summaries.size, MemoryStore.load(context, "friend_a").summaries.size)

    // 清空之后再合并:记忆被补回来
    MemoryStore.remove(context, "friend_a")
    val again = BackupService.restore(context, BackupDocument.of(file), RestoreMode.Merge)
    assertEquals(1, again.memoriesAdded)
    assertEquals(1, MemoryStore.load(context, "friend_a").summaries.size)

    // 覆盖:本机多出来的角色记忆会被清掉
    MemoryStore.save(context, ImmediateMemoryState(friendId = "friend_b", rounds = 1))
    val replaceResult = BackupService.restore(context, BackupDocument.of(file), RestoreMode.Replace)
    assertEquals(1, replaceResult.memoriesAdded)
    assertEquals(1, MemoryStore.loadAll(context).size)
    assertEquals("friend_a", MemoryStore.loadAll(context).single().friendId)
  }

  /** 导入一类不能动到别类:这是「四份文件各自独立」的底线。 */
  @Test
  fun importingApiKeysDoesNotTouchOtherStores() {
    seed()
    val file = exportFile(BackupSection.ApiKeys)
    ApiKeyStore.save(context, emptyList())

    val friendsBefore = FriendsStore.load(context).map { it.id }
    val chatsBefore = ChatStore.load(context)!!.size
    BackupService.restore(context, BackupDocument.of(file), RestoreMode.Replace)

    assertEquals(1, ApiKeyStore.load(context).size)
    assertEquals(friendsBefore, FriendsStore.load(context).map { it.id })
    assertEquals(chatsBefore, ChatStore.load(context)!!.size)
  }

  /** 旧版加密备份不再支持:必须明确报错,而不是当成空备份静默恢复。 */
  @Test
  fun legacyIbkRejected() {
    val legacy = File(context.cacheDir, "intersect-backup-old.ibk").apply {
      writeText(
        """
        {
          "schema": "intersect.backup",
          "schema_version": 1,
          "sections": ["characters"],
          "encryption": {"cipher": "AES-256-GCM"},
          "payload": "c29tZS1jaXBoZXJ0ZXh0"
        }
        """.trimIndent()
      )
    }
    exported = legacy
    try {
      BackupService.restore(context, BackupDocument.of(legacy))
      fail("旧版 .ibk 应当被拒绝")
    } catch (e: BackupFormatException) {
      assertTrue(e.message!!.contains(".ibk"))
    }
  }

  // ---------------------------------------------------------------- 其它

  @Test
  fun clearChatsEmptiesStore() {
    seed()
    assertEquals(1, BackupService.clearChats(context))
    assertEquals(0, ChatStore.load(context)!!.size)
    // 角色卡与模型配置不受影响
    assertEquals(2, FriendsStore.load(context).size)
    assertEquals(1, ApiKeyStore.load(context).size)
  }

  @Test
  fun statsReflectStores() {
    seed()
    val stats = BackupService.stats(context)
    assertEquals(1, stats.chats)
    assertEquals(2, stats.messages)
    assertEquals(2, stats.characters)
    assertEquals(1, stats.memories)
    assertEquals(1, stats.memoryOwners)
    assertEquals(1, stats.apiKeys)
  }

  /** 真机上的落盘路径:走 MediaStore 的 Downloads 集合,等价于用户在「保存到」里选的位置。 */
  @Test
  fun exportWritesThroughContentResolver() {
    seed()
    val archive = BackupService.prepare(context, BackupSection.Chats)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, archive.fileName)
      put(MediaStore.Downloads.MIME_TYPE, BackupFiles.MIME_TYPE)
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
    try {
      val result = BackupService.export(context, uri, archive.content)
      assertNotNull(result.name)
      assertEquals(BackupSection.Chats, result.section)
      // 写完的文件能被自己的解析器读回来
      val payload = BackupService.payload(BackupFiles.document(context, uri))
      assertEquals(1, payload.chats!!.size)
    } finally {
      runCatching { resolver.delete(uri, null, null) }
    }
  }
}
