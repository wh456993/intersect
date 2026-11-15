# 剩余待办(按优先级)

> 接手方式:先读 `handoff/README.md` 了解现场与硬性约定,再按 P0 → P1 → P2 推进。
> 每完成一项就跑一次 `sh gradlew :app:compileDebugKotlin --offline`,别攒到最后。

## 时间子系统(阶段一~七)状态

**代码全部落地,并与聊天链路接通。** 单测 348 例全绿,`assembleDebug` 通过。

目录:`data/time/`(纯 Kotlin,不依赖 Android 除 `ClockStore` 的 prefs)。

| 任务 | 内容 | 文件 |
|---|---|---|
| T1.1 | 时段枚举 + 半开区间表(8 段覆盖 00:00–24:00) | `Period.kt` |
| T1.2 | 每时段默认名词 | `PeriodNouns.kt` |
| T1.3 | fallback 四级降级 + 降级标记 | `TimeFallback.kt` |
| T1.4 | 五份契约字段表 + 校验 | `TimeContracts.kt` |
| T1.5 | 轮次分类 + 普通轮白名单 | `RoundType.kt` |
| T1.6 | 阈值/模板/pending 消费规则 | `TimeConfig.kt` |
| T2.1 | `ClockState`(不含 `round_type`) | `ClockState.kt` |
| T2.2–T2.5 | 时段映射 / 推进 / 导演对齐 / 暂停倍率 | `SceneClock.kt` |
| T2.6 | 快照 + 事务日志 + 回滚 | `ClockLedger.kt` |
| T3.1–T3.5 | 规则块 / 普通轮 / 总结轮 / 临时查询 / 保险丝 | `TimeInject.kt` |
| T4.1–T4.5 | 总结触发 / 元数据 / 继承合并 / 注入位置 / 导演轻量事件 | `SummaryMeta.kt` |
| T5.1–T5.2 | 结构化指令 + 受限自然语言解析 | `DirectorTx.kt` |
| T5.3–T5.5 | 唯一写入口 / 事务日志 / 写权限隔离 | `TimeAuthority.kt` |
| T6.1–T6.3 | 只读约束 / 违规报时 / 标签复述清洗 | `TimeGuard.kt` |
| T6.4 | 角色查询时间(用后即焚) | `TimeGuard.kt` + `TimeInject.kt` |
| T7.1 | 四类日志 | `TimeLog.kt` |
| 接线 | 事件 → 轮次类型 → 注入计划 | `TimeRounds.kt` |
| 接线 | 解析 → 执行 → 快照/日志 → 回滚(不碰 Android) | `ClockSession.kt` |
| T2.6 | 按角色的账本落盘 | `ClockStore.kt` |
| 序列化 | 自建纯 Kotlin JSON(JVM 单测里 `org.json` 是空实现) | `MiniJson.kt` |
| 场景面板 | 三字段(时间/地点/天气)编辑 + `<旧变更新>` 一次性注入 | `SceneFields.kt` |
| 时间管理器 | 基准时间(程序设定的初始账本)+ 非公元纪年解析/格式化 | `BaseTime.kt` + `SceneFields.kt` |

### 导演模式场景面板(时间/地点/天气)

聊天输入栏的「+」弹出附件面板,面板里的折线图图标进入导演模式,再从下方弹出三字段面板。

- 字段冻结为 `SceneField.TIME / PLACE / WEATHER`,**没有第四项**;地点/天气上限 40 字(`SceneConfig`)。
- 人读时间格式 `2026.5.6 8:22`(月/日/时不补零,分钟两位),用固定纪元把 `ClockState.dateAnchor`
  往返成日期,换算全为无时区整数逻辑。
- 写入口仍是 `TimeAuthority.applyScene(...)`:无差异直接返回 `null`(不写时钟、不产生事务、不注入);
  有差异则记快照 + 事务日志、生成 pending 补丁里的 `changes`,可按 `tx_id` 回滚(回滚会恢复地点/天气)。
