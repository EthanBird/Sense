# ADR 0016：M9 Memory Wire 与 Durability

**状态：** Accepted for Gate 0 decision；implementation evidence pending<br>
**决策日期：** 2026-07-26<br>
**适用版本：** `sense.memory.v1` / physical format `1.0`<br>
**上位设计：** [`agent-event-memory-architecture-v1.0.md`](../design/agent-event-memory-architecture-v1.0.md)<br>
**相关决策：**

- [`0015-release-identity-and-data-continuity.md`](0015-release-identity-and-data-continuity.md)
- [`0017-m9-memory-security-and-erasure.md`](0017-m9-memory-security-and-erasure.md)
- [`0018-m9-memory-budget.md`](0018-m9-memory-budget.md)

> 本 ADR 接受的是 Gate 0 的协议与持久性决定，不是运行时实现通过声明。当前仓库没有
> Memory writer、Blob store、Journal reader 或用户数据迁移实现；本文的测试矩阵均是
> 后续 M9A 的退出条件，不得写成“已通过”。

---

## 1. 决策摘要

M9.0 采用以下不可分割的基线：

1. 长期 Proto namespace 是 `sense.memory.v1`；Proto 的 `fixed32` / `fixed64` 按
   Protobuf 规范使用 little-endian，Journal、frontier、footer 和 recovery sidecar
   的物理整数一律 big-endian。两种字节序不得由同一个未标注 helper 隐式切换。
2. M9.0 Proto feature registry 为空，故每个 `required_features` 必须为 `0`。
3. `BlobRefV1` 恰好只有五个字段：随机逻辑 Blob ID、明文长度、内容类型、明文
   SHA-256 和 required features。物理文件 ID、key generation、chunking 参数及 locator
   generation 不得泄漏进逻辑引用。
4. producer admission、Journal append 与 durable acknowledgement 是三个不同事实。
   writer 仅在 dequeue 后分配 sequence；拒绝的 attempt 不制造 sequence 空洞。
5. `DurableAck` 的证明顺序固定为：

   ```text
   referenced Blob physical commit
     → Blob locator 两个 ACTIVE 槽收敛
     → Journal frame writeFully
     → Journal data force
     → new frontier first-slot write/fsync/close/reopen adjacent-pair verification
     → exact same new frontier peer mirror/fsync/close/reopen
     → 全量复读 A/B 并验证 same-generation/exact-bytes
     → callback
   ```

6. `DurableAckObserved ⇒ FrontierCommitted`，反向不成立。callback 丢失不能证明 record
   未落盘。
7. retry 只允许复用仍在内存中保留的、逐字节相同的 `ProducerAppendAttemptV1`。
   callback 丢失后只能以仍保留的 exact record ID+commitment 做 reconciliation；
   producer exact bytes/identity 丢失后禁止按“相同语义”重建 attempt 并盲重试，只能在
   新 record 中诚实记录无法定位的 gap。
8. 一旦 write/force/frontier 结果不确定，原 epoch、segment、DEK 和已分配 nonce ordinal
   永久只读。恢复不 truncate、不续写、不向旧段追加 footer。
9. 正常 seal 只向 bootstrap 时已经发布的 `segment` 追加固定 footer，并推进已有 A/B
   frontier；不创建 `sealed/` 目录、不 rename segment、不写 current pointer 或 catalog。
10. orphan recovery 只发布确定性、不可覆盖的 `RecoveredSealV1` sidecar；之后由全新
    writer epoch 记录 gap。
11. X02 只允许 `SCHEMA_ONLY` scaffolding 与 in-memory synthetic codec/fuzz。真实数据路径至少同时依赖
    `ReleaseOwnerContinuityGateV1`、独立 writer ownership/storage/crypto subgate、
    CapturePolicy/consent、backup exclusion、
    `LocalErasureControlPhaseGateV1`、`LocalErasureCapabilityGateV1`、
    stage-revocation freshness 和 budget gate。

---

## 2. 问题与承诺边界

输入法跨 `main`、`:ime`、`:brain` 三个进程工作。Binder callback、进程生命期和文件系统
durability 之间没有天然事务。下面三句话也不是同义词：

- “queue 接受了 record”；
- “某个 frame 的完整 bytes 可以从页缓存读回”；
- “调用方观察到该 record 已进入已认证 durable frontier”。

若把三者折叠为一个 boolean，至少会产生四类不可恢复错误：

- queue 满时先分配 sequence，制造不存在的历史空洞；
- callback 前进程死亡，调用方用新 ID 重试，得到重复事实；
- frame bytes 完整但 frontier 未提交，reader 把 volatile tail 当作 durable；
- `force()` 或 slot write 的结果不确定后继续复用 `(DEK, sequence)`，破坏 AEAD nonce
  唯一性。

M9.0 的可对外承诺严格限定为：

> 对已捕获、调用方已观察 `JournalAckStatusV1.DURABLE`、仍在 retention 内、当前
> CapturePolicy 允许读取且其依赖 Blob locator 仍收敛的单 writer local evidence，
> reader 可以按原始 payload bytes 精确读取，并可以证明它处于某个已认证 frontier 内。

它不承诺：

- `VOLATILE_ACCEPTED` 后进程死亡仍然无遗漏；
- 全部历史永久驻留本机；
- A/B 两槽被一起回滚到一对旧而自洽的 bytes 时可以仅靠本地文件检测；
- 不受信任的同 UID 代码无法访问同 UID 数据；
- fsync 能证明 NAND 介质已物理清零；
- M9.1 Event、Relation、Recall Receipt 或 M9.2 Claim 的语义已经冻结；
- 多 writer 的全局顺序可以由墙上时间推断。

---

## 3. 规范用语、字节序与基础类型

本文的 **MUST / MUST NOT / SHOULD** 是实现与测试门禁，不是建议。

### 3.1 整数

| 域 | 编码 |
|---|---|
| Proto `fixed32` / `sfixed32` / `float` | 4 bytes little-endian |
| Proto `fixed64` / `sfixed64` / `double` | 8 bytes little-endian |
| Proto enum / ordinary integer | 标准 varint |
| 本 ADR 的物理 header/slot/footer/sidecar 整数 | unsigned big-endian |
| 长度算术 | checked unsigned 64-bit；转换到 JVM `Int` 前再次检查 |

所有物理 reader 必须先用固定小缓冲读取长度字段，完成 magic/version、上限和
`offset + length` 溢出检查后才允许按声明长度分配。

### 3.2 canonical ASCII

本文的 `canonical_ascii` 是逐字节约束，不是 Unicode 文本规范：

```text
每个 byte ∈ [0x21, 0x7e]
```

因此禁止空格、换行、NUL、非 ASCII、case folding、Unicode normalization 和首尾修剪。
每个字段另有更窄语法时必须同时满足更窄语法。

### 3.3 ID

`id128`：

- 由 CSPRNG 生成 16 bytes；
- 全零值禁止；
- Proto/API canonical form 是恰好 32 个 lowercase hex ASCII：
  `[0-9a-f]{32}`；
- 物理 fixed layout 保存对应 16 raw bytes；
- parser 不接受大写、连字符、UUID 花括号、前缀或自动修复。

ID 不编码时间。M9.0 使用：

| ID | 含义 |
|---|---|
| `installation_id` | 首次 Keyring 空根 bootstrap 时生成；来源与稳定性由 ADR 0017 冻结 |
| `record_id` | 一次已授权 capture record；相同内容再次发生仍是新 ID |
| `session_id` | Sense 本地 session；不是 Provider conversation ID |
| `run_id` | 对现有 Agent request/run 的本地映射 |
| `turn_id` | session 内逻辑 turn |
| `segment_id` | 一个物理 Journal segment |
| `writer_epoch` | writer 每次取得新可写 epoch 时生成 |
| `key_generation_id` | ADR 0017 Keyring generation 的稳定 ID |
| `blob_id` | 随机逻辑 Blob ID；不是内容 hash，也不是物理文件名 |

`event_id` 不属于 M9.0 Session wire。M9.1 Event 必须分配独立 CSPRNG id128，并以
provenance 显式关联被验证的 `record_id`；不得从 record ID 确定性派生、复用或把
`record_id` 静默改名为 Event，也不得为历史 record 伪造发生事实。

### 3.4 digest

除非显式写明其他算法，本文所有 digest 是 raw 32-byte SHA-256。digest 总是对规范指定的
原始 bytes 计算；Protobuf 不是 canonical encoding，禁止 parse 后重新 serialize 再算
历史 payload digest。

### 3.5 时间

M9.0 wire 不以墙上时间排序。时间将来只能是 record payload 或 M9.1 Event 的一个有来源、
精度和不确定性的属性。单 writer 顺序只由：

```text
(writer_epoch lineage, writer_sequence)
```

证明。若无法建立 epoch lineage，reader 返回 gap，不用时间猜测先后。

---

## 4. 版本、feature 与兼容规则

### 4.1 四个独立版本域

实现必须分别记录：

1. physical format major/minor；
2. `sense.memory` protocol major/minor；
3. payload schema major/minor；
4. producer/reducer/registry build digest。

不得用一个 `version` 同时代表以上四者。

M9.0 固定：

```text
physical format = 1.0
protocol         = 1.0
session schema   = 1.0
required_features = 0
```

### 4.2 feature bit

`required_features` 是 unsigned 64-bit bitset。M9.0 registry 为空，故：

- writer 只能写 `0`；
- reader 遇到任何非零 bit fail closed；
- bit 一经分配永不改义；
- optional hint 不能偷用 required bit；
- 后续分配必须先修改 ADR、descriptor digest registry 和 N-1 fixture。

### 4.3 major/minor

- 未知 physical major：不解密、不 upcast；只允许 opaque quarantine/export。
- 已知 major、较高 physical minor：只有新增区域被旧版明确标为 reserved 且
  `required_features` 可理解时才可读；否则 fail closed。
- 未知 protocol/payload major：保留原始 frame bytes，不产生语义 view。
- 较高 minor 的可忽略字段：reader 可以生成旧 view，但必须在 transparent
  parse/serialize 路径保留 unknown fields。
- unknown enum numeric value 一律不是默认值；validator fail closed。
- field number 和 enum number 永不复用；删除项必须 `reserved`。
- upcaster 是纯函数，只生成读取 view；不得覆写历史 frame 或 payload bytes。
- JSON round-trip、开放 `oneof` 和 map iteration order 不构成 unknown-field preservation。

M9A-01 必须将每个 `.proto` 的 normalized descriptor set SHA-256 固定进仓库。descriptor
改变但没有显式 schema review 时 CI 失败。

---

## 5. M9.0 Proto registry

下面是字段号权威表。实际 `.proto` 必须逐项等价，不得增加未进入本 ADR 的字段。

### 5.1 enum registry

Proto3 的 numeric `0` 只作 `UNSPECIFIED`，validator 永远拒绝。

```text
WriterKindV1
  0 UNSPECIFIED
  1 IME
  2 BRAIN
  3 MAIN

RecordClassV1
  0 UNSPECIFIED
  1 EVIDENCE
  2 CONTROL
  3 GAP

DurabilityClassV1
  0 UNSPECIFIED
  1 BEST_EFFORT
  2 DURABLE_REQUIRED

AdmissionStatusV1
  0 UNSPECIFIED
  1 ACCEPTED_VOLATILE
  2 REJECTED_BACKPRESSURE
  3 STORAGE_UNAVAILABLE

JournalAckStatusV1
  0 UNSPECIFIED
  1 DURABLE
  2 FAILED
  3 INDETERMINATE

JournalAckReasonV1
    0 UNSPECIFIED
    1 PROVEN_EXCLUDED_FROM_SELECTED_FRONTIER
    2 RECORD_ID_CONFLICT_PROVEN_NOT_APPENDED
  100 FRONTIER_DURABILITY_OUTCOME_UNKNOWN
  101 RECOVERY_CANDIDATE_NOT_YET_RESOLVED
  102 CALLBACK_OUTCOME_LOST_RECONCILIATION_REQUIRED
```

以上 registries 恰好只包含所列 numeric value。新增 enum 值需新 protocol minor；
旧 reader 对 unknown numeric value fail closed，同时保留原始 frame bytes供 opaque
诊断/导出。不得把 unknown reason 映射为 `UNSPECIFIED`。

### 5.2 `WriterRefV1`

```proto
message WriterRefV1 {
  string installation_id = 1; // id128
  WriterKindV1 writer_kind = 2;
  string writer_epoch = 3;    // id128
  reserved 4 to 15;
}
```

约束：

- 三个字段必须存在且 canonical；
- 不接受自由文本 `writer_id`；
- writer kind 与固定目录 token 的映射唯一：
  `IME → ime`、`BRAIN → brain`、`MAIN → main`；
- `installation_id` 必须来自已选择的 ADR 0017 Keyring A/B payload，不得从
  SharedPreferences 或目录缺失时重新生成；
- `writer_epoch` 与当前 segment header 逐字节相等。

### 5.3 `BlobRefV1`

```proto
message BlobRefV1 {
  string blob_id = 1;            // id128；随机逻辑 ID
  fixed64 plaintext_length = 2;  // little-endian in Proto
  string content_type = 3;       // canonical_ascii，0..126 bytes；空值表示 opaque
  bytes plaintext_digest = 4;    // exactly 32 bytes
  fixed64 required_features = 5; // M9.0 MUST be 0
  reserved 6 to 15;
}
```

五个字段均为 `blob_ref_commitment` 的组成部分。commitment 由 ADR 0017 固定，至少保证
以上五项逐字节绑定。以下字段明确禁止进入 `BlobRefV1`：

- physical storage ID / filename；
- key alias / key generation；
- locator generation；
- chunk size / count / chunking version；
- compression/cipher suite；
- creation time、app package、Provider 或用户叙事。

`content_type`、plaintext digest 与 ADR 0017 加密 `BlobWrapPayloadV1` 解密后的对应字段
必须逐字节相同。clear physical header/footer/locator 不得复制二者或 public unkeyed
commitment；它们只携带 purpose-9 keyed metadata commitment。
M9.0 `plaintext_length` 上限及物理文件上限由 ADR 0017 的
`ADR0017_BLOB_CAPS_V1` 导入；validator 不能自行使用更宽上限。

### 5.4 `SessionRecordV1`

```proto
message SessionRecordV1 {
  fixed32 protocol_major = 1;          // 1
  fixed32 protocol_minor = 2;          // 0
  fixed64 required_features = 3;       // 0
  string record_id = 4;                // id128
  string session_id = 5;               // id128
  RecordClassV1 record_class = 6;
  string payload_type = 7;             // canonical_ascii，1..64 bytes
  fixed32 payload_schema_major = 8;
  fixed32 payload_schema_minor = 9;
  optional string run_id = 10;          // id128
  optional string turn_id = 11;         // id128
  optional bytes inline_payload = 12;
  optional BlobRefV1 payload_blob = 13;
  bytes payload_digest = 14;            // exactly 32 bytes
  bytes capture_policy_digest = 15;     // exactly 32 bytes
  fixed64 record_flags = 16;            // M9.0 MUST be 0
  repeated string causal_predecessor_record_id = 17;
  optional string correlation_id = 18;  // id128
  optional string branch_id = 19;       // id128
  reserved 20 to 63;
}
```

验证规则：

- `record_id` 在 `(installation_id, writer_kind)` 范围内唯一；
- `session_id` 是 Sense 本地 ID，不能直接保存 Provider conversation ID；
- `payload_schema_major ≥ 1`；M9.0 reader 只解释注册过的 major；
- `inline_payload` 与 `payload_blob` 恰好存在一个；
- inline payload 长度为 `0..1,048,576` bytes
  (`MAX_INLINE_RECORD_BYTES_V1 = 1 MiB`)；零长度仍须显式 present；
- Blob payload 的 `payload_digest == payload_blob.plaintext_digest`；
- inline payload 的 `payload_digest == SHA-256(exact inline_payload bytes)`；
- `capture_policy_digest` 是做出最终 ALLOW 的不可变 policy artifact digest；它不是 consent
  本身，也不能替代 runtime gate；
- `causal_predecessor_record_id` 最多 8 个，均 canonical、非自身、无重复，并按 raw 16-byte
  lexicographic ascending 编码；M9.0 只允许引用同一
  `(installation_id, writer_kind)` 中已经进入 selected durable frontier 的较早 record，
  不允许用 bare ID 表达 cross-writer predecessor。跨 writer 关系留给带完整 provenance 的
  M9.1 Event/Relation；它表达已知因果依赖，不创造全局时间线；
- optional ID 不得以空 string 表示 absent；
- M9.0 `record_flags=0`。

`payload_type` 采用版本化 closed registry，而不是把 Event kind 提前冻结进 Gate 0。
protocol minor 0 的 registry **为空**：Gate 0/M9A-01 只能验证 schema/opaque bytes，不能
生成任何语义 `SessionRecordV1`。未知/未注册 `payload_type` 的 frame 仍可做物理恢复和
opaque export，但不能进入被声称完整的语义 Session view。首批合法 token、对应 payload
Proto/schema digest、presence rules 与 golden 必须由 M9A-05 提升 protocol minor 后冻结；
在此之前 semantic recorder gate BLOCKED。

