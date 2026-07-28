# Sense Skills 工程开发文档

## 键盘交互、不可变存储、Agent 工具与分层设置

**文档版本：** 1.0\
**状态：** Implementation in progress\
**编制日期：** 2026-07-27\
**目标基线：** Sense v0.4.5\
**架构决策：** [ADR 0020](../adr/0020-v0.4.5-skills-runtime-and-keyboard.md)\
**上位 Agent 架构：**
[agent-event-memory-architecture-v1.0.md](../design/agent-event-memory-architecture-v1.0.md)

## 1. 交付定义

本阶段交付一个能真实使用、可继续演进的 Skills substrate：

1. 用户从键盘方向选择已绑定 Skill，并获得明确的 active 视觉反馈；
2. Space hold 启动 Agent 时使用屏幕上同一份完整 Skill revision；
3. 默认 Agent 只看到短 description，可按需分块读取其他完整文档；
4. Agent 能创建、修订、绑定和解绑 Skill；
5. Settings 能分层管理 Skill，并保留所有历史和未保存草稿；
6. 普通输入热路径不承担 Skill 文件 I/O；
7. Provider、进程、取消和崩溃边界都有确定测试。

“文件存在”不是完成。必须从设置保存、键盘选择、Binder 冻结、Provider tool call、本地
mutation、跨进程刷新一直走通。

## 2. 模块落点

| 模块 | 新职责 | 不得承担 |
|---|---|---|
| `brain-api` | Skill domain、catalog reducer、summary、typed tool args | Android View、文件格式 |
| `ai-protocol` | run 冻结的 catalog generation 与 active instruction | 当前目录查询 |
| `ai-runtime` | immutable repository、OS file lock、Brain tool source、Binder codec | 键盘绘制 |
| `ai-brain` | provider tool schema/loop、prompt discovery、tool router | 直接文件 I/O |
| `ime-service` | 后台目录投影、FileObserver、run snapshot | 主线程目录扫描/fsync |
| `ime-ui` | 手势 reducer、picker geometry、aurora | 完整 Skill 正文 |
| `app` | 分层 Settings、草稿、历史与绑定配置 | Provider 私有推理 |

## 3. Domain 与持久化契约

### 3.1 身份

```text
SkillId       = [a-z0-9][a-z0-9._-]{0,63}
SkillRevision = positive signed 64-bit integer
CatalogGen    = positive signed 64-bit integer
Slot          = BindableSemanticKey × {up,right,down,left}
```

一次 current catalog 包含完整 definition revision 引用、全部 binding 和最多一个 active。
定义、binding slot 和 active backing binding 在构造时统一校验；Router、Settings 和
Repository 不复制一套更宽松规则。

### 3.2 文件布局

```text
files/agent-skills-v1/
  documents/<skill-id>/<revision>.skill
  catalogs/<generation>.catalog
  CURRENT
  HIGH_WATER
  store.lock
```

revision 与 catalog 是 immutable。临时文件不属于历史，可在失败后清理；已 rename 的
对象不能删除。读取必须验证：

- UTF-8 严格解码；
- schema version 和字段全集；
- filename `id/revision/generation` 与正文一致；
- catalog 引用的 revision 存在且身份一致；
- 定义、binding、active 全部满足共享 Policy；
- 文件字节不超过写入前使用的同一上限。

### 3.3 mutation 顺序

Reducer 先在内存构造下一 catalog 并完成全部边界检查，然后才允许写文件。Agent 和
Settings mutation 都带 expected generation。冲突返回 typed failure，不自动 last-write
wins。

Repository 在同一 OS file lock 内先以 atomic replace + root directory `fsync` 把
`HIGH_WATER` 推进到预留 generation，再写任何新 revision/catalog。`HIGH_WATER` 永不
回退，reservation hole 也不会复用。`CURRENT` 是对用户可见的提交点：

```text
reserve HIGH_WATER=G → optional revision@G → catalog G → CURRENT=G
```

