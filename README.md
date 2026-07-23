# Sense 输入法（先思输入法）

> Android 原生高性能中文输入法：普通输入完全本地运行，AI、记忆与工具能力通过可配置的长按方向 Skill 显式触发。

**项目状态：** M6 输入精修与候选交互预览；等待 GitHub Actions 与 Android 真机验收

**最新预览：** `v0.3.3-m6`（`versionCode 9`，prerelease）

**更新日期：** 2026-07-23
**目标平台：** Android 10+（`minSdk 29`，首版按 `targetSdk 36` 建设）

本文基于《GlassIME Android AI 中文输入法产品与技术设计文档 v0.1》重新整理，并统一改名为：

| 项目项 | 定义 |
|---|---|
| 英文名 | Sense |
| 中文名 | 先思输入法 |
| 产品称呼 | Sense 输入法 / 先思输入法 |
| 仓库名 | `Sense` |
| 暂定 Android namespace | `io.github.ethanbird.senseime` |
| 默认主题 | Arctic Glass |

Android 官方要求自 2026 年 8 月 31 日起，新应用和更新需面向 Android 16（API 36）或更高版本，因此项目从第一天按 API 36 的行为约束构建，而不是后期再迁移。

## 0. 当前迭代：v0.3.3-m6 输入精修与候选交互

`v0.3.3-m6` 继续停留在 `0.3.x`：本轮修复最后 composing 字母残留，为英文模式建立独立词典组合态，并重新收敛候选滑动、Emoji、符号页和顶部布局。Android `versionCode` 从 8 单调增加到 9；发布 tag 固定为 `v0.3.3-m6`，只允许创建 prerelease。

本轮保留 `ygz → 一个字`、`w → 我`、`pipei → 匹pei → 匹配`、中英混输、文字编辑、高速触控、加速退格和九宫格等已有能力，并加入：

- 中文或英文 composition 删到空时用空字符串替换宿主 composing span；宿主移动光标或取消 composing 时同步清空本地 session，不再留下或错位重建旧字母；
- 英文模式使用 revisioned 组合态和 20,000 词本地词典，固定回放 `host → host, hosts, hostile`，旧候选 revision 不可提交；
- 移除“中 · 先思输入法”品牌行；空闲时顶部只保留与键盘同底色的工具栏，有输入时候选复用该空间；
- 中文模式 `S/L` 上滑提交全角 `！/？`，英文模式保持半角 `!/?`；
- 折叠候选支持像素级慢拖、快速最多一页 fling、边界阻尼和候选边界吸附，展开候选继续保留全局索引与翻页；
- Emoji 扩充为八类离线目录并加入有界惯性滚动；越过 touch slop 后取消同 pointer 的元素点击；
- 符号页改为分类轨道与连续网格，覆盖常用、数学、角标、序号、音标、平假、片假、箭头、拼音、注音、部首、希腊、制表、藏文等 22 类；
- 增加低优先级拼音语义候选：`ji→🐔`、`yao→💊`、`you→🈶/→`、`suo→🔒`、`oumu→Ω`、`pai→π`、`zuo→←`；默认插在索引 6，不改变前六个主中文候选顺序。

当前验证状态如下。只有 GitHub Actions 的 `verify` 作业全部通过，工作流才会创建 `v0.3.3-m6` prerelease；未实际执行的 Kotlin、Lint、APK 或真机检查不会写成“已通过”。