### 5.5 `ProducerAppendAttemptV1`

```proto
message ProducerAppendAttemptV1 {
  fixed32 protocol_major = 1;       // 1
  fixed32 protocol_minor = 2;       // 0
  fixed64 required_features = 3;    // 0
  string record_id = 4;             // must equal decoded record
  DurabilityClassV1 durability = 5;
  bytes exact_record_bytes = 6;     // exact serialized SessionRecordV1 bytes
  bytes record_bytes_digest = 7;    // SHA-256(exact_record_bytes)
  bytes capture_policy_digest = 8;  // must equal decoded record
  bytes record_commitment = 9;      // exactly 32；must match formula below
  fixed64 attempt_flags = 10;       // M9.0 MUST be 0
  reserved 11 to 31;
}
```

此 message 是 process-local admission contract，不以它的 Proto serialization 作为
canonical identity。producer 必须保留原始 `exact_record_bytes` ByteArray，不得在 retry
前 parse/serialize。

`record_commitment` 的唯一计算为：

```text
SHA-256(
  ASCII("sense.memory.v1/record-commitment/v1") ||
  0x00 ||
  raw_record_id_16 ||
  durability_u32_be ||
  exact_record_length_u32_be ||
  exact_record_bytes ||
  record_bytes_digest_32 ||
  capture_policy_digest_32
)
```

`exact_record_length` 必须不超过由 M9.0 descriptor、各字段 cap 和
`MAX_INLINE_RECORD_BYTES_V1` 机械计算的 `MAX_SERIALIZED_SESSION_RECORD_V1_BYTES`。
writer 还必须在 admission 前用将采用的 `WriterRefV1` 和 fixed-width sequence 机械计算
最终 `JournalFramePayloadV1`，证明它不超过同样由 descriptor 推导的
`MAX_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES`。这两个上限不是手填预算数字；CI 从
冻结 descriptor 与本 ADR field caps 生成并 pin golden，checked u64 后证明结果可放进
physical u32 length。任一字段不 canonical、digest 不匹配、record 内
`record_id`/policy digest 不匹配或最终长度证明失败，均在 queue admission 前拒绝。

### 5.6 canonical `ack_token`

`ack_token` 是 live writer 为一次 `DURABLE_REQUIRED` admission 生成的 CSPRNG id128：

- Proto/API 中恰好为 32 lowercase hex；
- raw identity 恰好 16 bytes、禁止全零；
- 只用于当前 writer lifetime 内把 Ack callback 与 admission waiter 关联；
- 不写入 Journal frame、SessionRecord、frontier、Room、Blob、文件名、日志或恢复 sidecar；
- 不是 record identity、dedup key、capability、secret 或 durable recovery locator；
- writer 终止后 token 失效；recovery 不得从 record bytes“重建”同 token；
- retry/reconciliation 创建新的 live admission 时使用新的 token。

同一 writer lifetime 内，每个 accepted durable token **至多**交付一个 terminal
`DURABLE | FAILED | INDETERMINATE`；只要 callback channel 与 writer lifetime 都存活，
writer 必须最终交付恰好一个。writer/Binder 在 dispatch 前死亡时允许零次交付，此时
producer 侧仍存活的 volatile waiter/process-epoch barrier 将该 attempt 标为
`CALLBACK_LOST`；这不是持久化 per-token ledger。原 token 随 lifetime 失效，只能以 exact
record/commitment reconciliation 创建新 token。若 producer 也死亡，恢复端只能看到 retained
attempt/record authority，不能声称找回旧 token 或其 callback 结果。terminal dispatch 后从
live waiter map 删除，迟到/重复 callback 只记录 protocol fault，不再次交付。

因此 callback/token 丢失后只能以调用方仍保留的 exact
`(record_id, record_commitment, ProducerAppendAttempt bytes)` 做恢复核验。不能对 token
做 hash 派生、截断、Base64/UUID 改写或跨 writer 复用。

相同 `(installation_id, writer_kind, record_id)` 对应不同 `record_commitment` 是永久
conflict，不得择一覆盖。

### 5.7 `JournalFramePayloadV1`

```proto
message JournalFramePayloadV1 {
  fixed32 protocol_major = 1;       // 1
  fixed32 protocol_minor = 2;       // 0
  fixed64 required_features = 3;    // 0
  WriterRefV1 writer = 4;
  fixed64 writer_sequence = 5;
  SessionRecordV1 record = 6;
  bytes record_commitment = 7;      // exactly 32
  DurabilityClassV1 durability = 8;
  reserved 9 to 31;
}
```

约束：

- `writer_sequence ≥ 1`；
- payload writer/sequence/record ID/commitment 必须与 physical segment/frame header 相等；
- 两种 durability 都不得把 live `ack_token` 持久化；
- physical frame flags bit 0 当且仅当 durability=`DURABLE_REQUIRED`；
- decoded record 的 exact source bytes 必须等于 attempt 保留的 bytes；writer 不做
  semantic reserialization；
- payload plaintext 总长度为
  `1..MAX_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES`；oversize body 必须先走 ADR 0017
  Blob，再让 SessionRecord 使用五字段 BlobRef。

为使“exact source bytes”在所有 Proto runtime 上唯一，M9.0 writer 对 outer
`JournalFramePayloadV1` 使用 closed raw-wire profile，而不是把 nested message 交给 builder
merge 后再序列化：

- field 6 的 tag 必须恰好出现一次，wire type 必须是 length-delimited；
- field 6 length 使用最短 uvarint，紧随其后的 payload slice 必须逐字节等于
  `ProducerAppendAttemptV1.exact_record_bytes`；
- reader 在任何 semantic Proto parse 前 raw-scan outer payload，拒绝重复 field 6、错误
  wire type、non-minimal/truncated/overflow length、越界 slice与 reserved field 9..31；
  field number ≥32 只有在 §4.3 minor/required-feature 规则判定为 ignorable 时才 bounds-skip
  并保留于原 outer plaintext bytes，否则 fail closed；
- semantic parser 只能消费已通过 raw validator 的同一 byte array，并验证得到的 record 与
  raw slice 一致；不得依赖 Proto 对 repeated singular message 的 merge 行为；
- golden/fuzz fixtures 必须覆盖 duplicated field 6、分片式 duplicate nested record、
  non-minimal length、reserved field、可忽略与不可忽略 unknown outer field，以及
  parse→reserialize 改写。

其它已知 outer field 继续服从 §4 的 Proto/validator 规则；本段只冻结
`JournalFrameRecordEmbeddingProfileV1` 的 exact nested-record embedding，不暗中建立第二套
完整 Proto canonicalization。在该 raw validator/descriptor/golden 未落地前，
`WireCompatibilityGateV1` 不得 PASS。此约束不把
`ProducerAppendAttemptV1` 自身的 serialization 升格为 canonical identity。

### 5.8 `DurableFrontierRefV1`

```proto
message DurableFrontierRefV1 {
  string segment_id = 1;             // id128
  WriterRefV1 writer = 2;
  string key_generation_id = 3;      // id128
  fixed64 writer_sequence = 4;
  fixed64 byte_offset = 5;
  bytes prefix_digest = 6;            // exactly 32
  fixed64 frontier_generation = 7;
  fixed64 frame_count = 8;
  reserved 9 to 31;
}
```

Ack 中的 frontier 必须满足：

- `writer_sequence ≥ 1` 且等于被 Ack record 的 sequence 或更大；
- `frame_count ≥ writer_sequence` 不成立为一般规则；sequence 与 frame count 是独立
  验证量。M9.0 每 epoch 从 sequence 1 开始且不跳号，因此已选 frontier 中二者必须相等；
- `byte_offset` 精确落在完整 frame 尾，不能指向 footer 中部；
- `prefix_digest == SHA-256(segment[0, byte_offset))`；
- 与关闭后复读选出的 physical frontier slot 所有身份字段相等；
- 不返回 bootstrap empty frontier。

### 5.9 admission

```proto
message AppendAdmissionV1 {
  fixed32 protocol_major = 1;
  fixed32 protocol_minor = 2;
  fixed64 required_features = 3;
  AdmissionStatusV1 status = 4;
  DurabilityClassV1 durability = 5;
  optional string record_id = 6;
  optional string ack_token = 7; // canonical id128；live correlation only
  reserved 8 to 31;
}
```

严格 presence matrix：

| status | durability | record_id | ack_token |
|---|---|---:|---:|
| `ACCEPTED_VOLATILE` | `BEST_EFFORT` | 必须 | absent |
| `ACCEPTED_VOLATILE` | `DURABLE_REQUIRED` | 必须 | 必须 |
| `REJECTED_BACKPRESSURE` | 任一已知 class | absent | absent |
| `STORAGE_UNAVAILABLE` | 任一已知 class | absent | absent |

registry 不提供额外的 durable-fast-path、policy/feature/error-detail admission status。
Policy、feature、erasure、owner 与 Blob prerequisite 必须在进入 admission contract 前 fail closed；若
writer 已接收 reconciliation request，即使它随后命中已有 durable record，admission
仍是 `ACCEPTED_VOLATILE`，然后用本次新 live token 返回 Ack。

rejected response 不能回显未被接受的 record ID。`UNSPECIFIED`、unknown status、
unknown durability 或任何其他 presence 组合均为 invalid wire。

### 5.10 acknowledgement

```proto
message JournalAckV1 {
  fixed32 protocol_major = 1;
  fixed32 protocol_minor = 2;
  fixed64 required_features = 3;
  JournalAckStatusV1 status = 4;
  string ack_token = 5; // canonical id128；live correlation only
  string record_id = 6;
  optional fixed64 writer_sequence = 7;
  optional DurableFrontierRefV1 frontier = 8;
  optional JournalAckReasonV1 reason = 9;
  optional string failure_detail_uri = 10;
  optional bytes failure_detail = 11;
  bytes record_commitment = 12; // exactly 32
  reserved 13 to 31;
}
```

严格 presence matrix：

| status | token / record / commitment | sequence | frontier | reason | detail URI/bytes |
|---|---|---:|---:|---|---|
| `DURABLE` | 必须 | 必须 | 必须 | absent | 两者 absent |
| `FAILED` | 必须 | absent | absent | 只能 `1` 或 `2` | M9.0 两者必须 absent |
| `INDETERMINATE` | 必须 | absent | absent | 只能 `100`、`101` 或 `102` | M9.0 两者必须 absent |

`UNSPECIFIED(0)` 不得在 wire 上显式生成；reason 的 exact 语义：

| code | status | 证明 |
|---:|---|---|
| 1 `PROVEN_EXCLUDED_FROM_SELECTED_FRONTIER` | `FAILED` | 相关已验证 lineage/selected frontier 已证明 exact `(record_id, record_commitment)` 不在 durable prefix |
| 2 `RECORD_ID_CONFLICT_PROVEN_NOT_APPENDED` | `FAILED` | 已有相同 record ID/不同 commitment；本次 exact commitment 已证明没有 append |
| 100 `FRONTIER_DURABILITY_OUTCOME_UNKNOWN` | `INDETERMINATE` | data/frontier write、force、fsync 或 reopen 后无法证明目标 frontier |
| 101 `RECOVERY_CANDIDATE_NOT_YET_RESOLVED` | `INDETERMINATE` | recovery 尚未唯一选择 candidate/sidecar |
| 102 `CALLBACK_OUTCOME_LOST_RECONCILIATION_REQUIRED` | `INDETERMINATE` | callback/Binder 结果丢失，必须用 exact record identity+commitment 重新核验 |

`FAILED` 的充分必要条件是 exact `(record_id, record_commitment)` 已证明被排除；“write 调用
抛错”“没有收到 callback”“当前进程内存没看到 slot”都不是该证明。任何 callback/recovery
不确定性必须返回 `INDETERMINATE`。一旦开始向 segment 发出任何 write，后续 write 异常、
force 异常、frontier write/fsync/reopen 失败均至少为 reason 100，并永久退休 segment。

M9.0 failure-detail URI registry 为空，所以 writer 必须让 fields 10/11 都 absent；minor-0
validator 看到任一 present 都判 Ack semantic invalid/fail closed，同时保留 enclosing raw
bytes供 opaque recovery/export。future protocol minor 可以登记 exact URI、bytes schema、
caps、敏感信息禁令与 golden；旧 reader 对其只 opaque preserve，不能解释、显示或用于 retry
决策。reason code 才决定 status 语义，future detail 也不得扩大权限或把
INDETERMINATE 降为 FAILED。

整个 `BEST_EFFORT` 分支不存在 `JournalAckV1`。收到任何 best-effort Ack 都是 protocol
violation。每个合法 Ack 的 `record_commitment` 必须逐字节等于 waiter 保留的
`ProducerAppendAttemptV1.record_commitment`；`DURABLE` 时还必须等于 selected frontier
内对应 physical frame 与 decrypted payload 的 commitment。Ack 的 `ack_token` 必须等于
本次 live `AppendAdmissionV1` 返回的 token；不得接受旧 admission、另一个 waiter 或恢复
扫描生成的 token。

---

## 6. Admission、sequence、dedup 与 retry

### 6.1 处理顺序

```text
metadata-only signal
  → 验证 FeatureStage / ReleaseOwner / local-erasure /
    storage-root / keyring / crypto / writer-ownership gates
  → CapturePolicy preflight
  → 选择 sealed AppendMaterializationPlanV1(INLINE | BLOB)
  → 取得 process/Broker 全局 plaintext read lease
  → 取得 conservative queue slot/byte reservation
  → BLOB plan另取得 BlobPlaintextReservation与 per-volume failure-contingency reservation
  → 至多一次 bounded body read，buffer ownership属于上述 typed reservations
  → CapturePolicy final decision
  → 复验全部 gates、generation、authorization token 与 erasure fence
  → final ALLOW原子 commit plan并把 read charge转入已持有的 typed reservations
  → 分配 record_id
  → BLOB plan分配 logical/physical IDs并立即 durable写 materialization/orphan intent
  → 若需 Blob，完成 ADR 0017 physical+locator durable publish
  → 构造 exact SessionRecord bytes
  → 构造并保留 ProducerAppendAttempt
  → 将 attempt commit 到同一 reservation（不得再次 backpressure）
  → AppendAdmission
  → writer dequeue
  → 查询 record-id / record-commitment dedup state
  → 分配 sequence
  → encode/encrypt/write
```

第一次 gate 验证和 preflight 前不得读取 body。preflight `ALLOW_TO_MATERIALIZE` 后、实际
读取前，必须取得无 ID、无持久副作用的 global plaintext read lease；已知 length 按
checked exact/worst-case expansion charge，unknown length 按本 source/protocol 最大允许
bytes/codepoints 全额 charge；并在调用 body builder前取得 conservative queue slot/byte
reservation。preflight必须选择不可变 `AppendMaterializationPlanV1`：只有能证明 bounded
result必可 inline才选 INLINE，不确定时保守选 BLOB；BLOB还必须先取得全额
`BlobPlaintextReservationV1` 与目标 volume的
`BlobFailureContingencyReservationV1`。后者同时预留 F019 live、F010/F012/F014成功目的与
F020/F051失败目的的 class headroom，以及 F029成功 live-count与 F052失败 orphan-count；
对应 actual+reserved均须在同一 coordinator内 checked/atomic满足 ADR 0018 §5.2。它不是
三份 physical allocation charge。
这些 reservation同时受 fixed slot count、max-total bytes、process/Broker aggregate 与有限
monotonic deadline约束；任一取不到就不调用 builder、不读 body，并返回 backpressure。
final `DENY`、取消、deadline或异常立即零化 materialized buffer并释放全部 reservation。
测试/实现不得用“每次 read 自己有上限”替代全局并发上限。

`DENY`、任一 gate blocked 或 final 后复验失败时，不得生成 record ID、Blob physical ID、
DEK、writer sequence、稀疏统计、队列 token 或持久化 bytes。final ALLOW 后，read charge
必须在固定锁序下原子转入**已经持有**的 queue/Blob reservations并 commit sealed plan；
不得临时重新申请、切换 INLINE/BLOB，或出现 plaintext 已无任何 downstream resource charge
的窗口。任何后续 byte handoff 仍按 §17 继续复验，不能把该 snapshot 当作永久授权。

queue reservation 同时受固定 slot 数与保守的 per-slot/max-total byte charge 约束；它只存在于
live writer lifetime，具有由 accepted BudgetProfile/measurement permit 预承诺的有限
monotonic deadline。reservation 不返回给不可信 producer，也不是 admission/ack token。
容量不足必须在任何 record/blob ID 或持久化 I/O 前返回
`REJECTED_BACKPRESSURE`；因此该 **pre-I/O backpressure rejection** 不得留下 durable
Blob/orphan。已取得 reservation 后的取消、gate 翻转或确定 I/O 失败可释放 live queue
slot，并以
rejected `AppendAdmissionV1(STORAGE_UNAVAILABLE)`（record/token 均 absent）结束；显式
取消可以只在 call layer 结束，但两者都不得产生 accepted admission。不确定物理 publish
仍按 §12 退休 ID/DEK；一旦 persistent Blob I/O 已开始，后述 durable capacity charge不得
随 live slot 释放。deadline、slot/byte charge 或 contingency上限未由对应 phase evidence
冻结时，只允许 codec/fake，不得启用 persistent runtime。

