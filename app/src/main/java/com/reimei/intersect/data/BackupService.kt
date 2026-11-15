package com.reimei.intersect.data

import android.content.Context
import android.net.Uri

/** 备份文件头部的预览信息。 */
data class BackupPreview(
  val name: String,
  val size: Long,
  val epochMs: Long,
  val exportedAt: String,
  val appVersion: String,
  val section: BackupSection,
  val counts: BackupCounts,
)

/** 已经打包好、等用户挑位置落盘的一份备份。 */
data class BackupArchive(
  val content: String,
  val fileName: String,
  val section: BackupSection,
  val counts: BackupCounts,
)

/** 导出结果:存成了什么名字、包含哪一类内容。 */
data class ExportedBackup(
  val name: String,
  val section: BackupSection,
  val counts: BackupCounts,
)

/** 本机各类数据现在有多少条(备份页的每张卡片都要显示)。 */
data class BackupStats(
  val chats: Int = 0,
  val messages: Int = 0,
  val characters: Int = 0,
  val memories: Int = 0,
  val memoryOwners: Int = 0,
  val apiKeys: Int = 0,
)

/** 恢复结果:每类内容实际动了多少条。 */
data class RestoreResult(
  val mode: RestoreMode,
  val section: BackupSection,
  val charactersAdded: Int = 0,
  val charactersUpdated: Int = 0,
  val chatsAdded: Int = 0,
  val chatsMerged: Int = 0,
  val messagesAdded: Int = 0,
  val memoriesAdded: Int = 0,
  val apiKeysAdded: Int = 0,
  val apiKeysUpdated: Int = 0,
) {
  fun summary(): String = buildList {
    if (charactersAdded > 0 || charactersUpdated > 0) {
      add("角色 +$charactersAdded / 更新 $charactersUpdated")
    }
    if (chatsAdded > 0 || chatsMerged > 0) {
      add("会话 +$chatsAdded / 补充 $chatsMerged($messagesAdded 条消息)")
    }
    if (memoriesAdded > 0) {
      add("记忆 +$memoriesAdded")
    }
    if (apiKeysAdded > 0 || apiKeysUpdated > 0) {
      add("模型配置 +$apiKeysAdded / 更新 $apiKeysUpdated")
    }
  }.joinToString(" · ").ifEmpty { "没有可恢复的内容" }
}

/**
 * 数据备份的编排层:收集某一类数据 → 打包成明文 JSON → 落盘,以及读文件 → 写回各 Store。
 *
 * 四类内容各自独立成一份文件([BackupSection]):聊天记录、角色卡、记忆库、模型配置。
 * 导出固定是「这一类全带走」,恢复端按文件头声明的那一类写回,不会顺手动到别的数据。
 *
 * 落到哪里由调用方决定:界面用系统的「保存到」选择器拿 Uri,再交给 [export] 写进去。
 * 这样文件在用户的文件管理器里看得见,卸载应用也不会连带删除。
 *
 * 备份**不加密**:文件就是一段可读的 JSON,不需要口令,也不会因为口令丢失而变砖。
 * 恢复有两种模式:
 * - [RestoreMode.Merge] 只补本机没有的东西:同 id 的角色 / 模型配置以备份为准,
 *   已有会话按「本机没有的那几条消息」补进去(同样的内容不会重复落两条),本机已有的记忆不覆盖;
 * - [RestoreMode.Replace] 用备份内容整份替换,本机多出来的角色 / 会话 / 记忆 / 配置会被清掉。
 *
 * 这里不碰 ViewModel,只负责让磁盘上的数据变对;界面状态由调用方重新加载。
 */
object BackupService {

  // ---------------------------------------------------------------- 导出

  /**
   * 收集 [section] 这一类内容 → 打包 → 序列化。不落盘:落到哪里由调用方决定。
   */
  fun prepare(
    context: Context,
    section: BackupSection,
    nowMs: Long = System.currentTimeMillis(),
  ): BackupArchive {
    val appContext = context.applicationContext
    val payload = collect(appContext, section)
    val counts = payload.counts()
    val envelope = BackupEnvelope(
      epochMs = nowMs,
      exportedAt = BackupFormat.timestamp(nowMs),
      appVersion = appVersion(appContext),
      section = section,
      counts = counts,
      payload = BackupFormat.payloadJson(payload),
    )
    return BackupArchive(envelope.toJson(), BackupFiles.fileName(section, nowMs), section, counts)
  }

  /**
   * 把备份内容写到用户在选择器里挑的位置(SAF)。
   *
   * @throws IllegalStateException 选的位置写不进去
   * @throws BackupFormatException 内容不是本应用的新版备份
   */
  fun export(context: Context, uri: Uri, content: String): ExportedBackup {
    val appContext = context.applicationContext
    val envelope = BackupFormat.readEnvelope(content)
    BackupFiles.write(appContext, uri, content)
    return ExportedBackup(
      name = BackupFiles.displayName(appContext, uri),
      section = envelope.section,
      counts = envelope.counts,
    )
  }

