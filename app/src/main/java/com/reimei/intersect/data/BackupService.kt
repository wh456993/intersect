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

/**
 * 导出选择器里的一行:一个可以被选中的角色卡或会话(选择器是单选,一次导一个角色)。
 *
 * [id] 是实体在存储层的稳定标识(角色卡与会话都用角色 id,会话就是以对方为键),
 * 导出时按它过滤;[user] 用来画头像与名字;[preview] 只有会话用得上(最后一条消息),角色卡为 null。
 */
data class BackupPickItem(
  val id: String,
  val user: User,
  val preview: String? = null,
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
 * 备份页上是三张卡片 + 一份整包([BackupSection]):聊天记录、角色卡、模型配置,
 * 以及把全部数据一起带走的「软件备份」。恢复端按文件头声明的那一类写回,不会顺手动到别的数据。
 *
 * 聊天记录与角色卡导出前先在界面上选一个具体的角色([pickList] 提供候选,[prepare] 按 id 过滤);
 * 导出聊天记录时,**所选会话各自的记忆也跟着一起走** —— 记忆不再有单独的卡片。
 * 模型配置与整包没有选择器,整类带走。
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
   *
   * [selectedIds] 是导出选择器里选中的对象 id:[BackupSection.Characters] 时是角色卡 id,
   * [BackupSection.Chats] 时是会话 id(即会话里的角色 id)。选择器是单选,所以集合里通常只有一项;
   * 保留集合类型是为了这层不关心界面怎么选。
   * **为 null 表示不筛选**(整类导出);给了集合就只导出其中的实体,绝不顺带全量塞进去。
   * 空集合是合法的(导出成一份 0 条内容的备份),界面已经拦住「什么都没选」的情况。
   */
  fun prepare(
    context: Context,
    section: BackupSection,
    nowMs: Long = System.currentTimeMillis(),
    selectedIds: Set<String>? = null,
  ): BackupArchive {
    val appContext = context.applicationContext
    val payload = collect(appContext, section, selectedIds)
    val counts = payload.counts()
    val envelope = BackupEnvelope(
      epochMs = nowMs,
      exportedAt = BackupFormat.timestamp(nowMs),
      appVersion = appVersion(appContext),
      section = section,
      counts = counts,
      payload = BackupFormat.payloadJson(payload),
    )
    val name = BackupFiles.fileName(section, subjectOf(section, selectedIds, payload), nowMs)
    return BackupArchive(envelope.toJson(), name, section, counts)
  }

  /**
   * 文件名里的角色名:只在「明确只选了一个角色 / 一条会话」时才有。
   *
   * 整类导出(selectedIds 为 null)不能用内容里的第一个名字 —— 文件里装着所有角色,
   * 拿小明的名字当文件名就是在说谎。模型配置与整包本来就只写应用名,不取 subject。
   */
  private fun subjectOf(
    section: BackupSection,
    selectedIds: Set<String>?,
    payload: BackupPayload,
  ): String? {
    if (selectedIds?.size != 1) return null
    return when (section) {
      BackupSection.Characters -> payload.characters?.firstOrNull()?.name
      BackupSection.Chats -> payload.chats?.firstOrNull()?.friend?.name
      BackupSection.Memories, BackupSection.ApiKeys, BackupSection.Full -> null
    }
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

  /**
   * 导出选择器要列的候选:角色卡是全部角色,聊天记录是全部会话(带最后一条消息的预览)。
   *
   * 模型配置与整包没有选择器(整类导出),返回空列表。
   */
  fun pickList(context: Context, section: BackupSection): List<BackupPickItem> {
    val appContext = context.applicationContext
    return when (section) {
      BackupSection.Characters ->
        FriendsStore.load(appContext).map { BackupPickItem(id = it.id, user = it) }
      BackupSection.Chats ->
        (ChatStore.load(appContext) ?: emptyList()).map { chat ->
          BackupPickItem(
            id = chat.friend.id,
            user = chat.friend,
            preview = chat.msgs.lastOrNull()?.text?.takeIf { it.isNotBlank() },
          )
        }
      BackupSection.Memories, BackupSection.ApiKeys, BackupSection.Full -> emptyList()
    }
  }

  /** 这一类内容的数据;[selectedIds] 非 null 时只取其中的实体。 */
  private fun collect(
    context: Context,
    section: BackupSection,
    selectedIds: Set<String>? = null,
  ): BackupPayload = when (section) {
    // 名字跟着会话走:带走的会话,它自己的那份记忆也一起带走。
    // 这里不带头像(角色卡归属另一份备份):图片一张就是几百 KB,而恢复会话时并不会用到它们。
    BackupSection.Chats -> {
      val chats = (ChatStore.load(context) ?: emptyList()).filterBy(selectedIds) { it.friend.id }
      BackupPayload(
        chats = chats,
        memories = memoriesOf(context, chats.map { it.friend.id }.toSet()),
      )
    }
    BackupSection.Characters -> {
      val characters = FriendsStore.load(context).filterBy(selectedIds) { it.id }
      BackupPayload(characters = characters, avatars = avatarsOf(context, characters))
    }
    BackupSection.Memories -> BackupPayload(memories = MemoryStore.loadAll(context))
    BackupSection.ApiKeys -> BackupPayload(apiKeys = ApiKeyStore.load(context))
    BackupSection.Full -> {
      val characters = FriendsStore.load(context)
      BackupPayload(
        characters = characters,
        chats = ChatStore.load(context) ?: emptyList(),
        memories = MemoryStore.loadAll(context),
        apiKeys = ApiKeyStore.load(context),
        avatars = avatarsOf(context, characters),
      )
    }
  }

  /** [friendIds] 这些角色在本机存的记忆;一条都没有时返回 null(备份里就不写这一段)。 */
  private fun memoriesOf(
    context: Context,
    friendIds: Set<String>,
  ): List<ImmediateMemoryState>? =
    MemoryStore.loadAll(context).filter { it.friendId in friendIds }.takeIf { it.isNotEmpty() }

  /** [ids] 为 null 时原样返回(整套导出);否则只留下 id 被勾选的实体,顺序不变。 */
  private fun <T> List<T>.filterBy(ids: Set<String>?, idOf: (T) -> String): List<T> =
    filterSelectedBy(this, ids, idOf)

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
      BackupSection.Full -> restoreAll(appContext, payload, mode, envelope.section)
    }
  }

  /** 整包恢复:四段各按同一个 [mode] 写回一次,彼此不串。 */
  private fun restoreAll(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
  ): RestoreResult {
    val characters = restoreCharacters(context, payload, mode, section)
    // 记忆统一交给 restoreMemories 处理:否则同一批记忆会被写两次、完成提示里的「记忆 +N」永远是 0
    val chats = restoreChats(context, payload, mode, section, withMemories = false)
    val memories = restoreMemories(context, payload, mode, section, missingAsEmpty = true)
    val apiKeys = restoreApiKeys(context, payload, mode, section)
    return RestoreResult(
      mode = mode,
      section = section,
      charactersAdded = characters.charactersAdded,
      charactersUpdated = characters.charactersUpdated,
      chatsAdded = chats.chatsAdded,
      chatsMerged = chats.chatsMerged,
      messagesAdded = chats.messagesAdded,
      memoriesAdded = memories.memoriesAdded,
      apiKeysAdded = apiKeys.apiKeysAdded,
      apiKeysUpdated = apiKeys.apiKeysUpdated,
    )
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

  /**
   * 写回会话。
   *
   * [withMemories] 为 true 时会顺手把 [payload] 里这几条会话的记忆也写回(聊天记录备份就是这个形态);
   * 整包恢复传 false —— 那里记忆由 [restoreMemories] 统一处理。
   */
  private fun restoreChats(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
    withMemories: Boolean = true,
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
    // 记忆跟着会话走:导聊天记录时会带上这些会话各自的记忆,恢复时也只写回它们
    val memoriesAdded = if (!withMemories) 0 else restoreMemoriesFor(
      context = context,
      chatIds = incoming.map { it.friend.id }.toSet(),
      states = payload.memories.orEmpty(),
      mode = mode,
    )
    return RestoreResult(
      mode = mode,
      section = section,
      chatsAdded = added,
      chatsMerged = mergedChats,
      messagesAdded = messagesAdded,
      memoriesAdded = memoriesAdded,
    )
  }

  /**
   * 把 [states] 里的记忆逐个写回本机,返回实际写进去的条数。
   *
   * 合并模式只补本机**还没有总结**的那几个角色 —— 本机已有总结的是这段对话后来攒的,盖回去等于抹掉新记忆。
   * 判据用的是「有没有总结」而不是「prefs 里有没有这条」:每轮对话、每次拖滑块都会落一条空 state,
   * 拿条目存在性当判据会把备份里的真记忆静默跳过。
   *
   * 覆盖模式只动 [chatIds] 这几条会话,并且备份里没带记忆的那几条会把本机那仍清掉 ——
   * 那些总结描述的是已经被覆盖掉的对话,留着只会在下次发消息时被当成真的历史注入。
   * 同一份文件里没涉及的其它角色一律不动(一份只有一条会话的备份,不该把别人的记忆清空)。
   */
  private fun restoreMemoriesFor(
    context: Context,
    chatIds: Set<String>,
    states: List<ImmediateMemoryState>,
    mode: RestoreMode,
  ): Int {
    val existing = MemoryStore.loadAll(context).associateBy { it.friendId }
    val incomingById = states.filter { it.friendId in chatIds }.associateBy { it.friendId }
    val targets = incomingById.values.filter { state ->
      val local = existing[state.friendId]
      mode == RestoreMode.Replace || local == null || local.summaries.isEmpty()
    }
    targets.forEach { MemoryStore.save(context, it) }
    if (mode == RestoreMode.Replace) {
      chatIds.forEach { id ->
        if (id !in incomingById && existing[id] != null) MemoryStore.remove(context, id)
      }
    }
    return targets.size
  }

  /**
   * 写回记忆。
   *
   * [missingAsEmpty] 为 true 时把「没有这一段」当成「记忆是空的」:整包覆盖时它就是真的空,
   * 不能因为文件里没写而把本机记忆留着。单独导入记忆库备份时仍是 null 就直接返回(什么都不做)。
   */
  private fun restoreMemories(
    context: Context,
    payload: BackupPayload,
    mode: RestoreMode,
    section: BackupSection,
    missingAsEmpty: Boolean = false,
  ): RestoreResult {
    val incoming = payload.memories ?: if (!missingAsEmpty) return RestoreResult(mode, section) else emptyList()
    if (mode == RestoreMode.Replace) {
      MemoryStore.replaceAll(context, incoming)
      return RestoreResult(mode, section, memoriesAdded = incoming.size)
    }
    // 合并只补本机还没有总结的角色:本机已有的那份是这段对话后来攒的,盖回去等于把新记忆抹掉
    val existing = MemoryStore.loadAll(context).associateBy { it.friendId }
    val additions = incoming.filter { state ->
      val local = existing[state.friendId]
      local == null || local.summaries.isEmpty()
    }
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

/**
 * 按勾选结果过滤导出内容:[ids] 为 null 表示整套带走,[ids] 非 null 时只留下 id 在其中的实体。
 *
 * 抽成纯函数是为了能被单测钉住 —— 「导出只带勾中的那些,绝不顺带全量塞进去」是这一层的硬约束,
 * 一旦有人把它改成「空集合当成不过滤」,用户就会在毫无提示的情况下把全部角色/会话导出出去。
 */
internal fun <T> filterSelectedBy(
  items: List<T>,
  ids: Set<String>?,
  idOf: (T) -> String,
): List<T> = if (ids == null) items else items.filter { idOf(it) in ids }
