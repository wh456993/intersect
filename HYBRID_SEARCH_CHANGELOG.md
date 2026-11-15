# 混合检索(Hybrid Search)+ Prompt 构建系统 —— 修改日志(留存审计)

- **日期**:2026-06-03
- **项目**:Intersect(`com.reimei.intersect`,debug 变体 applicationId `com.reimei.intersect.beta`)
- **范围**:Phase 1–5 全部落地(纯本地混合检索 + AI 上下文构建),未接 UI、未改既有聊天/记忆链路
- **验证状态**:`:app:assembleDebug` 构建通过;真机功能验证未执行(由用户自行安排,见文末「未验证项」)

---

## 一、技术决策(本特性全局约定)

| 决策 | 约定 |
|---|---|
| 技术栈 | Room 2.6.1 + KSP 2.1.0-1.0.29 + ONNX Runtime Android 1.17.1 + Coroutines;无网络依赖 |
| 向量字节序 | FloatArray↔ByteArray 显式 `ByteOrder.LITTLE_ENDIAN`(`VectorCodec`),不依赖 ByteBuffer 默认序 |
| 归一化 | 推理输出 Mean Pooling 后 L2 归一化(‖v‖=1),点积即余弦相似度 |
| 推理并发 | `session.run()` 全程 `Mutex` 串行,防并发 native 内存峰值 OOM |
| RRF 融合 | `score(d) = Σ_r 1/(k+rank_r(d))`,k=60;只用名次不用原始分(三路量纲不可比) |
| 线程红线 | 分词/推理/向量运算/DB 操作一律 `Dispatchers.IO` 或 `Dispatchers.Default` |
| 降级红线 | ONNX 初始化/推理异常一律捕获,静默降级为仅字面检索(精确+模糊),严禁崩溃 |
| 防抖红线 | UI 搜索接入 ViewModel 时必须 `debounce(300)`,严禁逐键触发推理(**尚未接入 UI,待后续**) |
| ABI | `ndk.abiFilters 'arm64-v8a'`(减包体) |
| 模型 | `assets/models/bge-micro-v2-int8.onnx`(INT8,384 维,Mean Pooling)+ `vocab.txt`(WordPiece,30522 行,uncased) |

## 二、文件清单

### 新增(`app/src/main/java/com/reimei/intersect/search/`,共 13 个)

| 文件 | Phase | 内容 |
|---|---|---|
| `VectorCodec.kt` | 1 | FloatArray↔ByteArray,LITTLE_ENDIAN,含数学/字节序注释 |
| `VectorEntity.kt` | 1 | Room 表 `vectors`:id(PK, REPLACE) / content / embedding(BLOB) / model_version / metadata_tags(JSON) |
| `VectorDao.kt` | 1 | 幂等写入、分页读、maxModelVersion、clearAll、deleteById(s) |
| `VectorDb.kt` | 1 | 单表 Room DB(`hybrid_search.db`),fallbackToDestructiveMigration |
| `WordPieceTokenizer.kt` | 2 | 自研 BERT 分词器(BasicTokenizer + WordPiece 贪心最长匹配),读 vocab.txt,输出 inputIds/attentionMask/tokenTypeIds |
| `EmbeddingEngine.kt` | 2 | ONNX 单例:IO 初始化、`embed()` 三段调度(Default 分词→IO 推理 Mutex 串行→Default 池化归一化),异常置 `isAvailable=false` 降级;`MODEL_VERSION=1` |
| `SearchModels.kt` | 3 | `MatchSpan` / `SearchResult`(id/content/sourceType/rrfScore/matchSpans,向量命中为 null)/ `SearchEngine` 接口 |
| `LiteralMatchers.kt` | 3 | 精确子串命中(indexOf)+ 模糊命中定位(贪心最早命中,与 SublimeFuzzy 同策略),产出高亮区间 |
| `HybridSearchEngine.kt` | 3 | 三路并发(精确/模糊/向量)+ RRF 融合;候选行一次加载三路共享;metadata AND 过滤;modelVersion 不符行排除 |
| `SearchChatUseCase.kt` | 4 | UI 搜索用例:matchSpans 归一化(钳制/合并/排序)→ `UiSearchHit` + Compose `toAnnotatedString()` 高亮 |
| `BuildAIContextUseCase.kt` | 4 | AI 上下文用例:metadataFilters 检索 + Markdown 模板(`## 系统状态` / `## 相关记忆` / `## 用户输入`),单行折叠+截断 |
| `SearchIndexer.kt` | 5 | 快照(聊天消息+即时记忆总结)+ 增量回填(不变跳过/变更重算/陈旧删除)+ modelVersion 迁移(不符清空重算)+ WorkManager 排程(充电+息屏) |
| `IndexBackfillWorker.kt` | 5 | CoroutineWorker 执行体:异常→retry(指数退避),CancellationException 照常上抛 |