  /** 这一类内容的全部数据。 */
  private fun collect(context: Context, section: BackupSection): BackupPayload = when (section) {
    BackupSection.Chats -> {
      val chats = ChatStore.load(context) ?: emptyList()
      BackupPayload(chats = chats, avatars = avatarsOf(context, chats.map { it.friend }))
    }
    BackupSection.Characters -> {
      val characters = FriendsStore.load(context)
      BackupPayload(characters = characters, avatars = avatarsOf(context, characters))
    }
    BackupSection.Memories -> BackupPayload(memories = MemoryStore.loadAll(context))
    BackupSection.ApiKeys -> BackupPayload(apiKeys = ApiKeyStore.load(context))
  }

  /** 头像:本地路径换台机器就失效了,得把图片本身带上。 */
  private fun avatarsOf(context: Context, users: List<User>): Map<String, ByteArray> {
    val avatars = mutableMapOf<String, ByteArray>()
    users.forEach { user ->
      val file = FriendsStore.avatarFile(context, user.id)
      if (file.exists()) {
        runCatching { avatars[user.id] = file.readBytes() }
      }
    }
    return avatars
  }

  // ---------------------------------------------------------------- 预览与解析

  /** 只读文件头:用来说明「这份备份里有什么」。 */
  fun preview(document: BackupDocument): BackupPreview {
    val envelope = BackupFormat.readEnvelope(document.text())
    return BackupPreview(
      name = document.name,
      size = document.size,
      epochMs = envelope.epochMs,
      exportedAt = envelope.exportedAt,
      appVersion = envelope.appVersion,
      section = envelope.section,
      counts = envelope.counts,
    )
  }

  /** 解出内容(文件头 + 内容段)。 */
  fun payload(document: BackupDocument): BackupPayload {
    val envelope = BackupFormat.readEnvelope(document.text())
    return BackupFormat.payloadFromJson(envelope.section, envelope.payload)
  }

  /** 本机各类数据现在有多少条。 */
  fun stats(context: Context): BackupStats {
    val appContext = context.applicationContext
    val chats = ChatStore.load(appContext) ?: emptyList()
    val memories = MemoryStore.loadAll(appContext)
    return BackupStats(
      chats = chats.size,
      messages = chats.sumOf { it.msgs.size },
      characters = FriendsStore.load(appContext).size,
      memories = memories.sumOf { it.summaries.size },
      memoryOwners = memories.count { it.summaries.isNotEmpty() },
      apiKeys = ApiKeyStore.load(appContext).size,
    )
  }

  // ---------------------------------------------------------------- 恢复

  /**
   * 把备份写回本机。
   *
   * @throws BackupFormatException 文件不是本应用的新版备份
   */
  fun restore(
    context: Context,
    document: BackupDocument,
    mode: RestoreMode = RestoreMode.Merge,
  ): RestoreResult {
    val appContext = context.applicationContext
    val envelope = BackupFormat.readEnvelope(document.text())
    val payload = BackupFormat.payloadFromJson(envelope.section, envelope.payload)

    return when (envelope.section) {
      BackupSection.Characters -> restoreCharacters(appContext, payload, mode, envelope.section)
      BackupSection.Chats -> restoreChats(appContext, payload, mode, envelope.section)
      BackupSection.Memories -> restoreMemories(appContext, payload, mode, envelope.section)
      BackupSection.ApiKeys -> restoreApiKeys(appContext, payload, mode, envelope.section)
    }
  }

  /** 清空本机全部聊天记录,返回清掉的会话数。 */
  fun clearChats(context: Context): Int {
    val appContext = context.applicationContext
    val count = ChatStore.load(appContext)?.size ?: 0
    ChatStore.save(appContext, emptyList())
    return count
  }

  private fun restoreCharacters(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
  ): RestoreResult {
    val incoming = payload.characters ?: return RestoreResult(mode, section)
    // 先把头像落地:下面重建 User 时要引用新路径
    val avatarPaths = payload.avatars.mapValues { (id, bytes) ->
      val target = FriendsStore.avatarFile(context, id)
      runCatching {
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target.absolutePath
      }.getOrElse { null }
    }

    val existing = FriendsStore.load(context)
    val existingById = existing.associateBy { it.id }
    val restored = incoming.map { user ->
      user.copy(avatarPath = avatarPaths[user.id] ?: existingById[user.id]?.avatarPath)
    }
    val merged = LinkedHashMap<String, User>()
    var added = 0
    var updated = 0
    if (mode == RestoreMode.Replace) {
      restored.forEach { merged[it.id] = it }
      added = restored.size
    } else {
      existing.forEach { merged[it.id] = it }
      restored.forEach { user ->
        if (merged.put(user.id, user) == null) added++ else updated++
      }
    }
    FriendsStore.save(context, merged.values.toList())
    return RestoreResult(mode, section, charactersAdded = added, charactersUpdated = updated)
  }