| 门禁 | 当前状态 |
|---|---|
| M4/M5 生产资产 Python 回归 | M4 2/2、M5 4/4 本地通过；词典与 Bigram 仍做 fresh-checkout 字节级重建 |
| 生成资产 | 拼音 326,298 keys / SHA `eda69e…5ef6`；Bigram 46,655 / SHA `c3d806…2200`；英文 20,000 词 / SHA `1a1823…5624` |
| Kotlin 正确性 | 独立 Kotlin/JUnit 编译回归：core 77/77、service 22/22、UI 纯逻辑 61/61，共 160/160 本地通过；Android View、Lint 与正式 Gradle 任务以 Actions 结果为准 |
| M6 Host 门禁 | 本地 p95：完整 `host` session 62.98 µs、语义查找 0.82 µs、mixer 2.48 µs；门禁分别为 1 ms / 250 µs / 250 µs |
| M6 UI 纯逻辑 | 中文/英文上滑标点、候选慢拖/一页 fling/吸附、Emoji 惯性与 touch slop、22 类符号目录 |
| Android Lint 与编译 | GitHub Actions 构建 Debug、Benchmark 与 Macrobenchmark APK |
| APK 元数据门禁 | `versionCode 9`、`versionName 0.3.3-m6`、`minSdk 29`、`targetSdk 36` |
| APK 完整性门禁 | zipalign、签名、三项模型哈希、20,000 英文词、内置许可、无 `INTERNET` 权限 |
| Android 真机 | 尚未连接；空 composing 跨宿主、候选/Emoji 惯性、符号字体、OEM 事件合并、120 Hz 帧时间和横竖屏待测 |

标准工程验证命令：

```bash
python3 tools/test_build_pinyin_lexicon.py
python3 tools/test_build_bigram_model.py
python3 tools/test_m4_core_assets.py
python3 tools/test_m5_mixed_assets.py

./gradlew \
  :core-input:test \
  :ime-service:testDebugUnitTest \
  :core-input:m0HostBenchmark \
  :core-input:m1PinyinBenchmark \
  :core-input:m2AdaptiveBenchmark \
  :core-input:m3SentenceBenchmark \
  :core-input:m4CoreBenchmark \
  :core-input:m5MixedInputBenchmark \
  :core-input:m6InputPolishBenchmark \
  :ime-ui:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleBenchmark \
  :benchmark:assembleBenchmark
```