正常冷加载满足 `CURRENT == HIGH_WATER`，只精确读取 generation G 及其 revision 引用，
禁止列举或排序 `catalogs/`。`HIGH_WATER > CURRENT` 时只精确探测预留 generation：
完整 catalog 前滚 CURRENT；文件缺失或损坏表示保留的 hole，继续使用旧 CURRENT。只有
旧 store 缺失 HIGH_WATER、指针损坏/矛盾或当前 catalog 不可读时，才允许单次全扫描；
扫描结果只重建 CURRENT/HIGH_WATER 辅助指针，不改动任何历史文件。

崩溃语义：

| 最后完成点 | 重启可见 current | 必须保留 |
|---|---|---|
| HIGH_WATER 预留后、revision 前 | 原 catalog | 原数据 + generation hole |
| revision 后、catalog 前 | 原 catalog | 新 revision orphan |
| catalog 后、CURRENT 前 | 自动前滚完整新 catalog | 新 revision + catalog |
| CURRENT 后 | 新 catalog | 全部历史 |

## 4. IME 状态与线程模型

### 4.1 单一后台 lane

IME 的 `AgentSkillStore` 调用全部进入名为 `sense-agent-skills` 的单线程 executor。每个
提交分配 UI sequence，主线程只接收完整 `AgentSkillCatalog`；迟到 projection 由 sequence
丢弃。View 获得的只有：

```text
KeyboardSkillBinding(keyCode, direction, skillId, displayLabel, description)
ActiveKeyboardSkill(skillId, sourceKeyCode, direction)
```

完整正文不进入 View。

`CURRENT` 的 atomic move 由 FileObserver 监听。Settings 和 `:brain` 修改后，无需轮询
或重启输入法即可触发后台刷新。watcher 注册和 stop/start 也属于同一后台 lane，不能在
主线程调用 `File.isDirectory`。初次注册使用 `read → watch → catch-up read` 关闭观察
窗口；`DELETE_SELF/MOVE_SELF` 或生命周期 reconciliation 会合并为一次 rebuild + read，
因此事件丢失不会要求重启 IME，也不会形成无界自刷新。teardown 同样排在该 lane 上，
避免旧 watcher 的阻塞关闭重新进入 IME 主线程。

### 4.2 Space hold

Space hold 只读取内存 catalog：

```text
visible catalog G
  → freeze request.skill_catalog_generation = G
  → optional active id@revision + complete content
  → freeze editor snapshot
  → Binder start
```

若尚无完整投影，则本次使用默认 Agent；不在触摸回调中补做同步磁盘读取。下一次投影完成
后自然生效。

### 4.3 Brain IPC 与 admission

START Bundle 在创建 `Message` 之前经过不复制正文的 envelope preflight。估算覆盖键名、
类型、对齐、UTF-16 值、Messenger/Bundle framing，并固定预留 64 条最大 discovery
summary；产品 cap 448 KiB，相对 512 KiB 测试预算保留至少 64 KiB。语义上不合法且同时
超 envelope 的请求优先在 transport admission 返回 typed oversize，避免把恶意大对象
交给 Bundle/Binder；合法性仍由共享 ProtocolValidator 和 Brain admission gate 负责。

真实 catalog summaries 当前不跨 Binder，而由 Brain 按请求 generation 读取。这一优化
不能只用估算器自证：`ai-runtime/src/androidTest` 必须将最大 mixed UTF-16 snapshot +
active Skill + 64-summary future reserve 写入真实 Android Parcel，marshall、unmarshall、
decode 后逐字段一致且 `dataSize < 512 KiB`。

Brain Messenger handler 不读 Provider 设置、工具设置、Journal 或 Skill 文件。它只建立
exact run identity 后把请求交给专用单线程 admission lane。lane 在每次阻塞读取后检查
取消；销毁与 admission 交错时，先记录 Begin/Cancelled，禁止启动 engine，再由同一
executor 最后关闭 transport/Journal。禁止在主线程 `await` 该清理。

## 5. Picker 与极光状态机

### 5.1 事件