final ALLOW后可从 CSPRNG分配 record/logical/physical IDs；这些 ID一旦分配就永久 burn。
在任何 Blob DEK、Cipher或 physical/locator I/O 前，BLOB plan已持有的
queue/Blob/failure-contingency reservations必须与这些 exact IDs原子转换为 authenticated
durable materialization intent。它覆盖 logical/physical ID、worst-case bytes、locator
disposition、F019 live charge、F010/F012/F014 success headroom、F020 fallback与 cleanup state。
first physical allocation后 inode只计入 F019 `ACTIVE_DUPLICATE`；locator收敛且引用它的
Journal record获 DurableAck后原子 `F019→F010/F012/F014`、消费 F029 count并释放
F020/F051/F052 fallback；cancel、
deadline、gate flip、known ACTIVE但未被 durable引用、owner loss或 outcome indeterminate
原子 `F019→F020/F051`、消费 F052 count并释放 F012/F014/F029 success reservation。charge不能
随 live slot/process消失；F020只有在
unlink、parent fsync、close/reopen census与 terminal cleanup receipt全部 durable后释放。
任一目标 class/fallback cap耗尽时，新的 Blob
materialization 在生成 physical ID/DEK 前 fail closed。

该 authority 的 exact intent/state/receipt wire、crash reducer 与 caps 必须由
`BlobWireLocatorLeaseGateV1` phase evidence 冻结；只有 cross-writer/source provenance
场景才额外叠加 `SourceManifestPhaseGateV1`。当前尚未冻结，所以 persistent Blob path
保持 BLOCKED。实现不得只靠内存 queue、目录扫描或 BudgetProfile 中一个数字声称解决
已发布 orphan。

### 6.2 sequence

- 每个 writer epoch 从 `1` 开始严格递增；
- sequence 是 writer dequeue 后的本地分配，不由 producer 提供；
- rejected queue offer 不分配；
- last sequence 已为 `UINT64_MAX` 时禁止 checked `+1`；只有 I/O 状态确定时可正常 seal，
  后续 record 进入全新 epoch/DEK；
- encode/encrypt 前的确定失败可烧掉内存中的 tentative number，但 writer 必须退休当前
  epoch，不能在同 epoch 写出可观察 sequence gap；
- 开始 write 后任何不确定结果退休 epoch；
- reader 不接受重复、倒退或跳号；发现时按 gap/quarantine 处理。

### 6.3 dedup

dedup key：

```text
(installation_id, writer_kind, record_id)
```

dedup value：

```text
record_commitment
```

结果：

- typed `NEW_CAPTURE` 中，`record_id` 是本次 final `ALLOW`、queue reservation 成功后才由
  CSPRNG 新分配，且 complete selected-frontier scan 与 authenticated tombstone authority
  都无 gap/corruption 时，key absence 可作为 never-seen 继续 append；若极小概率命中已有
  key，当前 attempt 以冲突终止，只有重新进入完整 CapturePolicy/body/resource contract 的
  fresh capture 才可分配新 ID，writer/reconciliation 不得暗中换 ID；
- typed `RECONCILIATION` 中，只有调用方保留旧 exact attempt，且所有 relevant retained
  frontiers 与 authenticated tombstone authority 的 dedup coverage 完整时，key absence
  才能证明旧 attempt 尚未 durable 并允许 exact retry；coverage 不完整时为
  `INDETERMINATE`；
- key 存在且 commitment 相同、record 已在 selected frontier：不重复 append；本次 live
  reconciliation admission 仍返回 `ACCEPTED_VOLATILE`，随后用本次新 token 和已验证
  frontier 返回 `DURABLE`；
- key 存在且 commitment 相同、仍是同一 live volatile attempt：合并 waiter，不重复写
  frame；每个已接受 waiter 使用自己的 live token接收同一 terminal status；
- key 存在但 commitment 不同：只有证明本次 commitment 未 append 后，才可对本次
  waiter 返回 `FAILED + reason 2`；否则 `INDETERMINATE`。

Room/FTS 不是 dedup authority。内存索引可加速，冷启动结论必须从已认证 Journal prefix
重建。retention/compaction/erasure 若要移除 canonical frame，必须先由后续 phase ADR
冻结并 durable 写入 authenticated `RecordIdentityTombstone` authority，至少保留
`(installation_id, writer_kind, record_id, record_commitment, terminal disposition)`，
使被删除 record 不能被旧 attempt 复活。`NEW_CAPTURE` 与 `RECONCILIATION` 必须是宿主
API 的不同 sealed request types，不能接受 producer 自报 boolean 后走较宽分支。该 exact
wire 尚未接受，所以在它通过前：

- canonical record frame 不得因 retention/compaction 被移除；
- erasure phase 必须自行冻结等价的防复活 tombstone 后才可完成；
- cold `NEW_CAPTURE` 只有在 selected-frontier coverage 完整、且因为 canonical frame 尚不
  允许删除而不存在 tombstone coverage hole 时，fresh ID absence 才可继续；
- cold old-attempt reconciliation 在任一 relevant frontier gap、quarantine、source
  coverage 缺失或 tombstone authority 不完整时，不能把 absence 当作 never-seen，只能返回
  `INDETERMINATE`/gap 并禁止 retry。

后续 `RecordIdentityTombstonePhaseGateV1` 还必须通过新 protocol minor 冻结一个与现有
`DURABLE/FAILED/INDETERMINATE` 不混淆的 terminal control result（例如
`RETIRED/ERASED`）及其 retry prohibition。现有 `DURABLE` 必须携带仍可验证的 frontier，
reason 1 会授权 exact retry，reason 2 只表示不同 commitment conflict，三者都不能复用成
“同 commitment 已按 authority 删除”。在该 result/wire 未接受前，canonical frame
不得删除。

model/property test 必须至少覆盖：complete cold start 的 fresh CSPRNG ID 首次 append；
旧 exact attempt 在 complete coverage 下 absent 后 exact retry；任一 gap/quarantine 下旧
attempt absence=`INDETERMINATE`；authenticated tombstone 命中返回未来
`RETIRED/ERASED` 且永不复活。不得用“全局 absence 一律禁止”使新 capture 永远不可达，也
不得用“key 不存在即可写”掩盖 reconciliation coverage hole。

### 6.4 retry

唯一可重试输入是调用方仍持有的同一个 `ProducerAppendAttemptV1` 对象及其原始 bytes。
以下行为禁止：

- 根据 domain object 重新 serialize；
- 从 UI 文本或 Provider 消息重建“看起来一样”的 record；
- callback timeout 后换新 record ID 重发；
- `INDETERMINATE` 时直接 enqueue；
- producer 进程死亡后声称可以恢复未持久化 attempt。

处理表：

| 已知结果 | 允许动作 |
|---|---|
| `FAILED + reason 1`，exact attempt 仍保留 | 先复验 gate/fence，再以相同 bytes、record ID、commitment 发起新 admission；新 admission 生成新 token |
| `FAILED + reason 2` | 该 record ID 永久 conflict；禁止以同 ID retry。若宿主后来产生一个可独立证明的新事实，只能重新走 CapturePolicy 并使用新 record ID，不能重放原外部效果 |
| `INDETERMINATE` | 只按 exact record ID+commitment 做 reconciliation；未决期间不重写 |
| callback 丢失，但 reconciliation 找到 durable record | 新 live admission/token 返回对应 durable proof；旧 token 不恢复 |
| callback 丢失，scan 证明 exact commitment 被排除且不存在 ID conflict，attempt 仍保留 | 返回 `FAILED + reason 1` 后才允许 exact retry |
| producer state/token/bytes 已丢失 | 禁止 blind retry；需要时由新 record 写 `GAP`，不得声称原内容 |
| record ID 同、commitment 不同 | 本次未 append 才能 reason 2；既有 record 不被覆盖，同 ID 永不重试 |

诚实 gap 只能陈述宿主可证明的边界，例如：

```text
producer_process_lost_after_volatile_admission
unknown_record_count_lower_bound
last_known_durable_frontier
next_known_durable_frontier
```

不得让模型补写原文、工具结果或发生顺序。

---

## 7. 文件系统、Release owner 与 writer ownership

### 7.1 固定根

根目录只能来自 credential-protected：

```text
Context.noBackupFilesDir/sense-memory/v1/
```

root control 与 Journal 路径：

```text
control/
├── .sense-memory.bootstrap.lock
├── owner-a
└── owner-b
journal/
└── open/
    ├── .sense-memory.namespace.lock
    ├── ime/
    │   ├── owner.lease
    │   └── <writer_epoch_32_lower_hex>/
    ├── brain/
    │   ├── owner.lease
    │   └── <writer_epoch_32_lower_hex>/
    └── main/
        ├── owner.lease
        └── <writer_epoch_32_lower_hex>/
```

`journal/open/.sense-memory.namespace.lock` 是该 closed **Journal tree** 唯一 outer
namespace lock：
immutable regular file、mode 0600、`st_nlink=1`，其 exact EOF/authentication bytes由
`RootBootstrapControlPhaseGateV1` 后续 descriptor冻结；在此之前 data-plane bootstrap
保持 BLOCKED。future descriptor必须要求 create-new、file fsync、open-directory fsync、
close/reopen identity/MAC复验，之后永不 replace/unlink。scanner把其它层级或额外同名
data-plane lock视为非法；writer parent/epoch分别复用 `owner.lease`/`lease`，不另建 lock
inode。

它不是 zero-root祖先创建锁。ADR 0015 authority bootstrap另在固定 root-control path
`control/.sense-memory.bootstrap.lock` 创建唯一 immutable bootstrap lock；该 lock属于 root
shell/control plane，不在 `journal/` 下，也不能用于普通 append/rewrite。全局 lock顺序固定：

```text
root bootstrap lock
  -> data-plane namespace lock
  -> writer owner.lease
  -> epoch lease
```

Keyring/bootstrap staging持 root lock创建并 durability验证 `journal/open`与 data-plane lock，
随后在仍持 root lock时取得 data-plane lock，创建 writer parents/owner leases；完成
`KEYRING_BOOTSTRAP_COMMITTED` 后普通 operation永不反向取得 root lock。reset/whole-root
control按相同顺序 drain。另一路径、相反顺序、平行 bootstrap/data lock inode或试图在
不存在的 `journal/open` 中先取得锁都 fail closed。

每个 epoch 目录恰好允许：

```text
segment
frontier-a
frontier-b
lease
recovered-seal.<64_lower_hex_sha256>
```

bootstrap 临时目录名是：

```text
.bootstrap-<writer_epoch_32_lower_hex>
```

recovery sidecar 的唯一临时名是：

```text
.recovered-seal.tmp.<64_lower_hex_sha256>
```

其中 digest 与目标 final content digest 相同；它只可在持有 owner+epoch recovery lock 时
存在。其它临时名非法。

不接受大写、UUID 连字符、额外 suffix、symlink 或 path traversal。所有打开均相对已打开的
受信 parent directory，且拒绝 symbolic link。

`segment` 与 final `recovered-seal.*` 也必须是 regular file、`st_nlink=1`，已持
descriptor 的 `(st_dev,st_ino)` 必须与从受信 parent 重新打开同一 path 得到的值相等，
并且不得与 lease/frontier/任何其它 epoch entry 共享 inode。该检查在 bootstrap publish、
recovery、每次 close/reopen full-pair/full-segment 验证时重复；正常 seal 后 segment 的
exact EOF 必须等于 selected `NORMAL_SEALED` frontier，recovered epoch 则必须等于 selected
frontier 或更长，允许保留从未升格为 durable 的原始 volatile tail；其完整
length/digest 必须逐字节等于 final recovered-seal 中的
`observed_segment_length/observed_segment_digest`，sidecar 发布后 segment 不得再改变。
final sidecar 自身必须 exact size/EOF=256 且无 trailing byte。hard link、path replacement、
inode mismatch、normal seal 后额外 bytes或 recovery publication 后任何 segment 改写一律
quarantine，不能靠有效 AEAD/MAC 降级接受。

目录名 `open` 是 v1 的历史名称，不表示正常 seal 后移动文件。M9.0：

- 没有 `journal/sealed/`；
- 没有 segment rename；
- 没有 current/head pointer；
- 没有 sealed catalog；
- 没有以时间命名的文件。

### 7.2 两级 lease

`owner.lease` 不属于 ADR 0015 的 control-plane `AUTHORITY_BOOTSTRAP`。只有 owner local
A/B 已 durable、`ReleaseOwnerContinuityGateV1=PASS`，且 ADR 0017
`KeyringBootstrapControlPhaseGateV1` 与 `KeyringBootstrapCapabilityGateV1` 已授权 normal
data-plane Keyring bootstrap 后，才为每个 writer
kind 创建并持久化。它是 immutable 128 bytes：

| offset | size | 字段 |
|---:|---:|---|
| 0 | 8 | ASCII `SMOWN001` |
| 8 | 2 | format major = 1 |
| 10 | 2 | format minor = 0 |
| 12 | 4 | length = 128 |
| 16 | 16 | installation_id raw |
| 32 | 4 | writer_kind |
| 36 | 4 | reserved = 0 |
| 40 | 32 | KeyAuthorizationProfileV1 digest；对应 purpose 13 owner-lock alias |
| 72 | 4 | CRC32C of bytes `[0,72)` |
| 76 | 20 | reserved = 0 |
| 96 | 32 | installation-level purpose 13 `OWNER_LEASE_AUTH` HMAC-SHA-256 |

owner-lock MAC 输入：

```text
ASCII("sense.memory.v1/writer-owner-lease/v1") || 0x00 ||
bytes[0,96)
```

每次取得 file lock 后必须通过原 path 和已持 descriptor 分别完整复读，并验证 inode、
identity、CRC、profile digest 与 MAC；`fstat` 必须证明 regular file、`st_nlink=1`、
exact size/EOF=128，任何 trailing byte、hard link 或 descriptor/path inode 不同都
BLOCKED。purpose 13 是 ADR 0017 closed registry 中独立的：

```text
id       = 13
token    = owner-lease-auth
algorithm = HMAC-SHA-256
scope    = installation/root；non-generation
alias    = sense.memory.v1.k.owner-lease-auth.<installation-base32hex26>
```

`installation-base32hex26` 是 installation_id raw16 的 lowercase RFC 4648 base32hex、
无 padding、恰好 26 bytes且 canonical tail bits；alias digest 是 exact alias UTF-8 的
SHA-256。实现从 selected Keyring payload 的 installation ID 唯一重建 alias/digest，
重新验证 Android Keystore `KeyInfo` 与 purpose13 `KeyAuthorizationProfileV1`，再要求
owner slot 内 installation/profile digest 与之相等。purpose13 不占 generation record，
不参与普通 data-key rotation，也不能被 purpose5 AES或purpose8 HMAC alias替代。
Keyring bootstrap期间“selected”只可指 §14.1 在仍持 root/data-plane locks时由完整 A/B
得到的 bootstrap-staged selection；它在 joint receipt前不授权任何普通 operation，但正是
owner.lease 首次 MAC复验的唯一 key identity source。不得从 volatile intent字段直接跳过
staged-pair byte equality。

purpose13 alias 只可在受控 Keyring/data-plane bootstrap 中创建一次；authority-only root
shell 中它必须不存在。data-plane 已建立后缺失/多出平行 purpose13 alias 时
`DataRootContinuityGateV1=BLOCKED`，不得生成替代 alias继续写。它只认证 writer mutex，
不能替代 `ReleaseOwnerContinuityGateV1`、Keyring identity 或 data-root subgate。alias
只有在卸载/完整 installation root destruction 的 authority 下销毁；创建、灾难恢复和
census 必须进入 ADR 0017。writer/reaper 锁序固定为：

```text
owner.lease exclusive lock
  → epoch/lease exclusive lock
```

writer 在整个 live epoch 持有两个原 inode/handle。reaper 只有取得相同 writer kind 的
owner lock 后才可扫描 lineage、再对目标 epoch `tryLock()`。禁止用 PID、mtime、墙上时间、
进程列表或 CRC 代替 lock。

新 epoch 只有在 `ReleaseOwnerContinuityGateV1=PASS`，并且 ADR 0018 DAG 的
`DataRootContinuityGateV1`、`InstallationKeyringIdentityGateV1`、
`KeyUseSafetyGateV1` 与 `JournalFrontierDurabilityGateV1` 全部 PASS 时才可写：

1. installation identity 来自已验证 Keyring selected payload；
2. release identity/stage gate 允许当前数据类别；
3. owner lock 从 bootstrap 前连续持有；
4. 旧 epoch lineage 有唯一 terminal head，或这是经证明的空根；
5. predecessor 是正常 footer commitment 或确定性 recovered-seal content digest；
6. local-erasure generation/fence 在分配任何 ID 与每次 byte handoff 前均复验。

存在两个 head、断裂 predecessor、身份不一致或 lease inode 被替换时 fail closed。

### 7.3 lease file

