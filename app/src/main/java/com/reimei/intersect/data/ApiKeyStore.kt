package com.reimei.intersect.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * API KEY 列表的本地持久化。
 *
 * 和好友列表一样存成一段 JSON 放在 SharedPreferences 里,没有就返回空列表(不需要默认条目)。
 */
object ApiKeyStore {
  private const val PREFS = "api_key_store"
  private const val KEY_LIST = "keys"

  fun newId(): String = "key_${System.currentTimeMillis()}"

  fun load(context: Context): List<ApiKey> {
    val raw = prefsOf(context).getString(KEY_LIST, null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { keyFromJson(it) }
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  fun save(context: Context, keys: List<ApiKey>) {
    val array = JSONArray()
    keys.forEach { key -> array.put(keyToJson(key)) }
    prefsOf(context).edit().putString(KEY_LIST, array.toString()).apply()
  }

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** API KEY 的 JSON 表示;备份([BackupFormat])复用同一套字段。 */
  internal fun keyToJson(key: ApiKey): JSONObject = JSONObject().apply {
    put("id", key.id)
    put("name", key.name)
    put("model", key.model)
    put("url", key.url)
    put("key", key.key)
  }

  internal fun keyFromJson(obj: JSONObject): ApiKey? {
    val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
    return ApiKey(
      id = id,
      name = obj.optString("name"),
      model = obj.optString("model"),
      url = obj.optString("url"),
      key = obj.optString("key"),
    )
  }
}