| 状态 | 输入 | 输出 |
|---|---|---|
| IDLE | eligible pointer down | ARMED |
| ARMED | hold deadline 且未越过 stationary slop | PICKING |
| ARMED | 提前移动 | 取消 Skill eligibility，普通 flick 继续 |
| PICKING | move/up | edge-aware direction + hysteresis |
| PICKING | release on bound direction | emit explicit activate/deactivate intent；后台提交成功后再发布 active |
| PICKING | cancel/second owner invalidation | 不提交 |

进入 PICKING 后，第二 pointer 不能输入、切 panel 或启动 AI。`ACTION_UP` 必须先以最终坐标
执行一次 move，再 finish。

View 不绘制尚未提交的“乐观极光”。否则用户可以在后台 mutation 尚未完成时立刻按住
Space，造成屏幕显示 Skill B、实际冻结 Skill A。选择事件携带当时的显式
`ACTIVATE|DEACTIVATE` 意图；后台取得最新 catalog lock 后只在该意图仍未满足时推进
generation。Aurora、Space run snapshot 和 Settings 最终都只消费同一份已提交 projection。

该意图还携带一个单调、仅用于 UI 关联的 request token。Repository 不使用旧
`expected_generation` 推导 toggle，而是在跨进程 lock 内将意图应用到最新 catalog：
激活核对 exact slot→Skill，取消激活按 Skill identity 生效，并保留并发文档修订。

Aurora 的 owner 不能只记录 semantic key code。View 记录：

```text
surface + panel token + stable key signature + duplicate occurrence
```

并以 request token 维护 provisional→confirmed owner。bounds、显示 label、Shift 大小写
变化后仍可恢复原物理按键；同 key code 的 toolbar/panel 重复控件不能互相迁移。已确认
owner 暂时不可见时不绘制极光，而不是回退到另一个“看起来相同”的键。旧请求失败只撤销
匹配 token，不能清掉更新请求的 pending/confirmed 状态。

### 5.2 几何纯函数

Android-free 几何层输入 viewport、source bounds、enabled mask、density 和文字尺寸，
输出全部 chip bounds 与 radial/shelf mode。性质：

- chip 完全位于 safe viewport；
- chips 两两不重叠；
- 每个 enabled direction 都有可达 pointer path；
- 径向模式保持视觉方位；做不到时整体降级 shelf；
- shelf 的每项包含固定方向箭头；
- 320/360/411 dp、横屏、小高度和 1–4 项均确定。

### 5.3 动画隔离与预算

active Aurora 是长驻动画，必须位于只覆盖 exact physical owner bounds 的独立 child
render layer；父 `SenseKeyboardView` 只在 owner、布局、主题或静态 marker 变化时更新，
不得跟随每个动画 frame 重绘键盘背景、候选和全部按键。picker 是短暂手势反馈，可以由
父 View 绘制。不能依赖 `invalidate(left, top, right, bottom)` 达到相同效果：Android
硬件加速从 API 21 起可以忽略 dirty rectangle，只有独立 render layer 才是可测试的
隔离边界。

overlay 的 shader/matrix/Paint 复用，圆角 stroke 直接绘制，热路径零集合分配并以
`postOnAnimationDelayed` 对齐帧调度。active 目标约 30 fps；系统 animator scale 为 0
时显示确定的静态相位，重新启用后从受控 phase 继续。owner 不可见、View detach、窗口
结束或 active 清除时撤销 frame callback。active key 当前 panel 不可见时，Agent
surface 显示 active 名称，不能形成隐藏模式。

父键盘和 overlay 分别暴露只读测试计数器。测试读取计数不得引入生产热路径分配，也不得
把测试 Activity 的全屏背景重绘误计为键盘 active layer。

## 6. Agent 上下文与工具循环

### 6.1 Prompt 分层

```text
system / editor Patch contract
active Skill exact frozen content       (0 or 1)
catalog G discovery summaries           (<=64)
editor snapshot
provider-native tools
```

description 只用于发现，不能假装已读取正文。非 active Skill 必须先：

```json
{
  "skill_id": "weekly_report",
  "revision": 4,
  "offset": 0,
  "max_chars": 6000
}
```