在 Maven 仓库不可达、但已安装 API 36 SDK 与 Gradle 8.13 的环境，可运行离线门禁并产出工程 debug 签名 APK：

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk \
SENSE_GRADLE_HOME=/path/to/gradle-8.13 \
tools/offline_verify.sh
```

本次发布仍是工程预览：Host 状态机与延迟门禁不能替代真机质量结论。固定发布签名尚未建立，每次 GitHub runner 生成的 debug 证书可能不同，因此即使 `versionCode` 增加，`v0.3.3-m6` 也不保证能覆盖安装旧 APK。签名不一致时卸载旧版会同时清除本地 SQLite 用户词库和剪贴板历史。完整决策见 [`ADR 0008`](docs/adr/0008-v0.3.3-m6-input-polish.md)，变更与限制见 [`v0.3.3-m6 发布说明`](docs/releases/v0.3.3-m6.md)。

## 1. 项目结论

Sense 不是把聊天机器人塞进键盘，而是先做成一款足够快、足够稳定的中文输入法，再把输入法天然拥有的上下文、长期行为数据和即时操作入口编译成三种能力：

1. 更准确的本地中文候选与纠错；
2. 任意键长按后向四个方向选择的 Skill；
3. 不进入按键热路径的记忆、Provider 与工具执行系统。

最重要的架构边界是：**按键、解码、候选、绘制与上屏永远不等待网络、Provider、Harness、历史数据库或后台任务。** AI 故障时，Sense 仍然必须是一款完整可用的中文输入法。

## 2. 产品原则

- **输入优先：** 短按只负责输入；AI 不参与逐键候选生成。
- **运行时零弹窗：** Provider、模型、工具、输出策略与 Skill 绑定均在设置 App 中预先配置。
- **显式触发、静默执行：** 长按进入 Skill 层，中心释放保留原数字/符号，四方向执行已绑定 Skill。
- **全量归档、有限激活：** 这是长期边界；M2 先以内存双索引承载用户词，后续再编译为固定体积快照。
- **固定热路径：** M2 逐键不访问 SQLite；冷启动仍会加载用户词，长期档案独立性尚待后续快照阶段完成。
- **单层玻璃：** 毛玻璃只作用于键盘底板；不对每个键帽重复模糊。
- **可回放、可测量：** 候选、纠错、Skill、Provider、存储和性能均能通过统一事件重放评估。

## 3. 首版范围

### 3.1 纳入 v0.1

- Android 手机、平板与折叠屏的竖屏/横屏输入；
- QWERTY 全拼、候选栏、联想、简繁、中英文混输、数字与常用符号；
- 成熟中文解码底座与 Sense 自定义 Ranker（M2 先以纯 Kotlin 验证，librime 保留为后续 spike）；
- 任意键四方向 Skill 绑定、中心长按字符、滑出取消；
- Arctic Glass 真模糊、拟态玻璃和高对比纯色三级回退；
- OpenAI-compatible Provider，后续通过同一接口扩展其他 Provider；
- 6 个首批 Skill：润色、简短、扩写、中英翻译、回复建议、记录事项；
- 二进制 Journal、不可变 Segment、内容寻址 Blob 与离线回放；
- 词频、纠错、App profile、近期短语与 Skill 质量的用户快照；
- 设置 App、Provider 测试、Skill 键位编辑器、主题预览和性能实验室。

### 3.2 暂不纳入

- iOS、桌面端、Web 端；
- 通用聊天面板、任意脚本执行和无边界自主 Agent；
- Skill 商店、支付、公开第三方插件生态；
- 跨设备同步与账号体系；
- 在回放与真机数据不足时重写完整成熟拼音内核；
- 大型知识图谱和逐键大模型推理；
- 数据治理、合规和安全体系的完整产品设计。

暂不纳入不等于破坏未来兼容性。事件从首版开始保留 `schema_version`、`source`、`session_id`、`app_id`、`timestamp` 与 `lineage`，使后续策略能够在不重做数据底座的前提下加入。

## 4. 成功标准

以下是工程预算，不是已经验证的成绩；每个阈值都必须由真机和回放数据校准。

| 指标 | v0.1 目标 | 门禁 |
|---|---:|---|
| 按键视觉反馈 p95 | ≤ 8 ms | 超预算阻断合并 |
| 候选更新 p50 / p95 | ≤ 12 / 24 ms | p95 回退 > 5% 阻断合并 |
| 热启动显示 p95 | ≤ 80 ms | Macrobenchmark |
| 冷启动显示 p95 | ≤ 180 ms | Macrobenchmark |
| 连续输入掉帧 | < 0.5% | 60/90/120 Hz 真机 |
| IME RSS 空闲 / 输入 | ≤ 45 / 70 MB | 多机型 P90 |
| 每键 JVM 分配 | 接近 0，目标 < 128 B | Allocation 测试 |
| Skill 本地开销 | Provider TTFB 外 < 100 ms | 端到端 trace |
| Skill 成功率 | ≥ 97% | Provider 错误单列 |
| Journal 平均 CPU | < 1% 单核 | 连续输入 30 分钟 |
| 长期档案独立性 | 10 年档案无显著延迟回退 | 合成档案回放 |

候选质量使用 Top-1/Top-3 命中率、平均候选位次、按键节省率、上屏后三秒回删率衡量；Skill 质量使用首 token、总耗时、整段撤销率、用户修改距离和重复使用率衡量。

## 5. 总体架构

```mermaid
flowchart TD
    Host[宿主输入框] <--> IME[IME 进程]
    IME --> Decoder[本地解码与 Ranker]
    IME --> Journal[事件 Journal]
    IME -->|Skill 请求| Brain[Brain 进程]
    Brain --> Harness[Harness / Memory]
    Harness --> Provider[Provider / Tools]
    Journal --> Worker[维护与回放]
    Worker --> Snapshot[不可变用户快照]
    Snapshot --> Decoder
