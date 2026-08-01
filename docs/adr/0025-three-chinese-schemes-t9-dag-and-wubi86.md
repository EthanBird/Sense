# ADR 0025：三种中文输入方案、九键有界 DAG 与五笔 SWBX/1

- 状态：Accepted
- 日期：2026-08-01
- 相关决策：ADR 0002、ADR 0005、ADR 0021、ADR 0022、ADR 0023

## 背景

Sense 原有中文输入链路默认把“中文模式”与“全键盘拼音”视为同一件事。九键若仅在
UI 层把数字展开成字母组合，会产生指数级搜索空间，也会丢失用户选择过的音节边界；
五笔若复用拼音字符串状态，则四码、补全、自动上屏和反查的事务语义会互相污染。

本次演进需要同时保持以下能力：

1. 全键盘拼音继续使用既有渐进式分词、纠错、上下文和个性化链路；
2. 九键拼音从数字直接搜索合法拼音路径，支持 `1` 键分隔和可逆音节选择；
3. 五笔 86 使用固定上游词表，严格区分精确码与补全码，并提供 `z` 拼音反查；
4. 设置进程、IME 进程和异步解码之间共享明确的方案身份，切换方案后旧结果不得回流；
5. 新方案不增加触摸、绘制和按键主线程上的文件 I/O，也不以大规模对象图换取查询速度。

## 决策

### 1. 中文模式与中文输入方案正交

`ChineseInputScheme` 定义三个稳定值：

| 方案 | 输入源 | 主键盘几何 | 解码链路 |
|---|---|---|---|
| `PINYIN_QWERTY` | `a-z` 与撇号 | QWERTY | 既有渐进式拼音解码 |
| `PINYIN_T9` | `2-9`、`1` 分隔 | 九宫格 | 九键拼音路径 + 中文专用拼音解码 |
| `WUBI_86` | `a-y`，首键 `z` 进入反查 | QWERTY | SWBX/1 精确/补全或拼音反查 |

方案和五笔自动上屏策略由 `ime-config` 的 `ImePreferencesV1` 持久化。格式采用带
`schema_version` 的确定性小型键值编码，并通过 `AtomicFile` 与 sidecar 文件锁跨进程交换；
设置页面只写配置，IME 在编辑器会话边界读取并安装。未知或缺失字段回到版本化默认值，
后续迁移通过新 schema 显式处理。

UI 继续保持单 Canvas 键盘。`PrimaryKeyboardMode` 只选择 QWERTY/T9 几何，
`PrimaryKeyboardLegendMode` 独立选择拼音滑动提示或五笔字根提示。视觉字根不会改变原有
`swipeOutput`，从而保持物理键身份、Skills 长按和滑动语义。方案切换为幂等操作；切换几何
前取消并冻结在途 pointer，避免旧场景的 `DOWN` 在新场景的 `UP` 上提交。

### 2. 九键数字是唯一事实源

`T9Composition` 是不可变、带 revision 的输入事务：

- `rawDigits` 保存原始 `2..9`，不因候选音节选择而重写；
- `forcedJoints` 保存 `1` 键声明的数字边界；
- `lockedEdges` 保存用户确认的 `[digitStart, digitEnd) -> spelling` 约束；
- 回退顺序固定为“最近锁定边 → 尾部分隔 → 最后一个数字”。

`T9SyllableIndex` 从生产拼音音节清单生成数字 trie。每个 trie 命中在数字位置图上形成
`SYLLABLE`、`INITIAL` 或仅允许位于尾部的 `INCOMPLETE` 边。搜索直接遍历 trie 和位置 DAG，
不会把每个数字展开成三到四个字母的笛卡尔积。`forcedJoints` 禁止边跨越指定接缝，
`lockedEdges` 则过滤所有冲突边，因此分隔和选择都参与同一次结构化搜索。

路径优先级依次考虑未完成段、首字母段、完整拼写字符数、分段数、音节 inventory prior
和稳定词法键。默认 beam 为 32，公共 API 的硬上限为 256，到达目标宽度两倍时立即裁剪；
输入总长继续服从拼音 96 字符上限。IME 对最多 32 条去重路径先做每路 Top1 的词法探针，
再仅对胜出路径做最多 8 个中文候选的完整解码；候选叠加有限结构惩罚后按文本去重并截断到请求上限。
中文专用 decoder seam 跳过英文词表，
避免单个九键输入被英文补全占据。

`486743697` 必须保留 `hun'shen'x's` 结构路径，使 `hunshenxs` 一类简拼/完整音节混合输入
进入既有拼音词格，而非依赖单条短语特判。

### 3. 五笔采用独立四码事务

`WubiComposition` 只接受 `a..y` 且直接码最长四个字符。空状态首键 `z` 进入
`reversePinyin`，再次回退可逐字删除反查拼音并最终退出反查状态。`z` 因而不是五笔词表
中的普通编码；构建时排除所有 `z*` 上游记录。