结果给出本段正文、`next_offset` 与 `eof`。下一段仍固定同一 revision。

### 6.2 mutation

每个 `skill_manage` 参数都包含：

```json
{"expected_catalog_generation": 11}
```

成功返回 12 后，本 run 的下一次 mutation 必须使用 12。冲突时 Agent 可以说明冲突、
重新读取下一 run，不能以旧 prompt 的 description 覆盖新设置。

### 6.3 Provider 能力矩阵

| Provider path | Patch terminal | progress | Skills tools |
|---|---:|---:|---:|
| DeepSeek native Chat | required | required | required |
| OpenAI-compatible Chat | required | required | required |
| OpenAI Responses | required | required | required |

Request factory 先得到“真实可编码的工具集合”，再生成 prompt。不得根据 Settings 的逻辑
enabled set 宣传 wire 未暴露的工具。

### 6.4 Skills 工具开关

设置页工具分类提供总开关，以及独立的 `skill_read`、`skill_manage` 开关。配置 codec
使用 schema v2；读取旧 schema v1 时迁移为两个 Skills 工具默认开启，且不修改历史数据。
Brain admission 为每个 run 冻结一次 allow-list；总开关关闭返回空集合，任何调用链都
不得再无条件 union Skills 工具。一个工具关闭后，prompt 能力说明、Provider wire schema
和本地 router 必须同时不可用；当前 active Skill 的冻结正文仍是用户明确选择的 run
instruction，不等价于偷偷开启 `skill_read` 或 `skill_manage`。

## 7. Settings

首页固定分类：

```text
Provider
记忆（Soul）
工具
Skills
语音
关于
```

Skills 编辑器支持名称、description、正文、基础意图、键位、方向、保存、解绑、历史和
恢复。页面切换滚动归零。草稿用明确的 selected Skill identity + base revision +
generation 保存；切换/旋转后恢复。保存发生 generation conflict 时保留草稿并刷新基线，
绝不清空用户正文。

现有 Skill 的文档 `base/draft` 不承载单个绑定槽位；独立
`SkillBindingSelection(slot, explicitlySelected)` 保存 selector 操作上下文。用户明确
选择的第二或后续槽位跨绑定替换、解绑、reconcile、discard、save、Skill 切换和 Activity
恢复都不回跳首槽位，也不制造 binding-only dirty；未明确选择时才跟随 catalog 首选槽位。
文档 Update 不得改变该 Skill 的其他绑定。新建 Skill 仍可把初始绑定与创建原子提交。

历史恢复读取旧 revision，但落地为新的 current revision，从而保持因果和全部中间历史。

当前草稿的耐久性分为两条路径：

- 严格上限内的小草稿使用 exact Bundle fallback，不依赖 View hierarchy 自动保存全文；
- 完整草稿使用 app-private recovery 文件，临时文件 `fsync` 后 atomic rename，再同步
  目录；已损坏的旧 recovery 原始字节先另存保留。

recovery codec v3 按 UTF-16 code unit 往返并保存独立 binding selector，必须保留未配对
surrogate；v1/v2 只用于严格迁移读取。Activity 使用进程级串行 worker；`close` 只撤销
UI delivery，不取消已经接受的保存。对不能安全放入 Bundle 或尚未 durable 的当前状态，
`onSaveInstanceState` / `onStop` 使用相同 snapshot key 的有界 barrier，重复生命周期
回调复用同一 Future；超时不取消后台落盘。编辑器 InputFilter 对超上限的整次替换/粘贴
原子拒绝，保留此前所有字符并通过 live region 反馈，不做 prefix truncation。

## 8. 取消与副作用

取消测试使用 latch 固定两种交错：

1. cancel 先获得 run lock：executor 不得收到 `skill_manage`；
2. tool preflight 先获得 run lock：本地原子 mutation 可以完成，Journal 记录结果，
   FileObserver 刷新键盘；模型不能再生成 Patch。

网络/文件等不可回滚工具沿用同一原则。停止不是事务回滚按钮。

## 9. 测试矩阵

### 9.1 纯 JVM

