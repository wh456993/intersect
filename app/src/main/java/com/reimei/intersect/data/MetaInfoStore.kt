package com.reimei.intersect.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 即时元信息的本地持久化,按角色(好友)分开存 —— 和 [ItemStore] / [MemoryStore] 同一套模式。
 *
 * 一份 JSON 存一个角色的全部字段;没存过 / 存坏了都返回 [MetaInfo.empty],
 * 不让一次解析失败挡住设置页(数值回落默认值,用户重填一次就有)。
 */
object MetaInfoStore {
  private const val PREFS = "meta_info_store"
  private const val KEY_PREFIX = "meta_"

  /** 读某个角色的元信息;没存过就是默认初值。 */
  fun load(context: Context, friendId: String): MetaInfo {
    val raw = prefsOf(context).getString(keyOf(friendId), null)
      ?: return MetaInfo.empty(friendId)
    return try {
      jsonToMeta(JSONObject(raw), friendId)
    } catch (e: Exception) {
      // 记录坏了就当没存过,不让一次解析失败挡住整个设置页
      MetaInfo.empty(friendId)
    }
  }

  fun save(context: Context, info: MetaInfo) {
    prefsOf(context).edit().putString(keyOf(info.friendId), metaToJson(info).toString()).apply()
  }

  private fun keyOf(friendId: String) = "$KEY_PREFIX$friendId"

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun metaToJson(info: MetaInfo): JSONObject = JSONObject().apply {
    put("favorability", info.favorability)
    put("health", info.health)
    put("hunger", info.hunger)
    put("time", info.time)
    put("relationship", info.relationship)
    put("location", info.location)
    put("characters", info.characters)
    put("weather", info.weather)
    put("buffs", JSONArray().apply { info.buffs.forEach { put(it) } })
  }

  private fun jsonToMeta(obj: JSONObject, friendId: String): MetaInfo = MetaInfo(
    friendId = friendId,
    favorability = obj.optInt("favorability", MetaInfo.FAVOR_DEFAULT),
    health = obj.optInt("health", MetaInfo.HEALTH_DEFAULT),
    hunger = obj.optInt("hunger", MetaInfo.HUNGER_DEFAULT),
    time = obj.optString("time"),
    relationship = obj.optString("relationship"),
    location = obj.optString("location"),
    characters = obj.optString("characters"),
    weather = obj.optString("weather"),
    // 只收词表内的词条:脏数据里的野词条不进请求体
    buffs = obj.optJSONArray("buffs")?.let { array ->
      (0 until array.length()).mapNotNull { array.optString(it) }
        .filter { it in MetaInfo.BUFF_VOCAB }
    } ?: emptyList(),
  )
}
