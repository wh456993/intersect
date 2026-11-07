package com.reimei.intersect.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 备份文件格式的测试。
 *
 * 备份是用户换机时唯一的退路:字段名、内容范围、加密参数一旦漂了,数据就恢复不回来,
 * 而且在界面上完全看不出来。所以这里把「打包 → 解析 → 解密」整条链路钉住。
 * org.json 在 JVM 单测里是空实现,只能放在真机跑。
 */
@RunWith(AndroidJUnit4::class)
class BackupFormatTest {
  private val friend = User(
    id = "friend_1",
    name = "小明",
    avatar = 0,
    signature = "写代码",
    role = "程序员",
    personality = "话少",
  )

  private val payload = BackupPayload(
    characters = listOf(friend),
    chats = listOf(
      Chat(friend, mutableListOf(Msg(User.Me, "在吗"), Msg(friend, "在"))).apply {
        apiKeyId = "key_1"
      }
    ),
    apiKeys = listOf(ApiKey("key_1", "我的 KEY", "deepseek-chat", "https://api.deepseek.com/v1", "sk-test")),
    avatars = mapOf(friend.id to byteArrayOf(1, 2, 3, 4)),
  )

  @Test
  fun payloadRoundTrip() {
    val restored = BackupFormat.payloadFromJson(BackupFormat.payloadToJson(payload))

    val characters = restored.characters!!
    assertEquals(1, characters.size)
    assertEquals("friend_1", characters[0].id)
    assertEquals("小明", characters[0].name)
    assertEquals("写代码", characters[0].signature)
    assertEquals("程序员", characters[0].role)
    assertEquals("话少", characters[0].personality)
    // 本机的绝对路径不能跟着走,否则换台机器就是个死路径
    assertNull(characters[0].avatarPath)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.avatars["friend_1"])

    val chat = restored.chats!!.single()
    assertEquals(listOf("在吗", "在"), chat.msgs.map { it.text })
    assertEquals(User.Me.id, chat.msgs[0].from.id)
    assertEquals("friend_1", chat.msgs[1].from.id)
    assertEquals("key_1", chat.apiKeyId)

    val key = restored.apiKeys!!.single()
    assertEquals("sk-test", key.key)
    assertEquals("deepseek-chat", key.model)
  }

  @Test
  fun singleSectionKeepsOthersNull() {
    val onlyCharacters = BackupPayload(characters = listOf(friend))
    val restored = BackupFormat.payloadFromJson(BackupFormat.payloadToJson(onlyCharacters))
    assertEquals(1, restored.characters!!.size)
    assertNull(restored.chats)
    assertNull(restored.apiKeys)
    assertEquals(listOf(BackupSection.Characters), BackupPayload.sectionsOf(onlyCharacters))
  }

  @Test
  fun countsFollowPayload() {
    assertEquals(BackupCounts(characters = 1, chats = 1, messages = 2, apiKeys = 1), payload.counts())
  }

  @Test
  fun envelopeRoundTrip() {
    val sections = BackupPayload.sectionsOf(payload)
    val block = BackupCrypto.encrypt(
      password = "123456",
      plaintext = BackupFormat.payloadToJson(payload).toByteArray(Charsets.UTF_8),
      aad = BackupFormat.aadOf(sections),
      iterations = 2_000,
    )
    val text = BackupEnvelope(
      epochMs = 1_700_000_000_000L,
      exportedAt = BackupFormat.timestamp(1_700_000_000_000L),
      appVersion = "1.0",
      sections = sections,
      counts = payload.counts(),
      block = block,
    ).toJson()

    val parsed = BackupFormat.readEnvelope(text)
    assertEquals(1, parsed.sections.size)
    assertEquals(sections, parsed.sections)
    assertEquals(1_700_000_000_000L, parsed.epochMs)
    assertEquals("1.0", parsed.appVersion)
    assertEquals(payload.counts(), parsed.counts)

    val plain = BackupCrypto.decrypt("123456", parsed.block, BackupFormat.aadOf(parsed.sections))
    val restored = BackupFormat.payloadFromJson(String(plain, Charsets.UTF_8))
    assertEquals("小明", restored.characters!!.single().name)
  }

  @Test
  fun tamperedSectionsFail() {
    val sections = listOf(BackupSection.Chats)
    val block = BackupCrypto.encrypt(
      password = "123456",
      plaintext = BackupFormat.payloadToJson(payload).toByteArray(Charsets.UTF_8),
      aad = BackupFormat.aadOf(sections),
      iterations = 2_000,
    )
    val obj = JSONObject(
      BackupEnvelope(0L, "x", "1.0", sections, payload.counts(), block).toJson()
    )
    // 把「只备份了聊天」改成「还备份了 API KEY」,AAD 对不上
    obj.put("sections", org.json.JSONArray(listOf(BackupSection.Chats.key, BackupSection.ApiKeys.key)))
    val tampered = BackupFormat.readEnvelope(obj.toString())
    try {
      BackupCrypto.decrypt("123456", tampered.block, BackupFormat.aadOf(tampered.sections))
      fail("内容范围被改动后仍然解出了明文")
    } catch (e: BackupCryptoException) {
      assertTrue(e.message.orEmpty().isNotEmpty())
    }
  }

  @Test
  fun notABackupFile() {
    try {
      BackupFormat.readEnvelope("{\"schema\":\"intersect.usage.export\"}")
      fail("别的 JSON 不应该被当成备份文件")
    } catch (e: BackupFormatException) {
      assertTrue(e.message.orEmpty().contains("intersect"))
    }
    try {
      BackupFormat.readEnvelope("不是 JSON")
      fail("非 JSON 内容不应该被当成备份文件")
    } catch (e: BackupFormatException) {
      assertTrue(e.message.orEmpty().isNotEmpty())
    }
  }

  @Test
  fun newerVersionRejected() {
    val block = BackupCrypto.encrypt("123456", ByteArray(1), ByteArray(0), 1_000)
    val obj = JSONObject(
      BackupEnvelope(0L, "x", "9.9", listOf(BackupSection.Chats), BackupCounts(), block).toJson()
    )
    obj.put("schema_version", BackupFormat.SCHEMA_VERSION + 1)
    try {
      BackupFormat.readEnvelope(obj.toString())
      fail("更高版本的备份文件不应该被直接接受")
    } catch (e: BackupFormatException) {
      assertTrue(e.message.orEmpty().contains("升级"))
    }
  }

  @Test
  fun encryptionParamsInHeader() {
    val block = BackupCrypto.encrypt("123456", ByteArray(4), ByteArray(0), 3_000)
    val obj = JSONObject(
      BackupEnvelope(0L, "x", "1.0", listOf(BackupSection.Chats), BackupCounts(), block).toJson()
    )
    val encryption = obj.getJSONObject("encryption")
    assertEquals(BackupFormat.CIPHER_NAME, encryption.getString("cipher"))
    assertEquals(3_000, encryption.getInt("iterations"))
    assertEquals(block.kdf, encryption.getString("kdf"))
  }
}
