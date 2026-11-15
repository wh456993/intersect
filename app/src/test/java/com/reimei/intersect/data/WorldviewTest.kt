package com.reimei.intersect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界观数据与「哪个模式有哪些功能」的单元测试。
 *
 * 这里只测纯逻辑。世界观 / 背景的**落盘**([FriendsStore] 的 JSON 往返)在本工程里没有自动化覆盖:
 * JVM 单测里的 `org.json` 是空实现,这类断言只能进 androidTest(目前没接线)。改那一层时请手工验证一次
 * ——填好设定、杀掉进程重开、看内容是否还在,以及老数据(没有这两个字段)读入是否为 null。
 *
 * 钉住两条契约:
 * 1. 四项全空 = 没填过([Worldview.isEmpty]),这样「没填」与「填了空壳」在存储层是同一种状态;
 * 2. **两种扮演模式各有一份手写的入口清单**([RoleplayMode.modules]):角色代入有空间 / 物品管理器、
 *    世界观与背景、可改的基准时间;本体代入一个都没有,只有实时时间那个展示行。
 *    两份列表互不推导 —— 以后任一侧增删只动它自己那份。
 */
class WorldviewTest {

  @Test
  fun `四项全空算没填过`() {
    assertTrue(Worldview().isEmpty)
    assertTrue(Worldview(laws = "", factions = "  ", economy = null, history = null).isEmpty)
    assertEquals(0, Worldview(laws = "", history = " ").filledCount)
  }

  @Test
  fun `填了几项按非空计`() {
    val partially = Worldview(laws = "记忆即代价", history = "断河之战")
    assertFalse(partially.isEmpty)
    assertEquals(2, partially.filledCount)
    assertEquals(4, Worldview("a", "b", "c", "d").filledCount)
  }

  @Test
  fun `四项标题与顺序固定`() {
    assertEquals(
      listOf("法则体系", "势力分布", "经济架构", "历史与重大事件"),
      Worldview.LABELS
    )
  }

  @Test
  fun `两种模式的入口清单各自独立`() {
    assertEquals(
      listOf(
        CharacterModule.IDENTITY,
        CharacterModule.MEMORY,
        CharacterModule.SPACE,
        CharacterModule.ITEMS,
        CharacterModule.WORLDVIEW,
        CharacterModule.BACKGROUND,
        CharacterModule.BASE_TIME,
      ),
      RoleplayMode.AVATAR_BASED.modules
    )
    assertEquals(
      listOf(
        CharacterModule.IDENTITY,
        CharacterModule.MEMORY,
        CharacterModule.LOCAL_TIME,
      ),
      RoleplayMode.SELF_INSERT.modules
    )
  }

  @Test
  fun `世界侧功能只在角色代入那一份里`() {
    val worldModules = listOf(
      CharacterModule.SPACE,
      CharacterModule.ITEMS,
      CharacterModule.WORLDVIEW,
      CharacterModule.BACKGROUND,
    )
    worldModules.forEach { module ->
      assertTrue("角色代入应该有 $module", RoleplayMode.AVATAR_BASED.has(module))
      assertFalse("本体代入不该有 $module", RoleplayMode.SELF_INSERT.has(module))
    }
    // 时间管理器两边都有,但一个是可改的基准时间、一个只是实时展示
    assertTrue(RoleplayMode.AVATAR_BASED.has(CharacterModule.BASE_TIME))
    assertFalse(RoleplayMode.AVATAR_BASED.has(CharacterModule.LOCAL_TIME))
    assertTrue(RoleplayMode.SELF_INSERT.has(CharacterModule.LOCAL_TIME))
    assertFalse(RoleplayMode.SELF_INSERT.has(CharacterModule.BASE_TIME))
  }
}