- Policy、Reducer、codec、极限 Unicode；
- 全部 mutation 与 generation 冲突；
- revision/catalog 身份错配；
- HIGH_WATER 预留及 revision/catalog/CURRENT 五个提交点故障注入；
- hole、旧 store 迁移、损坏/矛盾指针和 generation 文件冲突；
- 10,000 catalog 有效指针冷加载/下一代预留零目录扫描与宽松 wall-clock 上限；
- 双 JVM OS file lock；
- picker 几何属性与手势 reducer；
- Provider request/response/tool 多轮；
- Settings 草稿与历史恢复 reducer。

### 9.2 Android

已实现的 AndroidTest 源码为：

- 最大合法 mixed UTF-16 Brain Bundle + 64-summary reserve 的真实 Parcel round-trip
  1 项；
- Q-left、P-right、最终 ACTION_UP、CANCEL、多指、Space/Delete/flick 共存、exact
  physical owner、Aurora sibling 触摸透传、无障碍和 reduced motion 的真实
  View/MotionEvent 11 项；
- Aurora 3×30 秒结构隔离门禁 1 项：父键盘 `onDraw`、每 5 秒 callback/draw/
  FrameMetrics liveness、唯一 callback ownership、ART 分配、FrameMetrics 分项/有界
  报告丢失和无条件隐藏停止；SwiftShader 不判绝对 p95/active CPU；
- 固定实体设备绝对性能门禁 1 项：默认以 JUnit Assumption 明确跳过，只有显式 opt-in
  且 exact target/test APK SHA-256、fingerprint、刷新率与 thermal ceiling 全部匹配时
  执行；
- 真实 FileObserver 对 CURRENT 的 MOVED_TO/CLOSE_WRITE、目录 MOVE_SELF/DELETE_SELF
  与两轮 teardown/re-register 恢复 1 项；
- 隔离 filesDir 下真实 Store + Projection + Watcher 的 start/rebuild/mutation/close
  主线程 StrictMode 零磁盘 I/O 1 项；
- Settings hierarchy、Activity recreation、exact draft、整次超限输入拒绝和 live
  accessibility status 2 项。

这些源码已纳入 `assembleDebugAndroidTest` 编译。PR CI 另用固定
`ReactiveCircus/android-emulator-runner@e89f39f1abbbd05b1113a29cf4db69e7540cae5a`
的 API 36 x86_64 模拟器实际运行四个模块的 `connectedDebugAndroidTest`，package/release
依赖该 job；当前本地和 PR 前仍没有设备执行通过证据。

仍待补齐或单独证明的 Android 项包括：

- 完整系统 IME host 下 pointer-up、onStartInputView、onWindowShown 的 StrictMode
  主线程零 Skill 文件 I/O；
- FileObserver 跨真实 `:brain`/IME 进程提交与连续 window show 的系统级交错；
- Brain destroy 与真实 Android config/catalog read 的 latch 交错；
- Activity 进程死亡后的文件恢复、2× font、RTL 与深浅色截图。

### 9.3 显式真实 Provider 探针

`LiveSkillAgentProbeTest` 使用生产
`HttpUrlConnectionProviderTransport → AiBrainEngine → DefaultAgentToolExecutor`，
但以进程内 `AgentSkillToolSource` 提供确定且不落盘的 Skill catalog。默认用官方
DeepSeek 的 OpenAI-compatible Chat Completions 路径，也可通过环境变量指向兼容端点。

读取探针只向默认 Agent 暴露一个**未激活** Skill 的短 description；唯一校验令牌仅存在
完整正文。测试要求真实模型调用 `skill_read(id, exact revision)`，取得工具结果后再生成
唯一一个通过本地 Patch gate 的 whole-field replacement，并验证同一 call id 的
`TOOL/RUNNING → TOOL/COMPLETED`。公开事件还会检查 `reasoning_content` 和 `<think>` 等
私有推理协议标记没有泄露。测试输出只包含耗时和事件类型计数，不打印 Key、请求、
Skill 正文、模型正文、私有推理或 Patch。

