# X-02 Stage Substrate 实施边界

Status: implementation checkpoint；等待 Pull Request 门禁，不是 FeatureStage、release、
measurement 或数据授权 authority。

本文记录工程计划 `X-02` 的可执行收口。权威顺序仍是已接受 ADR、Agent 架构与
[Agent 工程计划](agent-engineering-plan-v1.0.md)；本文只说明当前代码已经实现什么、明确
没有实现什么。它不能把
[Gate 0 派生状态报告](../generated/gate0-status-report.json)变成 runtime 输入，也不能替代
后续 phase 的 schema、认证或设备证据。

## 1. §31 术语裁决

工程计划 §31 原来的“三进程 watcher/store”是 X-02 的历史简写。在 X-02 中，它**只**指：

- 为 main、`:ime`、`:brain` 三种 consumer role 建模的 process-local safe holder；
- holder 冷启动即持有 `SCHEMA_ONLY` safety ceiling；
- holder 只接受 closed rejected input，并在进程堆内更新 immutable safe view；
- 使用不逃逸的 `AtomicReference` 证明并发读取只能观察到完整的 fail-closed view；
- 使用纯内存测试模拟 role 隔离、进程重建和拒绝态转换。

这里没有 production watcher，也没有 cross-process store。X-02 不读取文件、不监听 slot、
不发布 snapshot、不比较 generation，也不把任何输入接受成 higher-stage authority。

production `FeatureStageSnapshotV1` 的 exact wire、static receipt、认证与 freshness authority
由 `R-02S` 冻结；A/B store、publisher、file/transition watcher 与 evaluator 由
`M9A-02S` 实现。在这两个工作包完成并通过各自门禁前，不能用临时 JSON、Preferences、
Room、文件或“仅 synthetic”目录填补空位。

## 2. 已交付的 SCHEMA_ONLY substrate

### 2.1 `memory-protocol`

当前纯 Kotlin/JVM substrate 包含：

- `FeatureStageV1` 的显式全序：
  `OFF < SCHEMA_ONLY < DARK < SHADOW < CANARY < DEFAULT`；
- 与 stage 不相交的 `GateVerdictV1`、`PermitDecisionV1`、
  `ProfileExecutionClassV1` 和 `NormalProfileCapabilityIdV1` closed domains；
- ADR 0018 的 exact 45-entry `GateIdV1` registry 与六种 exact scope；
- 不使用 enum ordinal 的 `FeatureStageOrderV1`；
- X-02 专属 hard-cap reducer：只有 `SCHEMA_CODEC + SCHEMA_ONLY +
  ProfileExecutionClassV1.SCHEMA_ONLY + empty gate set` 是有效 schema request；
- `OFF` 无条件保持 `OFF`，其余非法、额外或重复 gate 输入 fail closed；
- 对 request、build、local、dependency 与 X-02 hard maximum 同时取安全上界；
- 只表达 safety ceiling、不能 mint operation/effect token 的 X-02 decision；
- `ABSENT | CORRUPT | UNKNOWN | UNAUTHENTICATED` 四种 closed rejected input；
- 永远只返回 `normalStageCeiling=SCHEMA_ONLY` 的 immutable safe view；
- main、IME、Brain 三种 role 的 process-local `AtomicReference` holder。

X-02 decision、observation、safe view 与 holder 都使用 `X02` 前缀，避免提前冻结 ADR 0018
未来 `NormalStageDecisionV1` 或 `FeatureStageSnapshotV1` 的 ABI。当前代码没有
`ProfileContextDigestV1`、完整 capability DAG、authenticated receipt 或 operation
admission；这些缺失不能被解释为一个较宽松的实现。

### 2.2 test-only in-memory channel

`X02TestOnlyByteArrayChannel` 只存在于 `memory-protocol/src/test`：

- send 与 receive 都 defensive copy；
- 有显式测试上限；
- 可输送 arbitrary synthetic fault bytes；
- 不定义 snapshot framing、version、generation、digest、slot 或 parser；
- 不创建 OS pipe、FD、PFD、目录或文件。

它是 ByteArray/in-memory harness，不是 Memory transport 或 snapshot codec。

### 2.3 `event-journal`

`event-journal` 当前只提供 `X02EventJournalScaffoldV1`：