epoch `lease` 是 immutable 128-byte identity record；file lock 作用于同一 inode。

| offset | size | 字段 |
|---:|---:|---|
| 0 | 8 | ASCII `SMLSE001` |
| 8 | 2 | format major = 1 |
| 10 | 2 | format minor = 0 |
| 12 | 4 | length = 128 |
| 16 | 16 | installation_id raw |
| 32 | 4 | writer_kind |
| 36 | 4 | reserved = 0 |
| 40 | 16 | writer_epoch raw |
| 56 | 16 | segment_id raw |
| 72 | 16 | key_generation_id raw |
| 88 | 4 | CRC32C of bytes `[0,88)` |
| 92 | 4 | reserved = 0 |
| 96 | 32 | ADR 0017 `SEGMENT_META_MAC` |

MAC 输入是 domain `sense.memory.v1/epoch-lease/v1`、NUL、bytes `[0,96)`。lease bootstrap
file fsync 后才可用于最终目录发布。key generation 必须与 segment header 逐字节相等，
并选择该 generation 的 purpose 8 alias；`fstat` 必须证明 regular file、`st_nlink=1`、
exact size/EOF=128。内容改变、trailing byte、hard link 或 inode/path 不一致永久阻断该
epoch。

---

## 8. Segment header

### 8.1 固定 512-byte layout

所有整数 big-endian。

| offset | size | 字段 |
|---:|---:|---|
| 0 | 8 | ASCII `SMJNL001` |
| 8 | 2 | physical major = 1 |
| 10 | 2 | physical minor = 0 |
| 12 | 2 | header length = 512 |
| 14 | 2 | flags = 0 |
| 16 | 16 | segment_id |
| 32 | 16 | installation_id |
| 48 | 4 | writer_kind |
| 52 | 16 | writer_epoch |
| 68 | 16 | key_generation_id |
| 84 | 2 | protocol major = 1 |
| 86 | 2 | protocol minor = 0 |
| 88 | 2 | payload schema major = 1 |
| 90 | 2 | payload schema minor = 0 |
| 92 | 8 | required_features = 0 |
| 100 | 8 | first_sequence = 1 |
| 108 | 4 | frame_header_length = 96 |
| 112 | 4 | max_inline_record_bytes = 1048576 |
| 116 | 2 | ADR 0017 crypto_suite = 1 |
| 118 | 2 | ADR 0017 wrap_suite = 1 |
| 120 | 1 | predecessor_kind: 0 NONE / 1 NORMAL_FOOTER / 2 RECOVERED_SEAL |
| 121 | 3 | reserved = 0 |
| 124 | 2 | wrapped_dek_length；suite 1 必须为 48 |
| 126 | 2 | reserved = 0 |
| 128 | 16 | predecessor_segment_id；NONE 时全零 |
| 144 | 32 | predecessor_commitment；NONE 时全零 |
| 176 | 12 | DEK wrap IV |
| 188 | 80 | wrapped DEK region；suite 1 为 ciphertext 32 + GCM tag 16，后 32 bytes 全零 |
| 268 | 32 | KeyAuthorizationProfileV1 digest |
| 300 | 4 | CRC32C of bytes `[0,300)` |
| 304 | 32 | ADR 0017 `SEGMENT_META_MAC` |
| 336 | 8 | ADR 0017 JOURNAL_WRAP operation ordinal |
| 344 | 168 | reserved = 0 |

MAC 输入：

```text
ASCII("sense.memory.v1/segment-header/v1") || 0x00 ||
bytes[0,304) || bytes[336,512)
```

完整 header digest 是 `SHA-256(bytes[0,512))`。它绑定进 frontier、footer、frame AAD 和
recovered seal。wrapped DEK 的算法、IV 唯一性、alias 和 authorization profile 由
ADR 0017 进一步约束；两份 ADR 任一不满足都不得解密。

suite registry：

```text
crypto_suite 1 =
  AES-256-GCM frame encryption
  injective 12-byte per-frame nonce encoding
  128-bit GCM tag

wrap_suite 1 =
  ADR 0017 JOURNAL_WRAP purpose alias
  Android Keystore AES-256-GCM
  provider-generated 12-byte IV
  wrapped plaintext = segment DEK 32 bytes
  wrapped result = ciphertext 32 bytes || tag 16 bytes
```

DEK-wrap AAD：

```text
ASCII("sense-memory-journal-dek-wrap-v1") ||
bytes[0,176) ||
bytes[268,300) ||
bytes[336,344)
```

其中 bytes `[176,268)` 是 provider IV/wrapped result 区，不能自我进入 AAD；
`KeyAuthorizationProfileV1 digest` 必须对应 ADR 0017 purpose 1 `JOURNAL_WRAP`。未知 suite
或长度不等于固定值时，在 unwrap 前 fail closed。operation ordinal 必须先由该 exact
JOURNAL_WRAP alias 的 ADR 0017 operation-frontier A/B reservation 获得；crash 后未使用
ordinal 永久 burn，header ordinal 超出已认证 reservation 时拒绝。

### 8.2 predecessor

- 空根首段：`NONE`，两个 predecessor 字段全零；
- 正常前驱：kind `NORMAL_FOOTER`，commitment 是 §11.2 的 footer commitment；
- orphan 前驱：kind `RECOVERED_SEAL`，commitment 是完整 sidecar SHA-256；
- 非空根再次写 `NONE` 非法；
- 一个前驱有多个 successor、多个无 successor head 或 predecessor commitment 不存在时
  lineage fail closed。

该链提供 writer epoch 的先后，不引入墙上时间。

---

## 9. Journal frame

### 9.1 caps

```text
fixed frame header       = 96 bytes
max inline record        = 1,048,576 bytes
max frame ciphertext     = MAX_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES
max frames/segment DEK   = MAX_JOURNAL_FRAMES_PER_SEGMENT_DEK_V1 = 65,536
max GCM auth blocks/DEK  = MAX_TOTAL_GCM_AUTH_BLOCKS_PER_SEGMENT_DEK_V1 = 16,777,216
AEAD tag                 = 16 bytes
commit magic             = 4 bytes
CRC32C                   = 4 bytes
physical frame bytes     = checked_add(96, ciphertext_length, 16, 4, 4)
normal footer reserve    = 256 bytes

ADR0016_DERIVED_MIN_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES =
  descriptorDerivedMin(valid canonical JournalFramePayloadV1)
ADR0016_DERIVED_MIN_PHYSICAL_FRAME_BYTES_V1 =
  checked_add(
    96,
    ADR0016_DERIVED_MIN_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES,
    16,
    4,
    4
  )
ADR0016_DERIVED_MAX_SINGLE_ACCEPTED_PHYSICAL_FRAME_BYTES_V1 =
  checked_add(
    96,
    descriptorDerivedMax(valid canonical JournalFramePayloadV1),
    16,
    4,
    4
  )
ADR0016_DERIVED_MIN_WRITABLE_SEGMENT_BYTES_V1 =
  checked_add(512, ADR0016_DERIVED_MIN_PHYSICAL_FRAME_BYTES_V1, 256)

ADR0016_DERIVED_REACHABLE_SEGMENT_BYTES_MAX_V1 =
  checked_max over every integer n and ordered ciphertext-length vector c[0..n):
    checked_add(
      512,
      checked_sum(120 + c[i] for i in 0..<n),
      256
    )
  subject to:
    1 <= n <= 65,536
    c[i] in ACCEPTED_CANONICAL_JOURNAL_FRAME_CIPHERTEXT_LENGTH_SET_V1
    checked_sum(12 + ceil_div(c[i],16) for i in 0..<n) <= 16,777,216
```

M9.0 不在缺少 ADR 0018-B 设备证据时猜测独立 frame/segment budget。协议 reader 在分配前
要求：

```text
0 < ciphertext_length
ciphertext_length <= MAX_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES
physical_frame_length = checked_u64(96 + ciphertext_length + 16 + 4 + 4)
current_offset + physical_frame_length + 256 不溢出
结果同时不超过当前已 PASS 的 BudgetProfile
```

`MAX_TOTAL_GCM_AUTH_BLOCKS_PER_SEGMENT_DEK_V1 = 2^24` 是密码学协议上限，不是性能预算。
三个 `ADR0016_DERIVED_MIN_*` 常量、
`ADR0016_DERIVED_MAX_SINGLE_ACCEPTED_PHYSICAL_FRAME_BYTES_V1` 与
`ADR0016_DERIVED_REACHABLE_SEGMENT_BYTES_MAX_V1` 由同一冻结 descriptor、frame-count/GCM
block hard cap及合法
`SessionRecordV1`/`JournalFramePayloadV1` presence、长度和 canonical-wire 规则生成并 pin
golden；不得手填成 `1`，也不得用一个不可被 reader 接受的空 Proto 作为最小值。它们只证明
一个最小合法 durable frame连同 header/footer能被 append并正常 seal，以及在全部协议 hard
cap下能到达的最大 sealed EOF，不放宽任何最大值。max生成器必须在有限
`n=1..65,536` domain上做 checked exact optimization；不得以分别取
`max_frames*max_payload` 和 `max_blocks*16` 后再取较大/较小的松弛界替代，因为 frame overhead
与 per-frame ceil会改变可达集合。
`ACCEPTED_CANONICAL_JOURNAL_FRAME_CIPHERTEXT_LENGTH_SET_V1` 也由冻结 descriptor、field
presence/caps与 canonical serializer机械生成；不能把 min/max之间有空洞的整数长度当作
可构造 payload。max golden必须保存一个实际 canonical payload-vector witness，writer可
构造、reader可接受并逐 frame复算相同 EOF/GCM charge。

上述 accepted set、五个 derived 数值与 witness 都是 **protocol-minor indexed**，不是
minor 0 已经存在的虚构常量：

```text
protocol minor 0:
  ACCEPTED_CANONICAL_JOURNAL_FRAME_CIPHERTEXT_LENGTH_SET_V1 = {}
  ADR0016_DERIVED_GEOMETRY_STATUS_V1 =
    UNAVAILABLE_EMPTY_PAYLOAD_REGISTRY
  all five numeric derived values = ABSENT

first semantic payload minor and later:
  accepted set != {}
  ADR0016_DERIVED_GEOMETRY_STATUS_V1 = AVAILABLE
  all five numeric derived values and constructible witnesses = PRESENT
```

unknown/opaque recovery frame不是 writer 可接受的 semantic frame，不能拿来填空集合、生成
minimum或证明 flush liveness。M9A-05 必须先提升 protocol minor，冻结首批
`payload_type` token、payload Proto/schema/presence/cap、canonical serializer与新的
descriptor digest；生成器随后从该 exact registry机械导出非空 accepted set、五个 checked
u64 数值及 witness。只有这一步完成，依赖这些值的 ADR 0018-B profile 才可评估。以后
registry/token/schema/cap的任何变化都必须再提升 minor、更换 descriptor digest、重算全部
geometry与 BudgetProfile identity；不得沿用旧值或用 `0`/`1`/`UINT64_MAX` sentinel
冒充 absent。

frame AAD 的 exact length 是 `33 + 1 + 32 + 96 = 162` bytes，因此：

```text
frame_gcm_auth_blocks =
  checked_add(
    ceil_div(162, 16),
    ceil_div(ciphertext_length, 16),
    1  // GCM GHASH length block
  )
= 12 + ceil_div(ciphertext_length, 16)
```

writer 必须从 segment header 后的零值开始维护 live monotonic
`all_cipher_initializations` 与 `all_gcm_auth_blocks`；它们覆盖该 DEK 下**所有已经开始的
Cipher initialization/产生的 ciphertext**，包括尚未 checkpoint 的 batch/volatile frame，
不能从 selected frontier 倒推为更小值。在分配 sequence 或初始化 Cipher 前，先证明
`checked_add(all_cipher_initializations,1) ≤ 65536` 且
`checked_add(all_gcm_auth_blocks,next_frame_gcm_auth_blocks) ≤ 16777216`；将超过任一上限
时，若当前 I/O 状态确定且没有未决 init/write，先正常 seal，再用新 segment identity 与
独立 DEK 写入。Cipher init 后任何失败/不确定性立即退休整个 segment/DEK，因此无需在重启后
猜测未落盘 attempt 的 charge。单 frame 自身超过上限则拒绝，不能通过跨 segment 拆
ciphertext 绕过 record/Blob 规则。

reader/recovery 对完整 observed segment 逐帧重算 exact AAD/ciphertext charge，包括 selected
frontier 后仍完整可解析的 volatile tail；半 frame 至少证明曾有不完整 attempt，但 recovery
不会复用该 DEK。selected prefix 或 observed complete-frame aggregate 超限均 quarantine，
不能因为 durable cut 较短就把已经发生的 key usage 擦掉。BudgetProfile 只能进一步收紧
frame/bytes/blocks，不能放宽这两个常量。

`MAX_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES` 只由冻结 descriptor、字段存在上限、
ID/string caps、最多 8 个 predecessor 和 `MAX_INLINE_RECORD_BYTES_V1` 机械生成；不得由
设备 profile 放宽。BudgetProfile 可以更小，此时 oversize body 走 Blob。不满足空间/profile
时，在 I/O 状态确定的前提下正常 seal，随后建立新 epoch；不得写到 profile 上限后才发现
footer 无空间。

### 9.2 fixed 96-byte header

| offset | size | 字段 |
|---:|---:|---|
| 0 | 4 | ASCII `SMF1` |
| 4 | 2 | physical major = 1 |
| 6 | 2 | physical minor = 0 |
| 8 | 2 | header length = 96 |
| 10 | 2 | flags：bit 0 = durable-required；其余 0 |
| 12 | 4 | ciphertext_length，范围见 §9.1 |
| 16 | 8 | writer_sequence，≥1 |
| 24 | 16 | record_id raw |
| 40 | 32 | record_commitment |
| 72 | 2 | payload schema major = 1 |
| 74 | 2 | payload schema minor = 0 |
| 76 | 8 | required_features = 0 |
| 84 | 8 | nonce ordinal = writer_sequence |
| 92 | 4 | reserved = 0 |

随后：

```text
ciphertext[ciphertext_length]
aead_tag[16]
commit_magic ASCII "CMT1"
crc32c[4] big-endian
```

CRC32C 覆盖从 frame magic 到 `commit_magic` 最后一个 byte，即：

```text
header || ciphertext || tag || "CMT1"
```

CRC 只用于快速发现意外损坏。ADR 0017 冻结 key purpose、authorization 与算法许可；
本节冻结 suite 1 的 nonce 和 AAD exact bytes。

suite 1 的 nonce 是无截断、可逆拆分的 12-byte injective encoding：

```text
frame_nonce_12 =
  ASCII("SMF1") || writer_sequence_u64_be
```

frame AEAD AAD 唯一为：

```text
ASCII("sense-memory-journal-frame-aad-v1") || 0x00 ||
segment_header_digest_32 ||
exact_frame_header_96
```

suite 1 的 `ciphertext_length == plaintext_length`。同
`(segment DEK, nonce ordinal)` 只能初始化一次。原 writer 对同一次初始化产出的
immutable ciphertext 做有进展短写时可以继续 `writeFully`；任何重新加密、write 结果
不确定、force 失败或进程死亡都永久退休 segment/DEK。nonce 的唯一性依赖同 segment
sequence 不重复和每 segment 独立 DEK；任一不变量不能证明时不得初始化 Cipher，而不是
把 hash 碰撞概率当作唯一性证明。

nonce injectivity 只是必要条件，不等于无限 key usage。NIST SP 800-38D Appendix B 要求
限制单 key 生命周期内处理的 plaintext/AAD blocks；NIST 2026 年第二次 revision
pre-draft 也把 total data、message length 与 invocations/key 的 usage bounds 明确列为
安全参数。本协议选 `2^24` aggregate authenticated blocks，使通用
`sigma^2 / 2^128` GHASH birthday 项在 `sigma=2^24` 时量级约为 `2^-80`，相对
`2^-64` 设计目标保留 16-bit margin；这不是对整个系统的完整形式化证明，后续若更换
suite/分析只能新开 protocol minor，不能静默放宽 V1。

### 9.3 reader 顺序

reader 必须：

1. 先验证 segment header、selected frontier 和 `byte_offset ≤ file_length`；
2. 只在 `[512, selected.byte_offset)` 内迭代；
3. 小缓冲读取 96-byte header；
4. 验证 magic/version/header length/flags/sequence/caps/checked addition；
5. 验证完整 tail 与 `CMT1`；
6. 验证 CRC32C；
7. 验证 AEAD；
8. parse Proto 并保留 exact plaintext bytes/unknown fields；
9. 运行 §5 validator 和 physical/payload cross-binding；
10. 更新 prefix digest 和连续 sequence；
11. 到达 selected offset 时必须恰好位于 frame 尾或合法 footer 尾。

有效 CRC、commit magic 和 AEAD 都不能把 selected frontier 后的 frame 升格为 durable。

---

## 10. Durable frontier A/B

### 10.1 固定 256-byte slot

`frontier-a`、`frontier-b` 均在 epoch bootstrap 时预创建为恰好 256 bytes。整数
big-endian。