```bash
export SENSE_TEST_API_KEY
export SENSE_RUN_LIVE_SKILL_READ_TEST=1
./gradlew :ai-runtime:testDebugUnitTest \
  --tests '*.LiveSkillAgentProbeTest.nonActiveSkillDescriptionCausesExactRevisionReadBeforeTerminalPatch'
```

可选的高方差 mutation 探针使用独立开关。它要求模型先以 generation 91 更新一个 Skill，
再用工具结果中的新 revision 读取 generation 92 的正文，最后生成唯一 Patch。它用于人工
联调，不是普通 CI 或自动发布门禁：

```bash
export SENSE_TEST_API_KEY
export SENSE_RUN_LIVE_SKILL_MANAGE_TEST=1
./gradlew :ai-runtime:testDebugUnitTest \
  --tests '*.LiveSkillAgentProbeTest.manageThenReadUsesNewCatalogGenerationAndRevision'
```

可选覆盖：

- `SENSE_TEST_API_BASE`：默认 `https://api.deepseek.com/v1`；
- `SENSE_TEST_MODEL`：默认 `deepseek-v4-pro`。

description 的触发质量另按固定小语料人工统计，不能只凭一次成功决定。语料不包含完整
正文的 sentinel：

| 分组 | 输入意图（实际运行时使用等价自然语言，不携带 sentinel） | 期望 |
|---|---|---|
| `should_trigger` | 明确要求使用目录中的“离线验收令牌”Skill | `skill_read` exact revision |
| `should_trigger` | 要求按“真实读取验收”Skill 执行，且不可根据 description 猜正文 | `skill_read` exact revision |
| `should_not_trigger` | 只把一条普通中文病句改得简洁 | 不调用 `skill_read` |
| `should_not_trigger` | 回答一个不依赖用户 Skill 的简单常识问题 | 不调用 `skill_read` |

首次真 Key 联调先运行上述单次 read 硬探针，避免因配置错误重复计费。发布候选评估再把每条
语料独立运行 3 次，记录 `trigger_rate = 正确触发次数 / should_trigger 总次数` 与
`false_trigger_rate = 错误触发次数 / should_not_trigger 总次数`；每次都是全新 run，
禁止复用前一轮工具结果。当前小语料门槛为 trigger rate 100%、false-trigger rate 0%；
任一失败先调整 description 并重跑，不把付费随机测试放入普通 CI。

仅设置 Key 而未设置对应 enable 变量时仍然跳过。当前环境没有 Key、网络不可达或测试显示
`SKIPPED`，都**不能**记为真实 Provider 通过；普通 CI 的预期结果就是 skipped。真实通过
记录必须同时包含模型/端点标识（不含凭证）、事件类型计数、耗时和 commit SHA。

截至本次快照，`SENSE_TEST_API_KEY` 未提供，4 个 opt-in 真实网络探针全部走明确
`SKIPPED` 路径；真实 Key 通过数为 **0**。

### 9.4 性能与长期

Aurora 性能证据分成两个不可互换的 gate：

| 环境 | 必须证明 | 明确不能证明 |
|---|---|---|
| API 36 x86_64 + SwiftShader CI | 3×30 秒；每窗口父键盘 `onDraw` 增量 `<=5`；至少 2/3 窗口的每个 5 秒 slice 都推进 callback、overlay draw 与 FrameMetrics，`|callback-draw|<=1`、`|draw-report|<=2`，slice 末 `posted-executed=1`、零取消；ART 分配 `<=1 KiB/overlay frame`；FrameMetrics overflow 计入 dropped 且 dropped `<=2%`；记录全部分项；可采用 static/active ABBA 相对基线 | 共享 runner 上的固定帧数、绝对 p95、active CPU、GPU 或功耗结论 |
| active 清除后的 1 秒停止窗口 | reported + dropped frame `<=12`；进程 CPU `<=500 ms`；callback 不再自续 | 不适用 2/3 quorum，任一次失败都阻断 |
| 显式 opt-in 固定实体设备 | 运行前后绑定 exact target/test APK SHA-256，运行前固定动画配置，运行前、每个 5 秒 slice 与结束时复验 fingerprint、刷新率和 thermal ceiling；三个窗口均通过全部结构不变量、`TOTAL_DURATION p95 <=32 ms`、33 ms 调度目标帧数 `>=80%`（约 24.2 fps），并保存原始样本和分项 | 不能由模拟器、一次手工观察或相邻 commit 外推；默认 skipped 不能记为通过 |

