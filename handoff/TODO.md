# 剩余待办(按优先级)

> 接手方式:先读 `handoff/README.md` 了解现场与硬性约定,再按 P0 → P1 → P2 推进。
> 每完成一项就跑一次 `sh gradlew :app:compileDebugKotlin --offline`,别攒到最后。

## 已完成(本轮)

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

## 验证记录(本轮的)

```
sh gradlew :app:compileDebugKotlin --offline   → BUILD SUCCESSFUL
sh gradlew :app:testDebugUnitTest --offline    → BUILD SUCCESSFUL,18 个测试类全绿
sh gradlew :app:assembleDebug --offline        → BUILD SUCCESSFUL
```

单测共 **134 例,0 失败 0 跳过**。本轮新增/扩充:

- `EnStringsPatternsTest`(7 例):英文规则对「真拼出来的中文」都能匹配(含回滚文案单复数);
- `MemoryEpochTest`(4 例):纪元单调、同秒内可区分、时钟回拨不退化;
- `MsgTruncateTest`(6 例):回滚截断按对象身份、同内容两条不串、目标不在列表时不删;
- `MemoryPromptTest` 由 5 例扩到 9 例:补三态与 `cap()` 上限 / 代理对安全。

**还没做**:真机安装 + 启动核对(按约定不做)。所以以下行为只是「代码上成立」,没有实际跑过:

- 每 20 轮自动总结的真实触发(需要 20 轮真聊天);
- 重启后滑块/轮数/总结列表的恢复(`MemoryStore` 逻辑有单测覆盖,但没有端到端验证);
- 记忆块在真实请求体里的样子(`ChatServiceMemoryPayloadTest` 覆盖了拼接,但没看过真实报文);
- 「回滚期间在飞回复被丢弃」与「总结结果作废」这两条新路径。
