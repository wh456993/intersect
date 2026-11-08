package com.reimei.intersect.data

import android.content.Context
import android.net.Uri

/** 备份文件头部的预览信息:不输口令就能看到的那部分。 */
data class BackupPreview(
  val name: String,
  val size: Long,
  val epochMs: Long,
  val exportedAt: String,
  val appVersion: String,
  val sections: List<BackupSection>,
  val counts: BackupCounts,
)

/** 已经打包好、等用户挑位置落盘的一份备份。 */
data class BackupArchive(
  val content: String,
  val fileName: String,
  val sections: List<BackupSection>,
  val counts: BackupCounts,
)

/** 导出结果:存成了什么名字、包含哪些内容。 */
data class ExportedBackup(
  val name: String,
  val sections: List<BackupSection>,
  val counts: BackupCounts,
)

/** 恢复结果:每类内容实际动了多少条。 */
data class RestoreResult(
  val mode: RestoreMode,
  val sections: List<BackupSection>,
  val charactersAdded: Int,
  val charactersUpdated: Int,
  val chatsAdded: Int,
  val chatsMerged: Int,
  val messagesAdded: Int,
  val apiKeysAdded: Int,
  val apiKeysUpdated: Int,
) {
  fun summary(): String = buildList {
    if (charactersAdded > 0 || charactersUpdated > 0) {
      add("角色 +$charactersAdded / 更新 $charactersUpdated")
    }
    if (chatsAdded > 0 || chatsMerged > 0) {
      add("会话 +$chatsAdded / 补充 $chatsMerged($messagesAdded 条消息)")
    }
    if (apiKeysAdded > 0 || apiKeysUpdated > 0) {
      add("API KEY +$apiKeysAdded / 更新 $apiKeysUpdated")
    }
  }.joinToString(" · ").ifEmpty { "没有可恢复的内容" }
}

/**
 * 数据备份的编排层:收集数据 → 加密打包 → 落盘,以及读文件 → 解密 → 写回各 Store。
 *
 * 落到哪里由调用方决定:界面用系统的「保存到」选择器拿 Uri,再交给 [export] 写进去。
 * 这样文件在用户的文件管理器里看得见,卸载应用也不会连带删除。
 *
 * 三类内容([BackupSection])完全独立可选。恢复有两种模式:
 * - [RestoreMode.Merge] 只补充本机没有的东西:同 id 的角色 / KEY 以备份为准,
 *   已有会话按「本机没有的那几条消息」补进去(同样的内容不会重复落两条,重复恢复也不会翻倍);
 * - [RestoreMode.Replace] 用备份内容整份替换,本机多出来的角色 / 会话 / KEY 会被清掉。
 *
 * 这里不碰 ViewModel,只负责让磁盘上的数据变对;界面状态由调用方重新加载。
 */
object BackupService {

  // ---------------------------------------------------------------- 导出

  /**
   * 收集选定内容 → 加密打包 → 序列化。不落盘:落到哪里由调用方决定。
   *
   * @throws BackupCryptoException 加密失败
   * @throws BackupFormatException 一项内容都没选
   */
  fun prepare(
    context: Context,
    password: String,
    sections: Set<BackupSection>,
    nowMs: Long = System.currentTimeMillis(),
  ): BackupArchive {
    if (sections.isEmpty()) throw BackupFormatException("至少选择一项要备份的内容")
    val appContext = context.applicationContext
    val payload = collect(appContext, sections)
    val plain = BackupFormat.payloadToJson(payload).toByteArray(Charsets.UTF_8)
    val ordered = BackupSection.entries.filter { it in sections }
    val block = BackupCrypto.encrypt(
      password = password,
      plaintext = plain,
      aad = BackupFormat.aadOf(ordered),
    )
    val counts = payload.counts()
    val envelope = BackupEnvelope(
      epochMs = nowMs,
      exportedAt = BackupFormat.timestamp(nowMs),
      appVersion = appVersion(appContext),
      sections = ordered,
      counts = counts,
      block = block,
    )
    return BackupArchive(envelope.toJson(), BackupFiles.fileName(), ordered, counts)
  }

  /**
   * 把备份内容写到用户在选择器里挑的位置(SAF)。
   *
   * 内容与「这份备份里有什么」都从传进来的文本读(文件头不需要口令),
   * 所以回传时即使进程已经被系统重建过、内存里的状态都没了,也只需要这一段文本。
   *
   * @throws IllegalStateException 选的位置写不进去
   * @throws BackupFormatException 内容不是本应用的备份
   */
  fun export(context: Context, uri: Uri, content: String): ExportedBackup {
    val appContext = context.applicationContext
    val envelope = BackupFormat.readEnvelope(content)
    BackupFiles.write(appContext, uri, content)
    return ExportedBackup(BackupFiles.displayName(appContext, uri), envelope.sections, envelope.counts)
  }

  private fun collect(context: Context, sections: Set<BackupSection>): BackupPayload {
    val characters = if (BackupSection.Characters in sections) {
      FriendsStore.load(context)
    } else {
      null
    }
    val chats = if (BackupSection.Chats in sections) ChatStore.load(context) ?: emptyList() else null
    val apiKeys = if (BackupSection.ApiKeys in sections) ApiKeyStore.load(context) else null

    // 头像:本地路径换台机器就失效了,得把图片本身带上
    val avatars = mutableMapOf<String, ByteArray>()
    val owners = buildList {
      characters?.let { addAll(it) }
      chats?.forEach { chat -> add(chat.friend) }
    }
    owners.forEach { user ->
      val file = FriendsStore.avatarFile(context, user.id)
      if (file.exists()) {
        runCatching { avatars[user.id] = file.readBytes() }
      }
    }
    return BackupPayload(characters, chats, apiKeys, avatars)
  }