SwiftShader 的帧时延受软件 GPU、共享宿主和 runner 调度影响，因而结构门禁不设置绝对
p95/active CPU 上限，也不以某个降低后的固定帧数替代设备 SLA。CI #60 的三个窗口中，
FrameMetrics 为 344/335/337、overlay draw 为 342/334/335，父键盘 draw 与 dropped
均为 0、ART 分配每窗口低于 132 KiB；同时软件 GPU 的
`COMMAND_ISSUE/SWAP_BUFFERS/GPU` p95 约 90/103/103 ms，使 total p95 达到
292–310 ms。这证明循环仍随共享渲染管线推进，不证明约 11 fps 可作为产品目标。
相对基线若用于阻断，必须在同一进程以
static→active→active→static 或反向配对执行，并预先固定统计量；它只能发现候选相对
自身基线的退化，不能铸造成实体设备证明。固定实体设备的 `p95 <=32 ms` 未通过前，
这一绝对性能 gate 保持未满足，任何 Release 都必须逐字披露该事实。

普通 `connectedDebugAndroidTest` 不提供任何 physical 参数，因此绝对门禁以
Assumption 明确 skipped。受控设备入口必须单独执行并提供全部 attestation；省略、错配
或检测到 emulator 都失败/跳过，不能静默回落到结构门禁：

```bash
./gradlew :ime-ui:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.ethanbird.senseime.ui.SkillAuroraDevicePerformanceTest#fixedPhysicalAuroraMeetsAbsoluteP95AndFrameRateGate' \
  -Pandroid.testInstrumentationRunnerArguments.senseAuroraPhysicalGate=true \
  '-Pandroid.testInstrumentationRunnerArguments.senseAuroraExpectedFingerprint=<exact Build.FINGERPRINT>' \
  -Pandroid.testInstrumentationRunnerArguments.senseAuroraExpectedTargetApkSha256=<64 lowercase hex> \
  -Pandroid.testInstrumentationRunnerArguments.senseAuroraExpectedTestApkSha256=<64 lowercase hex> \
  -Pandroid.testInstrumentationRunnerArguments.senseAuroraExpectedRefreshRateHz=<exact Hz> \
  -Pandroid.testInstrumentationRunnerArguments.senseAuroraMaximumThermalStatus=<0..6>
```

两份 SHA-256 分别对应设备实际安装的 ime-ui target APK 与 instrumentation APK；它们
是当前渲染测试宿主的精确字节身份，不得冒充最终 Sense benchmark APK 的哈希。发布证据
还须在外层记录候选 APK 哈希、commit、原始 XML/log 与设备配置。

Aurora 的运行生命周期由 `SenseInputMethodService.onWindowShown/onWindowHidden`
显式传给独立 child layer。输入法窗口可以带 `FLAG_NOT_FOCUSABLE`，所以 Debug Activity
中的 `hasWindowFocus=true` 不是生产证据，也不得成为动画启动条件。

- 10,000 generation 有效指针 cold load/下一代预留零目录扫描；迁移扫描只允许一次；
- 64 Skills × 208 bindings 上限；
- 65,536 UTF-16 code-unit active Skill + 448 KiB Binder envelope（至少 64 KiB headroom）；
- HyperOS/Pixel/中端设备输入延迟。

## 10. 提交与发布纪律

实现按以下原子提交拆分：

1. run 冻结协议；
2. domain + immutable store；
3. keyboard reducer + aurora；
4. hierarchical Settings；
5. provider/tool loop；
6. IME background integration；
7. hardening、docs 和 gates。