```

### 5.1 进程边界

| 进程 | 负责 | 明确禁止 |
|---|---|---|
| `:ime` | InputMethodService、Canvas UI、本地 Decoder、Ranker、用户词写入队列 | 网络、Provider SDK、逐键数据库查询、同步写盘、JSON 解析 |
| `:brain` | Harness、记忆检索、Provider、工具、流式 trace | 持有 IME View 或长期持有 InputConnection |
| `main` | 设置 App、配置编译、维护任务调度、性能实验室 | 参与逐键解码与绘制 |

### 5.2 线程模型

- **IME Main：** 触摸、局部绘制、候选 UI、InputConnection；
- **Decoder：** 增量拼音解码与候选生成；
- **Journal Writer：** SPSC 队列批量写盘、分段与校验；
- **Brain Coroutine：** 记忆、Provider 流、工具调用和取消；
- **Maintenance Worker：** 压缩、索引、Embedding、快照编译和回放。

### 5.3 关键技术决策

| 领域 | 决策 | 原因 |
|---|---|---|
| IME UI | 自定义 `View/Canvas`；设置 App 使用 Compose | 控制逐帧分配和绘制范围 |
| 中文内核 | M2 纯 Kotlin 紧凑词典 + 自适应层；librime 作为后续对照 spike | 先冻结可测闭环，再用回放与真机数据决定迁移 |
| Ranker | 确定性特征 + 可选小型量化重排 | 不让神经模型成为候选可用性的前置条件 |
| IPC | AIDL 流式回调、取消与死亡监听 | 隔离 Brain 崩溃和系统回收 |
| 配置 | 设置层编译为带版本的二进制快照，原子替换 | IME 不解析 JSON/YAML |
| 原始事件 | Journal → Segment + Blob | 避免每键一行 SQLite/Room |
| 个性化 | 后台编译不可变 mmap 快照 | 热路径成本不随历史增长 |
| 玻璃主题 | 真模糊、拟态、纯色三级实现 | Android 12+ 也可能因 GPU/OEM/节电关闭模糊 |

## 6. 工程结构

原设计一次拆成十多个 Gradle 模块，边界清晰但会让项目早期构建和重构成本过高。首轮先保留七个稳定边界，待实现增长后再拆分 Provider、Memory、Event Store 和 Ranker。

```text
Sense/
├── app/                 # 设置 App、启用向导、配置与主题编辑
├── ime-service/         # InputMethodService、InputConnection、生命周期
├── ime-ui/              # Canvas 键盘、候选栏、手势、Arctic Glass
├── core-input/          # 输入状态、候选协议、编辑事务、Ranker API
├── native-input/        # librime、CMake、JNI、词典打包
├── brain/               # 独立进程、Harness、Provider、Memory、Tools
├── data-runtime/        # Journal、Segment、Blob、快照与维护任务
├── benchmark/           # Macrobenchmark、输入回放、长期档案压测
├── build-logic/         # Convention plugins 与质量门禁
└── docs/                # ADR、协议、测试记录和设计资料
```

当任一模块出现独立发布节奏、独立依赖边界或明显构建瓶颈时再拆分；不为“看起来架构完整”提前制造空模块。

## 7. 核心交互设计

### 7.1 长按方向 Skill

| 动作 | 行为 |
|---|---|
| 短按 | 永远输入当前键，不允许 Skill 覆盖 |
| 长按中心释放 | 输入原数字或符号 |
| 长按向上/左/右/下 | 执行绑定的 U/L/R/D Skill |
| 滑出取消区 | 不输入字符，也不执行 Skill |

初始参数为：进入 Skill 层 220 ms、中心死区 10–14 dp、方向激活 22–30 dp、方向锁定 ±35°。这些值必须通过单手/双手、手机/平板和不同触控采样率的误触实验确定，不能直接当作最终常量。

### 7.2 编辑事务

每次 Skill 在手指松开时冻结 `EditorTransaction`，记录会话、editor revision、选区、光标前后文和 composing 文本。结果返回后：

- revision 未变化：按策略替换或插入；
- 用户继续输入但锚点仍有效：在锚点插入，不覆盖新输入；
- 选区或上下文变化：结果降级到候选栏；
- InputConnection 失效：写入短期结果缓存；
- 自动插入文本作为一个事务块，第一次删除可整块撤销。

## 8. 中文输入与个性化

### 8.1 首批中文能力

- 全拼、模糊音、首字母缩写；
- 中英文、数字、日期、时间、金额、邮箱、URL 混输；
- 简繁、中文标点、Emoji、用户词和专有名词；
- 拼音纠错、候选纠错学习、上屏后回删识别；
- 下一词预测只占低优先级候选，不挤占正在输入的拼音结果。

### 8.2 排序顺序

`base_language_score + user_frequency + recency + app_profile + context_ngram + correction_gain + optional_neural_reranker`

前六项本地确定性计算；神经重排最多处理前 16–32 个候选。Ranker 只读取编译后的特征快照，不查询完整历史。

### 8.3 学习信号

- 用户输入、候选选择和主动纠错是强信号；
- 粘贴文本是低权重信号；
- AI 生成和工具结果默认权重为 0；
- AI 结果只有在用户保留、修改或重复使用后才形成学习信号；
- 每个 App 分别维护语言、词汇、语气和 Skill 质量画像。

## 9. 事件档案与回放

事件层采用固定头、变长整数和批量写入。重复文本、Prompt、Response 和工具结果进入内容寻址 Blob，事件仅保存引用。达到 4–16 MiB 的 Journal 被封存为不可变 Segment；索引、Embedding 和模型快照均为可重建派生物。

落盘优先级：输入语义事件 > 编辑与 Skill 事件 > Provider trace > 高频性能采样。队列接近满时仅允许降低非关键性能采样，不允许阻塞 IME Main。

离线回放以同一真实会话比较不同解码器、Ranker 和快照，至少输出：Top-1 命中、平均位次、按键节省、纠错概率、延迟分布与内存峰值。

## 10. Harness、Provider 与工具

v0.1 的 Harness 是**有界执行器**，不是自由规划 Agent：

- 默认最多 2 个工具步骤；
- Harness 总超时 8 秒；
- 单工具超时 3–5 秒；
- 最多并行 2 个工具；
- 默认最大返回文本 4 KiB；
- 每次执行可取消、可超时、可回放；
- 所有流式 delta、tool call、tool result 和错误进入 trace。

Provider 先实现 OpenAI-compatible 适配器，并抽象 `fast`、`smart`、`embed` 三个逻辑 Profile。Skill 依赖逻辑能力而不依赖厂商；首 token 超时、5xx 或能力不匹配时按预设 fallback，运行时不弹窗。

## 11. 里程碑

| 阶段 | 主要交付 | 退出条件 |
|---|---|---|
| M0 工程与基线 | Gradle 工程、IME 壳、Canvas 空键盘、基准、CI | Debug APK 可安装启用；冷/热启动和帧指标可重复 |
| M1 离线输入闭环 | 字母/符号、composing、候选栏、上屏/删除、横竖屏 | 断网可稳定完成完整输入闭环 |
| M2 中文引擎 | 纯 Kotlin 全拼、扩充词典、短码/纠错、基础 Ranker、SQLite 用户词 | Host 门禁通过；等待候选 p95 与进程恢复真机验收 |
| M3 句级排序与候选分页 | 词典派生字符 bigram、有界句级重排、候选展开与分页 | Host 正确性和回放门禁通过；等待真机候选质量与分页交互验收 |
| M4 渐进式输入与高速键盘 | 简拼索引、自动分词、异步解码、多指触控、加速退格、候选展开 | Host 回归保留在后续所有发布门禁；等待真机高速输入验收 |
| M5 混输与交互收敛 | 20k 英文词典、hybrid 拼音、稳定候选发布、连续 Emoji、剪贴板与文字编辑页 | `v0.3.2-m5` CI 通过后，完成 Android 真机混输、滚动、选择和视觉验收 |
| M6 输入精修与候选交互 | 空 composition 修复、英文独立组合态、候选横滑、Emoji 惯性、分类符号与语义候选 | `v0.3.3-m6` CI 通过后，完成 Android 真机宿主兼容、滚动和字体验收 |
| M7 事件底座 | Journal、Segment、Blob、Manifest、100 万事件回放 | 高负载采集不影响输入；崩溃恢复无逻辑丢段 |
| M8 AI 垂直切片 | `:brain`、AIDL、OpenAI-compatible、流式、6 个 Skill | Brain 被杀不影响输入；异步结果不覆盖新文本 |
| M9 记忆与快照 | 词汇/纠错/App/风格记忆、索引、快照编译、A/B 回放 | 个性化相对基线出现可重复增益 |
| M10 稳定化 | 机型矩阵、24h 压测、安装包、故障降级、发布检查 | 所有 P0 门禁通过，形成首个内测版本 |

连续多轮真实输入反馈使 M3–M6 优先关闭候选质量、渐进选词、高速触控、工具面板、中英混输和字符可达性缺口。事件事实底座顺延到 M7，AI、记忆和稳定化阶段依次顺延；四方向 Skill、完整事件归档和 Arctic Glass 三级回退仍保留在后续里程碑。

## 12. 迭代记录：M0 可运行骨架

以下保留 M0 的实施记录；当前代码位于 M6 的 `v0.3.3-m6` 工程预览，仍需完成 GitHub Actions、Android 真机安装、SQLite/剪贴板进程恢复、空 composing 跨宿主兼容、候选与 Emoji 惯性、符号字体和高速输入性能验收。

### 12.1 实施清单

1. 初始化 Gradle Wrapper、Version Catalog、Kotlin/Android convention plugin；
2. 固定 `minSdk 29`、`compileSdk/targetSdk 36`，启用 arm64-v8a 与 x86_64 测试 ABI；
3. 创建 `app`、`ime-service`、`ime-ui`、`core-input`、`benchmark` 五个首批模块；
4. 实现 Sense 设置首页、输入法启用与切换入口；
5. 注册带 `BIND_INPUT_METHOD` 的 `SenseInputMethodService`；
6. 用单个自定义 View 绘制 QWERTY、候选栏、按下态与基础 Arctic Glass B；
7. 实现短按、删除、空格、回车、大小写、composing 和假候选数据闭环；
8. 建立无网络的 `FakeDecoder`，先冻结 Kotlin 与 native 解码接口；
9. 加入帧时间、输入延迟、冷/热启动与每键分配基准；
10. 建立 lint、单元测试、构建 Debug APK 的 CI；
11. 输出首份基准报告与至少一张实际运行截图；
12. 用 ADR 记录 namespace、模块边界、渲染方案和后续 librime 接入点。

### 12.2 M0 验收

- `./gradlew test lint assembleDebug` 全部通过；
- APK 能安装、启用、切换到 Sense，并在系统文本框输入；
- Provider 不存在、网络关闭时行为完全一致；
- 键盘高度稳定，候选栏不导致宿主页面反复重排；
- 60 Hz 模拟器或真机连续点击无可见卡顿；
- 基准结果以机器可读文件纳入版本控制；
- 不把 librime、Provider SDK 或事件存储提前耦合到 UI。

## 13. 必做技术验证

| Spike | 要回答的问题 | 通过标准 |
|---|---|---|
| P1 librime/JNI | ABI、词典体积、首启部署、候选延迟、内存是否满足预算 | arm64/x86_64 可重复构建；首批基准达标后锁定版本 |
| P2 IME Window Blur | Android 12+ 各 OEM 是否允许稳定跨窗口 blur | 任何时候关闭 blur 都能无闪烁切回拟态背景 |
| P3 AIDL Streaming | Brain 重启、断流、取消、Binder death 是否可控 | 反复杀死 Brain 不影响逐键输入 |
| P4 Gesture Lab | 四方向与中心符号的误触边界 | 多尺寸、多刷新率下取消率和误触率可测且可调 |
| P5 Journal Replay | 100 万事件写入、封存、损坏恢复、重放 | IME Main 不做 IO；事件顺序和校验可复现 |

librime 当前正式版本线已到 1.17.x，但项目不会因为“最新”就直接锁定；先完成 Android ABI、依赖、体积、许可证和性能验证，再把精确 commit 固定到版本目录。

## 14. 测试策略

- **单元测试：** 输入状态机、拼音状态、候选排序、方向判定、EditorTransaction、事件编解码；
- **属性测试：** 任意按键序列不破坏 composing 状态，Segment 编解码可逆；
- **仪器测试：** InputConnection、横竖屏、WebView、进程回收、主题切换；
- **黄金图测试：** Arctic Light/Dark、Glass B/Solid、不同 DPI 与字体缩放；
- **Macrobenchmark：** 冷/热启动、首帧、连续输入、候选更新；
- **回放测试：** 空档案、1 月、1 年、10 年合成档案；
- **故障注入：** Provider 超时/断流、Brain 被杀、快照损坏、Journal 队列高水位；
- **真机矩阵：** Pixel、Samsung、小米/Redmi、OPPO/vivo 的低中高端代表机，覆盖 60/90/120 Hz。

## 15. 风险与止损线

| 风险 | 早期信号 | 处理 |
|---|---|---|
| librime 包体或首启过重 | M2 前已超冷启动/RSS 预算 | 词典分包、延迟部署；必要时评估替代内核 |
| 毛玻璃 OEM 差异大 | 真模糊启用率低或频繁掉帧 | Glass B 成为默认视觉，真模糊仅增强 |
| 模块过多拖慢构建 | 空壳模块多、跨模块改动频繁 | 维持七个稳定边界，按实际依赖拆分 |
| 事件采集干扰输入 | 队列高水位、CPU/IO 抖动 | 降低非关键采样，输入事件固定结构批量写入 |
| Skill 异步覆盖新文本 | stale transaction 测试失败 | 禁止自动应用，统一降级到候选或缓存 |
| AI 输出污染个性化 | AI 短语排名自行升高 | 来源标签强制参与学习权重，AI 默认 0 |
| Provider 适配失控 | Skill 出现厂商分支 | 以能力快照与逻辑 Profile 隔离厂商协议 |

如果中文与混输内核无法在中端 Android 设备上满足候选延迟与 RSS 预算，项目暂停叠加 AI，优先修复输入内核；后续 Brain 影响 IME 稳定性时，Brain 整体必须可关闭并继续发布纯输入版本。

## 16. Definition of Done

任一里程碑只有同时满足以下条件才算完成：

- 功能在真实 InputConnection 中可用，不只在 Demo Activity 中展示；
- 正常路径、降级路径和进程回收路径均有测试；
- 对应性能预算有可重复数据；
- 新增协议带版本，新增数据可回放；
- 没有网络和 AI 服务时普通输入不退化；
- 文档、ADR、基准结果与实现同步提交；
- 没有以 TODO 代替会影响下一阶段的核心行为。

## 17. 参考资料

- [Android Developers：Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Android Developers：InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)
- [Android Developers：Google Play target API 要求](https://developer.android.com/google/play/requirements/target-sdk)
- [AOSP：Window blurs](https://source.android.com/docs/core/display/window-blurs)
- [Android Developers：Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [Android Developers：Startup Profiles](https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations)
- [Android Developers：WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)
- [Rime：librime](https://github.com/rime/librime)

---

**下一步：** 先让 `v0.3.3-m6` 的 GitHub Actions 阻断门禁全部通过，再在 Android 10+ 真机完成启用、切换、`host / fun / zhongwsrf / ji / oumu / pipei`、最后 composing 字符退格、候选横滑、Emoji 惯性、分类符号、高速触控、横竖屏与候选延迟 Macrobenchmark。固定预览签名建立前，不把旧 APK 覆盖安装视为已支持能力。