- 注入形态由 `DirectorEvents.format(...)` 决定:有 `changes` 时逐行渲染 `<旧值变更新值>`,
  否则仍走原轻量事件模板。
- **一次性**:下一次聊天进入导演事件轮时注入,发出成功后 `consumeSceneChanges(...)` 清掉 `changes`,
  补丁本体保留给下一次总结吸收 —— 所以同一行变更不会每轮重复出现。

已接入现有链路的地方:

- `ChatService.payload/messages/reply` 多了 `time: InjectionPlan?` 参数:规则块进 system 末尾,
  动态块(临时事实 / 导演事件 / 总结块+元数据)单独成条,位置固定为「记忆块之后、当前输入之前」。
- `MainViewModel.send()`:构造请求时确定 `round_type`(有记忆总结 ⇒ 总结轮;否则有 pending 补丁 ⇒
  导演事件轮;否则普通轮),回复落地前过 `OutputGuard.sanitize`,总结轮成功后 `markSummary` + 落盘。
- `MainViewModel.runDirectorTime()`:`/time ...` 指令入口(不受 `DevMode` 门禁),回显走新的
  `clockNotice` 状态行;`rollbackClock()` 按 `tx_id` 回滚。
- `MainViewModel.deleteFriend()` 顺手 `ClockStore.remove`。

### 时间管理器:基准时间与非公元纪年

「时间管理器」是角色信息页(`CharacterInfoPage`)里的一行,**角色代入专属**,点开是基准时间编辑页
(`BaseTimePage`)。(早期版本它在创建角色页的角色卡下面,后来随「点模式即进角色信息页」一起挪了过来,
交互没变:能填、能改、格式不对时「完成」不可点。)

- 基准时间是**程序设定的**初始 `ClockState`,有两条写路径:
  - 新角色:`BaseTime.parse(...)` + `BaseTime.seedLedger(...)`,经 `MainViewModel.addFriend(..., baseTime)`
    直接作为账本初始状态落盘 —— 不产生导演事务、不记快照,事务日志从空开始;
  - 已有角色:`BaseTime.mergeInto(current, text)` 只换时间四项,地点 / 天气 / 倍率 / 暂停 /
    待注入的导演补丁全部保留,事务与快照也不动。**别用 `parse` 的结果整份覆盖** ——
    那会把导演设过的场景清成空串、把待注入补丁抹掉。
- 角色信息页那一行:有具体时刻时显示 `基准时间 · <值>`,由 `MainViewModel.baseTimeLabel()` 拼;
  草稿(还没改过)时显示 `基准时间(程序设定的)`。
- 非公元纪年只影响**人读文本**,不参与推进 / 跨日 / 闰年等算法,内部仍用 `dateAnchor` 天序号:
  `eraName` 空串表示公元,`eraPlusSign` 决定是否显式显示 `+`。
- 格式为「纪年名 + 空格 + 年份」,如 `第三纪 +2026.1.2 0:30`;解析认 `.` / `/` / `-` 三种日期分隔符
  与全角冒号/点/斜杠。年份范围 `-9999..9999`,纪年名上限 12 字且不能含数字(`SceneConfig`)。

顺带的两处收尾:

- **图标改标准黑色**:输入栏「+」(3 处)、附件面板图标、折线图图标(`LineChartIcon` 默认值)的 tint
  全部统一为 `AppTheme.colors.textPrimary`;灰色等同未激活,不再出现。
- **i18n**:新增基准时间 / 纪年提示 / 校验失败的英文对照,`EnStringsPatternsTest` 收敛原先重复的
  基准时间用例(合并为「文案会翻译」+「带具体时刻只翻前缀」两条),并钉住导演面板格式提示已是带纪年的版本。

这条路径的验证记录(本轮):