每个提交必须能由对应纯测试解释，不能把依赖缓存、真实 Key、APK 或无关换行带入。
纯门禁通过后可以创建 Draft PR 让 GitHub 执行 Android 门禁。默认只有 ADR 0020 的
12 条发布门禁全部通过后，才允许升级版本、将 PR 标记为 ready、合并和创建 Release。

v0.4.5 使用一次显式产品所有者例外：2026-07-28，产品所有者要求立即发布，并接受真实
Provider Key、完整系统 IME 宿主交错与固定实体设备绝对性能证据延期补齐。例外不适用于
确定性回归、API 36 模拟器结构门禁、APK、签名、权限或资产验证，也不能把 skipped
写成 passed；发布说明和 ADR 必须保留三项缺口。

## 11. 当前验证快照

截至 2026-07-28，当前分支的测试源码 inventory 为：

| 范围 | 当前源码测试数 |
|---|---:|
| `ai-protocol` 冻结请求与结构校验 | 77 |
| `brain-api` Skill domain / reducer / typed tools | 37 |
| `ai-brain` 三类 Provider wire、router 与工具循环 | 120 |
| `ai-runtime` repository、Journal、工具 executor 与语音继承回归 | 151（147 个确定性/离线用例 + 4 个 opt-in live probe） |
| `ime-service` 编辑器、run snapshot 与跨进程 projection 协调 | 119 |
| `ime-ui` 手势、edge-aware 几何、physical owner、Aurora 与既有 UI | 142 |
| Settings 纯状态、草稿、冲突、历史与 Back | 38 |
| AndroidTest | 18（runtime 1、IME Service 2、IME UI 13、Settings 2；普通 CI 预期 17 executed + 固定实体设备绝对门禁 1 skipped） |
| release-plan suite | 25 / 25（其中 workflow contract 6 / 6；固定 Action SHA、API 36、动画开启、`--continue` 执行四模块且不吞失败、精确权限三元组与 release 依赖） |

其中持久化门禁包含 65,536 字符中文文档、五个 crash point、预留 hole、旧 store/损坏
指针单次迁移扫描、损坏且超前 CURRENT/落后 HIGH_WATER 的前沿恢复、10,000 catalog
零扫描冷加载/预留、损坏身份、并发线程和两个真实 JVM 的 OS file lock；repository
定向 K2/JUnit 本地为 31 / 31 通过。projection 门禁包含初读与
watcher 注册之间的提交，以及
“工具先开始、用户后取消、mutation 再提交”的确定性交错。表中数字用于审查测试覆盖，
不是把“源码中存在测试”冒充为当前 commit 的全量执行结果。完整 Android 编译、Lint、
严格主线程 I/O、设备 Aurora 性能、APK、签名、权限与资源哈希仍由后续 CI/设备门禁判定。

IPC resilience 的依赖无关定向门禁另有 5 项通过：最大合法 envelope/headroom、typed
本地超限、admission FIFO、close 拒绝和 destroy-during-read 清理顺序；projection
coordinator 的定向门禁覆盖初始观察窗口、取消后提交、损坏恢复、生命周期合并、watcher
invalidation 与后台 teardown。真实 Android Parcel、MotionEvent/physical owner、
Aurora 结构隔离、FileObserver/StrictMode 和 Settings recreation 测试已纳入四个模块
的 `assembleDebugAndroidTest` 编译门禁，并由独立 API 36 x86_64 模拟器运行
`connectedDebugAndroidTest`。CI #61 已报告 18 项 AndroidTest 中 17 项通过、固定实体
设备测试 1 项明确 skipped，并通过三段 Aurora 结构/停止门禁；这仍不等于固定实体设备
`p95 <=32 ms` 已通过。ADR 0020 因显式发布决定转为 Accepted，同时原绝对性能 gate
继续保持未满足。

Skills 真实 Provider 探针已经纳入源码并保持显式 opt-in；本地 K2/JUnit 可验证其默认
Assumption 路径，但没有临时 Key 时 4 项结果只能记录为 `SKIPPED`，真实 Key 通过数为
0，不能计入通过项；本次发布例外也不改变该证据状态。
