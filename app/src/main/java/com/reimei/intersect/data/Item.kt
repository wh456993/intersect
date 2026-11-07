package com.reimei.intersect.data

/**
 * 物品管理器里的一件物品。
 *
 * [name] 是关键词扫描的依据:用户消息里出现它时,这件物品的名称与 [description]
 * 会被一起注入请求体的用户消息。简介可以留空,但名称必须有(否则没法被扫到)。
 */
data class Item(
  val id: String,
  val name: String,
  val description: String,
)
