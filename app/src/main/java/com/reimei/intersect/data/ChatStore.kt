package com.reimei.intersect.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话(含聊天记录)的本地持久化。
 *
 * 以前会话只存在内存里,进程一重启就回到源码里硬编码的示例数据;
 * 现在发消息、删除都会立刻写盘,重启后保持原样。
 */
object ChatStore {
  private const val PREFS = "chat_store"
  private const val KEY_LIST = "chats"

  /** 读取会话列表;从没保存过时返回 null(首次启动要靠调用方播种默认角色)。 */
  fun load(context: Context): List<Chat>? {
    val raw = prefsOf(context).getString(KEY_LIST, null) ?: return null
    return try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { chatFromJson(it) }
      }
    } catch (e: Exception) {
      // 记录坏了就当空列表:绝不能再把内置角色变回来,否则删除就不算永久
      emptyList()
    }
  }

  fun save(context: Context, chats: List<Chat>) {
    val array = JSONArray()
    chats.forEach { chat -> array.put(chatToJson(chat)) }
    prefsOf(context).edit().putString(KEY_LIST, array.toString()).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 会话的 JSON 表示;好友信息与好友列表共用一套字段,见 [userToJson]。
 *
 * 备份([BackupFormat])也复用这一套字段,保证备份里的结构与本地存盘一致。
 */
internal fun chatToJson(chat: Chat): JSONObject = JSONObject().apply {
  put("friend", userToJson(chat.friend))
  // 没关联时存空串:老数据里本来就没有这个字段,读回来同样是「跟随默认」
  put("apiKeyId", chat.apiKeyId ?: "")
  val msgs = JSONArray()
  chat.msgs.forEach { msg ->
    msgs.put(JSONObject().apply {
      // 消息只区分「我发的」和「对方发的」,对方是谁由 friend 决定
      put("fromMe", msg.from.id == User.Me.id)
      put("text", msg.text)
      msg.time?.let { put("time", it) }
      put("read", msg.read)
    })
  }
  put("msgs", msgs)
}

/** 从 JSON 还原会话;好友信息缺失或不可解析时返回 null。 */
internal fun chatFromJson(obj: JSONObject): Chat? {
  val friend = obj.optJSONObject("friend")?.let { userFromJson(it) } ?: return null
  // 必须是可观察的列表:普通 ArrayList 改了不会触发重组,发出去的消息在界面上就看不见
  val msgs = mutableStateListOf<Msg>()
  obj.optJSONArray("msgs")?.let { array ->
    for (index in 0 until array.length()) {
      val msgObj = array.optJSONObject(index) ?: continue
      val from = if (msgObj.optBoolean("fromMe")) User.Me else friend
      msgs.add(
        Msg(
          from = from,
          text = msgObj.optString("text"),
          time = msgObj.optString("time").takeIf { it.isNotEmpty() },
        ).apply {
          read = msgObj.optBoolean("read", true)
        }
      )
    }
  }
  return Chat(friend, msgs).apply {
    // 老数据没有这个字段:optString 给空串,归一成 null(跟随默认)
    apiKeyId = obj.optString("apiKeyId").takeIf { it.isNotEmpty() }
  }
}