五笔自动上屏有三个显式策略：关闭、四码唯一精确候选、Rime 风格。自动提交只依据精确
候选，补全候选不触发唯一性判定；第五个形码到来时，Rime 风格先提交当前四码精确结果，
再以新键开始下一事务。候选始终先列精确结果，再列带 canonical code 的有界补全结果。

五笔个性化使用独立的 `(canonical_code, phrase)` 模型和
`sense_wubi86_user_lexicon.db`，不复用拼音的 full-pinyin/initials 键空间。启动时把持久化证据装入
有容量上限的内存索引；选择、默认提交、快速删除和立即替换先同步更新内存排名，再由单线程 FIFO
写入 SQLite。解码热路径只读内存快照，不等待数据库。精确候选与补全候选分组排序，个性化只改变组内
次序，因此学习证据不会把补全候选抬到精确候选之前。

第五个形码由 `WubiOverflowCoordinator` 作为显式事务处理。`RIME_STYLE` 提交当前个性化排序后的首个
精确候选并重放第五键；`UNIQUE_AT_4` 只在精确候选恰好一个时提交并重放；`OFF` 保持四码 composition
并拒绝第五键。解码尚未完成时，第五键单独保存在 pending intent 中，并绑定当时的 presentation
revision；之后的退格、语言、标点和文本进入另一条有界 FIFO。结果到达或超时后只允许该事务提交一次。
`UNIQUE_AT_4` 超时保持四码并只丢弃第五键，`RIME_STYLE` 超时按原始四码提交并重放第五键；随后按顺序
处理 FIFO 中的其他输入。补全候选、反查状态与 `z` 均不参与第五形码提交。

反查使用现有中文拼音 decoder 获得文字，再通过 SWBX/1 的 Unicode code point 反向索引
标注最多四个字符的首选五笔码。该链路复用中文上下文和拼音个性化证据，但不会把五笔
直接码写入拼音别名。

### 4. SWBX/1 是确定性、可审计的只读资产

上游固定为 `rime/rime-wubi` revision
`152a0d3f3efe40cae216d1e3b338242446848d07`，源表、`LICENSE` 和 `AUTHORS` 的 SHA-256
记录在 `wubi_sources.json`。源表及派生资产遵循 GNU LGPL v3.0；源文件、构建脚本、
统计、许可全文和归属说明均保留在源码树，许可全文与说明同时进入 APK assets。

`tools/build_wubi86_lexicon.py` 生成大端二进制 `SWBX/1`：

1. `SWBX` magic、版本和 exact/prefix/reverse 三段计数；
2. 按编码排序的精确组，每码最多 128 个候选；
3. 仅为 1–3 码生成的补全组，每前缀最多 16 个去重候选；
4. 按 Unicode code point 排序的反查组，每字最多 8 个编码。

同码候选按上游权重降序、文字 code-point 长度、文字和编码稳定排序。运行时
`Wubi86Lexicon` 保留原始 `ByteArray`，只建立 packed-code/offset 的 `IntArray` 索引；
查询用二分定位后顺序读取有限记录，避免把十万级词条展开为 `Map<String, List<...>>`。

当前固定产物为：

| 项目 | 数值 |
|---|---:|
| exact codes | 99,054 |
| exact candidates | 136,239 |
| completion prefixes | 15,259 |
| completion candidates | 116,889 |
| reverse characters | 70,386 |
| asset bytes | 5,611,947 |
| asset SHA-256 | `e2d47d43ab702862c349cd7f9ad36b2d4cbd72963c95cdb6f7911bf849937207` |

### 5. scheme epoch 隔离异步结果

全键盘拼音继续由原有 progressive session 管理；九键和五笔通过
`AlternativeCandidateSession` 进入独立的 latest-only worker。每个请求捕获：

```text
scheme + schemeEpoch + localRevision + presentationRevision + rawCode
+ pinyinDecoderGeneration + wubiDecoderGeneration
```

完成回调只有在整个 key、两个 decoder generation 和当前活动方案仍一致时才发布。
切换方案、清空或提交 composition 都推进 `schemeEpoch` 和展示 revision、清理会话并移除
待发布回调。因此即使旧任务已经开始执行，它也不会覆盖新方案候选。

五笔资产由专用后台加载器构建完整不可变 decoder 后一次性安装；加载期间保留稳定的
composition/pending 展示。九键 trie 随拼音运行时一同构建和热切换，候选 worker 每次请求
捕获当前不可变引用。Android `InputConnection`、View 和候选交互状态始终留在主线程。

九键的完整热路径由 `T9AlternativeInputDecoder` 统一承载，Android service 与 M7 使用同一实现。它先对
DAG 路径按 canonical query 去重，再用路径结构差异选择小型代表 beam；首轮每条路径只取 Top1 探针，
证据不足时才扩大路径阶段，并仅对胜出路径做有界候选扩展。latest-only worker 在每次昂贵查询前检查
supersession probe，新按键到达后旧请求会在下一查询边界停止，而不仅是丢弃最终回调。单次解码的路径、
查询、扩展查询和候选池都具有硬上限。