```
sh gradlew :app:compileDebugKotlin --offline   → BUILD SUCCESSFUL
sh gradlew :app:testDebugUnitTest --offline    → BUILD SUCCESSFUL,348 例 0 失败
sh gradlew :app:assembleDebug --offline        → BUILD SUCCESSFUL(APK 已重建)
```

时间相关测试类(`app/src/test/java/com/reimei/intersect/data/time/`):`PeriodTest`、`PeriodNounsTest`、
`TimeFallbackTest`、`MiniJsonTest`、`TimeContractsTest`、`RoundTypeTest`、`TimeConfigTest`、`ClockStateTest`、
`SceneClockTest`、`ClockLedgerTest`、`TimeInjectTest`、`SummaryMetaTest`、`DirectorTxTest`、`TimeGuardTest`、
`TimeLogTest`、`TimeIntegrationTest`、`TimeRoundsTest`、`ClockSessionTest`、`TimeAcceptanceTest`、
`SceneFieldsTest`、`SceneEditTest`、`EraTimeTest`,外加 `data/ChatServiceTimePayloadTest`(请求体拼接契约)与
`i18n/EnStringsPatternsTest`(导演面板 / 基准时间文案规则反查)。

### T7.4 验收清单自动化

`TimeAcceptanceTest` 把需求第 8 节的 16 条逐条跑真实链路(不是断言常量),失败时连编号与原文一起报。
另有一条用例钉住「编号连续且不重复」。

### T7.5 人工核对(仍需人看,无法自动化)

1. **AI 输出样本里是否真的不再自行报时 / 复述标签** —— `OutputGuard` 只保证「命中就删」,
   没有命中但换个说法的报时(如「日头偏西」)只能靠人读回复判断是否需要补词表。
2. **元数据虽已贴尾,模型是否仍把它当台词引用** —— 自动化只能验证它排在总结块末尾且带「非台词」标记。

核对方式:真机聊 20 轮触发一次总结,把请求体与回复各存一份人工过一遍(按约定本轮未做真机启动)。

## 本轮已完成的既有待办

- **主聊天「回滚」已落地**:`MainViewModel.rollbackTo` + `ConversationPage` 长按菜单的「回滚」+ 确认弹窗,
  入口不受 `DevMode` 门禁(普通模式可达)。截断逻辑抽成 `data/Chat.kt` 的纯函数 `truncateAt()` 并有单测。
- **回滚/删除后在飞回复不再追加**:`send()` 记下这一轮的提问对象,回复到达时若它已不在会话里就丢弃
  (同时把消耗流水提到这个判断之前,已计费的请求在统计里仍看得到)。
- **纪元守卫可测 + 更严**:`MemoryStore.nextEpoch()` 同一秒内也递增(`maxOf(now, prev + 1)`),
  `clear()` / `reconcile()` 都走它;守卫命中时不再谎报「总结完成」,改为记一条错误并返回 `Fail`(可重试)。
- **提示词长度上限**:`MemoryPromptStore.MAX_LENGTH = 4000`,`cap()` 代理对安全;`init` / `setPrompt` /
  `draft()` / `template()` 四处同源裁剪;编辑框下方有「已用 N / 4000 字符」计数。
- **多选顶栏重叠**:换成 `SelectionTopBar`(返回 44dp + 标题 `weight(1f)` 省略 + 三个动作),不再有叠字风险。
- **i18n**:补记忆库/控制台缺失条目与 8 条 patterns(含新的回滚文案),新增
  `EnStringsPatternsTest` 钉住「真拼出来的中文能匹配到规则」;删掉半句死条目「这条消息以及它之后的」。
- **`rollbackTo` / `deleteMsgs` 会清理残留多选项**(`pruneSelection`),多选状态不再指向已删对象。


## P0 — 待确认(需要用户拍板)

### 记忆块文案格式与需求有偏差

需求给的固定格式用**全角竖线 `|` 与全角逗号 `,`**:

```
【记忆上下文|角色过去经历,非用户发言】
[即时记忆层·最近3条总结]
[总结1|生成时间|覆盖轮次]
```

当前实现(`data/ImmediateMemory.kt` 的 `MemoryInject`)用**半角** `|` 与半角逗号:

```
【记忆上下文|角色过去经历,非用户发言】
[即时记忆层·最近 2 条总结]
[总结1|2025-01-15 14:05|第 1-20 轮]
```

**本轮已向用户提过这个问题但没得到回答,先按「不动」处理**(半角保持原样)。
若之后决定改成全角,需要同步改:`MemoryInject.HEADER` / `SECTION` / `block()` 的拼接、
`MemoryInject.RULES` 里的措辞,以及 `ChatServiceMemoryPayloadTest`(8 例里多处硬编码)与
`ImmediateMemoryTest` 的断言,改完跑单测。

## P1 — i18n 收尾(英文模式下会显示中文)

主界面三个页面(`MemoryPage` / `ConversationPage` / `DevConsolePage`)的**可见文案已全部登记**:
现存的「未登记」只剩 `${}` 插值串与 `log()` 日志串,前者由 `patterns` 覆盖并有测试,
后者不进界面。

仍需处理的是**其它页面的记忆相关文案**(`CharacterInfoPage` / `ShellPage` 等)与 `contentDescription`:

- `contentDescription` 传的是裸中文字符串(如 `ConversationPage` 的「退出多选」「聊天信息」),
  不经过 `t()`,英文模式下 TalkBack 仍读中文。这是既有约定,要么统一改走 `t()`,要么在
  `EnStrings` 里把这些条目删掉免得误导。

**怎么重新生成清单**(本机无 python/node,用 shell):

```sh
mkdir -p build/tmp/i18n
grep -oh '"[^"]*"' app/src/main/java/com/reimei/intersect/ui/{MemoryPage,ConversationPage,DevConsolePage}.kt \
  | tr -d '"' | grep -P '[\x{4e00}-\x{9fff}]' | sort -u > build/tmp/i18n/ui.txt
grep -oP '^\s*"\K[^"]+(?="\s+to\s+)' app/src/main/java/com/reimei/intersect/i18n/EnStrings.kt \
  | sort -u > build/tmp/i18n/en.txt
comm -23 build/tmp/i18n/ui.txt build/tmp/i18n/en.txt | grep -v '^['"'"'$]'
```

(`$` 开头的是 Kotlin 插值或日志串,不逐条翻译。)

## P2 — 审查遗留项(都是小项,但别跳过)

1. **控制台与主聊天的一致性**:身份匹配(`msg.role == "user"` → `User.Me`)与列表 `key`
   在两处各写了一遍(`DevConsolePage.consoleRounds()` 与 `MainViewModel`/`ConversationPage`),
   核对口径是否一致(尤其 `it.error` 过滤与空文本过滤)。主聊天没有 error 消息,目前等价。
2. **控制台的 `rollbackTo` 仍逐条删除**:主聊天已改用 `truncateAt()` 纯函数,控制台那份是
   `DevMsg` 版本,可以照抄一份 `truncateDevAt()` 并复用同一批测试思路。
3. **确认弹窗的条数文案已统一**:主聊天与控制台都改成「从这条起的 N 条都会被删除」,
   N 含被点的那条自己,英文对 N==1 有单独说法。
4. **`MemoryJobs` 丢弃路径的错误条数**:每次纪元冲突都会 `recordError` 一条,
   错误日志上限 20 条,频繁回滚时可能被刷屏 —— 观察是否需要合并同类项。
5. **可达性复核**:记忆库入口(角色信息页 / 控制台)、「回滚」入口、`PromptCard` 都不能挂在
   `DevMode.enabled` 这类默认关闭的开关上。当前三者都不门禁 —— 保持。
