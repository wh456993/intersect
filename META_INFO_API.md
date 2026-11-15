# MetaInfo 接口调用档(即时元信息 · 数据层)

> 状态:**数据层接口已完成**。请求体组装方式**未接入** —— 调用方自行决定
> 把元信息块放进 system、记忆块还是总结请求的 user,本文档只约定「怎么取、长什么样」。
>
> 章节划分:**第 1~5、7 章**讲元信息的常规字段(手填 + 数值→名词);
> **第 6 章**讲时间 —— 它规则不同、模块独立,单独成章。

## 1. 文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/reimei/intersect/data/MetaInfo.kt` | 数据结构 + 数值→名词映射(`MetaPhrases`)+ 名词块拼装(`block()`) |
| `app/src/main/java/com/reimei/intersect/data/MetaInfoStore.kt` | 按角色持久化(SharedPreferences,仿 `ItemStore`) |
| `app/src/main/java/com/reimei/intersect/data/SceneTime.kt` | **时间专用**(独立模块,见第 6 章):剧情时钟接口 + 按扮演模式分流 |
| `app/src/test/java/com/reimei/intersect/data/MetaInfoTest.kt` | 边界值与块格式单测(11 用例) |
| `app/src/test/java/com/reimei/intersect/data/SceneTimeTest.kt` | 时间分流单测(12 用例) |

## 2. 核心契约

1. **数值一律 0~100**:`favorability`(好感度)、`health`(生命值)、`hunger`(饥饿值)。
2. **数值永远不进请求体**:对外只能用 `favorNoun()` / `healthNoun()` / `hungerNoun()` /
   `block()`,这些输出里**不含任何阿拉伯数字**(单测 `元信息块里不出现任何数字` 钉住)。
3. **buff 词条由 AI 按剧情定**:程序不根据数值自动塞词条;`MetaInfo.buffs` 只是
   人工登记的当前生效词条,合法值仅限 `MetaInfo.BUFF_VOCAB`(饥饿/失血/中毒/重病,
   存储层会过滤野词条)。

## 3. 数据结构

```kotlin
data class MetaInfo(
  val friendId: String,        // 角色 id(= Friend/User.id)
  val favorability: Int = 50,  // 好感度 0~100,50=陌生人
  val health: Int = 80,        // 生命值 0~100,0=死亡
  val hunger: Int = 80,        // 饥饿值 0~100,越高越饱
  val time: String = "",       // 时间(**特殊字段**,读写前先看第 6 章)
  val relationship: String = "", // 关系(自由文本)
  val location: String = "",   // 地点(预留地图管理器)
  val characters: String = "", // 场景在场角色
  val weather: String = "",    // 气象(常规/未知)
  val buffs: List<String> = emptyList(), // 生效 buff,仅限 BUFF_VOCAB
)
```

- 文本字段留空 = 没登记,`block()` 里**整行省略**。
- 三个数值字段永远有值(有默认),总是出现在 `block()` 里。
- 初值:好感 50(陌生)/ 生命 80(健康)/ 饥饿 80(正常吃饱)——
  **好感度初始值应由用户按角色性格在设置页改**。

## 4. 数值 → 名词映射(确定性,`MetaPhrases`)

| 字段 | 分档 |
|---|---|
| 好感 `favor` | 0-19 仇视 / 20-39 冷淡 / 40-59 陌生(50 居中)/ 60-79 熟悉 / 80-100 亲密 |
| 生命 `health` | 0 死亡 / 1-29 重病 / 30-49 状态不佳 / 50-69 亚健康 / 70-90 健康 / 91-100 优质(90 归健康) |
| 饥饿 `hunger` | 0-19 饥饿 / 20-49 微饿 / 50-79 六分饱 / 80 正常吃饱 / 81-100 吃撑 |

- 输入越界自动 `coerceIn(0,100)`,不抛异常。
- 饥饿 20-49 的「微饿」是实现时补的中间档(规格只给了锚点 50/80/>80/<20),要改只动
  `MetaPhrases.hunger` 一处。
- 数值↔buff 对应(**给 AI 提示词引用,程序不自动套用**):
  饥饿 ← 饥饿值 <20;重病 ← 生命值 <30;失血/中毒 无数值规则,纯看剧情。

## 5. 名词块格式(`MetaInfo.block()`)

```
[元信息]
时间:黄昏
好感:陌生
关系:点头之交
地点:镇口酒馆
角色:旅人、守卫
天气:小雨
生命:亚健康
Buff:失血、中毒
饥饿:六分饱
```

首行常量 `MetaInfo.HEADER`。全空时只剩首行 + 三行数值名词。

## 6. 时间字段(独立模块 · 特殊规则)

> **与第 3~5 章的元信息字段隔离。** 关系、地点、角色、气象都是用户手填、AI 只读;
> 时间不同 —— 它**不由用户填**、也**不由 AI 生成**,而是由程序的时钟决定。
> 所以它单独成模块(`data/SceneTime.kt`),其他字段的接口不受它影响。

### 6.1 两条来源路径

