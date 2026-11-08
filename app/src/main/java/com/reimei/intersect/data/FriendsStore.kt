package com.reimei.intersect.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 好友列表的本地持久化。
 *
 * 好友头像是裁切后写到 filesDir/friends/<id>.jpg 的独立文件,
 * 这里只记录路径,读的时候再解码。
 */
object FriendsStore {
  private const val PREFS = "friends_store"
  private const val KEY_LIST = "friends"
  private const val AVATAR_DIR = "friends"

  /** 默认角色:首次启动(没有任何记录)时给内置助手,避免列表空着。 */
  private val DEFAULTS = listOf(DawnAssistant.assistant())

  fun avatarFile(context: Context, friendId: String): File =
    File(File(context.filesDir, AVATAR_DIR).apply { mkdirs() }, "$friendId.jpg")

  fun newFriendId(): String = "friend_${System.currentTimeMillis()}"

  /** 读取好友列表;从未保存过时返回默认角色并落盘。 */
  fun load(context: Context): List<User> {
    val raw = prefsOf(context).getString(KEY_LIST, null) ?: run {
      save(context, DEFAULTS)
      return DEFAULTS
    }
    return try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { userFromJson(it) }
      }
    } catch (e: Exception) {
      DEFAULTS
    }
  }

  fun save(context: Context, friends: List<User>) {
    val array = JSONArray()
    friends.forEach { friend -> array.put(userToJson(friend)) }
    prefsOf(context).edit().putString(KEY_LIST, array.toString()).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 用户的 JSON 表示;好友列表使用这套字段。 */
internal fun userToJson(user: User): JSONObject = JSONObject().apply {
  put("id", user.id)
  put("name", user.name)
  user.avatarPath?.let { put("avatarPath", it) }
  user.signature?.let { put("signature", it) }
  // 角色设定:没设置就不写,老数据里本来也没有这两个字段
  user.role?.let { put("role", it) }
  user.personality?.let { put("personality", it) }
  put("roleplayMode", user.roleplayMode.name)
  // 唯一 ID 跟着角色一起存:备份([BackupFormat])复用这一段,所以恢复 / 换机都不会换号
  user.uid?.let { put("uid", it) }
}

/** 从 JSON 还原用户;缺少 id 时返回 null。 */
internal fun userFromJson(obj: JSONObject): User? {
  val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
  // 内置角色的签名始终以代码为准:旧版本落盘的是旧品牌名,读回来要跟着改。
  // 名字则优先用落盘值 —— 用户在角色设置里改过名就得算数,没存过才回落到代码里的名称
  val builtIn = if (id == DawnAssistant.ID) DawnAssistant.assistant() else null
  // 内置角色没存过模式时按它的默认值(Self-insert)算;用户手动切换过后就尊重落盘值
  val modeField = obj.optString("roleplayMode")
  return User(
    id = id,
    name = obj.optString("name").takeIf { it.isNotEmpty() } ?: builtIn?.name ?: "未命名",
    avatar = DawnAssistant.builtInAvatarFor(id),
    avatarPath = obj.optString("avatarPath").takeIf { it.isNotEmpty() },
    signature = builtIn?.signature ?: obj.optString("signature").takeIf { it.isNotEmpty() },
    role = obj.optString("role").takeIf { it.isNotEmpty() },
    personality = obj.optString("personality").takeIf { it.isNotEmpty() },
    roleplayMode = if (modeField.isEmpty() && builtIn != null) builtIn.roleplayMode
      else RoleplayMode.fromName(modeField),
    // 老数据还没有这一项:留 null,由 MainViewModel 读入时补号;内置角色永远是 0
    uid = builtIn?.uid
      ?: if (obj.has("uid")) obj.optLong("uid", 0L).takeIf { it > 0L } else null,
  )
}