### 修改(既有文件,3 个)

| 文件 | 改动 |
|---|---|
| `build.gradle`(根) | KSP 插件声明 `com.google.devtools.ksp:2.1.0-1.0.29`(apply false);临时 netprobe 探测任务已用完删除 |
| `app/build.gradle` | 应用 ksp 插件;`ksp room-compiler:2.6.1`;room-runtime/room-ktx 2.6.1;onnxruntime-android 1.17.1;`androidx.work:work-runtime-ktx:2.8.1`;`ndk.abiFilters 'arm64-v8a'` |
| `MainActivity.kt` | `onCreate` 增加 `SearchIndexer.onAppStart(this)`(版本迁移检查 + 回填排程,内部全程 try/catch 不影响启动) |

### 未触碰(既有链路,保持兼容)

`ChatService`(四段式 Prompt)、`MemoryStore`/`MemoryInject`(SharedPreferences 记忆层)、`SublimeFuzzy`(仅复用其 `score/matches`,未改一行业务逻辑)、`ChatStore`、`settings.gradle`(仓库配置保持官方源)。

## 三、关键实现要点(审计用)

1. **字节序**:`VectorCodec.toBytesLE/toFloatsLE` 显式 LITTLE_ENDIAN,写库/读库同序;向量 BLOB 每条 384×4=1536 字节。
2. **降级链路**:`EmbeddingEngine.embed()` 任何 Throwable → 返回 null;`HybridSearchEngine` 向量路得 null 即返回空列表,RRF 自动退化为纯字面融合;`SearchIndexer.backfill` 在引擎不可用时**照常入库(空向量 BLOB)**,字面检索读 content 不受影响,向量路按维度(0≠384)跳过空向量。
3. **modelVersion 迁移**:启动时 `maxModelVersion()` 与 `MODEL_VERSION` 不符 → `clearAll()` + 排单次全量重算(防新旧模型向量空间混排静默出错);空表为正常首启,不清空。
4. **回填幂等**:内容未变 + 版本一致 + 已有向量 → 跳过;快照外 id 批量删除(消息删除/回滚不留陈旧行);随时可重跑收敛。
5. **WorkManager 约束**:充电 + 息屏(`setRequiresDeviceIdle`,API 23+ 生效,Lollipop 退化为仅充电;WorkManager 无「屏幕熄灭」约束,DeviceIdle 是标准近似);周期任务每 24h(唯一任务 KEEP,不叠加)。
6. **文档 id**:`chat:{friendId}:{消息下标}`(消息无稳定 id,下标移位由「内容变更重算 + 陈旧删除」兜住);`mem:{friendId}:{记忆id}`(记忆自带稳定 id)。
7. **元数据**:metadata_tags 统一带 `source`(chat/memory)与 `friendId`,过滤为 AND 语义,JSON 解析失败按空对象处理不崩溃。
8. **KSP 解析阻塞(已解除)**:早期解析失败定性为瞬时网络/缓存污染,KSP 2.1.0-1.0.29 全套制品在 repo1/aliyun 均存在,未改仓库配置;临时 netprobe 代码已全部移除。

## 四、红线符合性自查

| 红线 | 状态 |
|---|---|
| 1 UI 搜索 debounce(300) | ✅ 写入 `SearchChatUseCase` KDoc 硬性约定;⚠️ 尚未接 UI,接入时必须落地 |
| 2 ONNX 异常静默降级、严禁崩溃 | ✅ init/embed 全捕获;worker 全捕获(Throwable→retry,不冒泡) |
| 3 IO/Default 调度 | ✅ 分词/池化 Default,推理/DB/快照 IO(EmbeddingEngine 与 SearchIndexer 内显式调度) |
| 4 算法处数学注释 | ✅ 字节序、L2 归一化(`v̂=v/‖v‖`)、Mean Pooling、点积=余弦、RRF(k=60)均有公式注释 |

## 五、未验证项 / 后续待办

- 真机功能验证未执行(回填触发、陈旧清理、降级、迁移)——验证任务清单已在对话中交付用户自行测试。
- `SearchChatUseCase`/`BuildAIContextUseCase` 未接入 UI 与 ChatService 请求链(本特性按要求在既有链路旁新增,不破坏)。
- 建议补单测:RRF 公式、`normalizeSpans`、WordPiece 分词(纯 JVM,可 `run_tests` 直验)。
- 联网仓库为 IDE 注入的镜像 + 官方源,本特性依赖(work-runtime-ktx 等)已解析成功并落缓存。

—— 完 ——