| 扮演模式 | 时间从哪来 | 当前状态 |
|---|---|---|
| **本体代入** `SELF_INSERT` | 时间管理器 `TimeManager.formatMinuteAt()` 直接输出本地真实年月日时刻(`yyyy-MM-dd HH:mm`) | ✅ 已实现 |
| **角色代入** `AVATAR_BASED` | 由 `FictionClock` 按剧情算法生成 | ⚠️ **接口已留,算法未实现** |

角色代入的算法未实现时,`DefaultFictionClock` 恒返回 `null`,表现为**回落到用户手填的 `MetaInfo.time`**。

### 6.2 接口签名

```kotlin
fun interface FictionClock {
  // 返回 null = 该角色没有剧情时钟 → 回落手填值
  // 返回 ""   = 确定没有时间(失忆/梦境)→ 该行整行省略
  // 返回文本 = 直接进请求体,不含数值
  fun timeAt(friendId: String, nowMillis: Long): String?
}

object SceneTime {
  // 统一分流入口
  fun of(
    mode: RoleplayMode,
    friendId: String,
    fallback: String = "",
    nowMillis: Long = System.currentTimeMillis(),
    fiction: FictionClock = DefaultFictionClock,
  ): String

  // 套回元信息,得到「这一轮真正进请求体」的那份
  fun applyTo(meta: MetaInfo, mode: RoleplayMode, nowMillis: Long = ...): MetaInfo
}
```

### 6.3 硬保证(单测钉住)

1. **本体代入永远走真实时钟**:就算传了 `fiction`,也不会生效 —— 不给算法留后门。
2. **本体代入忽略手填值**:真实时间覆盖 `MetaInfo.time`。
3. **角色代入算法优先**;算法返回 `null` 才回落手填;返回 `""` 时不回落(那是“确定没时间”)。
4. **算法应是纯函数**:同一 `nowMillis` 给同一结果,否则历史记忆会前后打架。
5. 时间行不含阿拉伯数字以外的干扰(算法时间是纯文本名词)。

### 6.4 调用示例

```kotlin
// 要一句话的时间:按会话扮演模式自动分流
val timeText = SceneTime.of(
  mode = friend.roleplayMode,
  friendId = friend.id,
  fallback = meta.time,   // 角色代入且算法未实现时用它
)

// 要整块元信息:先套时间,再取块
val effective = SceneTime.applyTo(meta, friend.roleplayMode)
val segment = effective.block()
```

### 6.5 将来实现角色代入算法时要改的地方

只实现 `FictionClock` 一个方法,然后把它传给 `SceneTime.of` / `applyTo` 的 `fiction` 参数
(或把 `DefaultFictionClock` 换成真实实现)。**不要**改 `MetaInfo` 字段、块格式、总结链路。

## 7. 调用示例(元信息部分)

```kotlin
// 读(没存过返回默认初值,坏数据也回落默认)
val meta = MetaInfoStore.load(context, friendId)

// 改一个字段后存回去
MetaInfoStore.save(context, meta.copy(favorability = 65, location = "镇口酒馆"))

// 总结请求 / 聊天请求里要的那一段(只有名词,没有数字)
val segment: String = meta.block()
```

## 8. 接线状态

已接通(按需求给的**请求体模板**落地):

| 位置 | 怎么用 |
|---|---|
| 聊天 `system` | `ChatService.characterCard()`:设定卡(名称 / 性格 / 身份 / 世界观 / 背景)+ `MetaInfo.RULES`(系统元信息规则 + 状态名词对照表),再接 `MemoryInject.RULES` 与时间规则 —— 三段都是常量,前缀稳定 |
| 聊天状态快照(第二条 `user`) | `ChatService.snapshotBlock(meta, memory)`:先 `meta.block()`(元信息块),再 `MemoryInject.block(...)`(记忆库摘要);总结轮时后段省略(总结块自己成条) |
| 聊天调用链 | `MainViewModel.send()` 里 `SceneTime.applyTo(MetaInfoStore.load(...), friend.roleplayMode)` 后再交给 `ChatService.reply(..., meta)` —— **时间由时钟决定,不由手填** |
| 总结请求 | `MemorySummarizer.userContent(rounds, meta)` 在原文末尾追加 `meta.block()`;`DEFAULT_TEMPLATE` 末尾接 `MetaInfo.SUMMARY_RULES`(怎么读元信息 + buff 词条由 AI 按剧情定 + 不许输出数值);`MemoryJobs.effectiveMeta()` 负责先套时钟再传进去 |

仍未做:

- 设置界面:`CharacterInfoPage` 加「元信息」入口(照物品管理器的导航模式)—— 现在只有数据层与请求链路,
  `MetaInfoStore.save` 还没有界面调用方(好感初始值按角色性格改也在这里)。
- **角色代入的剧情时钟算法**:实现 `FictionClock` 即可,见 6.5。
- 物品管理器**没有**进状态快照:维持原做法(扫描用户输入里出现的物品名,把名称 + 简介拼到话尾,
  见 `ChatService.injectItems`)。
- 开发者控制台(`DevRequestConfig.payload`)仍是自己那套拼法:body 由代码框决定,只有简版角色卡
  + 记忆块,不带元信息块。要接的话按 `MainViewModel.send()` 的写法传一份 `MetaInfo` 即可。
