package com.reimei.intersect.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 备份文件不合法:不是本应用的备份、版本过新、或结构缺失。 */
class BackupFormatException(message: String) : Exception(message)

/**
 * 备份可以单独选择的三类内容。
 *
 * 每一项都能独立导出:只想带走角色设定就只勾角色,不想让密钥离开本机就不勾 API KEY。
 * [key] 是写进备份文件的稳定标识,不随文案改动。
 */
enum class BackupSection(val key: String, val label: String) {
  Characters(
    key = "characters",
    label = "角色信息",
  ),
  Chats(
    key = "chats",
    label = "聊天记录",
  ),
  ApiKeys(
    key = "apiKeys",
    label = "API KEY",
  );

  companion object {
    fun ofKey(key: String): BackupSection? = entries.firstOrNull { it.key == key }
  }
}

/** 恢复方式:[Merge] 保留本机多出来的数据,[Replace] 用备份内容整份替换。 */
enum class RestoreMode(val label: String) {
  Merge("合并"),
  Replace("覆盖"),
}

/** 一份备份内容的规模概览,明文写在文件头里,不输口令也能看到。 */
data class BackupCounts(
  val characters: Int = 0,
  val chats: Int = 0,
  val messages: Int = 0,
  val apiKeys: Int = 0,
) {
  fun summary(): String = "$characters 个角色 · $chats 个会话($messages 条消息) · $apiKeys 个 API KEY"

  fun toJson(): JSONObject = JSONObject().apply {
    put("characters", characters)
    put("chats", chats)
    put("messages", messages)
    put("apiKeys", apiKeys)
  }

  companion object {
    fun fromJson(obj: JSONObject?): BackupCounts = BackupCounts(
      characters = obj?.optInt("characters") ?: 0,
      chats = obj?.optInt("chats") ?: 0,
      messages = obj?.optInt("messages") ?: 0,
      apiKeys = obj?.optInt("apiKeys") ?: 0,
    )
  }
}

/**
 * 备份文件的明文外壳。
 *
 * 里面全是「能公开」的东西:什么时候导出的、来自哪个版本、包含哪几类内容、各自的规模、
 * 以及解密所需的参数。真正的数据是一整块密文([block]),没有口令什么都读不出来。
 */
class BackupEnvelope(
  val epochMs: Long,
  val exportedAt: String,
  val appVersion: String,
  val sections: List<BackupSection>,
  val counts: BackupCounts,
  val block: EncryptedBlock,
) {
  fun toJson(): String = JSONObject().apply {
    put("schema", BackupFormat.SCHEMA)
    put("schema_version", BackupFormat.SCHEMA_VERSION)
    put("exported_at", exportedAt)
    put("exported_at_epoch_ms", epochMs)
    put("app", JSONObject().apply { put("version_name", appVersion) })
    put("sections", JSONArray(sections.map { it.key }))
    put("counts", counts.toJson())
    put("encryption", JSONObject().apply {
      put("cipher", BackupFormat.CIPHER_NAME)
      put("kdf", block.kdf)
      put("iterations", block.iterations)
      put("salt", Base64Codec.encode(block.salt))
      put("iv", Base64Codec.encode(block.iv))
    })
    put("payload", Base64Codec.encode(block.ciphertext))
  }.toString(2)
}

/**
 * 解密之后的真正内容。
 *
 * 三类内容都是「可空」的:null 表示这份备份根本没包含它(而不是它有零条)。
 * [avatars] 是角色的头像图片原始字节,按角色 id 索引——本地文件路径换到别的机器就失效了,
 * 所以头像得跟着数据一起走,恢复时再落到新机器的 filesDir 下。
 */
class BackupPayload(
  val characters: List<User>? = null,
  val chats: List<Chat>? = null,
  val apiKeys: List<ApiKey>? = null,
  val avatars: Map<String, ByteArray> = emptyMap(),
) {
  fun counts(): BackupCounts = BackupCounts(
    characters = characters?.size ?: 0,
    chats = chats?.size ?: 0,
    messages = chats?.sumOf { it.msgs.size } ?: 0,
    apiKeys = apiKeys?.size ?: 0,
  )

  companion object {
    /** 从内容本身反推它覆盖了哪几类(用于构建文件头)。 */
    fun sectionsOf(payload: BackupPayload): List<BackupSection> = buildList {
      if (payload.characters != null) add(BackupSection.Characters)
      if (payload.chats != null) add(BackupSection.Chats)
      if (payload.apiKeys != null) add(BackupSection.ApiKeys)
    }
  }
}

/**
 * 备份文件的读写格式。
 *
 * 只做「数据 ↔ JSON」这一件事,不碰文件、不碰加解密,所以它既可以被导出流程调用,
 * 也可以在测试里单独喂一段 JSON 验证。真正的数据段用的是各 Store 已有的字段定义
 * ([userToJson] / [chatToJson] / [keyToJson]),保证备份里的结构与本地存盘一致。
 */
object BackupFormat {
  const val SCHEMA = "intersect.backup"
  const val SCHEMA_VERSION = 1
  const val PAYLOAD_SCHEMA = "intersect.backup.payload"
  const val CIPHER_NAME = "AES-256-GCM"

  /** AAD 前缀:版本化,将来换格式时不会把老文件的 AAD 一起改坏。 */
  private const val AAD_PREFIX = "intersect.backup.v1"