两者必须是 regular file、各自 `st_nlink=1`、exact size/EOF=256，且 `(st_dev,st_ino)`
互不相同；segment、lease 与两个 frontier 也不得共享 inode。任何 trailing byte、hard
link、symlink 或 aliasing 使整个 epoch fail closed，不能降级成“一份有效槽”。

| offset | size | 字段 |
|---:|---:|---|
| 0 | 8 | ASCII `SMFRT001` |
| 8 | 2 | format major = 1 |
| 10 | 2 | format minor = 0 |
| 12 | 4 | slot length = 256 |
| 16 | 1 | state：0 UNUSED / 1 COMMITTED |
| 17 | 1 | writer_kind |
| 18 | 2 | flags：bit 0 NORMAL_SEALED；其余 0 |
| 20 | 8 | frontier_generation |
| 28 | 16 | installation_id |
| 44 | 16 | segment_id |
| 60 | 16 | writer_epoch |
| 76 | 16 | key_generation_id |
| 92 | 8 | writer_sequence |
| 100 | 8 | byte_offset |
| 108 | 8 | frame_count |
| 116 | 32 | prefix_digest |
| 148 | 32 | segment_header_digest |
| 180 | 4 | CRC32C of bytes `[0,180)` |
| 184 | 32 | ADR 0017 `FRONTIER_MAC` |
| 216 | 32 | previous_committed_frontier_digest；bootstrap 为零 |
| 248 | 8 | reserved = 0 |

MAC 输入：

```text
ASCII("sense.memory.v1/journal-frontier/v1") || 0x00 ||
bytes[0,184) || bytes[216,256)
```

`previous_committed_frontier_digest` 在 generation>0 时等于
`SHA-256(exact previous mirrored frontier 256 bytes)`；它参与 MAC。adjacent crash pair
中 higher 必须逐字节引用 lower digest。same-generation mirrored pair 中两槽 exact bytes
相同，因此该字段也相同。

### 10.2 bootstrap empty mirrored pair

bootstrap 必须写入两个**逐字节相同、均完成身份绑定、CRC 与 MAC**的 COMMITTED slot：

`frontier-a` 与 `frontier-b`：

```text
state = COMMITTED
flags = 0
frontier_generation = 0
writer_sequence = 0
byte_offset = 512
frame_count = 0
prefix_digest = SHA-256(segment_header_512)
previous_committed_frontier_digest = ZERO32
其余 identity = segment header
```

UNUSED state 在 physical minor 0 不得出现在已发布 epoch；它只保留给 future migration，
不是全零/无校验占位或 candidate。

### 10.3 checkpoint

每次 checkpoint：

1. 计算将提交的完整 frame boundary、sequence、frame count 和 prefix digest；
2. `segment.force(true)`；
3. base 必须是 same-generation/exact-bytes mirrored pair；只有在**同一连续 writer
   lifetime**、owner/epoch lease从未丢失、first-target write/fsync/close/reopen outcome已
   确定，且易失的 max-allocated-sequence/DEK-usage witness与
   `LastCommittedFrontierWitnessV1` 仍连续持有时，live checkpoint才可先完成一次 interrupted
   adjacent mirror。witness绑定 last committed generation、exact 256-byte slot digest、
   writer sequence/frame count/offset/prefix digest；只有 full mirrored reread成功后、callback
   前才能更新。selected base必须 byte-equal该 witness；live adjacent还必须证明 lower等于
   witness且 higher等于当前唯一 in-flight candidate digest。任一回放/差异立即
   `INDETERMINATE`并退休旧 segment/DEK，零 peer write/callback。任何 process death、descriptor/lock
   discontinuity、reopen recovery或 outcome unknown下观察到的 adjacent pair都不得收敛或
   继续 checkpoint，而必须按 §15只读 reconcile：selected higher若自身为覆盖 valid footer
   的 `NORMAL_SEALED`，走 normal terminal且零 sidecar；否则才发布 recovered seal。两支都
   永久退休旧 epoch/segment/DEK；
4. checked 计算 `new_generation=base+1`，构造一份 exact 256-byte new frontier，其
   `previous_committed_frontier_digest=SHA-256(exact base 256 bytes)`；
5. 按 `(new_generation & 1)` 选择 deterministic first target，写完整 new bytes、file
   fsync、close；
6. close/reopen full-pair read，必须得到 base/new adjacent pair，并验证 new 对 base 的
   digest、prefix/sequence/count/offset 单调扩展；
7. 把 **同一份 exact new 256 bytes** 写入 peer、file fsync、close；
8. 再次 close/reopen A、B，各自独立验证 length、reserved、CRC、MAC、identity、header
   digest、file boundary、sequence、frame count、prefix digest；
9. 两槽必须 same-generation 且逐字节相同，重新选择结果精确等于 new frontier；
10. 只有此时才可 callback。

普通 checkpoint 不创建/rename 文件，不写 pointer，不改变目录项，因而不执行 directory
fsync。任一 slot fsync 或复读结果未知时返回 `INDETERMINATE` 并退休 segment。first target
成功、peer mirror 前 crash 的 new frontier 可以在恢复后被证明 durable，但 producer 尚未
收到 Ack；只能通过 exact record reconciliation 重新观察，不能伪造旧 callback。

### 10.4 selection

candidate 必须：

- state `COMMITTED`；
- fixed magic/version/length、known flags、reserved、CRC、MAC 全部有效；
- identity 与 segment header、目录 writer token 和 lease 完全一致；
- `byte_offset` 位于 `[512, file_length]`，checked u64 且不超过已 PASS 的
  BudgetProfile；
- sequence `0` 当且仅当 frame_count `0`；未 seal 的空 frontier offset 必须为 `512`，
  已正常 seal 的空段 offset 必须为 `768` 且 `[512,768)` 是有效 footer；
- 非空 M9.0 frontier 中 `writer_sequence == frame_count`；
- offset 精确位于已验证 frame/footer 边界；
- prefix digest 对原始 `[0, byte_offset)` bytes 重算一致；
- `NORMAL_SEALED` 当且仅当 offset 以有效 normal footer 结束。
- `NORMAL_SEALED` 时必须
  `file_length == byte_offset == footer.sealed_end_offset`；footer 后任何 trailing byte
  都使该 segment quarantine，不能静默忽略。

选择只返回经过验证的 bytes classification，不自行返回 writable handle：

- 零 candidate：fail closed/quarantine；
- 一个 candidate：选择它；
- 两个不同 generation：只作为 interrupted mirror candidate 选择较高者；较低者必须是
  `higher - 1`，higher.previous digest 必须等于 exact lower slot digest，且 higher 的
  sequence/frame count/offset/prefix 是 lower 的合法单调扩展。只有调用方同时持有上段所述
  continuous-live witness（包括 lower/current-candidate 与
  `LastCommittedFrontierWitnessV1` 的 exact binding）时，classification才是
  `LIVE_INTERRUPTED_MIRROR` 并允许写 exact
  higher bytes到 peer；从磁盘恢复、进程重启或 witness缺失时一律为
  `RECOVERED_READ_ONLY_ADJACENT`；
- 两个同 generation、内容不同：fail closed；
- 两个同 generation、逐字节相同时，以 A 为 deterministic selected base；这只是
  `MIRRORED_BYTES_VALID`。只有原始 epoch bootstrap返回的 writer仍连续存活、连续持锁并持有
  未中断的 DEK-usage witness，且 pair byte-equal `LastCommittedFrontierWitnessV1` 时，
  它才可用于 append/checkpoint。任何从 disk重新构造的
  handle，即使看到 exact mirrored pair，也只能只读 reconcile并进入新 epoch/segment/DEK；
- 只有一个 candidate 时返回 `READ_ONLY_SINGLE_FRONTIER_UNCERTAIN`；不得镜像 peer、返回
  writable handle、append/checkpoint、扩大 cut或 callback。old-valid survivor与 random
  corruption本地不可区分，镜像后重启会把不可信旧 cut伪装成 writable pair，并可能在同一
  segment DEK下复用已发生过的 frame nonce。recovery只能按 §15保全只读 prefix：
  survivor若自身为覆盖 valid footer 的 `NORMAL_SEALED`，走 normal terminal且零 sidecar；
  否则才发布 recovered seal（可证明时）。两支都永久退休 old segment/DEK，再以 new
  epoch/segment/DEK bootstrap；future repair只有独立 rollback-resistant current-head +
  exact DEK-usage witness时才可定义；
- generation `UINT64_MAX-1` 只保留给最后一次 normal seal：writer 可以把尚未 Ack 的连续
  frames 与 footer 一起 force，并用唯一一次 checkpoint 提交 generation `UINT64_MAX`；
  不得先做数据 checkpoint 再发现 footer 无 generation；
- generation `UINT64_MAX` 必须已经是 `NORMAL_SEALED`。若读到未 seal 的 MAX frontier，
  禁止继续 checkpoint/normal seal，只能按 crash recovery fail closed/sidecar 结束并进入
  新格式或迁移门禁。

A/B 可以抵抗单槽撕裂和单槽旧值，但**不能仅靠自身检测整对文件被一起回滚成一对更旧而
内部自洽的有效 bytes**。产品不得以 A/B generation 代替外部 rollout revocation freshness
或抗回滚硬件证明。

所谓“抵抗单槽损坏”以 checkpoint 回调前已经形成 exact mirrored pair 为前提：任一槽之后
随机 bitflip/丢失，另一槽仍可保全 acknowledged cut用于只读/reconciliation，但 V1不会把
它修成 old-segment writable state。
同理，full-old exact pair或 old adjacent pair被一致回放时，disk bytes本身不能证明尚未在
volatile tail初始化过更高 sequence/nonce；它们绝不能恢复旧 DEK 的 Cipher-init 权限。
任意 old-valid single-slot replay 与 random corruption 在本地不可区分；它与 full-old-pair
rollback 属于同一显式 threat boundary，real-data stage 仍要求 ADR 0018
`StageRevocationFreshnessGateV1`，不能仅凭 repair 宣称 globally latest。

---

## 11. 正常 seal

### 11.1 语义

正常 seal 只允许仍连续持有 owner/epoch lease、且从最后 checkpoint 后没有 write/force
不确定性的原 writer 执行。过程：

```text
append/force 所有待提交 frame
  → checkpoint frame frontier（如需要）
  → append fixed footer
  → segment.force(true)
  → checkpoint 同一 A/B pair，NORMAL_SEALED=1、offset 包含 footer
  → close/reopen 复读 segment/A/B/footer
  → 释放 epoch lease
  → 释放 owner lease
```

segment 文件仍在原 epoch 路径。没有 rename、sealed directory、catalog 或 pointer。

若 selected frontier generation 已为 `UINT64_MAX - 1`，上式中的“checkpoint frame
frontier”必须省略：尚未 Ack 的连续 frames 与 footer 一起写入并 force，唯一剩余的
generation `UINT64_MAX` 直接提交覆盖二者的 sealed frontier，之后才交付这些 Ack。任何
实现都不得消耗最后一代做普通 checkpoint 后留下无法提交 footer 的 open segment。

### 11.2 固定 256-byte footer

| offset | size | 字段 |
|---:|---:|---|
| 0 | 8 | ASCII `SMJFT001` |
| 8 | 2 | format major = 1 |
| 10 | 2 | format minor = 0 |
| 12 | 4 | footer length = 256 |
| 16 | 16 | segment_id |
| 32 | 16 | writer_epoch |
| 48 | 16 | key_generation_id |
| 64 | 4 | writer_kind |
| 68 | 4 | flags = 1 NORMAL_SEALED |
| 72 | 8 | frame_count |
| 80 | 8 | first_sequence；空段为 0，否则 1 |
| 88 | 8 | last_sequence；空段为 0 |
| 96 | 8 | data_end_offset = footer 起点 |
| 104 | 8 | sealed_end_offset = data_end + 256 |
| 112 | 32 | data_prefix_digest = SHA-256(bytes `[0,data_end)`) |
| 144 | 32 | footer commitment |
| 176 | 8 | frontier_generation 将提交值 |
| 184 | 4 | CRC32C of bytes `[0,184)` |
| 188 | 4 | reserved = 0 |
| 192 | 32 | ADR 0017 `SEGMENT_META_MAC` |
| 224 | 32 | reserved = 0 |

footer commitment 唯一计算：

```text
SHA-256(
  ASCII("sense.memory.v1/normal-footer/v1") || 0x00 ||
  bytes[16,144) ||
  frontier_generation_u64_be
)
```

MAC 输入：

```text
ASCII("sense.memory.v1/normal-footer-mac/v1") || 0x00 ||
bytes[0,192) || bytes[224,256)
```

footer 不新增 writer sequence，也不增加 frame count。最终 frontier 的 prefix digest 覆盖
header、全部 frames 和 footer。新 epoch header 的 predecessor commitment 使用 offset
144 的值。

空 segment 可以正常 seal：frame count/first/last 均 0；但不会产生 `DurableAck`。

---

## 12. Blob-before-Journal

ADR 0017 是 Blob physical format、chunk AEAD、logical commitment 与 512-byte locator A/B
的权威。本文冻结 Journal 的依赖顺序。

对于 record 引用的每个 Blob：

1. 生成随机 logical `blob_id` 与全新 physical storage ID，durable bind materialization
   intent并 reserve/burn purpose-2 BLOB_WRAP ordinal；
2. 单次生成全新 physical DEK；失败/不确定不重 draw、不复用 ordinal；
3. 在同一目标目录创建 temp，流式写固定 header/chunks/footer；
4. file fsync；
5. 从 temp 完整复读 length、commitment、chunk tags、footer 和 caps；
6. publish-no-replace 到最终 physical path；
7. parent directory fsync；
8. 从最终 path 完整复读；
9. locator inactive/initial slot 写 `PREPARED`，slot fsync、关闭并全量复读 A/B；
10. peer slot 写 `ACTIVE` 同一 mapping，slot fsync、关闭并全量复读；
11. 原 `PREPARED` 槽覆写为 `ACTIVE` 同一 mapping，slot fsync、关闭并全量复读；
12. selector 必须得到相邻 generations 的两个 `ACTIVE`，且 ADR 0017 定义的 mapping bytes
    完全相同；
13. 此后才构造五字段 `BlobRefV1`；
14. Journal 才可 write frame；
15. frame force、frontier commit/reopen 成功后才可 DurableAck。

任一步失败：

- 不允许 frame 引用未收敛 locator；
- 已发布但未被 Journal 引用的 Blob 是可证明 orphan；包括 locator 已双 ACTIVE 后发生的
  cancel/gate flip。其 durable orphan charge 必须按 §6.1 保留，后续只由受认证
  cleanup/GC/erasure reducer处理；
- physical publish 或 locator outcome 不确定时永久退休 physical ID、DEK 和 locator
  attempt；不能覆盖重试；
- 已经 DurableAck 的 Journal 引用若 locator 后来不收敛，recall 必须 fail/gap，不能扫描
  “看起来像对应内容”的物理文件替代。

逻辑 Blob ID 与物理 ID 分离，所以 compaction/rotation 可在 lease fence 下更新 locator，
而历史五字段 BlobRef bytes 保持不变。

---

## 13. DurableAck 算法与不对称性

### 13.1 单 checkpoint 可 Ack 多个 record

writer 可以批量写多个 frame，只要：

- 所有 frame 顺序连续；
- 所有 referenced Blob 各自已完成 §12；
- batch 最迟 deadline/上限由 ADR 0018 设备证据冻结；
- data force 后 frontier 指向 batch 最后完整 frame；
- close/reopen 选择结果覆盖每个待 Ack sequence。

对每个 `DURABLE_REQUIRED` record 返回各自 token/sequence，但 `frontier` 可以是同一 batch
末端，因此：

```text
ack.writer_sequence <= ack.frontier.writer_sequence
```

BEST_EFFORT frame 可以被同一 batch 意外带入 durable frontier，但因为它没有 token/callback，
产品仍不得向 producer 声称观察到 DurableAck。

### 13.2 callback 窗口

合法状态：

```text
frontier committed
  → writer dies before callback
  → recovery reads record as durable
  → original producer never observed Ack
```

因此：

```text
DurableAckObserved(record) ⇒ SelectedFrontierContains(record)
SelectedFrontierContains(record) ⇏ OriginalProducerObservedAck(record)
```

callback transport、Binder success、UI “已保存”不是 Journal authority。record
reconciliation 必须以 retained exact record ID/commitment 与冷启动重新验证过的 A/B +
segment prefix 为依据；live token 不能承担恢复查询。

### 13.3 effect barrier

Tool effect intent、不可逆外部效果前 checkpoint、effect receipt 和 Run terminal 必须使用
`DURABLE_REQUIRED`。关键 admission 被拒或 ack 未决时，Run 进入
`PERSISTENCE_BLOCKED`，不得继续外部效果。

若本地编辑效果已经发生但 receipt 未能 durable：

- 不能回写一个虚假的成功 receipt；
- volatile health 标为 unknown；
- 存储恢复后只记录宿主能再次观察确认的结果及 gap；
- Patch 权威仍属于既有 editor guard/post-apply verifier，Memory 不接管。