  private fun restoreApiKeys(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
  ): RestoreResult {
    val incoming = payload.apiKeys ?: return RestoreResult(mode, section)
    val existing = ApiKeyStore.load(context)
    val merged = LinkedHashMap<String, ApiKey>()
    var added = 0
    var updated = 0
    if (mode == RestoreMode.Replace) {
      incoming.forEach { merged[it.id] = it }
      added = incoming.size
    } else {
      existing.forEach { merged[it.id] = it }
      incoming.forEach { key ->
        if (merged.put(key.id, key) == null) added++ else updated++
      }
    }
    ApiKeyStore.save(context, merged.values.toList())
    return RestoreResult(mode, section, apiKeysAdded = added, apiKeysUpdated = updated)
  }

  private fun restoreChats(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
  ): RestoreResult {
    val incoming = payload.chats ?: return RestoreResult(mode, section)
    val existing = ChatStore.load(context) ?: emptyList()
    // 角色卡可能不是这份备份里的:会话里那份对方快照要换成本机最新的角色(名称 / 设定 / 头像)
    val latestById = FriendsStore.load(context).associateBy { it.id }
    val restored = incoming.map { chat ->
      latestById[chat.friend.id]?.let { chat.friend = it }
      chat
    }
    val merged = LinkedHashMap<String, Chat>()
    var added = 0
    var mergedChats = 0
    var messagesAdded = 0
    if (mode == RestoreMode.Replace) {
      restored.forEach { chat ->
        merged[chat.friend.id] = chat
        added++
        messagesAdded += chat.msgs.size
      }
    } else {
      existing.forEach { merged[it.friend.id] = it }
      restored.forEach { chat ->
        val local = merged[chat.friend.id]
        if (local == null) {
          merged[chat.friend.id] = chat
          added++
          messagesAdded += chat.msgs.size
        } else {
          val count = mergeBackupMsgs(local, chat)
          if (count > 0) {
            mergedChats++
            messagesAdded += count
          }
          // 本机没有绑定 KEY 时才用备份里的绑定关系,避免把用户的当前选择改掉
          if (local.apiKeyId == null && chat.apiKeyId != null) local.apiKeyId = chat.apiKeyId
        }
      }
    }
    ChatStore.save(context, merged.values.toList())
    return RestoreResult(
      mode = mode,
      section = section,
      chatsAdded = added,
      chatsMerged = mergedChats,
      messagesAdded = messagesAdded,
    )
  }

  private fun restoreMemories(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
  ): RestoreResult {
    val incoming = payload.memories ?: return RestoreResult(mode, section)
    if (mode == RestoreMode.Replace) {
      MemoryStore.replaceAll(context, incoming)
      return RestoreResult(mode, section, memoriesAdded = incoming.size)
    }
    // 合并只补本机没有的角色记忆:本机已有的那份是这段对话后来攒的,盖回去等于把新记忆抹掉
    val existing = MemoryStore.loadAll(context).associateBy { it.friendId }
    val additions = incoming.filter { existing[it.friendId] == null }
    if (additions.isEmpty()) return RestoreResult(mode, section)
    additions.forEach { MemoryStore.save(context, it) }
    return RestoreResult(mode, section, memoriesAdded = additions.size)
  }

  private fun appVersion(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
  }.getOrDefault("")
}

/**
 * 把 [source] 里本机还没有的消息补到 [target] 末尾。
 *
 * 消息没有 id 也没有时间戳([Msg] 只有发送方 + 文本 + 已读 + 记忆索引),所以按「发送方 + 文本」
 * 做多重集差值:本机已有的同内容消息先抵消掉,剩下的是真正的新消息。
 * 这样同一份备份恢复两次不会把消息翻倍,本机原有的顺序也不会被打乱。
 *
 * 抵消掉的那条也不是什么都不做:备份里那份带记忆索引、本机那条没有时,把索引回填给本机那条
 * —— 升级前发出的消息本来没有索引,恢复一次就能补上,不必白丢。
 *
 * @return 实际补进去的条数
 */
internal fun mergeBackupMsgs(target: Chat, source: Chat): Int {
  // 按「发送方 + 文本」把本机消息分组:每命中一条就消掉一条,剩下的才算新消息
  val remaining = HashMap<String, MutableList<Msg>>()
  target.msgs.forEach { msg ->
    remaining.getOrPut(msgsKey(msg)) { mutableListOf() }.add(msg)
  }
  var added = 0
  source.msgs.forEach { msg ->
    val pending = remaining[msgsKey(msg)]
    val local = if (pending.isNullOrEmpty()) null else pending.removeAt(0)
    if (local != null) {
      // 同一轮本机已有:只把备份里的记忆索引补过去,不新增消息
      if (local.memory == null) local.memory = msg.memory
    } else {
      // 记忆索引跟着消息一起搬:恢复出来的那一轮仍然记得自己是谁、什么时候落过盘
      target.msgs.add(
        Msg(msg.from, msg.text, time = msg.time, memory = msg.memory).apply { read = msg.read }
      )
      added++
    }
  }
  return added
}

private fun msgsKey(msg: Msg): String = "${msg.from.id}\u0000${msg.text}"
