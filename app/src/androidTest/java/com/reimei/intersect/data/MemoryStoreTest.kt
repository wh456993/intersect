package com.reimei.intersect.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 角色唯一 ID 与记忆索引的存储测试(真机跑,org.json 与 SharedPreferences 都只有真机才有)。
 *
 * 这两样东西用户看不见,坏了也不会报错 —— 只会静默地"查不到是谁的记忆"。所以把三条底线
 * 钉在这里:号发出去不会重复也不会退回 0/1、号跟着角色记录(含备份)一起走、
 * 记忆索引(角色 UID / 用户 UID / 秒级落盘时间)落盘后能原样读回来。
 *
 * 每个用例前后都把用到的两份存储原文备份再还原,跑测试不会把设备上的数据搞乱。
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  /** 和 ChatStore 里的私有常量一致。计数器是 Long,在下面单独备份。 */
  private val stores = listOf("chat_store" to "chats")
  private val snapshot = mutableMapOf<String, String?>()
  private val uidSnapshot = mutableMapOf<String, Long?>()

  private val friend = User("friend_1", "小明", 0, signature = "写代码")

  @Before
  fun snapshotStores() {
    stores.forEach { (prefs, key) ->
      snapshot["$prefs/$key"] = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        .getString(key, null)
    }
    // 计数器是 Long,单独存一份原文
    uidSnapshot["next_uid"] = context.getSharedPreferences("uid_store", Context.MODE_PRIVATE)
      .let { if (it.contains("next_uid")) it.getLong("next_uid", 0L) else null }
  }

  @After
  fun restoreStores() {
    snapshot.forEach { (path, value) ->
      val (prefs, key) = path.split("/")
      val edit = context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
      if (value == null) edit.remove(key) else edit.putString(key, value)
      edit.commit()
    }
    val next = uidSnapshot["next_uid"]
    val edit = context.getSharedPreferences("uid_store", Context.MODE_PRIVATE).edit()
    if (next == null) edit.remove("next_uid") else edit.putLong("next_uid", next)
    edit.commit()
  }

  @Test
  fun newCharactersGetDistinctUidsFromTwo() {
    // 连着发三个号:互不相同,而且都落在 2 之后(intersect 占 0、我占 1)
    val a = UidStore.nextUid(context)
    val b = UidStore.nextUid(context)
    val c = UidStore.nextUid(context)
    assertEquals(3, setOf(a, b, c).size)
    assertTrue("号不能落在 0 / 1 上:$a", a >= UidStore.FIRST_FREE_UID)
    assertTrue("号不能落在 0 / 1 上:$b", b >= UidStore.FIRST_FREE_UID)
    assertTrue("号不能落在 0 / 1 上:$c", c >= UidStore.FIRST_FREE_UID)
    assertNotEquals(UidStore.INTERSECT_UID, a)
    assertNotEquals(UidStore.ME_UID, a)
  }

  @Test
  fun reservedUidIsNeverIssuedAgain() {
    // 恢复备份回来的角色带着一个大号:计数器必须跳到它后面,之后的新角色不会撞上去
    UidStore.reserve(context, 500L)
    val next = UidStore.nextUid(context)
    assertTrue("恢复进来的号被重复发出了:$next", next > 500L)
  }

  @Test
  fun dirtyCounterNeverIssuesReservedUids() {
    // 计数器被写坏(0 / 1 都是被占用的号)时,也要从 2 开始发
    context.getSharedPreferences("uid_store", Context.MODE_PRIVATE).edit()
      .putLong("next_uid", 0L).commit()
    val uid = UidStore.nextUid(context)
    assertTrue("脏计数器发出了被占用的号:$uid", uid >= UidStore.FIRST_FREE_UID)
  }

  @Test
  fun uidTravelsWithCharacterRecord() {
    // 号跟着角色记录走:存盘 → 读回还是同一个号(备份复用同一段 JSON,所以恢复到换机都不变)
    val withUid = friend.copy(uid = 42L)
    assertEquals(42L, userFromJson(userToJson(withUid))!!.uid)
    // 内置角色不用落盘也永远是 0
    assertEquals(UidStore.INTERSECT_UID, userFromJson(userToJson(DawnAssistant.assistant()))!!.uid)
    // 老数据没有 uid 字段:读回来是 null,交给上层补号
    assertNull(userFromJson(userToJson(friend))!!.uid)
  }

  @Test
  fun memoryStampSurvivesStoreRoundTrip() {
    val stamp = MemoryStamp(
      characterUid = friend.uid ?: 7L,
      userUid = UidStore.ME_UID,
      storedAt = 1_700_000_123L,
    )
    val chat = Chat(friend, mutableListOf(Msg(User.Me, "在吗"), Msg(friend, "在", memory = stamp)))
    ChatStore.save(context, listOf(chat))

    val loaded = ChatStore.load(context)!!.single()
    assertEquals(listOf("在吗", "在"), loaded.msgs.map { it.text })
    assertNull("用户那一句不该带记忆索引", loaded.msgs[0].memory)
    assertEquals(stamp, loaded.msgs[1].memory)
    // 秒级时间原样回来,显示时再按本地时区格式化
    assertEquals(1_700_000_123L, loaded.msgs[1].memory!!.storedAt)
  }

  @Test
  fun incompleteMemoryIsDropped() {
    // 缺字段的索引整条丢掉,不能拿默认值编一条出来(那会伪装成 intersect 的记忆)
    val obj = JSONObject().apply {
      put("friend", userToJson(friend))
      put(
        "msgs",
        org.json.JSONArray().apply {
          put(
            JSONObject().apply {
              put("fromMe", false)
              put("text", "在")
              put("memory", JSONObject().put("storedAt", 1_700_000_000L))
            }
          )
        }
      )
    }
    assertNull(chatFromJson(obj)!!.msgs.single().memory)
  }

  @Test
  fun memoryStampLabelCarriesBothUidsAndSecond() {
    val stamp = MemoryStamp(characterUid = 0L, userUid = UidStore.ME_UID, storedAt = 1_700_000_123L)
    val label = stamp.label()
    assertTrue(label, label.contains("UID 0"))
    assertTrue(label, label.contains("UID ${UidStore.ME_UID}"))
    // 精确到秒:标签里是 "yyyy-MM-dd HH:mm:ss" 那一份格式化结果
    assertTrue(label, Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").containsMatchIn(label))
  }
}