---

## 14. Bootstrap 与 directory durability

### 14.1 固定 parents

ADR 0015 的 future `AuthorityBootstrapPermitV1` 最多预创建固定 root shell、
`control/.sense-memory.bootstrap.lock` 与 owner-control A/B；它不得创建本节数据面目录。
normal branch在 `ReleaseOwnerContinuityGateV1=PASS` 后，measurement-specific branch则只在
ADR 0018 不可转换的 exact authority 下运行：evidence branch消费当次
`CANDIDATE_EVIDENCE_OWNER_CONTROL[attempt]`，primary branch只在已原子 durable consume三份
permit的 `CandidatePrimaryRootEpochStartDecisionV1` 同一 S0 owner-bootstrap transaction
内运行。两者都不能 cast成 normal gate，也不能写 normal owner A/B。两类 Keyring
bootstrap staging都先验证各自 authority class绑定的 root lock。在
`KEYRING_BOOTSTRAP_COMMITTED`（candidate为同构但不可转换的 candidate commit receipt）
**之前**，同一 transaction
必须按 parent→child创建/durability验证 `journal`、`open`、
`journal/open/.sense-memory.namespace.lock`、三个 writer parent、三个 `owner.lease`与
purpose-1/2 operation frontiers，以及
`blobs`、`manifests/{keyring,operation-frontier,blob-locator,erasure-control}`、
`manifests/blob-locator/bootstrap-control`、
`index/{projection,hot-snapshot}`、`quarantine`、`temp`和每个 closed ordinary-parent
namespace lock；每个文件先 file fsync，随后按 child→parent逐级 directory fsync和
close/reopen identity复读。erasure-control A/B fixed slots还必须在 commit前完成物理
预分配与 block-charge census。

唯一 owner-lease self-bootstrap按 ADR 0017 `NamespaceMutationLockMapV1`：仍持 root lock时
取得刚刚验证的 data-plane namespace lock，在其下建立 fixed writer parent与唯一
`owner.lease` bytes，但此时不得声称 MAC已被 on-disk Keyring认证或取得 owner handle。
bootstrap随后必须按唯一顺序：

```text
write owner.lease bytes from the precommitted installation/purpose-13 identity
  -> close/reopen structural/inode/CRC check
  -> acquire rank-20 ProvisionalOwnerLeaseHandleV1 for IME, BRAIN, MAIN in canonical order
  -> write + file-fsync + close/reopen a complete Keyring A/B pair
  -> select the staged pair under the still-held root/data-plane locks
  -> reconstruct installation_id, purpose-13 alias/profile from that selected payload
  -> require byte equality with the precommitted lease identity/profile
  -> close/reopen verify owner.lease identity/CRC/MAC
  -> promote the same still-held provisional inode handles to AuthenticatedOwnerLeaseHandleV1
     without unlock/reopen/reacquire
  -> full alias/frontier/pair/lease/parent census
  -> commit the joint bootstrap receipt
```

staged pair在 joint receipt前不是 `PERSISTENT_SUBSTRATE`，generic reader/writer不能使用；
purpose-13只能认证相同 transaction预写的 lease，不能借外部临时 key跳过 selected-payload
复验。provisional handle只持有 OS inode lock，不能创建 epoch、append、reap、调用普通
owner API或退出该 root-bootstrap transaction；promotion失败时永不成为 authenticated
handle。Keyring pair、aliases、frontiers、namespace lock、parents与 leases的同一 bootstrap
receipt全部提交后，terminal必须按 authority class分支且不可互 cast：

```text
NORMAL
  -> KEYRING_BOOTSTRAP_COMMITTED
  -> normal PERSISTENT_SUBSTRATE may later become reachable through ADR 0018 DAG

CANDIDATE_EVIDENCE
  -> attempt-scoped CandidateKeyringBootstrapEvidenceReceiptV1
  -> evidence-only substrate
  -> mandatory external wipe + zero-census

CANDIDATE_PRIMARY
  -> CandidateKeyringCommitReceiptV1
  -> CANDIDATE_PERSISTENT_SUBSTRATE for that precommitted root epoch only
  -> epoch terminal external wipe + zero-census
```

candidate receipt不得 mint、serialize或 cast `KEYRING_BOOTSTRAP_COMMITTED`。evidence attempt
无论是否走到该 receipt，都必须由 ADR 0018 external ledger产生
`CandidateKeyringBootstrapEvidenceAttemptTerminalV1` 后再 containment cleanup。对应
successful receipt durable后才释放 data-plane/root locks；normal epoch bootstrap或
candidate assigned cases
只能发生在各自 terminal之后。owner lease与两级 lock此后永不 replace/unlink；已有内容/
identity不匹配时 fail closed，不能创建平行 lock inode。

crash/kill matrix必须覆盖 lease write前后、第一/第二/第三 provisional owner lock、
A-only、B-only、pair fsync前后、pair已 select但 lease复验/promotion前、每个 promotion、
joint receipt与 parent fsync各边界。恢复只能消费
同一 durable bootstrap intent中预承诺的 IDs、aliases、purpose-5/13 attempted-use
dispositions与 slot attempt；single/partial pair、pair↔lease identity/profile mismatch、
lease已被其它 owner占用、intent缺失或 staged authority cleanup不确定都保持
`BLOCKED_PARTIAL_BOOTSTRAP`。不得重新生成 installation/keyring ID、把 single-valid pair
提升为 normal authority、用另一 purpose-13 alias重 MAC、或创建平行 lease；candidate失败
还必须由外部 containment wipe/zero-census，normal失败只能 exact-resume或走未来显式 reset。
Android Java API 没有可移植
的 directory-fsync 承诺；实现必须通过经过设备验证的 `DirectoryDurabilityPort`。port
不可用时：

- root/parent/epoch/Blob/locator/new sidecar 等目录项发布全部 blocked；
- 不得降级为“rename 返回成功就算 durable”；
- 不得返回 DurableAck；
- 普通已经 bootstrap 完成的 frontier checkpoint 不需要 directory fsync。

### 14.2 epoch bootstrap

在持有 `owner.lease` 后：

```text
生成 writer_epoch / segment_id
  → durable reserve/burn purpose-1 JOURNAL_WRAP ordinal
  → 单次生成 independent DEK（失败/不确定不重 draw、不复用 ordinal）
  → CREATE_NEW .bootstrap-<epoch>
  → CREATE_NEW segment/frontier-a/frontier-b/lease
  → 写 segment header、A/B exact mirrored empty COMMITTED、lease identity
  → 分别 file fsync
  → 打开临时目录中的 epoch lease 并取得 exclusive lock
  → 临时 epoch directory fsync
  → atomic publish-no-replace temp dir 为 <epoch>（目标已存在即失败）
  → writer parent directory fsync
  → 保持同一 lease inode/handle 的 lock
  → 从最终路径关闭/另开 read descriptor，完整复读四个文件
  → 验证 inode/identity/A-B selection
  → 返回 writable handle
```

任意 kill-point：

- rename 前只留下不可见于正式 epoch scan 的 `.bootstrap-*`；
- rename 后、parent fsync 前的目录视为 recovered/uncertain，绝不恢复 writable；
- lock 取得失败不得 rename；
- final reread 失败永久退休该 epoch；
- cleanup 只有持 owner lock 的 reaper 可以执行，不能启动时无条件删共享 temp。

---

## 15. Crash recovery

### 15.1 recovery ownership

reaper：

1. 取得 writer-kind `owner.lease`；
2. 扫描 canonical epoch path；
3. 验证唯一 predecessor chain/head；
4. 对目标 epoch `tryLock()` 成功；
5. 重新验证 header、lease、A/B、segment；
6. 只读取 selected frontier；
7. 只有 selected frontier 自身设置 `NORMAL_SEALED`、offset 覆盖 footer 且 footer 全量验证
   时才视为正常结束；frontier 后即使存在逐字节完整 footer也只是 volatile tail；
8. 非正常终止则按 §15.3 发布 sidecar；
9. 释放旧 epoch lock；
10. 以新 epoch/segment/DEK 继续。

recovery ownership 永远不能提升为旧 segment 的 byte-write ownership。唯一允许的旧
segment 操作是 §15.3 在锁内、零 byte mutation 的 `forceExistingDataAndMetadata()`；
append、truncate、rewrite、hole-punch 与 timestamp-as-authority 均禁止。

### 15.2 recovery matrix

| 观察 | 动作 | 对完整性的表述 |
|---|---|---|
| frontier 后有半 frame | 保留原文件；按 frontier sidecar seal | volatile tail lost/ignored |
| frontier 后有完整 CRC/AEAD frame | 仍忽略 | `VOLATILE_TAIL_IGNORED` |
| selected prefix 内 frame 损坏 | quarantine 整段 | gap；不提供 exact recall |
| sequence 重复/倒退/跳跃 | quarantine | `DROPPED_RANGE` / gap |
| 一个 frontier slot 损坏 | 单槽只读；不镜像/不写旧 segment。若 survivor 自身为覆盖 valid footer 的 `NORMAL_SEALED`，按 normal sealed结束且**零 sidecar**；否则发布可证明的 recovery seal后 new epoch/new DEK | 保全 survivor Ack cut用于只读/对账；old-single replay不可区分，旧 DEK 零新 init |
| restart/reopen 后观察到 valid adjacent pair | 选择 higher仅用于只读/reconcile；不完成 peer mirror。若 higher 自身为覆盖 valid footer 的 `NORMAL_SEALED`，按 normal sealed结束且**零 sidecar**；否则发布可证明的 recovery seal后 new epoch/new DEK | disk pair不能证明 live DEK-usage witness；旧 DEK 零新 Cipher init |
| restart/reopen 后观察到 exact mirrored pair | 选择该 cut仅用于只读/reconcile；不重构 writable handle，非正常 seal时转 recovered seal与新 epoch/new DEK | full-old replay不可区分；旧 DEK 零新 Cipher init |
| 两槽均无 candidate | quarantine | durable state unknown |
| 同 generation 不同内容 | quarantine | fork/rollback ambiguity |
| full old A/B pair rollback | 本地无法单独检测 | 明示限制；不得升格 rollout stage |
| key 不可用 | 不明文降级 | policy restricted/failed |
| live lock 未释放 | 立即退出 | 不修改 |
| selected `NORMAL_SEALED` frontier 覆盖 valid normal footer | 只读 | normal sealed |
| 完整 footer 位于 selected frontier 之后 | 不提升；按 frontier 发布 recovery seal | volatile tail ignored |
| 多个不同 valid recovery sidecar | quarantine | conflicting recovery proof |
| segment 在 sidecar 后改变 | quarantine | source mutation |

### 15.3 `RecoveredSealV1`

sidecar 是 fixed 256 bytes，整数 big-endian：

| offset | size | 字段 |
|---:|---:|---|
| 0 | 8 | ASCII `SMRSL001` |
| 8 | 2 | format major = 1 |
| 10 | 2 | format minor = 0 |
| 12 | 4 | length = 256 |
| 16 | 16 | segment_id |
| 32 | 16 | installation_id |
| 48 | 4 | writer_kind |
| 52 | 4 | recovery_reason closed enum |
| 56 | 16 | writer_epoch |
| 72 | 16 | key_generation_id |
| 88 | 8 | selected_frontier_generation |
| 96 | 8 | selected_writer_sequence |
| 104 | 8 | selected_byte_offset |
| 112 | 32 | selected_prefix_digest |
| 144 | 8 | observed_segment_length |
| 152 | 32 | observed_segment_digest |
| 184 | 32 | selected_frontier_slot_digest |
| 216 | 4 | CRC32C of bytes `[0,216)` |
| 220 | 4 | reserved = 0 |
| 224 | 32 | ADR 0017 `RECOVERY_SEAL_MAC` |

`selected_frontier_slot_digest = SHA-256(exact selected 256-byte slot)`。
sidecar 的 segment/installation/writer kind/writer epoch/key generation/frontier generation/
sequence/offset/prefix digest 必须与该 selected slot 逐字段相等；因此 slot digest 是 compact
frontier identity，不允许用另一个 valid slot 的字段拼装 sidecar。

MAC 输入：

```text
ASCII("sense.memory.v1/recovered-seal/v1") || 0x00 ||
bytes[0,224)
```

文件 content digest 是 `SHA-256(exact final 256 bytes)`；文件名恰好：

```text
recovered-seal.<64 lowercase hex content digest>
```

M9.0 的 closed recovery reason registry 恰好只有：

```text
1 ORPHANED_UNCERTAIN
```

reason 由下列机械 predicate 唯一决定：

```text
writer/reaper 已取得 writer owner lock 与目标 epoch recovery lock
AND selected frontier 已唯一验证
AND selected frontier 不是覆盖 valid normal footer 的 `NORMAL_SEALED` frontier
AND 对原 segment 执行一次零 byte mutation 的 force(true) 并确定成功
AND force 后对原 segment 做两次 close/reopen full read 得到相同 length+digest
=> ORPHANED_UNCERTAIN
```

force 必须作用于经过 path/descriptor inode equality、regular file、`st_nlink=1` 验证的
exact segment；调用前后 length/bytes 必须相同。它只把已经观察到的完整 tail 推向稳定
介质，不能把 frontier 后 bytes 升格为 durable record。force 抛错、返回语义未知、descriptor
不可取得或任一复读不一致时，零 sidecar、零 successor，只能 quarantine/等待 operator；
不能把 page-cache digest 写进 durable sidecar 后再让掉电丢失 source bytes。

predicate 不成立时不发布 sidecar，只能等待或 quarantine。process death、write/force outcome、
bootstrap publish、normal-close intent 等更细诊断不是可从 durable bytes 唯一恢复的
sidecar identity，故只允许在 sidecar 发布后由新 writer 的 recovery/gap record 陈述，不得
改变 sidecar reason。不得写墙上时间、PID、随机叙事、路径或异常字符串。

开始生成前先枚举已有 sidecar：若恰有一个 valid sidecar 且其 selected frontier、segment
length/digest 与当前机械复验相等，必须幂等接受，不能重选 reason；不存在时才生成 reason
1；多个不同 valid sidecar 或 source bytes 已改变时 quarantine。由此，同一 segment bytes
+ selected frontier 只产生一种 canonical sidecar。

发布：

```text
同 epoch 目录 CREATE_NEW `.recovered-seal.tmp.<content-digest>`
  → writeFully 256
  → sidecar file fsync
  → publish-no-replace 到 content-addressed final
  → epoch directory fsync
  → 关闭并从 final path 全量复读
  → 验证 digest/CRC/MAC/全部 binding
  → 第三次 close/reopen 原 segment，验证 length/digest 仍等于 force 后绑定值
  → 再允许新 writer 记录 RECOVERY/GAP
```

同名 temp/final 已存在只接受逐字节相同；reaper 在 owner+epoch recovery lock 下只能清理
digest/长度/CRC/MAC 与 target 不一致或已经由同 digest final 取代的 canonical temp，且清理
后必须 directory fsync/full census。不同 valid sidecar、目录 fsync 不明、原 segment
length/digest 变化均 fail closed。原 `segment` bytes 在全过程前后必须保持完全相同。
kill/power-loss matrix 必须覆盖 force 前、force 返回前后、第一次/第二次复读、sidecar
file fsync、publish、directory fsync、final reread、第三次 source reread与 successor
bootstrap；任何无法证明 force+source+sidecar 闭合的 cut 都不得产生 successor。

---

## 16. 读取、recall 与 completeness

### 16.1 authority

Canonical authority 的公共部分是 validated segment header、converged Blob locator/validated
Blob bytes，以及以下互斥分支之一：

```text
LIVE_OPEN =
  validated owner/epoch lease identity
  + stable selected authenticated frontier snapshot
  + exact segment prefix [0, selected.byte_offset)

TERMINAL_NORMAL =
  selected authenticated frontier with NORMAL_SEALED
  + exact segment through validated normal footer

TERMINAL_RECOVERED =
  selected authenticated durable frontier
  + unchanged full observed segment
  + matching deterministic recovered seal
```

live epoch 不需要先有 footer/sidecar 才能读取已发布 durable prefix。lock-free live reader
必须从已持 no-follow descriptors 做 `A/B full read + selection → exact prefix read →
A/B second full read + selection`；前后 selected slot digest、frontier identity/offset/prefix
digest 与 pair validity 必须相同。checkpoint 并发使任一值变化、出现 torn/invalid pair 或
segment prefix 不匹配时，丢弃本次结果并做有界 retry；达到 retry cap 返回
`STORAGE_UNAVAILABLE/partial`，不得混合两个 cut。reader 绝不读 selected offset 后的 bytes
来补正文。terminal branch 则按 §11/§15 的 close/reopen full validation。

Room、FTS、HotSnapshot、内存 dedup map 和 UI cache 都是可丢弃 projection。

### 16.2 exact read

exact Session read 必须返回：

- exact stored `SessionRecordV1` source bytes；
- `WriterRefV1`、sequence 和 physical locator；
- selected frontier proof；
- inline payload exact bytes，或通过五字段 BlobRef + active locator 复读的 exact Blob
  plaintext bytes；
