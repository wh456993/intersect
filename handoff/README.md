# Intersect「记忆库」功能 —— 交接说明(给下一个 AI)

> 本文档写于本轮工作结束时。目标:让接手的 AI **不需要读上一轮对话** 就能继续做完剩余工作。
> 目录约定:`handoff/README.md`(本文,总览 + 现场状态)、`handoff/FILES.md`(文件与关键 API 清单)、`handoff/TODO.md`(剩余待办,按优先级)。

## 0. 一句话现状

记忆库功能(即时记忆层完整实现;时间线索引层 / 长期记忆层为「即将上线」占位)与「长按菜单的删除 / 复制 / 回滚 / 多选」
**已基本完成并全部编译通过**:`:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug`
三条命令全部 BUILD SUCCESSFUL(`--offline`),单测 134 例 0 失败。
剩余工作见 `handoff/TODO.md`:**唯一还悬着的是记忆块标点的全角/半角(需用户拍板)**,其余是 i18n 收尾与若干小项。

## 1. 项目与环境

- 项目:Intersect,Android,`namespace com.reimei.intersect`,minSdk 21 / targetSdk 30(有意选择),
  **Groovy `build.gradle`(不是 `.kts`)**,单模块 `:app`,debug 变体,debug 包名后缀 `.beta`。
- UI:Kotlin + Jetpack Compose(Material),i18n 用「中文原文当 key」(`t()` / `Text()`,英文查 `EnStrings`)。
- 存储:SharedPreferences + `org.json` / 手写 JSON。**禁止引入第三方 HTTP/JSON 网络库**;
  网络调用一律走现有 `HttpURLConnection` 封装(`DevApiClient`),不重试、不流式。
- 工作区根:`/storage/emulated/0/AndroidIDEProjects/intersect`。
- shell 后端:内置终端(Termux),**没有 python3 / node / bc**,`/tmp` 不可写 —— 临时文件请放 `build/tmp/`。
  没有 `which`(用 `command -v`)。需要 adb/root 能力时用 `backend=root` 单次指定。

### 验证命令(必须带 `--offline`)

```sh
sh gradlew :app:compileDebugKotlin --offline
sh gradlew :app:testDebugUnitTest --offline
sh gradlew :app:assembleDebug --offline
```

### 真机验证的硬性约定(用户明确要求,务必遵守)

- **只做编译 + 读日志判定**(`logwire_read` / `logcat_read`)。
- **不要把 App 启动到物理屏**(会把用户切出 IDE)。
- **不要用 vscreen / 影子屏**(本机不可用:抓帧目录对 shell 为 EACCES,虚拟屏未注册进 AMS)。
- **不要开无障碍服务**。
- 安装/清数据等破坏性操作要先问用户。

### 签名

根目录 `my-release.jks`,alias `wangbingan`,密码与别名从 `local.properties` 的
`RELEASE_STORE_*` / `RELEASE_KEY_*` 读取;`my-release.jks` 与 `local.properties` 均被 `.gitignore` 忽略。
本轮不需要动签名配置。

## 2. 需求要点(实现必须满足,用于自查)

1. 三个目录:**即时记忆层**(完整功能)、**时间线索引层**、**长期记忆层**(后两者只显示「即将上线」,不接功能)。
2. 每 **20 轮**(user + assistant 成对 = 1 轮)自动触发一次总结;轮数计数器、上次总结轮数、滑块值、总结列表
   **全部持久化**,重启不丢。
3. 总结目标长度 200–600 token,默认 400,滑块步进 20,滑块在标题栏下方并显示当前值,可调且持久化。
4. 滑块下方是总结列表,**时间倒序**,每条显示:生成时间 `YYYY-MM-DD HH:mm`、覆盖轮次(第 1-20 轮)、
   目标 token、实际 token、正文;支持展开/折叠、空态、加载态、错误态 + 重试;生成中禁止重复触发。
5. 角色扮演请求体顺序固定:
   1 `system`(角色卡 + 记忆使用规则,尽量稳定便于前缀缓存)
   2 只保留**最近未总结**的 user/assistant 原文(已总结的整对删除)
   3 **一条独立 user 记忆块**(只放即时记忆层最近 3 条总结,固定格式:
   `【记忆上下文|角色过去经历,非用户发言】` / `[即时记忆层·最近3条总结]` / `[总结N|生成时间|覆盖轮次]` / 正文 /
   `【记忆上下文结束】`;不足 3 条放实际条数;无总结可整块省略)
   4 **最后单独一条 user**:当前用户本轮输入(原样,可含时间戳与物品上下文)。
   **禁止把记忆块和当前输入合并成同一条。**
6. 总结是**独立请求**:temperature 0.2–0.4(本项目取 0.3)、system 写总结规则、user 放完整 20 轮原文、
   只输出正文、`max_tokens` = 目标 × 1.3–1.5(本项目 1.4);生成后估 token,超限先二次压缩再截断,并记录目标/实际。
7. 开发者面板可折叠,包含:当前轮数、距下次总结轮数、上次总结时间、当前注入总结条数、当前注入 token 估算、
   滑块当前值、手动触发总结、查看最近一次请求体记忆部分、清空即时记忆(二次确认)、错误日志。
