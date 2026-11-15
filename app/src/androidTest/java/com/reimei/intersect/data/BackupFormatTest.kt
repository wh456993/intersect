package com.reimei.intersect.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 备份文件格式的测试。
 *
 * 备份是用户换机时唯一的退路:字段名与内容范围一旦漂了,数据就恢复不回来,
 * 而且在界面上完全看不出来。所以这里把「打包 → 解析」整条链路钉住。
 * 备份是明文 JSON,不再有加解密环节,也不再兼容旧的加密 `.ibk`(见 [legacyIbkRejected])。
 *
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

  private val memory = ImmediateMemory(
    id = "immediate_1",
    fromRound = 1,
    toRound = 10,
    createdAt = 1_700_000_000L,
    targetTokens = 400,
    actualTokens = 380,
    text = "他们聊了很久的代码评审。",
  )

  private val payload = BackupPayload(
    characters = listOf(friend),
    chats = listOf(
      Chat(friend, mutableListOf(Msg(User.Me, "在吗"), Msg(friend, "在"))).apply {
        apiKeyId = "key_1"
      }
    ),
    memories = listOf(
      ImmediateMemoryState(friendId = friend.id, rounds = 10, coveredRounds = 10, summaries = listOf(memory))
    ),
    apiKeys = listOf(ApiKey("key_1", "我的 KEY", "deepseek-flash", "https://api.deepseek.com/v1", "sk-test")),
    avatars = mapOf(friend.id to byteArrayOf(1, 2, 3, 4)),
  )

  @Test
  fun characterPayloadRoundTrip() {
    val restored = BackupFormat.payloadFromJson(
      BackupSection.Characters,
      BackupFormat.payloadJson(payload)
    )

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
  }

  @Test
  fun chatPayloadRoundTrip() {
    val restored = BackupFormat.payloadFromJson(
      BackupSection.Chats,
      BackupFormat.payloadJson(payload)
    )

    val chat = restored.chats!!.single()
    assertEquals(listOf("在吗", "在"), chat.msgs.map { it.text })
    assertEquals(User.Me.id, chat.msgs[0].from.id)
    assertEquals("friend_1", chat.msgs[1].from.id)
    assertEquals("key_1", chat.apiKeyId)
  }

  @Test
  fun memoryPayloadRoundTrip() {
    val restored = BackupFormat.payloadFromJson(
      BackupSection.Memories,
      BackupFormat.payloadJson(payload)
    )

    val state = restored.memories!!.single()
    assertEquals("friend_1", state.friendId)
    assertEquals(10, state.rounds)
    assertEquals(10, state.coveredRounds)
    val summary = state.summaries.single()
    assertEquals("immediate_1", summary.id)
    assertEquals(1, summary.fromRound)
    assertEquals(10, summary.toRound)
    assertEquals("他们聊了很久的代码评审。", summary.text)
    assertEquals(380, summary.actualTokens)
  }

  @Test
  fun apiKeyPayloadRoundTrip() {
    val restored = BackupFormat.payloadFromJson(
      BackupSection.ApiKeys,
      BackupFormat.payloadJson(payload)
    )

    val key = restored.apiKeys!!.single()
    assertEquals("sk-test", key.key)
    assertEquals("deepseek-flash", key.model)
    assertEquals("https://api.deepseek.com/v1", key.url)
  }

  @Test
  fun singleSectionKeepsOthersNull() {
    val onlyCharacters = BackupPayload(characters = listOf(friend))
    val restored = BackupFormat.payloadFromJson(
      BackupSection.Characters,
      BackupFormat.payloadJson(onlyCharacters)
    )
    assertEquals(1, restored.characters!!.size)
    assertNull(restored.chats)
    assertNull(restored.memories)
    assertNull(restored.apiKeys)
  }

  @Test
  fun countsFollowPayload() {
    assertEquals(
      BackupCounts(characters = 1, chats = 1, messages = 2, memories = 1, apiKeys = 1),
      payload.counts()
    )
  }

  /**
   * 规模概览:单类内容只出一句;整包与带记忆的会话备份拼成多段,
   * 分段由 Lang 的规则回退逐段翻译(拼成一整串是查不到规则表的)。
   */
  @Test
  fun countsSummaryJoinsSegments() {
    assertEquals("1 个会话(2 条消息)", payload.counts().run {
      copy(characters = 0, memories = 0, apiKeys = 0).summary()
    })
    assertEquals("1 个角色 · 1 个会话(2 条消息)", payload.counts().run {
      copy(memories = 0, apiKeys = 0).summary()
    })
    assertEquals("没有内容", BackupCounts().summary())
  }

  private fun envelope(section: BackupSection = BackupSection.Characters): String = BackupEnvelope(
    epochMs = 1_700_000_000_000L,
    exportedAt = BackupFormat.timestamp(1_700_000_000_000L),
    appVersion = "1.2.3",
    section = section,
    counts = payload.counts(),
    payload = BackupFormat.payloadJson(payload),
  ).toJson()

  @Test
  fun envelopeRoundTrip() {
    val parsed = BackupFormat.readEnvelope(envelope())

    assertEquals(1_700_000_000_000L, parsed.epochMs)
    assertEquals(BackupSection.Characters, parsed.section)
    assertEquals("1.2.3", parsed.appVersion)
    assertEquals(1, parsed.counts.characters)
    // 明文:内容段就是一个可以继续往下读的 JSON 对象
    assertEquals(1, parsed.payload.getJSONArray("characters").length())
    assertFalse(parsed.payload.has("encryption"))
  }

  @Test
  fun envelopeFileIsPlainJson() {
    val obj = JSONObject(envelope(BackupSection.Chats))
    assertEquals(BackupFormat.SCHEMA, obj.getString("schema"))
    assertEquals(BackupFormat.SCHEMA_VERSION, obj.getInt("schema_version"))
    assertEquals("chats", obj.getString("section"))
    assertTrue(obj.get("payload") is JSONObject)
    // 新版格式里没有「加密信息」这一节
    assertFalse(obj.has("encryption"))
  }

  @Test
  fun legacyIbkRejected() {
    // 旧格式的内容段是加密后的 Base64 字符串,也没有 section 字段:一律明确拒绝,不猜着解
    val legacy = JSONObject().apply {
      put("schema", BackupFormat.SCHEMA)
      put("schema_version", 1)
      put("sections", JSONArray(listOf("characters", "chats", "apiKeys")))
      put("encryption", JSONObject().apply { put("cipher", "AES-256-GCM") })
      put("payload", "c29tZS1jaXBoZXJ0ZXh0")
    }
    try {
      BackupFormat.readEnvelope(legacy.toString())
      fail("旧版加密备份应当被拒绝")
    } catch (e: BackupFormatException) {
      assertTrue(e.message!!.contains(".ibk"))
    }
  }

  @Test
  fun unknownSchemaRejected() {
    try {
      BackupFormat.readEnvelope("{\"schema\":\"intersect.usage.export\"}")
      fail("别的应用的导出文件应当被拒绝")
    } catch (e: BackupFormatException) {
      assertTrue(e.message!!.contains("intersect"))
    }
  }

  @Test
  fun garbageRejected() {
    try {
      BackupFormat.readEnvelope("不是 JSON")
      fail("非 JSON 内容应当被拒绝")
    } catch (e: BackupFormatException) {
      assertTrue(e.message!!.contains("JSON"))
    }
  }

  @Test
  fun newerVersionRejected() {
    val obj = JSONObject(envelope())
    obj.put("schema_version", BackupFormat.SCHEMA_VERSION + 1)
    try {
      BackupFormat.readEnvelope(obj.toString())
      fail("更新版本的备份应当被拒绝")
    } catch (e: BackupFormatException) {
      assertTrue(e.message!!.contains("升级"))
    }
  }

  /** 文件头说导的是模型配置,内容里却只有角色:文件被改过,宁可报错也不要恢复半份。 */
  @Test
  fun headerContentMismatchRejected() {
    val onlyCharacters = BackupPayload(characters = listOf(friend))
    val obj = JSONObject(
      BackupEnvelope(
        epochMs = 1_700_000_000_000L,
        exportedAt = BackupFormat.timestamp(1_700_000_000_000L),
        appVersion = "1.2.3",
        section = BackupSection.ApiKeys,
        counts = onlyCharacters.counts(),
        payload = BackupFormat.payloadJson(onlyCharacters),
      ).toJson()
    )
    try {
      BackupFormat.payloadFromJson(BackupSection.ofKey(obj.getString("section"))!!, obj.getJSONObject("payload"))
      fail("内容与文件头不一致时应当被拒绝")
    } catch (e: BackupFormatException) {
      assertTrue(e.message!!.contains("不一致"))
    }
  }
}