- payload digest verification；
- policy/retention/stage outcome；
- gap/opaque/unsupported schema 状态。

不能用摘要、embedding、LLM reconstruction 或另一个相似 Blob 替代 missing bytes。

### 16.3 downgrade

reader 不支持某 major/required feature/payload type 时：

- 不删除原 bytes；
- 不把 unknown enum 映射到 `UNSPECIFIED` 后继续；
- 不进入 reducer；
- 可以在权限允许时 opaque export；
- completeness 返回 partial/failed，并明确 unsupported range。

### 16.4 `TransportCapsV1`

M9.0 跨进程 transport 的唯一 protocol-safety caps：

```text
MAX_BINDER_INLINE_SERIALIZED_ENVELOPE_BYTES = 49,152
MAX_RELIABLE_PIPE_LOGICAL_PAGE_BYTES        = 1,048,576
```

第一个上限覆盖完整 serialized control envelope 与 embedded inline payload，不是只数 body；
它是 Sense product cap，不是声称 Android Binder transaction 的系统极限。超过 49,152 bytes
必须走 reliable-pipe/PFD logical page 或五字段 BlobRef。第二个上限覆盖一个 logical page
的 exact content bytes；更大内容必须分页或先 durable publish 为 Blob。BudgetProfile 可以
收紧两者，不能放宽。这两个值是协议内存/边界安全上限，不是设备性能预算证据。

每个 pipe page 的 inline control descriptor 使用：

```proto
message ReliablePipePageEnvelopeV1 {
  fixed32 protocol_major = 1;        // 1
  fixed32 protocol_minor = 2;        // 0
  fixed64 required_features = 3;     // 0
  string transfer_id = 4;            // canonical id128
  fixed64 page_index = 5;            // first page = 0, checked +1
  fixed64 content_length = 6;        // 0..1,048,576
  bytes content_digest = 7;          // SHA-256(exact page content), 32 bytes
  string content_type = 8;           // canonical_ascii, 0..126 bytes
  bytes fixed_cut_digest = 9;        // exactly 32
  optional string page_cursor = 10;  // canonical id128；page 0 absent
  optional string next_cursor = 11;  // canonical id128；terminal page absent
  reserved 12 to 31;
}
```

envelope 本身连同其外层 request/response control message 仍受 49,152-byte cap。PFD 是
out-of-band reliable pipe handle，不是 file-backed payload。

本 page envelope **不单独授权一个无界 transfer**。每个具体外层 message-kind 在其 phase
schema Accepted 前，必须冻结并由 signed/hashed cut descriptor 绑定：

```text
max_page_count_u32
max_total_content_bytes_u64
expected_page_count_or_upper_bound
expected_total_content_bytes_or_upper_bound
```

两项 protocol hard cap 必须是 finite/nonzero，且还要取当前 BudgetProfile 的更小值；
`page_index+1`、累计 page count 与累计 content bytes 全部 checked arithmetic。外层 schema
缺任一字段、声明无界、累计超过声明/协议/profile，或 concrete hard cap 尚未由 phase ADR
接受时，整个 request 返回 `FEATURE_STAGE_BLOCKED`，不能只靠“每页最多 1 MiB”无限续页。

`fixed_cut_digest` 是 transport binding，不是单独的 frontier/completeness proof。具体
调用协议必须在外层 request 中提供并验证自己的 canonical cut descriptor：

```text
fixed_cut_digest =
  SHA-256(
    ASCII("sense.memory.v1/transport-fixed-cut/v1") || 0x00 ||
    cut_descriptor_uri_length_u16_be ||
    cut_descriptor_uri_canonical_ascii ||
    exact_cut_descriptor_length_u32_be ||
    exact_cut_descriptor_bytes
  )
```

`cut_descriptor_uri` 为 `1..255` bytes canonical ASCII；descriptor bytes 必须受完整 inline
envelope cap。digest 非零时，consumer 必须已持有由具体调用协议验证的 URI+descriptor，
复算相等后才接收 page；transport 不解释 descriptor，也不能仅凭 digest 宣称 cut 完整。
全零 digest 只在外层 message-kind closed registry 明确标为 `NO_FIXED_CUT` 时合法；需要
fixed cut 的 recall/export message 中全零一律 fail closed。
计算结果若恰为全零也 fail closed，不能与 sentinel 混用。

分页与验证：

- 同 transfer 的 `transfer_id`、content type、fixed-cut digest 必须逐页相同；
- 每页接收前复验外层 `max_page_count/max_total_content_bytes`，terminal 时实际 count/total
  必须满足 descriptor 的 exact 值或已声明 upper-bound grammar；
- `page_index` 从 0 严格连续；重复、倒退、跳跃或 overflow fail；
- page 0 的 `page_cursor` absent；后续 page 的 cursor 必须等于上一页 `next_cursor`；
- `next_cursor` absent 恰好表示 terminal page；terminal 后收到任何 page fail；
- `content_length=0` 只允许 terminal page，digest 必须是 SHA-256(empty)；
- consumer 在释放 content 前先按 descriptor 做 checked length/cap，再读恰好
  `content_length` bytes、等待 EOF、验证 digest；
- short read、trailing byte、length/digest/type/fixed-cut/cursor mismatch、writer
  `closeWithError`、Binder death 或取消都使该 page/transfer 失败或 partial，不能补零、
  截断或继续 reducer；
- 同一 request 最多一个未确认的 logical page；consumer 完成验证/close 后才请求
  `next_cursor`，防止无界在途页；
- producer 只有写完 exact page 后正常关闭 write end；失败使用 reliable pipe
  `closeWithError`；consumer terminal/cancel/error 必须关闭 read end，协调器同时关闭尚持有
  的两端；
- 禁止 file-backed PFD temp、plaintext temp、ashmem dump 和“先写文件再传 FD”旁路；
- X02/SCHEMA_ONLY 只对 envelope/page state machine 使用 ByteArray/in-memory fake，不创建
  OS pipe。

transport 只搬运已由当前 policy/cut 授权的 bytes，不改变 Journal/Blob authority；
pipe 成功不能替代 DurableAck，pipe 失败也不能删除 canonical record。

---

## 17. Erasure 与 capture gate 的交点

具体 WriterSourceAuthorityManifestV1、ErasureManifest、key destruction 和跨进程 lease fence wire 由
ADR 0017 的独立 phase gate 冻结。ADR 0016 只接受以下不可放宽的调用顺序：

```text
CapturePolicy preflight 前检查 erasure generation/fence
  → 取得 global plaintext read lease 前复验
  → 选择 sealed INLINE/BLOB materialization plan 前复验
  → 取得 conservative queue slot/byte reservation 前复验
  → BLOB plan取得 Blob plaintext + per-volume failure-contingency reservations 前复验
  → bounded body read 前复验（至多一次）
  → CapturePolicy final decision/atomic ownership transfer 前复验
  → 分配 record/blob/physical ID 前复验
  → Blob encrypt/write 每次 byte handoff 与 locator PREPARED/ACTIVE 前复验
  → attempt commit 到 reservation、返回 admission 前复验
  → writer dequeue/sequence 前复验
  → frame encrypt/write 每次 byte handoff 前复验
  → frontier commit 前复验
```

上述是 §6.1 的同一顺序，不是第二套可重排的摘要：queue reservation 和 BLOB plan的两个
额外 reservation 必须在第一字节 plaintext materialize **之前**已经持有。任何 reservation
失败、fence变化或 preflight/final拒绝都不得调用 body builder；不允许先读 body再决定
INLINE/BLOB或再申请 downstream capacity。

一旦 selected frontier 已 durable 覆盖 record，后续 fence 翻转不能把事实改判为 FAILED，也
不能压掉 terminal callback。erasure PREPARING barrier 必须等待所有旧 fence live token
已经交付唯一 terminal，或由仍存活 producer 的 volatile waiter/process-epoch barrier
明确归类为 `CALLBACK_LOST/INDETERMINATE` 并进入 reconciliation；完成该 drain 后才可删除
旧 generation。writer 与 producer 都死亡时，barrier 只能把整个已知 process epoch 标为
unresolved，再按 retained attempt 与 selected frontier 对账，不能虚构一个 durable
per-token outcome record。ADR 0018 的 outcome ledger 是测试/evidence wire，不能反向成为
运行时 token 持久化机制。Ack 只陈述 fence 生效前已发生的 durability，不授权新的读取、
外部效果或后续 capture。

进入 erasure PREPARING 后，旧 generation 禁止新 allocation；各 live process 必须通过
process-epoch barrier ACK 证明旧 queue/lease 已停止。Broker restart 后的空 heap 不等于
旧进程已 drain。

若 exact erasure control schema 尚未冻结/实现：

- real-data capture API 返回 `FEATURE_STAGE_BLOCKED`；
- `LocalErasureControlPhaseGateV1` 不为 PASS；
- X02 仍只可运行 `SCHEMA_ONLY` 的 in-memory codec/fuzz，不得创建 persistent root；
- 不得以“以后再删”作为先持久化用户原文的理由。

---

## 18. Feature stage 与所有权门禁

### 18.1 X02

X02 允许：

- 创建最小 `memory-protocol` / `event-journal` scaffold；
- 编译 Proto；
- 跑纯 synthetic golden/property/fuzz test，但载体只能是 ByteArray/in-memory channel；
- 以内存 fake 验证状态机；
- `FeatureStage = SCHEMA_ONLY`。

X02 禁止：

- Android 用户数据目录建根；
- 专用 lab root、临时 persistent root 或“只保存 synthetic”的磁盘旁路；
- Keystore alias、真实 Journal/Blob 文件；
- 读取输入框或 Provider transcript；
- 真实用户 plaintext；
- SHADOW/CANARY/DEFAULT；
- UI 宣称 Memory 已保存。

### 18.2 真实数据最小 gates

唯一 gate authority 是 ADR 0018 §14 的 closed `GateIdV1` registry 与
`ADR0018_CAPABILITY_DAG_V1`；本 ADR 不维护一份会漏掉条件边的平铺副本。实现必须用
`(capability_id, requested_stage, ProfileExecutionClassV1,
ProfileContextDigestV1(flags,writer,SourceWriterShape,projection_version))` 递归展开 exact
**static** prerequisites。具体 destination/source IDs、epochs、authority、cut/resource只进
`OperationContextV1`，不能退回 caller提供的两个 boolean。stage snapshot先导入 ADR 0018
§14.3 static reducer：

```text
all_pass =
  exact STATIC_SNAPSHOT verdict map keys == recursively expanded static prerequisite set
  AND every GateVerdictV1 is exactly PASS

prerequisite_stage_ceiling =
  requested_stage if all_pass else min(requested_stage, SCHEMA_ONLY)

effective_stage =
  min(build_profile_max,
      local_requested_stage,
      dependency_stage,
      prerequisite_stage_ceiling)
```

static ceiling本身不授权任何副作用。每次 producer admission必须再调用
`admitOperation(snapshot,NormalOperationCapabilityIdV1,
TrustedOperationProvenanceV1,OperationContextV1,DynamicAuthorizationBundleV1)`：要求
operation是 finite profile context的合法 instance，exact dynamic key set相等；observe+
revalidate current unlock/consent/source/cumulative-erasure authorities，原子 consume
one-shot MemoryUse/Export/Disclosure grant，并签发不可延长
`OperationValidityLeaseV1`。每次 builder、writer、Cipher、Blob/locator、frame/frontier
byte handoff前，都重验 lease中的 static receipt freshness、snapshot generation、
OBSERVE fences与 consumed grant；任何 bound跨越后后续零 byte/零 commit。

其中 real-data persistence 的共享晋级边恰为 ADR 0018
`NORMAL_PRODUCT_SHARED_V1 ∪ REAL_DATA_OVERLAY_V1`；product-synthetic DARK 只使用前者，
measurement-only 则完全走只返回 `ALLOW|NOT_RUN_BLOCKED` 的 permit reducer，不返回
FeatureStage。GateVerdict 与 FeatureStage 不得 cast、比较 ordinal 或放进同一 `min()`；
missing/unknown/任何非 PASS verdict 都 fail closed。这不是完整 flat list，仍须叠加
capability-specific edges：

requested `OFF` 的 prerequisite set 为空且 effective 必须保持 OFF；所有路径须满足
`effective_stage <= min(requested,build,local,dependency)`。

- `JOURNAL_WRITE` 递归要求 `PERSISTENT_SUBSTRATE`、
  `JournalFrontierDurabilityGateV1` 与 `BudgetProfileGateV1`；
- 任一 validated operation/cut closure 在 ADR 0018 trusted context 中
  `USES_BLOB=true`（无论 body 大小、直接或 transitive BlobRef/locator/lease）都叠加完整
  `BLOB_STORE`，因此必须有 `BlobWireLocatorLeaseGateV1`；只有 closure可证明
  `USES_BLOB=false` 的 inline-only path不依赖 Blob。size-threshold-only derivation必须在
  small-with-BlobRef、Blob-backed recall/index/maintenance/export/erasure负测中失败；
- ordinary single-writer capture 不要求 `SourceManifestPhaseGateV1`；cross-writer/source
  provenance、统一 Session 或 export 才叠加该 gate；
- retention/compaction、maintenance、local erasure 与旧物理对象回收必须按 DAG 叠加
  `RecordIdentityTombstonePhaseGateV1` 及各自 cumulative/drain/reboot/lease gates；
- capture 必须叠加 `SourceErasureAuthorityGateV1`；本地 erasure control 与 runtime
  capability 两个 gate 均不可省略；
- `ReleaseSigningAuthorityGateV1` 只属于 release publication branch，不进入已安装设备
  runtime。

snapshot 只能缓存/收紧授权；旧 A/B stage snapshot 的完整成对回滚不能由 generation 自证，
因此 SHADOW+ 必须由后续 rollout ADR 冻结可验证的新鲜度/单调性或显式 boot
reconfirmation。任一 recursively required gate 未 PASS 时，该 capability fail closed；
owner/root/keyring/control 等 substrate 前置未 PASS 时一律回退 `SCHEMA_ONLY` in-memory
codec/fuzz，不得创建任何 data-plane root/key/record。唯一例外是 ADR 0015 future
`AuthorityBootstrapPermitV1` 的 control-plane transaction：它不是 FeatureStage，只能在
`RootBootstrapControlPhaseGateV1` 等先行证据 PASS 时创建 root shell/bootstrap lock 和
owner-signed local owner A/B；当前该 phase gate BLOCKED，因此 X02 仍创建零目录。

Persistent synthetic/lab product `DARK` 仍是 normal FeatureStage，必须满足目标
capability 的 authoritative DAG，包括 accepted BudgetProfile；它不是预算或 durability
自举旁路，也不能充当 real-data gate evidence。缺任一 exact prerequisite 时，该
capability 仍为 `SCHEMA_ONLY`。

为了产生 `LocalErasureCapabilityGateV1` 与 0018-B 的被测 evidence，ADR 0018 另有一次性
`SyntheticMeasurementPermitV1`。它不是 FeatureStage 或 product `DARK`，只能在 control/
wire/substrate/harness/专用 namespace 与有界实验配置前置全部 PASS 后运行 known synthetic
bytes；被测 Journal/Blob/rotation/erasure gate 可以输出 FAIL/INCONCLUSIVE，不能反过来成为
permit 前置。当前 `SyntheticMeasurementControlPhaseGateV1` 与 evidence wire 均 BLOCKED，
所以该旁路现在也不可执行。

所以 X02/Gate 0 当前 hard maximum 仍是 SCHEMA_ONLY；本段只冻结未来 gate 分层，不授权
现在创建 persistent lab root。

`ReleaseOwnerContinuityGateV1` 只承担 ADR 0015 冻结的 release/application owner 连续性；
下列 filesystem/keyring 条件属于 `DataRootContinuityGateV1`、
`InstallationKeyringIdentityGateV1`、ADR 0017 的 key-use safety gates 和
`JournalFrontierDurabilityGateV1`，不能被 owner gate 的 PASS 隐式替代：

- fixed release/app/DataOwner identity 与 `installation_id` 由 accepted owner state +
  selected Keyring 共同认证并绑定；两者类型不同，绝不比较为“相等”；
- writer-kind owner lock 和 epoch lock 连续；
- predecessor chain 唯一；
- Keyring selected payload、segment header、lease、frontier 身份一致；
- erasure generation 没有回退。

---

## 19. fsync 语义与错误分类

### 19.1 本 ADR 中的词

| 术语 | 要求 |
|---|---|
| `writeFully` | 处理有进展短写；零进展/异常进入明确错误，不重新加密 |
| `file fsync` / `force` | 对同一已打开 regular file 请求 data+metadata 持久化，并检查返回 |
| `directory fsync` | 对包含发生 entry mutation 的目录请求持久化，并检查返回 |
| `close/reopen full reread` | 丢弃本进程解析对象，用新 descriptor 读取固定完整长度并重新验证 |
| `publish-no-replace` | final 不存在时原子发布；已存在只接受协议定义的逐字节同一内容 |

“调用成功”仍受 Android/Linux、文件系统、硬件和断电模型限制。本 ADR 声明的是在支持且
通过设备门禁的本地文件系统上的软件 durability protocol，不宣称对失效 NAND、恶意内核
或 OEM 虚假 fsync 有绝对证明。