  /** 参与 GCM 完整性校验的明文头:内容范围被改过就解不开。 */
  fun aadOf(sections: List<BackupSection>): ByteArray =
    ("$AAD_PREFIX|sections=" + sections.map { it.key }.sorted().joinToString(",")).toByteArray(Charsets.UTF_8)

  fun timestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

  // ---------------------------------------------------------------- 外壳

  fun readEnvelope(text: String): BackupEnvelope {
    val obj = try {
      JSONObject(text)
    } catch (e: Exception) {
      throw BackupFormatException("这不是有效的备份文件:内容不是 JSON")
    }
    if (obj.optString("schema") != SCHEMA) {
      throw BackupFormatException("这不是 intersect 的备份文件")
    }
    val version = obj.optInt("schema_version", 0)
    if (version <= 0) throw BackupFormatException("备份文件缺少版本号")
    if (version > SCHEMA_VERSION) {
      throw BackupFormatException("备份文件由更新版本的应用生成,请先升级应用")
    }
    val sectionsJson = obj.optJSONArray("sections")
      ?: throw BackupFormatException("备份文件没有声明内容范围")
    val sections = (0 until sectionsJson.length())
      .mapNotNull { BackupSection.ofKey(sectionsJson.optString(it)) }
    if (sections.isEmpty()) throw BackupFormatException("备份文件里没有任何可取的内容")

    val encryption = obj.optJSONObject("encryption")
      ?: throw BackupFormatException("备份文件缺少加密信息")
    val ciphertext = decodeBase64(obj.optString("payload"), "payload")
    if (ciphertext.isEmpty()) throw BackupFormatException("备份文件的内容为空")
    val block = EncryptedBlock(
      kdf = encryption.optString("kdf").ifEmpty { BackupCrypto.preferredKdf() },
      iterations = encryption.optInt("iterations", BackupCrypto.ITERATIONS),
      salt = decodeBase64(encryption.optString("salt"), "salt"),
      iv = decodeBase64(encryption.optString("iv"), "iv"),
      ciphertext = ciphertext,
    )
    return BackupEnvelope(
      epochMs = obj.optLong("exported_at_epoch_ms", 0L),
      exportedAt = obj.optString("exported_at").ifEmpty { "未知时间" },
      appVersion = obj.optJSONObject("app")?.optString("version_name").orEmpty(),
      sections = sections,
      counts = BackupCounts.fromJson(obj.optJSONObject("counts")),
      block = block,
    )
  }

  // ---------------------------------------------------------------- 内容

  fun payloadToJson(payload: BackupPayload): String = JSONObject().apply {
    put("schema", PAYLOAD_SCHEMA)
    put("schema_version", SCHEMA_VERSION)
    payload.characters?.let { list ->
      val array = JSONArray()
      list.forEach { user ->
        // 头像路径是本机的绝对路径,换台机器就没有意义了,这里换成图片本身
        val json = userToJson(user).apply { remove("avatarPath") }
        payload.avatars[user.id]?.let { bytes -> json.put("avatar", Base64Codec.encode(bytes)) }
        array.put(json)
      }
      put("characters", array)
    }
    payload.chats?.let { list ->
      val array = JSONArray()
      list.forEach { chat -> array.put(chatToJson(chat)) }
      put("chats", array)
    }
    payload.apiKeys?.let { list ->
      val array = JSONArray()
      list.forEach { key -> array.put(ApiKeyStore.keyToJson(key)) }
      put("apiKeys", array)
    }
  }.toString()

  fun payloadFromJson(text: String): BackupPayload {
    val obj = try {
      JSONObject(text)
    } catch (e: Exception) {
      throw BackupFormatException("备份内容无法解析")
    }
    if (obj.optString("schema") != PAYLOAD_SCHEMA) {
      throw BackupFormatException("备份内容格式不匹配")
    }
    val avatars = mutableMapOf<String, ByteArray>()

    val characters = obj.optJSONArray("characters")?.let { array ->
      (0 until array.length()).mapNotNull { index ->
        val json = array.optJSONObject(index) ?: return@mapNotNull null
        val user = userFromJson(json) ?: return@mapNotNull null
        json.optString("avatar").takeIf { it.isNotEmpty() }?.let { encoded ->
          runCatching { avatars[user.id] = Base64Codec.decode(encoded) }
        }
        // 路径一律清空:恢复时按本机目录重新生成
        user.copy(avatarPath = null)
      }
    }
    val chats = obj.optJSONArray("chats")?.let { array ->
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { chatFromJson(it) }
      }
    }
    val apiKeys = obj.optJSONArray("apiKeys")?.let { array ->
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { ApiKeyStore.keyFromJson(it) }
      }
    }
    if (characters == null && chats == null && apiKeys == null) {
      throw BackupFormatException("备份文件里没有任何可恢复的内容")
    }
    return BackupPayload(characters, chats, apiKeys, avatars)
  }

  private fun decodeBase64(text: String, field: String): ByteArray {
    if (text.isEmpty()) throw BackupFormatException("备份文件的 $field 缺失")
    return try {
      Base64Codec.decode(text)
    } catch (e: IllegalArgumentException) {
      throw BackupFormatException("备份文件的 $field 不合法")
    }
  }
}
