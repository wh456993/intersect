package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 合并恢复时「消息怎么补」的单元测试。
 *
 * 消息没有 id 也没有时间戳,恢复时光靠内容判重,所以这条规则必须钉死:
 * 同样的备份恢复两次不能让消息翻倍,本机原有的顺序也不能被打乱。
 */
class BackupMergeTest {
  private fun chat(vararg messages: Msg): Chat =
    Chat(User("friend_1", "小明", 0), messages.toMutableList())

  private val me = User.Me
  private val friend = User("friend_1", "小明", 0)

  @Test
  fun `本机为空时全部补进来`() {
    val target = chat()
    val source = chat(Msg(me, "在吗"), Msg(friend, "在"))
    assertEquals(2, mergeBackupMsgs(target, source))
    assertEquals(listOf("在吗", "在"), target.msgs.map { it.text })
  }

  @Test
  fun `已有消息不会被重复补一遍`() {
    val target = chat(Msg(me, "在吗"), Msg(friend, "在"))
    val source = chat(Msg(me, "在吗"), Msg(friend, "在"))
    assertEquals(0, mergeBackupMsgs(target, source))
    assertEquals(2, target.msgs.size)
  }

  @Test
  fun `只补本机没有的新消息,顺序接在后面`() {
    val target = chat(Msg(me, "在吗"), Msg(friend, "在"))
    val source = chat(Msg(me, "在吗"), Msg(friend, "在"), Msg(me, "今天有空吗"))
    assertEquals(1, mergeBackupMsgs(target, source))
    assertEquals(listOf("在吗", "在", "今天有空吗"), target.msgs.map { it.text })
  }

  @Test
  fun `同内容重复说过的消息按条数抵消`() {
    // 本机说过两次「哈哈哈」,备份里只有一次:这是同一类消息,不该再补
    val target = chat(Msg(me, "哈哈哈"), Msg(me, "哈哈哈"))
    assertEquals(0, mergeBackupMsgs(target, chat(Msg(me, "哈哈哈"))))
    // 反过来:备份里有两条、本机只有一条,要补一条回来
    val target2 = chat(Msg(me, "哈哈哈"))
    assertEquals(1, mergeBackupMsgs(target2, chat(Msg(me, "哈哈哈"), Msg(me, "哈哈哈"))))
  }

  @Test
  fun `同一句话由不同的人说出来算两条`() {
    val target = chat(Msg(me, "好"))
    assertEquals(1, mergeBackupMsgs(target, chat(Msg(friend, "好"))))
    assertEquals(listOf("好", "好"), target.msgs.map { it.text })
  }

  @Test
  fun `重复恢复同一份备份不会让消息翻倍`() {
    val target = chat(Msg(me, "在吗"))
    val source = chat(Msg(me, "在吗"), Msg(friend, "在"), Msg(me, "晚安"))
    mergeBackupMsgs(target, source)
    mergeBackupMsgs(target, source)
    assertEquals(3, target.msgs.size)
  }

  @Test
  fun `补进来的消息保持已读状态`() {
    val target = chat()
    val unread = Msg(friend, "新消息").apply { read = false }
    mergeBackupMsgs(target, chat(unread))
    assertEquals(false, target.msgs.single().read)
  }

  @Test
  fun `补进来的消息带着记忆索引`() {
    // 记忆索引是落盘时留下的身份与时间,恢复时不能丢:丢了这一轮就查不到是谁的号
    val target = chat()
    val stamp = MemoryStamp(characterUid = 3L, userUid = UidStore.ME_UID, storedAt = 1_700_000_000L)
    mergeBackupMsgs(target, chat(Msg(friend, "在", memory = stamp)))
    assertEquals(stamp, target.msgs.single().memory)
  }

  @Test
  fun `本机已有那条消息时,备份里的记忆索引会回填给它`() {
    // 升级前发出的消息没有索引;从备份恢复时不该再补一条,而是把索引补到本机那条上
    val stamp = MemoryStamp(characterUid = 3L, userUid = UidStore.ME_UID, storedAt = 1_700_000_001L)
    val target = chat(Msg(friend, "在"))
    assertEquals(0, mergeBackupMsgs(target, chat(Msg(friend, "在", memory = stamp))))
    assertEquals(1, target.msgs.size)
    assertEquals(stamp, target.msgs.single().memory)
  }

  @Test
  fun `本机那条已经有索引时不覆盖`() {
    val local = MemoryStamp(characterUid = 3L, userUid = UidStore.ME_UID, storedAt = 1L)
    val fromBackup = MemoryStamp(characterUid = 3L, userUid = UidStore.ME_UID, storedAt = 2L)
    val target = chat(Msg(friend, "在", memory = local))
    mergeBackupMsgs(target, chat(Msg(friend, "在", memory = fromBackup)))
    assertEquals(local, target.msgs.single().memory)
  }
}