6. `androidTest/MemoryStoreTest.kt` 从未运行过(需要设备 + runner)。按约定不跑 instrumented,
   如果要用请先问用户。
7. **`reconcile` 现在无条件写盘**:纪元每次都变,内容比较永远不相等,已按此简化;
   若将来觉得删除消息时写 prefs 太频繁,再考虑把「纪元变更」与「内容变更」分开处理。

## 验证记录(时间子系统接入后)

最新一次(含基准时间 / 非公元纪年 / 图标黑色):

```
sh gradlew :app:compileDebugKotlin --offline   → BUILD SUCCESSFUL
sh gradlew :app:testDebugUnitTest --offline    → BUILD SUCCESSFUL,348 例 0 失败
sh gradlew :app:assembleDebug --offline        → BUILD SUCCESSFUL
```

**还没做**:真机安装 + 启动核对(按约定不做)。所以以下行为只是「代码上成立」,没有实际跑过:

- 每 20 轮自动总结的真实触发(需要 20 轮真聊天);
- 重启后滑块/轮数/总结列表的恢复(`MemoryStore` 逻辑有单测覆盖,但没有端到端验证);
- 记忆块与时间注入块在真实请求体里的样子(`ChatServiceMemoryPayloadTest` /
  `ChatServiceTimePayloadTest` 覆盖了拼接,但没看过真实报文);
- 「回滚期间在飞回复被丢弃」与「总结结果作废」这两条路径;
- 导演 `/time` 指令从输入框到时钟落盘的完整交互(单测只到 `ClockSession`,`MainViewModel`
  那一层需要设备)。

## 尚未接线的部分(下一步)

1. **导演控制台 UI**:`MainViewModel.runDirectorTime` 已能用,但目前只能从聊天输入框发
   `/time ...`(`send()` 里识别前缀);还没有时钟面板(展示精确时刻 / 时段 / 事务列表 / 回滚按钮)。
2. **临时查询轮的触发**:`RoundType.TEMP_QUERY` + `CharacterTimeQuery` 已实现并有单测,
   但还没接入「用户问『现在几点』」的识别入口(按 R6,这属于新任务,需要先定识别规则)。
3. **保险丝注入的实际触发点**:`TimeDedupe` 已实现,`MainViewModel` 目前只走「总结轮 / 导演事件轮 / 普通轮」
   三条分支,跨时段且没能总结时的 `fuse` 尚未接上(需要先决定跨时段判定放在哪一层)。
4. **控制台与主聊天的编排一致性**:`DevConsolePage` 的请求仍走旧签名(不带 `time`),
   若要求两处一致需要同样接入 `TimeRounds`。
5. **场景面板的人工交互核对**:面板弹出/收起位置、折线图入口、三字段输入与保存、保存后回显、
   返回键逐层收起、保存后下一轮 `<旧变更新>` 只出现一次 —— 都是代码上成立,尚无真机核对
   (按约定不启动 App 到物理屏、不用 vscreen)。
6. **完整导演时钟面板**:事务列表 + 回滚按钮仍未做;当前只有三字段场景编辑与保存,
   回滚只能由 `rollbackClock()` 从代码路径触发。
7. **基准时间 / 纪年的人工核对**(按约定不自动跑):角色信息页时间管理器展开/保存/校验失败提示;
   改完基准时间后导演设过的地点 / 天气是否还在(这是 `BaseTime.mergeInto` 的契约,
   已有 JVM 单测钉住,但界面上的往返仍需手点一遍);`第三纪 +302.5.6 8:22` 与
   负数年份在导演面板与回显里能否往返;图标是否为纯黑(不再有灰色观感)。
8. **创建角色新流程的人工核对**:点「角色代入」/「本体代入」进的草稿页标题是否带模式名、
   草稿里加的物品与改的基准时间是否在点「完成」后落盘、退回表单改点另一种模式后物品是否不再掉落。

