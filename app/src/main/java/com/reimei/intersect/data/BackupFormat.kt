package com.reimei.intersect.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 备份文件不合法:不是本应用的备份、版本过新、或结构缺失。 */
class BackupFormatException(message: String) : Exception(message)

/**
 * 可以单独导出 / 导入的四类内容。
 *
 * 每一类都独立成一份 `.json` 文件:只想带走角色设定就只导角色卡,不想让密钥离开本机就不导模型配置。
 * [key] 是写进备份文件的稳定标识,不随文案改动;[fileTag] 只用于文件名。
 */
enum class BackupSection(val key: String, val label: String, val fileTag: String) {
  Chats(
    key = "chats",
    label = "聊天记录",
    fileTag = "chats",
  ),
  Characters(
    key = "characters",
    label = "角色卡",
    fileTag = "characters",
  ),
  Memories(
    key = "memories",
    label = "记忆库",
    fileTag = "memories",
  ),
  ApiKeys(
    key = "apiKeys",
    label = "模型配置",
    fileTag = "apikeys",
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

/** 一份备份的规模概览,直接写在文件头里。 */
data class BackupCounts(
  val characters: Int = 0,
  val chats: Int = 0,
  val messages: Int = 0,
  val memories: Int = 0,
  val apiKeys: Int = 0,
) {
  /**
   * 一句规模概览。
   *
   * 一份备份只装一类内容,所以这里只会命中一项 —— 不拼成一长串「A · B · C」,
   * 那样整串查不到翻译表,英文模式会静默退化成中文。
   */
  fun summary(): String = when {
    characters > 0 -> "$characters 个角色"
    chats > 0 -> "$chats 个会话($messages 条消息)"
    memories > 0 -> "$memories 条记忆"
    apiKeys > 0 -> "$apiKeys 个模型配置"
    else -> "没有内容"
  }

  fun toJson(): JSONObject = JSONObject().apply {
    put("characters", characters)
    put("chats", chats)
    put("messages", messages)
    put("memories", memories)
    put("apiKeys", apiKeys)
  }

  companion object {
    fun fromJson(obj: JSONObject?): BackupCounts = BackupCounts(
      characters = obj?.optInt("characters") ?: 0,
      chats = obj?.optInt("chats") ?: 0,
      messages = obj?.optInt("messages") ?: 0,
      memories = obj?.optInt("memories") ?: 0,
      apiKeys = obj?.optInt("apiKeys") ?: 0,
    )
  }
}

/**
 * 备份文件的明文外壳。
 *
 * 里面全是能直接读的东西:哪一类内容、什么时候导出的、来自哪个版本、有多少条,
 * 以及内容段 [payload] —— 它就是一段嵌套 JSON,用文本编辑器打开就能看。
 *
 * 备份**不做任何加密**:导出的文件是给人看、给人搬的,不需要口令也不会因为口令丢失而变砖。
 * 代价是任何拿到文件的人都能读,提示语写在界面上(`BackupPage`)。
 */
class BackupEnvelope(
  val epochMs: Long,
  val exportedAt: String,
  val appVersion: String,
  val section: BackupSection,
  val counts: BackupCounts,
  val payload: JSONObject,
) {
  fun toJson(): String = JSONObject().apply {
    put("schema", BackupFormat.SCHEMA)
    put("schema_version", BackupFormat.SCHEMA_VERSION)
    put("section", section.key)
    put("exported_at", exportedAt)
    put("exported_at_epoch_ms", epochMs)
    put("app", JSONObject().apply { put("version_name", appVersion) })
    put("counts", counts.toJson())
    put("payload", payload)
  }.toString(2)
}

/**
 * 一份备份里的真正内容。
 *
 * 四类内容都是「可空」的:null 表示这份备份根本没包含它(而不是它有零条)。
 * [avatars] 是角色的头像图片原始字节,按角色 id 索引——本地文件路径换到别的机器就失效了,
 * 所以头像得跟着数据一起走,恢复时再落到新机器的 filesDir 下。
 */
class BackupPayload(
  val characters: List<User>? = null,
  val chats: List<Chat>? = null,
  val memories: List<ImmediateMemoryState>? = null,
  val apiKeys: List<ApiKey>? = null,
  val avatars: Map<String, ByteArray> = emptyMap(),
) {
  fun counts(): BackupCounts = BackupCounts(
    characters = characters?.size ?: 0,
    chats = chats?.size ?: 0,
    messages = chats?.sumOf { it.msgs.size } ?: 0,
    memories = memories?.sumOf { it.summaries.size } ?: 0,
    apiKeys = apiKeys?.size ?: 0,
  )
}

/**
 * 备份文件的读写格式:只做「数据 ↔ JSON」这一件事,不碰文件。
 *
 * 数据段用的是各 Store 已有的字段定义([userToJson] / [chatToJson] / [MemoryStore.stateToJson] /
 * [ApiKeyStore.keyToJson]),保证备份里的结构与本地存盘一致。
 *
 * 旧版的 `.ibk`(外形还是同一个 schema、内容段却是加密的 Base64 字符串)**不再支持**:
 * 读到那样的文件时会明确报错,提示用旧版本应用恢复后重新导出,而不是猜一份半份数据出来。
 */
object BackupFormat {
  const val SCHEMA = "intersect.backup"

  /** 2 = 明文单段格式(`section` + 嵌套 `payload`)。1 是已废弃的加密格式。 */
  const val SCHEMA_VERSION = 2

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
    // 旧格式的内容段是加密后的字符串:这里见到它不是对象就直接说明不支持,不尝试解密
    val payload = obj.optJSONObject("payload")
      ?: throw BackupFormatException(LEGACY_MESSAGE)
    val section = BackupSection.ofKey(obj.optString("section"))
      ?: throw BackupFormatException(LEGACY_MESSAGE)
    return BackupEnvelope(
      epochMs = obj.optLong("exported_at_epoch_ms", 0L),
      exportedAt = obj.optString("exported_at").ifEmpty { "未知时间" },
      appVersion = obj.optJSONObject("app")?.optString("version_name").orEmpty(),
      section = section,
      counts = BackupCounts.fromJson(obj.optJSONObject("counts")),
      payload = payload,
    )
  }

  /** 旧版加密备份(.ibk)的统一提示:说得清「为什么打不开」和「该怎么办」。 */
  const val LEGACY_MESSAGE = "这是旧版 .ibk 备份,新版不再支持;请用旧版本应用恢复后重新导出 .json"

  // ---------------------------------------------------------------- 内容

  fun payloadJson(payload: BackupPayload): JSONObject = JSONObject().apply {
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
    payload.memories?.let { list ->
      val array = JSONArray()
      list.forEach { state ->
        array.put(MemoryStore.stateToJson(state).apply { put("friendId", state.friendId) })
      }
      put("memories", array)
    }
    payload.apiKeys?.let { list ->
      val array = JSONArray()
      list.forEach { key -> array.put(ApiKeyStore.keyToJson(key)) }
      put("apiKeys", array)
    }
  }

  /**
   * 按文件头声明的 [section] 解出内容段。
   *
   * 声明了哪一类就必须真的有那一类:文件头与内容对不上说明文件被改过,宁可报错也不要恢复半份。
   */
  fun payloadFromJson(section: BackupSection, obj: JSONObject): BackupPayload {
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
    val memories = obj.optJSONArray("memories")?.let { array ->
      (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val friendId = item.optString("friendId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        MemoryStore.stateFromJson(friendId, item)
      }
    }
    val apiKeys = obj.optJSONArray("apiKeys")?.let { array ->
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { ApiKeyStore.keyFromJson(it) }
      }
    }

    // 文件头说有什么就得有什么:对不上直接判为损坏(空数组是合法的,比如删光聊天记录之后再导出)
    when (section) {
      BackupSection.Characters -> if (characters == null) throw mismatch()
      BackupSection.Chats -> if (chats == null) throw mismatch()
      BackupSection.Memories -> if (memories == null) throw mismatch()
      BackupSection.ApiKeys -> if (apiKeys == null) throw mismatch()
    }

    return BackupPayload(characters, chats, memories, apiKeys, avatars)
  }

  private fun mismatch() = BackupFormatException("备份内容与文件头不一致")
}