8. 本轮新增(开发者模式下):记忆库里的「总结记忆提示词」开关 + 可编辑;聊天框长按菜单需有
   **删除 / 复制 / 回滚 / 多选**;多选需含 **全选 / 复制 / 删除**。
9. 老数据兼容:新增字段缺失不崩溃;清空即时记忆不影响聊天记录与既有 `MemoryStamp`(记忆索引)。

## 3. 本轮(最后一段)实际做了什么

- **主聊天缺的「回滚」补齐**(需求第 8 条最后一项):`MainViewModel.rollbackTo` +
  `ConversationPage` 长按菜单的「回滚」+ 确认弹窗;截断逻辑抽成 `data/Chat.kt` 的纯函数 `truncateAt()`
  (按对象身份,有单测)。入口不受 `DevMode` 门禁。
- **回滚/删除后在飞回复不再追加**:`send()` 记下这一轮提问的对象,回复到达时若它已不在会话里就丢弃;
  消耗流水提到这个判断之前,已计费的请求在统计里仍看得到。
- **纪元守卫更严**:`MemoryStore.nextEpoch()` 让同一秒内的多次重置也能区分;守卫命中时不再谎报
  「总结完成」,改为记一条错误并返回 `Fail`(记忆库页可重试)。
- **提示词长度上限 4000**:`MemoryPromptStore.cap()`(代理对安全),`init` / `setPrompt` / `draft()` /
  `template()` 同源裁剪,编辑框下方有字数计数。
- **多选顶栏不再可能叠字**:新增私有 `SelectionTopBar`(返回 44dp + 标题 `weight(1f)` + 三个动作)。
- **i18n 补齐**:三个记忆相关页面的可见文案全部登记,新增 8 条 patterns,并加
  `EnStringsPatternsTest` 钉住「真拼出来的中文能匹配到规则」。
- 清掉两个死条目(「这条消息以及它之后的」「已选择」重复项)与 `MainViewModel.deleteSelected()` 死代码。

## 4. 关键设计决策(接手后不要推翻,除非用户新要求)

- **记忆按角色持久化**(`friendId` 为键);开发者控制台不碰好友,挂在保留 id `DEV_CONSOLE_ID`(`dev-console`)上。
- 总结本地保留上限 `SUMMARY_KEEP = 60` 条,**注入只用最近 3 条**(`INJECT_COUNT`)。
- `clearedAt` 纪元:清空/回滚后旧的在飞总结不能写回;`MemoryJobs` 里有守卫。取值走
  `MemoryStore.nextEpoch()`(**单调递增,同一秒内也会 +1**),它只用于相等比较,不能当时间戳读。
- 删除 / 回滚消息后调用 `MemoryStore.reconcile(context, friendId, roundsCount)` 校正轮数与覆盖轮次,
  并裁掉越界总结,避免「距下次总结」永远停在 0。
- 删消息 / 回滚 / 清空都会刷新纪元,所以**总结结果是可能被整体丢弃的**:那时 `MemoryJobs` 记一条
  「本轮总结期间删过消息或回滚过,结果已作废」并返回 `Fail`,界面上表现为可重试的失败而不是假成功。
- 删除 / 回滚都按**对象身份**匹配(`it === msg`),不用 `equals`:内容相同的两条消息各自独立。
  回滚的截断逻辑统一走 `data/Chat.kt` 的 `truncateAt()`。
- 角色 UID 规则:intersect 固定 0,用户固定 1,角色从 2 起且不回收;老数据补号,恢复备份时纠正撞号(`UidStore`)。
- 自定义总结提示词:全局一份(不按角色分),开关 + 文本持久化;`{target}` / `{min}` 在发请求前替换为滑块目标值;
  关开关 **或文本为空** 都回退内置模板,判断统一走 `MemoryPromptStore.isCustom(enabled, text)`。
- 输入侧按 6000 token 上限逐轮裁(`MemorySummarizer.fitInput`),避免长文把上下文窗口撑爆后每轮都失败。
- 新功能入口**不得挂在默认关闭的 `DevMode.enabled` 上**(审查结论)。现状:
  - 记忆库入口(`CharacterInfoPage` / `DevConsolePage`)**不受 `DevMode` 门禁**,普通模式可达;
  - `MemoryPage` 的 `PromptCard`(自定义总结提示词)**无条件显示**,也在普通模式可达;
  - 主聊天多选顶栏(全选/复制/删除)**不受门禁**,可达;长按菜单(复制/删除/回滚/多选)同样不受门禁。
  - 只有「全部显示」记忆索引气泡仍按 `DevMode.enabled` 显示(这是原有设计,保持)。

## 5. 现在最应该先做的事

1. **问用户一个问题**:记忆块的标点要不要严格按需求的**全角** `|` `,`(现在是半角 `|` `,`)。
   不确认就不要改 —— 会让 8 例单测的断言跟着变,而且模型对全角/半角无差别,纯属字面对齐。
2. 然后是 `handoff/TODO.md` 的 P1(i18n 收尾:其它页面的记忆文案、`contentDescription`)
   与 P2 的小项。
