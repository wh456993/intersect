package com.reimei.intersect.data

/**
 * 「我」页名片下方的功能入口。
 *
 * 每一项点开都是一个从右侧推入的二级页,内容后续逐个填,现在先给出入口。
 */
enum class MeMenu(val title: String) {
  ApiKey("API KEY管理"),
  Usage("Token统计"),
  Backup("数据备份"),
  Support("支持开发者"),
  Privacy("隐私政策"),
  Settings("设置"),
}