  // ---------------------------------------------------------------- 预览

  /** 只读文件头:不需要口令,用来说明「这份备份里有什么」。 */
  fun preview(document: BackupDocument): BackupPreview {
    val envelope = BackupFormat.readEnvelope(document.text())
    return BackupPreview(
      name = document.name,
      size = document.size,
      epochMs = envelope.epochMs,
      exportedAt = envelope.exportedAt,
      appVersion = envelope.appVersion,
      sections = envelope.sections,
      counts = envelope.counts,
    )
  }

  /** 输口令解出内容,核对口令是否正确与内容规模是否与文件头一致。 */
  fun verify(document: BackupDocument, password: String): BackupPayload {
    val envelope = BackupFormat.readEnvelope(document.text())
    val plain = BackupCrypto.decrypt(
      password = password,
      block = envelope.block,
      aad = BackupFormat.aadOf(envelope.sections),
    )
    return BackupFormat.payloadFromJson(String(plain, Charsets.UTF_8))
  }

  // ---------------------------------------------------------------- 恢复

  /**
   * 把备份写回本机。
   *
   * @throws BackupCryptoException 口令不正确或文件被改动
   * @throws BackupFormatException 文件不是本应用的备份
   */
  fun restore(
    context: Context,
    document: BackupDocument,
    password: String,
    mode: RestoreMode = RestoreMode.Merge,
  ): RestoreResult {
    val appContext = context.applicationContext
    val payload = verify(document, password)
    val sections = BackupPayload.sectionsOf(payload)

    var charactersAdded = 0
    var charactersUpdated = 0
    var chatsAdded = 0
    var chatsMerged = 0
    var messagesAdded = 0
    var apiKeysAdded = 0
    var apiKeysUpdated = 0

    // 先把头像落地:下面重建 User 时要引用新路径
    val avatarPaths = payload.avatars.mapValues { (id, bytes) ->
      val target = FriendsStore.avatarFile(appContext, id)
      runCatching {
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target.absolutePath
      }.getOrElse { null }
    }

    // ---- 角色
    if (payload.characters != null) {
      val existing = FriendsStore.load(appContext)
      val existingById = existing.associateBy { it.id }
      val restored = payload.characters.map { user -> user.withAvatar(avatarPaths[user.id], existingById) }
      val merged = LinkedHashMap<String, User>()
      if (mode == RestoreMode.Replace) {
        restored.forEach { merged[it.id] = it }
        charactersAdded = restored.size
      } else {
        existing.forEach { merged[it.id] = it }
        restored.forEach { user ->
          val before = merged[user.id]
          merged[user.id] = user
          if (before == null) charactersAdded++ else charactersUpdated++
        }
      }
      FriendsStore.save(appContext, merged.values.toList())
    }

    // ---- API KEY
    if (payload.apiKeys != null) {
      val existing = ApiKeyStore.load(appContext)
      val merged = LinkedHashMap<String, ApiKey>()
      if (mode == RestoreMode.Replace) {
        payload.apiKeys.forEach { merged[it.id] = it }
        apiKeysAdded = payload.apiKeys.size
      } else {
        existing.forEach { merged[it.id] = it }
        payload.apiKeys.forEach { key ->
          val before = merged[key.id]
          merged[key.id] = key
          if (before == null) apiKeysAdded++ else apiKeysUpdated++
        }
      }
      ApiKeyStore.save(appContext, merged.values.toList())
    }

    // ---- 会话
    if (payload.chats != null) {
      val existing = ChatStore.load(appContext) ?: emptyList()
      // 上面可能刚写过通讯录:会话里那份对方快照要换成最新的角色(名称 / 设定 / 头像)
      val latestById = FriendsStore.load(appContext).associateBy { it.id }
      val restored = payload.chats.map { chat ->
        latestById[chat.friend.id]?.let { chat.friend = it }
        chat
      }
      val merged = LinkedHashMap<String, Chat>()
      existing.forEach { merged[it.friend.id] = it }
      if (mode == RestoreMode.Replace) merged.clear()
      restored.forEach { chat ->
        val local = merged[chat.friend.id]
        if (local == null) {
          merged[chat.friend.id] = chat
          chatsAdded++
          messagesAdded += chat.msgs.size
        } else {
          val added = mergeBackupMsgs(local, chat)
          if (added > 0) {
            chatsMerged++
            messagesAdded += added
          }
          // 本机没有绑定 KEY 时才用备份里的绑定关系,避免把用户的当前选择改掉
          if (local.apiKeyId == null && chat.apiKeyId != null) local.apiKeyId = chat.apiKeyId
        }
      }
      ChatStore.save(appContext, merged.values.toList())
    }

    return RestoreResult(
      mode = mode,
      sections = sections,
      charactersAdded = charactersAdded,
      charactersUpdated = charactersUpdated,
      chatsAdded = chatsAdded,
      chatsMerged = chatsMerged,
      messagesAdded = messagesAdded,
      apiKeysAdded = apiKeysAdded,
      apiKeysUpdated = apiKeysUpdated,
    )
  }

  /**
   * 给恢复出来的角色挑头像路径。
   *
   * 备份里带了图片就用新写出来那份;没带就沿用本机原有的自定义头像(如果有),
   * 否则留空,让界面回落到内置图。
   */
  private fun User.withAvatar(path: String?, existing: Map<String, User>): User = copy(
    avatarPath = path ?: existing[id]?.avatarPath,
  )

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
