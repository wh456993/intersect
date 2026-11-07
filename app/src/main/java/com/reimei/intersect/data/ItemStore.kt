package com.reimei.intersect.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 物品目录的本地持久化,按角色(好友)分开存。
 *
 * 物品管理器是角色信息页下的一个模块,每件物品都属于某个角色:发消息时只扫
 * 「这个会话对方」名下的物品,不会把别的角色的物品串进来。
 */
object ItemStore {
  private const val PREFS = "item_store"
  private const val KEY_PREFIX = "items_"

  fun newId(): String = "item_${System.currentTimeMillis()}"

  /** 读某个角色名下的物品;没存过就是空列表。 */
  fun load(context: Context, friendId: String): List<Item> {
    val raw = prefsOf(context).getString(keyOf(friendId), null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { itemFromJson(it) }
      }
    } catch (e: Exception) {
      // 记录坏了就当空目录,不让一次解析失败挡住整个物品管理器
      emptyList()
    }
  }

  fun save(context: Context, friendId: String, items: List<Item>) {
    val array = JSONArray()
    items.forEach { item -> array.put(itemToJson(item)) }
    prefsOf(context).edit().putString(keyOf(friendId), array.toString()).apply()
  }

  private fun keyOf(friendId: String) = "$KEY_PREFIX$friendId"

  private fun prefsOf(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun itemToJson(item: Item): JSONObject = JSONObject().apply {
    put("id", item.id)
    put("name", item.name)
    put("description", item.description)
  }

  private fun itemFromJson(obj: JSONObject): Item? {
    val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
    return Item(
      id = id,
      name = obj.optString("name"),
      description = obj.optString("description"),
    )
  }
}