### 19.2 definite 与 indeterminate

“没有向 segment 发出 byte”可以成为排除证据，但单独仍不能生成 `FAILED`；writer 必须
复读 relevant lineage/selected frontier，并证明 exact record ID+commitment 被排除，才能
返回 reason 1/2。以下一律 indeterminate：

- write 抛错但返回进度不可证明；
- force 失败/异常；
- frontier slot write/fsync 失败；
- fsync 后 close/reopen pair 不能重新选择目标；
- Binder 在 writer 内部结果提交期间断开。

indeterminate segment 永久只读，后续使用 new writer epoch、segment identity 和
independent DEK。不能通过 sleep/retry force、truncate 到“最后好 frame”或复用 sequence
恢复写入。

---

## 20. 实施分层

Gate 0 接受以下顺序：

### M9A-01：SCHEMA_ONLY

- 本 ADR Proto/enum/validator；
- exact physical codec（只对 ByteArray/内存 channel）；
- descriptor/golden/fuzz/property test；
- 不创建 Key、文件或用户数据；
- feature hard cap `SCHEMA_ONLY`。

### M9A-02E0：local-erasure contract 与 root-bootstrap control wire

- 先由 ADR 0017 冻结不依赖 installation data 的 local-erasure semantic/control phase；
- 冻结 root bootstrap lock/permit wire 与 clean namespace evidence；
- 本阶段只跑 codec/fuzz/kill contract，仍不创建 root/key/data。

### M9A-02O：release owner continuity

- 冻结 root bootstrap lock、owner-signed local A/B、`AuthorityBootstrapPermitV1` 与
  `RootBootstrapControlPhaseGateV1`；
- 冻结 owner manifest phase wire/store 与 `ReleaseOwnerContinuityGateV1` evaluator；
- 证明 stable owner continuity、升级/轮换与 full-old-pair limitation；
- 未通过前 data-plane persistent root blocked；control-plane root shell 只服从上述 permit。

### M9A-02K：Keyring bootstrap 与 key-use safety

- owner continuity PASS 后，冻结 canonical bootstrap intent/recovery/receipt；
- 只由 main/Broker one-shot 创建 Keyring/operation frontiers/purpose13 owner leases；
- API 29–30/31–36.0/36.1+ capability ceiling 与 key-use gates 分层验证。

### M9A-02：CapturePolicy producer

- 两阶段 preflight/finalize；
- bounded single body read；
- retained exact attempt；
- 不接文件 writer。

### M9A-03：ADR 0017 crypto/Blob/locator

- 消费 M9A-02K 已验证 Keyring；实现 operation frontier；
- Blob chunk AEAD；
- locator convergence、lease fence、erasure；
- kill-point test。

### M9A-04：Journal writer/frontier/recovery

- `ReleaseOwnerContinuityGateV1` 与独立 writer ownership/storage/crypto subgate；
- physical files；
- DurableAck/INDETERMINATE；
- recovery seal；
- Android device matrix。

### M9A-02E1：local erasure runtime

- erasure generation、allocation fence、process barrier、cumulative request；
- authenticated record-identity tombstone、防复活与 reboot census；
- 未通过前后续真实 capture blocked。

### M9A-05：Brain 单 writer evidence recorder

- 只接入已通过 M9A-02 CapturePolicy 且受 M9A-02E1 runtime gate 的 deterministic record；
- turn/Brain terminal 使用 Durable/Failed/Indeterminate 对账；
- 不扩大 M9A-04 的 authority，也不在 Brain 自行解释 frontier。

任何 PR 不得把后阶段 fake 当作前阶段 production gate PASS。

---

## 21. 测试与证据门禁

本节均为**未来必须通过**；本文合并不代表已经执行。

### 21.1 schema/golden

- descriptor digest 与字段号/enum/reserved registry；
- 每个 Proto message canonical valid/invalid fixture；
- `fixed32`/`fixed64` little-endian golden；
- physical big-endian golden；
- 32-lower-hex ID、canonical ASCII、optional presence；
- BlobRef 恰好五字段；
- M9.0 feature 非零拒绝；
- unknown field 保留、unknown enum fail closed；
- parse/serialize bytes 不 canonical 的对照 fixture。

### 21.2 layout arithmetic

CI 以独立脚本证明：

```text
segment header = 512
owner lease = 128
lease = 128
frame header = 96
MAX_INLINE_RECORD_BYTES_V1 = 1048576
frame physical bytes =
  checked_add(96, actual_serialized_payload_bytes, 16, 4, 4)
actual_serialized_payload_bytes <=
  descriptorDerivedMax(MAX_INLINE_RECORD_BYTES_V1, all_field_caps)
actual_serialized_payload_bytes >=
  ADR0016_DERIVED_MIN_SERIALIZED_JOURNAL_FRAME_PAYLOAD_V1_BYTES
minimum physical frame =
  ADR0016_DERIVED_MIN_PHYSICAL_FRAME_BYTES_V1
minimum writable segment =
  ADR0016_DERIVED_MIN_WRITABLE_SEGMENT_BYTES_V1
maximum reachable sealed segment =
  ADR0016_DERIVED_REACHABLE_SEGMENT_BYTES_MAX_V1
maximum single accepted physical frame =
  ADR0016_DERIVED_MAX_SINGLE_ACCEPTED_PHYSICAL_FRAME_BYTES_V1
frontier slot = 256
normal footer = 256
recovered seal = 256
```

每个 offset 必须无重叠、无空洞（显式 reserved 除外），所有 checked addition 在
`UINT64_MAX`、`UINT32_MAX`、`INT_MAX`、descriptor-derived min/max 和 BudgetProfile 边界有
property test。CI 还必须对 derived min/max做 descriptor独立重算，并覆盖
`minimum-1/minimum/minimum+1` 与 `maximum-1/maximum/maximum+1`；`minimum-1` 不能通过
构造空/unknown-only frame伪装合法，`maximum+1` 不能通过放宽 frame count、payload或 GCM
block cap伪装可达，并证明 `minimum <= maximum`。
minor 0 的独立 fixture还必须证明 accepted set恰为空、status恰为
`UNAVAILABLE_EMPTY_PAYLOAD_REGISTRY`、五个 numeric value/witness全部 absent，任何尝试把
opaque recovery frame或 sentinel当 semantic witness均拒绝。首个 token 的 minor-bump
fixture必须从空集合迁移到 `AVAILABLE`，更换 descriptor digest，生成非空集合、最大单帧和
min/max segment witness；随后删 token、改 presence/cap而未 bump digest或未重算 geometry
都必须失败。

### 21.3 admission/ack model

- status × durability × presence 全笛卡尔积；
- rejected 不分配 sequence；
- best-effort 无 token/ack；
- ack token canonical id128、CSPRNG/nonzero/unique 与 writer-death invalidation；
- 每个 durable token 全局 at-most-one terminal；writer+callback channel 存活时
  exactly-one；dispatch 前死亡允许 zero，并必须进入 `CALLBACK_LOST`、exact
  record/commitment reconciliation 与 new-token 路径；duplicate/late callback 不可达
  consumer；
- same record/same commitment dedup；
- same record/different commitment conflict；
- callback-before-frontier 模型不可达；
- frontier-before-callback 可达；
- `FAILED` 只在 exact record ID+commitment 已由 selected frontier/recovery 证明排除；
- `INDETERMINATE` 只能用 retained exact record ID+commitment reconciliation；
- lost producer state 禁止 blind retry并产生诚实 gap。

使用 model-based state machine/property test，不只写 happy-path examples。

### 21.4 physical corruption/fuzz

- 每个 header/slot/footer/sidecar byte 单点翻转；
- magic/version/flags/reserved/length/overflow；
- truncation at every byte boundary；
- CRC valid、MAC invalid；
- CRC/MAC valid但 identity binding 错；
- valid frame beyond frontier；
- arbitrary old-valid frontier survivor + invalid peer：只读/reconciliation，peer零写、
  old segment/DEK零新 init，successor必须 new epoch/segment/DEK；即使 survivor sequence
  小于曾用 volatile/later cut也绝不复用 nonce；
- duplicate/backward/gapped sequence；
- oversized Proto、deep nesting、unknown features；
- OOM 前拒绝，不由攻击 length 触发大分配。

### 21.5 kill-point

在以下每一步前后 kill：

- root/parent/bootstrap entry creation 与每层 directory fsync；
- segment/A/B/lease file write/fsync；
- lease lock、temp directory fsync、rename、parent fsync、final reread；
- Blob temp/chunk/footer/fsync/reread/publish/dir fsync/final reread；
- locator PREPARED/ACTIVE/ACTIVE 每次 write/fsync/reopen；
- frame 每个短写位置、data force；
- frontier slot 每个短写位置、fsync、close/reopen；
- Ack dispatch 前/中/后；
- footer write/force/frontier；
- recovered sidecar write/fsync/publish/dir fsync/reread；
- erasure fence 与每个 process barrier。

每次重启必须满足：

```text
不把 frontier 后 bytes 当 durable
不修改 orphan segment
不复用 old epoch/segment/DEK/nonce ordinal
不产生 callback-before-frontier
不让 BlobRef 指向未收敛 locator
不隐去 gap
```

### 21.6 A/B

- A torn/B valid、B torn/A valid；
- A old/B new、B old/A new；
- same generation same bytes；
- same generation different bytes；
- bootstrap A/B exact mirror；已发布 epoch 出现 UNUSED 必须拒绝；
- target-first write/fsync/reopen、peer-second write/fsync/reopen 的每个 kill/bitflip；
- callback 前必须 exact mirrored pair；callback 后任一槽 bitflip 仍保留相同 Ack cut；
- one-valid 只能进入只读恢复：不得向 peer 槽写回、不得重新初始化旧 DEK。survivor若为
  覆盖 valid footer 的 `NORMAL_SEALED`，必须走 normal-sealed/zero-sidecar branch；否则先写
  recovered-seal sidecar并永久退休旧 segment/epoch/DEK，再以新 epoch、新 segment、
  新 DEK继续。恢复过程不得扩大旧 generation/cut，也不得把单槽状态重新包装成
  callback 所要求的 exact mirrored pair；
- valid adjacent pair同样分别覆盖 higher=`NORMAL_SEALED`/non-normal 两支；前者零
  sidecar，后者才发布 recovered seal，二者都不补写 lower/peer；
- both invalid；
- full old pair rollback fixture，必须显示为“当前设计不可检测”，不能误报检测成功；
- generation overflow。

### 21.7 多进程与设备

- main/IME/brain 同时竞争 owner；
- writer crash 后 OS 释放 file lock；
- reaper 与新 writer 竞争；
- Binder death/late callback；
- broker restart 空 heap 不等于 old leases drained；
- API 29、当前 target API 与代表性 AOSP/OEM/filesystem；
- cold boot、process kill、app force-stop、存储满、I/O fault；
- directory fsync port 不可用时 fail closed；
- 设备预算与峰值空间满足 ADR 0018。

### 21.8 security/erasure cross-gate

- ADR 0017 alias/purpose/AAD/MAC fixed vectors；
- KeyAuthorizationProfile digest mismatch；
- local erasure request 与每个 ID/byte handoff race；
- old generation allocation impossible；
- old physical unlink + parent fsync + close/reopen census；
- backup/data-extraction rule inspection；
- 日志/diagnostic 不含用户文字、Key、path、package、Provider body。

### 21.9 transport caps

- 49,152-byte complete inline envelope boundary `-1/0/+1`；
- 1,048,576-byte logical page boundary `-1/0/+1`；
- descriptor-declared length checked before allocation；
- zero-length page、short read、trailing byte、digest/type/fixed-cut mismatch；
- fixed-cut canonical digest vector、NO_FIXED_CUT zero sentinel 与 required-cut zero rejection；
- cursor absent/present matrix、duplicate/skip/overflow/terminal-after-page；
- writer normal close、`closeWithError`、reader close、cancel 与 Binder death；
- exactly one unacknowledged page；
- source scan/manifest test 证明不存在 file-backed PFD/plaintext temp；
- SCHEMA_ONLY test 证明不创建 OS pipe、FD、directory 或 file。

---

## 22. CI fail-closed 条件

以下任一出现立即阻断 M9A merge 或 stage 提升：

- descriptor digest 未更新或字段号/enum 复用；
- BlobRef 出现第六个非 reserved 字段；
- Proto/physical endian 混用；
- required feature 非零仍被 M9.0 reader 接受；
- 单 segment DEK 的 frame count/GCM authenticated-block aggregate 超过协议 ceiling，或
  在 Cipher init 后才检查 ceiling；
- admission 返回 sequence；
- rejected admission 返回 record/token；
- best-effort 生成 Ack；
- Ack callback 可能早于 reopen-selected frontier；
- `INDETERMINATE` 自动 retry；
- producer exact bytes 丢失后重建 attempt；
- write/force 不确定后续写旧 segment；
- recovery truncate/append old segment；
- normal seal rename/move segment或写 catalog/current pointer；
- frontier checkpoint 做 slot rename或目录 mutation；
- Blob locator 未双 ACTIVE 收敛就写 Journal；
- inline envelope/page cap 被放宽、page cursor/cut/digest 未绑定或出现 trailing bytes；
- file-backed PFD/plaintext temp 进入 Memory transport；
- directory entry mutation 无 directory durability proof；
- `LocalErasureControlPhaseGateV1` 或 `LocalErasureCapabilityGateV1` 未通过却创建真实
  数据；
- X02 创建任何 persistent Memory root，或超过 `SCHEMA_ONLY`；
- owner lineage 多 head/断裂仍继续；
- A/B full-pair rollback被宣称可本地检测；
- 未运行要求的 Android kill-point/device matrix却将 implementation 标为 Accepted。

---

## 23. 已接受、延期与明确拒绝

### 23.1 Gate 0 已接受

- §3–§5 Proto/ID/compatibility registry；
- §6 admission/dedup/retry；
- §7–§11 Journal physical layout、owner、frontier、normal seal；
- §12 Blob-before-Journal dependency；
- §13 Ack asymmetry；
- §14–§15 bootstrap/recovery protocol；
- §16 reliable-pipe page envelope/cursor/cut 与协议安全 caps；outer per-call
  `max_page_count/max_total_bytes` 的 exact phase schema 和产品数值仍延期；
- §17–§18 erasure/stage dependency；
- §21–§22 implementation exit gates。

### 23.2 延期

- M9.1 Event/Relation/Recall/Receipt schema；
- M9.2 Claim/Coverage；
- M10 Tool Effect Ledger；
- WriterSourceAuthorityManifestV1/ErasureManifest/rotation control wire（由 ADR 0017 phase gate）；
- concrete queue/batch/deadline 数字（由 ADR 0018-B device evidence）；
- external archive/export protocol；
- anti-rollback hardware/remote witness。

延期项在 schema/phase gate Accepted 前必须返回 `FEATURE_STAGE_BLOCKED`。

### 23.3 拒绝

- JSON 作为 canonical Journal；
- content-addressed logical Blob ID；
- 用墙上时间排序 writer；
- queue admission 等同 durable；
- callback timeout 后新 ID 重发；
- 将 valid CRC/AEAD tail 自动提升为 durable；
- 单 mutable frontier/pointer；
- 每 checkpoint rename slot；
- recovery truncate；
- reuse old epoch/segment/DEK；
- normal seal 移入 `sealed/`；
- Room/FTS 作为 authority；
- summary/embedding 替代 exact transcript；
- “所有历史完整”而不声明 capture/durable/retention/policy 范围。

---

## 24. 参考

- [Protocol Buffers Encoding](https://protobuf.dev/programming-guides/encoding/)
- [Protocol Buffers Updating a Message Type](https://protobuf.dev/programming-guides/proto3/#updating)
- [Android Data and File Storage Overview](https://developer.android.com/training/data-storage)
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android Keystore System](https://developer.android.com/privacy-and-security/keystore)
- [Linux `fsync(2)`](https://man7.org/linux/man-pages/man2/fsync.2.html)
- [Linux `rename(2)`](https://man7.org/linux/man-pages/man2/rename.2.html)
- [RFC 4648 Base Encodings](https://www.rfc-editor.org/rfc/rfc4648)
- [NIST SP 800-38D: GCM](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- [NIST SP 800-38D Rev.1 second pre-draft call：usage bounds](https://csrc.nist.gov/pubs/sp/800/38/d/r1/2prd)

---

## 25. 最终验收语句

本 ADR 只能在以下精确意义上称为 Accepted：

> Sense 已经为 M9.0 接受一套可实现、可故障注入、可独立验证的 wire 与 durability
> contract；它把 queue admission、physical append、durable frontier 和 observed Ack
> 分离，把 Blob locator 收敛放在 Journal 引用之前，并在任何不确定 I/O 后永久退休旧
> epoch/segment/DEK。

在 M9A-01 至 M9A-05 的 descriptor、golden、property、kill-point、Android device、
security、erasure 和 budget evidence 全部满足前，必须继续标记：

```text
implementation evidence pending
real-user capture blocked
```