- availability 恰为 `SCHEMA_ONLY_NO_STORAGE`；
- normal stage ceiling 恰为 `SCHEMA_ONLY`；
- public API 没有 payload、record、append、read、recall、ack、writer、reader 或 path 输入；
- 模块没有 Android adapter、storage dependency 或持久化入口。

`common.proto`、`journal.proto`、`session.proto`、physical frame codec、validator 与 reserved
field registry 仍属于 `M9A-01`，不能把这个 scaffold 描述成 Journal 已实现。

## 3. 明确未交付

X-02 当前明确不包含：

- production Snapshot schema、bytes、parser、authentication envelope、receipt 或 accepted
  candidate；
- snapshot generation、previous digest、A/B slot、anti-replay 或 freshness；
- Android snapshot store、publisher、file watcher、broadcast receiver 或后台任务；
- Memory root、lab root、临时 persistent root、目录或文件；
- Keystore alias、Keyring、Journal、Blob、Room、FTS、index 或 WorkManager；
- Binder Service、MemoryBroker 注册或三进程 IPC；
- 输入框、Provider transcript、用户正文或任何真实用户 plaintext；
- capture、recall、maintenance、rotation、export、Tool 或 Skill effect；
- `DARK`、`SHADOW`、`CANARY`、`DEFAULT` 激活；
- synthetic measurement permit 或任何 operational gate PASS。

因此这一 checkpoint 不改变 APK 行为，不产生用户数据，也不对外宣称 Memory 已保存。

## 4. 测试与机械门禁

本 checkpoint 的测试契约包括：

- 34,992 个 typed stage 输入组合，证明输出不超过 requested、build、local、dependency 与
  `SCHEMA_ONLY`；
- `OFF`、`SCHEMA_CODEC`、persistent capability、execution class、unique extra gate、
  duplicate gate 与全部 GateVerdict 的正负矩阵；
- exact 45 GateId、六种 scope、unknown token 与 defensive registry 查询；
- 四 rejected input × 全 safe starting cause；
- 三 role 冷启动、隔离、重建与并发 reader/writer；
- safe view/decision 私有构造、defensive reasons 与 forged decision invariant；
- test-only ByteArray aliasing、边界、clear 与 arbitrary-fault tests；
- event-journal scaffold 的 exact zero-input public API。

Pull Request 必须运行，且只有全部成功才可合并：

```bash
python3 tools/test_check_gate0_contract.py
python3 tools/check_gate0_contract.py --check
python3 tools/test_check_x02_boundaries.py
python3 tools/check_x02_boundaries.py

./gradlew \
  :memory-protocol:test \
  :memory-protocol:jar \
  :event-journal:test \
  :event-journal:jar \
  :app:assembleDebug

python3 tools/check_x02_boundaries.py --check-artifacts
```

X-02 boundary checker 必须 fail closed 地检查 production source、Gradle dependency、Android
Manifest/component、禁止 API/路径与最终 JAR 内容；test-only ByteArray harness 不得进入
production JAR。现阶段这些是**待 Pull Request 执行的门禁**，本文不宣称 GitHub Actions、
Lint、APK 或发布门禁已经通过。

## 5. Checkpoint 退出条件

只有同时满足以下条件，第三个 X-02 checkpoint 才可合并：

1. Gate 0 checker 仍生成 non-authoritative、45 gate 全 `BLOCKED`、
   effective stage 恰为 `SCHEMA_ONLY` 的报告；
2. 两个新模块的测试与 JAR boundary 检查通过；
3. production source/JAR 中没有 test harness、Android storage、Keystore、Room、WorkManager、
   file/FD/PFD/pipe、Provider/input 或 Binder side effect；
4. app、`:ime`、`:brain` 没有新增 runtime dependency、component 或启动行为；
5. 没有 production Snapshot wire、认证、generation、A/B store 或 file watcher；
6. 没有 Memory root、Key、目录、文件、用户数据或 `DARK+`；
7. 提交只包含审阅过的目标文件，不包含 `gradlew.bat` 等无关行尾变化。

该 checkpoint 通过后，X-02 仍只是 fail-closed stage substrate。下一步必须继续按照
`R-02S`、`M9A-01`、`M9A-02S` 的依赖顺序推进，不能直接打开 normal `DARK`。
