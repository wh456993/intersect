# 文件与关键 API 清单(记忆库相关)

路径均相对项目根 `app/src/main/java/com/reimei/intersect/`。

## 新增文件(数据层,全部未提交,`git status` 里是 `??`)

| 文件 | 作用 | 关键符号 |
| --- | --- | --- |
| `data/ImmediateMemory.kt` | 总结模型 + 状态 + 注入块格式 | `ImmediateMemory(id, fromRound, toRound, createdAt, targetTokens, actualTokens, text)`、`roundLabel()`、`timeLabel()`;`ImmediateMemoryState(friendId, targetTokens, rounds, coveredRounds, lastSummaryAt, lastInjectedBlock, lastInjectedAt, clearedAt, lastError, errors, summaries)`;派生 `pendingRounds` / `roundsUntilNext` / `needsSummary` / `latest` / `injectBlock` / `injectTokens`;变换 `withRound()` / `aligned(roundsCount)` / `withTarget()` / `withSummary()` / `withInjected()` / `withError()`;常量 `SUMMARY_EVERY=20`、`INJECT_COUNT=3`、`TARGET_MIN=200`、`TARGET_MAX=600`、`TARGET_DEFAULT=400`、`SUMMARY_KEEP=60`;`MemoryRounds.group(msgs)`;`MemoryInject`(`HEADER` / `FOOTER` / `SECTION` / `RULES` / `block(summaries)`) |
| `data/MemoryStore.kt` | 按角色持久化(SharedPreferences,一个角色一个 key) | `load` / `save` / `afterRound` / `setTarget` / `addSummary` / `recordError` / `clear(context, friendId, roundsCount)` / `reconcile(context, friendId, roundsCount)` / `remove(context, friendId)`;`internal nextEpoch(previous, now)`(单调纪元) |
| `data/MemorySummarizer.kt` | 独立总结请求 | `TEMPERATURE=0.3`、`MAX_TOKEN_RATIO=1.4`、`DEFAULT_TEMPLATE`、`TARGET_SLOT` / `MIN_SLOT`(`{target}` / `{min}`)、`fitInput()`(按 6000 token 上限逐轮裁)、`clean()`(剥代码块与「总结:」标题),结果类型 `MemorySummaryResult.Ok(text, actualTokens, usage)` / `.Fail(message)` |
| `data/MemoryJobs.kt` | 总结任务单飞调度(自动/手动共用) | `isRunning(friendId)`、`launch(...)`;独立协程作用域、`clearedAt` 纪元守卫(命中时记错误并返回 `Fail`)、usage 在守卫之前计入消耗流水 |
| `data/MemoryPromptStore.kt` | 自定义总结提示词 | `enabled` / `prompt`(Compose 状态)、`MAX_LENGTH=4000`、`cap(text)`、`init` / `setEnabled` / `setPrompt` / `draft()` / `isCustom(enabled, text)` / `template()` |
| `data/TokenEstimate.kt` | 本地 token 估算(CJK 与非 CJK 分权重) | `TokenEstimator.estimate(text)`、`truncate(text, limit)` |
| `data/UidStore.kt` | 角色 UID 分配(intersect=0、用户=1、角色从 2 起不回收) | `nextUid` / `reserve` / `nextFree` / `counterAfter` |
| `data/DevMode.kt` | 开发者模式开关(原先内联在 `SettingsPage`,本轮抽出来) | `enabled`(Compose 状态)、`init` / `setEnabled` |
| `ui/MemoryPage.kt` | 记忆库页面(946 行) | 入口 `MemoryLibraryPage(...)`;`MemoryLayerTabs`(三层 Tab)、`ImmediateLayer`(PromptCard → TargetSliderCard → 状态条 → 总结列表 → DevPanel)、`PromptCard`、`TargetSliderCard`、`StatusBar`、`EmptyMemoryHint`、`SummaryCard`、`DevPanel`、`DevRow`、`DevButton`、`ComingSoonLayer`(时间线/长期记忆层的「即将上线」占位) |

## 新增测试(`app/src/test/java/com/reimei/intersect/` 与 `app/src/androidTest/...`)

- `data/ImmediateMemoryTest.kt`(15 例):分组、轮次、`aligned`、注入块格式、token 估算。
- `data/ChatServiceMemoryPayloadTest.kt`(8 例):请求体消息顺序、已总结原文剔除、记忆块独立成一条 user、当前输入不被合并。
- `data/MemoryPromptTest.kt`(9 例):`isCustom` 三态、占位符替换、`cap()` 上限与代理对安全。
- `data/MemoryEpochTest.kt`(4 例):纪元单调、同秒内可区分、时钟回拨不退化。
- `data/MsgTruncateTest.kt`(6 例):`truncateAt` 按对象身份、同内容两条不串、目标不在列表时不删。
- `data/UidStoreTest.kt`(5 例):UID 分配/保留/撞号纠正。
- `i18n/EnStringsPatternsTest.kt`(7 例):英文规则对真拼出来的中文都能匹配(含回滚文案单复数)。
- `app/src/androidTest/java/com/reimei/intersect/data/MemoryStoreTest.kt`:**instrumented 测试,本机没有跑过**(需要设备 + runner)。

## 改动的既有文件

