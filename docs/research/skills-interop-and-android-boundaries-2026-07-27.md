# Skills 互操作与 Android 边界研究记录

**研究日期：** 2026-07-27

**服务决策：** [ADR 0020](../adr/0020-v0.4.5-skills-runtime-and-keyboard.md)

**实现计划：** [Skills 工程开发文档](../development/skills-engineering-plan-v1.0.md)

本文固定 v0.4.5 实现所依据的外部规范与系统边界。它不是用外部规范替代 Sense
自己的数据模型，而是说明哪些接口应当保持可迁移、哪些 Android 限制必须由工程门禁
验证。

## 1. 固定的来源

| 来源 | 固定方式 | 本轮采用的事实 |
|---|---|---|
| [Agent Skills specification](https://agentskills.io/specification) | `agentskills/agentskills` main `38a2ff82958afee88dadf4831509e6f7e9d8ef4e`；`docs/specification.mdx` SHA-256 `494b0d84537c4d39714bf91e016d31d0731df0380015321cb12040625b22d3f9` | `SKILL.md`、name/description 发现、完整正文激活、references/assets 按需读取 |
| [Adding Skills support](https://agentskills.io/client-implementation/adding-skills-support) | 2026-07-27 访问快照 | 先暴露短 catalog，再由模型或用户选择完整指令；没有可用 Skill 时不注册空工具 |
| [Android Parcelables and Bundles](https://developer.android.com/guide/components/activities/parcelables-and-bundles) | 2026-07-27 官方文档 | Binder 缓冲区当前约 1 MiB，且由进程内并发事务共享；saved state 应保持很小 |
| [TransactionTooLargeException](https://developer.android.com/reference/android/os/TransactionTooLargeException) | 2026-07-27 官方 API 文档 | 超大请求与超大返回无法可靠区分，调用方必须把它视为可能的部分失败 |
| [FileObserver](https://developer.android.com/reference/android/os/FileObserver) | 2026-07-27 官方 API 文档 | 观察事件来自 inotify，可跨进程发现文件变化，但不能代替生命周期恢复读取 |
| [StrictMode](https://developer.android.com/reference/kotlin/android/os/StrictMode) | 2026-07-27 官方 API 文档 | 主线程文件/网络访问会造成不可预测卡顿；StrictMode 是检测器，不是正确性的来源 |
| [Android Emulator Runner v2.37.0](https://github.com/ReactiveCircus/android-emulator-runner/tree/e89f39f1abbbd05b1113a29cf4db69e7540cae5a) | `ReactiveCircus/android-emulator-runner` commit `e89f39f1abbbd05b1113a29cf4db69e7540cae5a` | Ubuntu KVM 硬件加速、`pixel_7_pro`/x86_64 profile、启动后运行 `connectedCheck` 或自定义 script，以及显式 `disable-animations` |
| [Macrobenchmark instrumentation arguments](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args) | 2026-07-27 官方文档 | 模拟器性能数字不代表真实设备；抑制 emulator error 不能把结果升级为设备证据 |
| [`View.invalidate(dirtyRect)`](https://developer.android.com/reference/android/view/View#invalidate(int,int,int,int)) | 2026-07-27 官方 API 文档 | API 21 起硬件加速 View 可忽略 dirty rectangle；小脏区请求不能证明只重绘该区域 |
| [`FrameMetrics`](https://developer.android.com/reference/android/view/FrameMetrics) | 2026-07-27 官方 API 文档 | 可分项记录 draw、sync、command issue、swap、GPU、deadline 与 total duration；listener 还会报告丢失的历史帧 |

## 2. 对 Sense v0.4.5 的直接结论

### 2.1 渐进披露保持三层，但每层都固定 revision

Sense 采用：

1. 默认 Agent 看到 `id@revision + name + description`；
2. 用户在键盘激活的 Skill 以精确 revision 的完整正文直接进入该 run；
3. 非 active Skill 由 `skill_read(id, revision, offset, max_chars)` 分页读取。

这与开放规范的“metadata → instructions → resources”方向一致，但比普通路径发现增加
了 catalog generation 和 immutable revision。模型不能把新旧目录混用，也不能猜测
未在本 run 目录中暴露的历史或未来 revision。

### 2.2 原生存储不是导入格式

v0.4.5 的 `documents/<id>/<revision>.skill` 和不可变 catalog 是运行时提交日志，不能
被误称为标准 `SKILL.md` bundle。后续导入/导出适配器应当：

- 接受标准 name、description、Markdown 正文及可选 metadata；
- 完整保留原始 bundle、来源格式版本和 digest；
- 把标准 name 映射到稳定内部 id，同时保留展示名称；
- 将 Sense 当前 240 字符 discovery description 视为运行时摘要，不截断或覆盖导入
  源的 1024 字符 description；
- 把 `scripts/`、`references/`、`assets/` 原样保留后再按能力逐项开放。

v0.4.5 不执行 bundle 脚本，是因为尚无可验证的 Android 执行器 ABI；这不是删除用户
文件，也不是永久禁止用户授权后的开放执行。

### 2.3 Binder 大小必须用真实编码测量

“正文最多 65,536 个 UTF-16 code unit”不等于“Binder 只占 64 KiB”。Java String、
Bundle key、64 条摘要、编辑器快照和 Binder 元数据都会增加体积。由于系统的约
1 MiB 缓冲区由并发事务共享，Sense 不能把 1 MiB 当作单次可用预算。

v0.4.5 的发布门禁应使用真实 `Bundle.writeToParcel` 测量最坏合法请求，目标上限为
512 KiB，并验证 encode/decode 往返逐字一致。若输入超过本地预算，应在 Binder 调用前
给出 typed failure；不能靠捕获 `TransactionTooLargeException` 猜测远端是否已经产生
副作用。

### 2.4 FileObserver 是提示，immutable CURRENT 才是事实

FileObserver 只触发合并刷新。IME 在创建观察器前后各读取一次，并在
`onStartInputView`/窗口恢复时安排后台 catch-up；观察器失效时重建。所有读取都进入
单线程 lane，主线程只接收完整 catalog snapshot。

即使某个事件丢失，下一次生命周期 catch-up 也必须恢复；即使重复收到事件，也只能
合并读取，不能重复执行 mutation。

### 2.5 Activity saved state 只保存小索引

完整用户草稿的权威恢复源是应用文件中的原子 recovery snapshot。Skill 编辑框关闭
Android View hierarchy 的自动全文保存；Bundle 只放有严格上限的小型 fallback/索引。
页面停止时承诺的 recovery 写入不能因为 Activity `destroy` 而被取消。

实现因此不能把 Java/Kotlin `String` 简化为 UTF-8 round-trip：Android 编辑缓冲区可以
暂时含有未配对 surrogate。recovery v2 按 UTF-16 code unit 保存，小型 Bundle fallback
也必须逐 code unit 一致。较大草稿的生命周期 barrier 可以有等待预算，但超时只解除主
线程等待，不能取消已经接受的原子写入；损坏的旧 recovery 原始字节必须先保留。

### 2.6 工具授权是一次 run 的显式 allow-list

设置页总开关与 `skill_read`、`skill_manage` 两个独立开关共同产生 allow-list，并在
Brain admission 时冻结。关闭能力必须同时影响 prompt、Provider schema 与执行 router；
不能因为 Skills 是内置能力就在 Service 中无条件补回。开关不删除任何 Skill、catalog、
Journal 或工具结果，下一 run 重新读取配置即可。

这与 Agent Skills 的渐进披露并不冲突：active Skill 是用户对当前 run 的明确 instruction；
`skill_read` 是让模型读取其他未激活完整文档的工具能力；`skill_manage` 是产生持久化
副作用的修改能力，三者必须分别建模。

### 2.7 selection intent 与物理视觉身份必须分离

方向选择发生时，旧 projection 只能生成 `ACTIVATE|DEACTIVATE` 意图；最终 catalog 由
Repository 在 OS lock 内针对最新状态线性化。激活需再次验证 exact slot→Skill，取消激活
按 Skill identity 处理，避免并发修订丢失或旧 picker 激活替换绑定。

Aurora 的 UI request token 只关联视觉请求，不进入持久化 catalog。physical owner 使用
surface、panel token、稳定 key signature 和 duplicate occurrence，避免同一 key code
在 toolbar/panel 或布局重建后串位。只有权威 projection 确认 token 后才显示 active
owner；这使“界面上亮起 Skill B，但 Space 冻结 Skill A”成为不可表达状态。

### 2.8 SwiftShader 只验证渲染结构，实体设备验证绝对性能

API 36 CI 使用 x86_64 模拟器与 SwiftShader。它适合稳定验证生命周期、callback 停止、
父子 View 隔离、分配和采样器边界，但软件 GPU、共享宿主和 runner 调度使绝对
FrameMetrics p95/active CPU 不具备实体设备代表性。Aurora 因而采用两层证据：

1. 模拟器以 3×30 秒验证 active 动画只重绘小型 child overlay，父键盘每窗口
   `onDraw <=5`；至少 2/3 窗口的每个 5 秒 watchdog slice 都必须推进唯一 callback、
   overlay draw 与 FrameMetrics report，并满足 `|callback-draw|<=1`、
   `|draw-report|<=2`，slice 末保持 `posted-executed=1` 且零取消；
   分配 `<=1 KiB/overlay frame`，dropped report `<=2%`，并记录
   `DRAW/SYNC/COMMAND_ISSUE/SWAP_BUFFERS/GPU/DEADLINE/TOTAL` 分项；同进程 ABBA
   static/active 相对基线可以补充，但不能成为绝对设备认证。这里不设置或降低固定帧数，
   因为共享 SwiftShader 吞吐不是产品帧率。
2. active 清除后的 1 秒内 reported/dropped frame `<=12`、进程 CPU `<=500 ms`，且
   callback 不再自续；这一项每次必须通过。
3. 固定实体设备测试显式 opt-in，运行前后绑定 exact target/test APK SHA-256，
   运行前固定动画配置，运行前、每个 5 秒 slice 与结束时复验 fingerprint、刷新率与
   热状态上限；三个窗口均独立要求全部结构不变量、
   `TOTAL_DURATION p95 <=32 ms`，以及不少于 33 ms 调度目标 80% 的帧推进
   （约 24.2 fps）。普通 CI 必须将它明确标为 skipped。没有这份证据时 ADR 仍是
   Proposed，不能发布 v0.4.5。

CI #60 的 3 个共享 SwiftShader 窗口分别记录 344/335/337 个 FrameMetrics report，
overlay draw 为 342/334/335，父键盘 draw 与 dropped 均为 0，单窗口 ART 分配低于
132 KiB；但 `COMMAND_ISSUE/SWAP_BUFFERS/GPU` p95 约为 90/103/103 ms，导致
`TOTAL_DURATION` p95 约 292–310 ms。帧报告与 overlay draw 同步推进、隔离/分配/停止
不变量成立，而软件 GPU 三段耗时主导总时长；因此它是共享宿主吞吐证据，不是把产品
30 fps 目标下调到约 11 fps 的依据。

该分层也决定实现结构。API 21 起 dirty rectangle 可被硬件加速 View 忽略，所以在一个
承载整张键盘的 custom View 上调用 `invalidate(activeKeyRect)` 不是隔离。长驻 Aurora
必须迁入 exact owner bounds 的 child render layer；短暂 picker 才可继续由父层绘制。

输入法窗口还有一个容易被普通测试 Activity 掩盖的边界：AOSP
[`InputMethodService`](https://android.googlesource.com/platform/frameworks/base/+/c12e4603586f16d20e29e8e4a59ce6b64b716a37/core/java/android/inputmethodservice/InputMethodService.java)
创建 `SoftInputWindow` 时传入 `takesFocus=false`，后者会设置
[`FLAG_NOT_FOCUSABLE`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-mainline-conscrypt-release/core/java/android/inputmethodservice/SoftInputWindow.java)。
因此 `View.hasWindowFocus()` 不能作为 Aurora 是否运行的条件。生产 Service 必须从
`onWindowShown` / `onWindowHidden` 显式传递 host rendering 状态；View 自身的 attach、
visibility 和 animator-scale 只作为补充门禁。

## 3. 本轮明确不接受的捷径

- 不把 description 当成已经读取完整 Skill；
- 不允许 `skill_read` 读取本 run catalog 未授权的 `id@revision`；
- 不在 mutation 成功后继续把旧摘要标成新 generation；
- 不用主线程目录扫描、gzip、`fsync` 或文件锁换取实现简单；
- 不覆盖旧 revision、旧 catalog、损坏文件或未保存草稿；
- 不以 FileObserver、Bundle 或向量召回作为唯一数据事实；
- 不以“CI 能编译”代替真实 Parcel、进程竞争、故障注入和设备动画门禁。

## 4. 当前验证边界

Android Parcel、真实 FileObserver/StrictMode、IME MotionEvent/physical owner、
Aurora 结构隔离/停止和 Settings recreation 已有 AndroidTest 源码，并已纳入
`ai-runtime`、`ime-service`、`ime-ui`、`app` 的
`assembleDebugAndroidTest` 编译任务。PR CI 的独立 API 36 x86_64 模拟器 job（固定
`ReactiveCircus/android-emulator-runner@e89f39f1abbbd05b1113a29cf4db69e7540cae5a`）
还会实际执行四个模块的 `connectedDebugAndroidTest`，并阻断 package/release。该 job
即使通过也只形成 SwiftShader 结构证据；截至本记录尚无同一候选提交满足新结构门禁和
固定实体设备 `p95 <=32 ms` 的证据，所以不能标记为 Aurora 发布门禁通过。

本地 release-plan suite 25 / 25（其中 workflow contract 6 / 6）只证明 workflow
固定了上述 Action SHA、API 36 x86_64、动画开启、四个 connected task 以及
package/release 依赖；它不证明模拟器已经启动或 AndroidTest 已通过。

真实 Provider 探针保持 opt-in。当前环境未提供 `SENSE_TEST_API_KEY`，包含两个 Skills
探针在内的 4 个真实网络用例均明确 `SKIPPED`；真实 Key 通过数为 0。离线 Provider wire
与 router 测试不能替代这一证据。

### 4.1 2026-07-28 发布决定附记

本研究记录对证据能力的判断不变：模拟器不能替代固定实体设备，离线 wire 测试不能替代
真实 Provider。产品所有者于 2026-07-28 明确要求发布 v0.4.5，并接受真实 Key、完整系统
IME 宿主交错和固定实体设备绝对性能证据延期补齐。该决定是产品发布例外，不是研究结论
发生变化；发布说明必须继续标明这些项目未通过，后续证据也必须绑定实际候选提交。

## 5. 后续兼容方向

运行时 ABI 继续保留 `source_format`、`source_revision`、`source_digest` 和资源 manifest
的扩展位置。后续可以加入标准 `SKILL.md` bundle 的双向适配、references/assets
逐项读取和用户授权后的脚本执行器，而无需重写键位绑定、catalog generation、Agent
发现协议或完整历史。