语言模式、QWERTY/T9 几何与五笔 legend 通过一次 `setInputPresentation` 原子安装。切换前统一取消活动
pointer，随后只重建一次最终 scene，避免语言、几何和主题/legend 分帧发布造成的闪烁或混色；尺寸、面板、
主题和方案未变化时保持幂等，不触发额外 scene rebuild。

## 性能约束

1. T9 查询按 trie/DAG 和 32 路去重路径硬上限运行；32 位以内先探测 8 条多样化路径，证据不足时逐路继续，
   胜出路径最多扩展 2 路、每路 8 候选；33–48/49–64/65–80/81–96 位的廉价词法探针上限依次为 8/4/2/1，
   64 位以上不运行句子结构兜底，禁止数字到字母全排列。
2. 五笔直接码最长 4，exact 128、completion 16、reverse 8 均为构建期和读取期硬上限。
3. SWBX/1 加载、校验和索引构建在后台完成；按键、MOVE、`onDraw` 与候选点击不读取文件。
4. 热路径只引用不可变 runtime；切换 runtime 采用 generation/epoch 核对，不复制词库。
5. QWERTY/T9 scene 仅在尺寸、拓扑、配置或方案变化时重建；pressed/selection 只更新视觉状态。
6. 新方案的 Host benchmark 应分别记录冷加载、热查询 p50/p95/p99、最大工作集和候选质量；
   固定真机再验收帧时间、触摸到 composing 延迟与 IME 端到端延迟。

当前 M7 固定工作站结果：T9 完整混合解码 p50 2.002 ms、p95 5.395 ms、p99 7.093 ms；
`486743697` 的 Top1 为“浑身解数”，p95 4.827 ms；显式边界 `64'426` Top1 为“你好”，p95
3.903 ms；32 位边界串 p95 8.212 ms，96 位极限串 p95 5.508 ms。
普通 DAG p95 0.148 ms，96 位路径搜索 p95 5.540 ms。门禁对聚合及每个完整 T9 case
分别设置 p95 20 ms 上限。
Wubi 热查询 p95 6.771 μs，估算保留内存 7,089,539 bytes。
T9 索引构建 91.39 ms，Wubi 冷加载与索引构建 64.86 ms；签名 APK 为 26,893,701 bytes，
相对 beta.8 增加 3,172,600 bytes（13.37%）。两类索引构建均在后台 lane 完成。

## 可复现与许可门禁

`tools/verify_wubi86_assets.py` 是五笔资产的统一本地门禁。它执行以下检查：

1. 校验固定 manifest 自身哈希、repository、revision、license 和全部源文件哈希；
2. 运行生产 builder 到临时目录，把 SWBX/1 与统计 JSON 同仓库产物逐字节比较；
3. 校验资产大小/哈希、vendor license、仓库许可副本、APK 许可副本和 NOTICE 同步；
4. 传入 `--apk` 时，从最终 APK 读取 SWBX/1、LGPL 全文、Rime Wubi 归属说明和总 NOTICE，
   与审核过的仓库文件逐字节比较，并拒绝缺失或重复 ZIP entry。

`offline_verify.sh` 显式执行 builder 测试、verifier 测试、生产可复现检查、许可副本检查和
最终 APK 检查；`local_release.ps1` 在本地测试阶段执行生产可复现检查，并在签名 APK
元数据检查阶段再次执行 `--apk` 门禁。

## 结果

- 拼音 QWERTY、拼音九键和五笔 86 拥有独立 composition 语义，同时共享候选展示契约；
- 九键可以自动展示清晰音节边界，并通过结构搜索覆盖简拼/全拼混合输入；
- 五笔精确、补全、自动上屏和拼音反查的边界可分别测试；
- 方案切换与 decoder 热切换由 epoch/generation 阻断陈旧异步结果；
- 五笔源到 APK 的每一层都有固定身份、逐字节重建与许可归属证据。

## 门禁

1. `T9CompositionTest` 覆盖数字、分隔、锁边和逐级回退；`T9SyllableIndexTest` 覆盖
   `486743697 -> hun'shen'x's`、路径上限和无字母全排列。
2. `Wubi86LexiconTest` 固定 `a -> 工`、`b -> 了`、`q -> 我`、`r -> 的`，并覆盖
   exact-before-completion、四码只保留精确结果、反查和 `z` 状态。
3. `AlternativeInputDecodingTest` 覆盖 scheme epoch、local/presentation revision、decoder
   generation、九键中文专用解码和五笔精确/补全标签。
4. `KeyboardArchitectureTest`、`KeyboardInteractionControllerTest` 与设备布局测试覆盖
   QWERTY/T9 切换、稳定物理键身份、在途 pointer 取消和五笔视觉字根。
5. `test_build_wubi86_lexicon.py` 与 `test_verify_wubi86_assets.py` 必须通过；本地发布必须执行
   `verify_wubi86_assets.py --apk <signed.apk>`。
