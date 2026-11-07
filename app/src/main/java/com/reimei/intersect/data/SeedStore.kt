package com.reimei.intersect.data

import android.content.Context

/**
 * 内置数据的一次性播种与迁移。
 *
 * 老版本把「高老师」「丢物线」两个示例角色硬编码在源码里,会话还只存在内存中;
 * 现在源码里只保留内置助手 intersect,会话也落了盘。首次启动新版本时做两件事:
 * 1. 把老版本的示例角色从通讯录、会话里清掉;
 * 2. 播种内置助手和它的欢迎语——只播这一次,用户之后删掉就不会再自动回来。
 */
object SeedStore {
  private const val PREFS = "seed_store"
  private const val KEY_VERSION = "version"

  /**
   * 数据版本:2 = 只保留内置角色并持久化会话;3 = 修正欢迎语里的括号;4 = 品牌名改为 intersect;
   * 5 = 内置角色的名字不再强制以代码为准(可改),迁移前落盘的旧名字在这里归一到当前品牌名;
   * 6 = 内置角色 intersect 的扮演模式归一到 Self-insert(之前落盘的是旧的 Avatar-based 默认值)。
   */
  private const val CURRENT_VERSION = 6

  /** 老版本源码里硬编码的两个示例角色。 */
  private val LEGACY_FRIEND_IDS = listOf("gaolaoshi", "diuwuxian")

  fun migrate(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return

    val contacts = FriendsStore.load(appContext).filterNot { it.id in LEGACY_FRIEND_IDS }
    val chats = (ChatStore.load(appContext) ?: emptyList())
      .filterNot { it.friend.id in LEGACY_FRIEND_IDS }

    val assistant = DawnAssistant.assistant()
    val seededContacts =
      (if (contacts.any { it.id == assistant.id }) contacts else listOf(assistant) + contacts)
        .map(::fixBuiltInName)
        .map(::fixBuiltInMode)
    val seededChats =
      (if (chats.any { it.friend.id == assistant.id }) chats
      else listOf(DawnAssistant.greetingChat()) + chats)
        .map { chat ->
          chat.friend = fixBuiltInName(chat.friend)
          chat.friend = fixBuiltInMode(chat.friend)
          fixGreetingText(chat)
        }

    FriendsStore.save(appContext, seededContacts)
    ChatStore.save(appContext, seededChats)
    prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
  }

  /**
   * 把内置角色的名字归一到当前品牌名。
   *
   * 版本 5 之前内置角色的名字一直由代码决定、落盘值仅是历史遗留,所以这里不同就是旧品牌名;
   * 版本 5 起用户能在角色设置里改名,迁移只在版本号落后的机器上跑一次,不会覆盖之后改的名字。
   */
  private fun fixBuiltInName(user: User): User =
    if (user.id == DawnAssistant.ID && user.name != DawnAssistant.NAME) {
      user.copy(name = DawnAssistant.NAME)
    } else {
      user
    }

  /**
   * 把内置角色 intersect 的扮演模式归一到 Self-insert(这是它的新默认)。
   *
   * 只在版本号落后的机器上跑一次:迁移后用户能在开发者控制台手动切换,
   * 那之后的落盘值由用户说了算,不会再被这里强制改回去。
   */
  private fun fixBuiltInMode(user: User): User =
    if (user.id == DawnAssistant.ID && user.roleplayMode != RoleplayMode.SELF_INSERT) {
      user.copy(roleplayMode = RoleplayMode.SELF_INSERT)
    } else {
      user
    }

  /**
   * 老版本播种的欢迎语写的是旧品牌名(半角括号、旧括号版本也一并覆盖),这里统一换成当前的欢迎语。
   * 只在「会话里只有那一条欢迎语、用户还没聊过」时修,避免动到真实聊天记录。
   */
  private fun fixGreetingText(chat: Chat): Chat {
    if (chat.friend.id != DawnAssistant.ID) return chat
    val only = chat.msgs.singleOrNull() ?: return chat
    if (only.from.id == User.Me.id) return chat
    if (!only.text.startsWith("欢迎加入")) return chat
    if (only.text == DawnAssistant.GREETING) return chat
    chat.msgs[0] = Msg(only.from, DawnAssistant.GREETING).apply { read = only.read }
    return chat
  }
}