| 文件 | 改了什么 |
| --- | --- |
| `data/Chat.kt` | 新增纯函数 `truncateAt(msgs, target): Int`(回滚截断,按对象身份,返回删除条数 / -1);`Msg` 本身未变 |
| `data/ChatService.kt` | 新增 `ChatMessage` / `messages()`;`payload()` 支持记忆注入与固定顺序;`HISTORY_ROUND_LIMIT = ImmediateMemoryState.SUMMARY_EVERY`;`systemWithMemory(friend)`(角色卡 + `MemoryInject.RULES`);`coveredRounds` 夹取 |
| `MainViewModel.kt` | `openMemory(friendId)` / `closeMemory()`;`send()` 每轮 +1 并自动触发总结(`summarizeMemory`,行 ~338);`recordInjected`;**`rollbackTo(context, chat, msg)`**(调 `truncateAt` + `saveChats` + `pruneSelection` + `reconcile`);`send()` 在回复到达时校验这一轮提问还在会话里(不在就丢弃,消耗流水仍记账);`deleteMsgs` 也会 `pruneSelection`;`toggleSelectAll` |
| `data/MemoryStore.kt` | `internal nextEpoch(previous, now)`(单调纪元,同一秒内 +1);`clear()` / `reconcile()` 用它;`reconcile()` 改为无条件写盘 |
| `data/MemoryJobs.kt` | 消耗流水提到纪元判断之前;纪元不一致时记一条错误并返回 `Fail(「本轮总结期间删过消息或回滚过,结果已作废」)` |
| `data/MemoryPromptStore.kt` | `MAX_LENGTH = 4000`;`cap(text)`(代理对安全);`init` / `setPrompt` / `draft()` / `template()` 四处同源裁剪 |
| `data/ImmediateMemory.kt` | `clearedAt` 的 KDoc 改为「单调纪元,不是时间戳」;其余未变 |
| `data/DevConsole.kt` | `DevRequestConfig.payload(..., memory = ...)`:控制台报文按同一套规则注入记忆块 |
| `ui/ConversationPage.kt` | 多选顶栏换成私有 `SelectionTopBar`(返回 44dp + `weight(1f)` 标题 + 全选/复制/删除);长按菜单 `复制 / 删除 / 回滚 / 多选`;回滚确认弹窗(条数实时计算、目标已不在时只留关闭);删除确认;`MessageList` / `MessageItem` 新增 `onRollbackOne` / `onRollback` 回调 |
| `ui/DevConsolePage.kt` | 记忆库入口(行 ~1017)与 `MemoryLibraryPage` 挂载(行 ~725);控制台状态里 `append` / `deleteMsgs` / `rollbackTo` / `consoleRounds()`;长按菜单含回滚/多选;回滚确认文案与主聊天统一成「从这条起的 N 条...」;报文接真实记忆注入(行 ~315) |
| `ui/MemoryPage.kt` | 提示词编辑框走 `MemoryPromptStore.cap`;占位符说明下加「已用 N / 4000 字符」计数 |
| `ui/CharacterInfoPage.kt` | 「记忆库」由 `ComingSoonRow` 换成真入口(行 ~124) |
| `ui/ShellPage.kt` | 记忆库页接在角色信息页之后的 `SlideOverlay`(行 ~320) |
| `MainActivity.kt` | 初始化 `MemoryPromptStore`(和既有 `DevMode.init` 一起) |
| `i18n/EnStrings.kt` | 补记忆/多选/回滚等英文文案与 8 条 patterns;删掉半句死条目「这条消息以及它之后的」 |
| `data/ChatStore.kt` / `BackupService.kt` / `FriendsStore.kt` / `User.kt` / `TimeManager.kt` | 模型与持久化字段、备份、UID 相关的小幅扩展(`MemoryStamp` 保持原语义=记忆索引,**不是**总结) |
| `app/build.gradle` / `gradle.properties` | 构建相关(离线可用、依赖版本等)——接手后如需改动请先确认 |

## 新增测试

| 文件 | 覆盖 |
| --- | --- |
| `test/.../data/ImmediateMemoryTest.kt` | 15 例:分组、轮次、`aligned`、注入块格式、token 估算 |
| `test/.../data/ChatServiceMemoryPayloadTest.kt` | 8 例:请求体顺序、已总结原文剔除、记忆块独立成 user、当前输入不被合并 |
| `test/.../data/MemoryPromptTest.kt` | 9 例:`isCustom` 三态、占位符替换、`cap()` 上限与代理对安全 |
| `test/.../data/MemoryEpochTest.kt` | 4 例:纪元单调、同秒内可区分、时钟回拨不退化 |
| `test/.../data/MsgTruncateTest.kt` | 6 例:回滚截断按对象身份、同内容两条不串、目标不在列表时不删 |
| `test/.../data/UidStoreTest.kt` | 5 例:UID 分配/保留/撞号纠正 |
| `test/.../i18n/EnStringsPatternsTest.kt` | 7 例:英文规则对真拼出来的中文都能匹配(含回滚文案单复数) |
| `androidTest/.../data/MemoryStoreTest.kt` | **instrumented,本机没有跑过**(需要设备 + runner) |

## 记忆块实际输出格式(当前实现)

```
【记忆上下文|角色过去经历,非用户发言】
[即时记忆层·最近 2 条总结]
[总结1|2025-01-15 14:05|第 1-20 轮]
正文......
[总结2|...]
正文......
【记忆上下文结束】
```

注意:实现用的是**半角** `|` 与半角逗号,需求文档里写的是全角 `|` 和 `,`。
本轮已向用户确认过但没得到回答,**当前按半角保持不动**;差异记在 `handoff/TODO.md` 的 P0(待确认)。

## 注入用 system 规则(`MemoryInject.RULES`)

```
记忆使用规则:
1. 后面的「记忆上下文」是角色过去的经历,不是用户此刻的发言。
2. 记忆与用户当前输入冲突时,一律以当前输入为准。
3. 不得把记忆内容当作「用户说过的话」引用或复述,也不要提及「即时记忆层」「总结」这类标签。
4. 记忆只用来保持角色连贯,不需要主动说明自己记得什么。
```

措辞固定(不要每轮变化),以免破坏前缀缓存。
