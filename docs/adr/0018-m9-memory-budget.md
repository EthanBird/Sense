# ADR 0018：M9 Memory 预算、设备证据与晋级门禁

- 状态：**Partially Accepted / Evidence Wire Blocked**
  - **0018-A Semantic Measurement Contract：Accepted**：仅接受 metric/profile JSON、
    workload、统计/停止规则、verdict/reason 语义与 dependency DAG
  - **0018-E Evidence Wire：Proposed / BLOCKED**：`DeviceSuiteManifestV1`、
    `BudgetEvidenceBundleV1`、raw rows 与三个 outcome ledger 的 exact schema/encoding/caps/
    digest/signature trust wire 均未接受
  - **0018-B BudgetProfileV1：Pending**：90 个 required budget value 全部 `UNSET`
- 决策日期：2026-07-26
- 适用范围：M9A/M9.x 的本地存储、capture、Journal、Blob、索引、Recall、维护、擦除与 egress
- 依赖：ADR 0015、ADR 0016、ADR 0017

> 本 ADR 不用桌面 JVM 数字伪装 Android 设备预算。Gate 0 只接受如何测量、如何验证、
> 如何形成 verdict 以及哪些依赖必须先通过。真实阈值只能由可复核的实体设备证据冻结。

---

## 1. 决策

ADR 0018 分为三个独立接受状态：

1. **0018-A** 冻结 metric/profile JSON、workload、场景、设备角色、统计与停止规则、outcome
   conservation 的语义、verdict lattice、reason registry/排序、required tuple registry 与
   依赖 DAG；
2. **0018-E** 未来以独立 phase ADR 冻结 `DeviceSuiteManifestV1`、
   `BudgetEvidenceBundleV1`、raw sample rows 与三个 outcome ledger 的 exact descriptors、
   presence/type/cap/unknown-field policy、digest/signature coverage、trust keys、golden 与
   fuzz；当前 `BudgetEvidenceWirePhaseGateV1=BLOCKED`；
3. **0018-B** 在 local-erasure/owner continuity 先行门禁通过后，按 §16 依次执行非
   FeatureStage 的 measurement-only calibration、完整 profile proposal 与独立
   confirmatory physical suite，再填写并冻结 `BudgetProfileV1` 的 90 个 `UNSET` 值。

0018-B 未接受时：

- codec、descriptor、golden/fuzz 可以处于 `SCHEMA_ONLY`；
- 任何设备 evidence artifact 都不能自称 canonical、attested 或可被 release planner 消费；
- 当前 `LocalErasureControlPhaseGateV1=BLOCKED`，因此不得创建 Memory root、Keystore key、record、
  Journal 或 Blob，连 persistent synthetic DARK 也不得运行；
- 未来只有 §14.2 的一次性、非 FeatureStage `SyntheticMeasurementPermitV1` 才能在
  `BudgetProfileGateV1` 尚未 PASS 时执行有界 synthetic measurement；它要求 sealed
  pre-certification candidate control/root/key/use/erasure/evidence-wire/attested-harness
  先行 decision/receipt全部 PASS，不要求 normal owner continuity或 final certification；
- 不得 capture 真实用户正文，即使产品 flag 名称叫 DARK；
- `SHADOW`、`CANARY`、`DEFAULT` 不可达；
- 不能把“没观察到卡顿”写成 PASS。

---

## 2. Stage 与术语

stage 全序：

```text
OFF < SCHEMA_ONLY < DARK < SHADOW < CANARY < DEFAULT
```

`GateVerdictV1` 是与 FeatureStage 不相交的 closed enum，恰有：

```text
PASS | BLOCKED | FAIL | INVALID | INCONCLUSIVE | UNSUPPORTED |
MEASURED_NO_BUDGET
```

phase/operational gate 尚未求值或前置不足时是 `BLOCKED`；设备/平台不支持已冻结 subject
时是 `UNSUPPORTED`；本文 `OverallVerdictV1` 同名值逐字映射，其中
`MEASURED_NO_BUDGET` 保持同名、绝不折叠成 PASS。只有 exact `PASS` 能授权 normal stage；
unknown numeric/token、missing/duplicate gate 或 future enum 对 v1 reducer都按非 PASS
fail closed。`PermitDecisionV1` 另为 closed enum
`ALLOW | NOT_RUN_BLOCKED`，不得 cast 成 GateVerdict 或 FeatureStage。

- `SCHEMA_ONLY`：只加载 schema/codec/validator；不创建 Keystore、目录、Journal、Blob、Room
  或后台任务；
- `synthetic measurement`：不是 FeatureStage/DARK。只能由
  `SyntheticMeasurementPermitV1` 用 exact production candidate APK/applicationId 在 pristine
  candidate-only lab root、已登记合成 corpus 与预承诺 attempts 上执行；companion harness
  使用独立 applicationId。它不读取实际编辑器正文，也不向 Provider 发真实数据；
- `synthetic DARK`：产品 stage 意义上的 DARK，仍受 `BudgetProfileGateV1` 等 normal DAG
  限制；不能被 measurement permit 冒充；
- `SHADOW`：真实请求旁路运行但不改变用户结果；它仍处理真实数据，因此必须通过本文全部
  real-data prerequisite；
- `CANARY/DEFAULT`：面向用户逐步/默认启用。

normal capability 的静态 stage ceiling 只使用 §14.3 的 finite profile reducer：

```text
staticPrerequisites(
  capability_id,
  requested_stage,
  profile_execution_class,
  profile_context_digest
)

all_prerequisites_pass =
  verdict-map keys equal the exact prerequisite set (no missing/extra/duplicate)
  AND every exact prerequisite has the exact closed verdict PASS

prerequisite_stage_ceiling =
  requested_stage if all_prerequisites_pass
  else min(requested_stage, SCHEMA_ONLY)

effective_stage =
  min(build_profile_max,
      local_requested_stage,
      dependency_stage,
      prerequisite_stage_ceiling)
```

`ProfileExecutionClassV1` 是 closed enum
`SCHEMA_ONLY | PRODUCT_SYNTHETIC | REAL_DATA`。`ProfileContextDigestV1` 绑定 §13 的 finite
flags、`WriterScopeKindV1`、`SourceWriterShapeV1` 与 projection-rule version，不能携带任意
gate list或 concrete record/cut ID；具体 destination/source IDs、epochs、authority、
resource 与 cut 只进入 `OperationContextV1/OperationContextDigestV1`，由 admission
证明是该 profile 的合法 instance。`MEASUREMENT_ONLY` 不是这个 enum 的成员；它只走不可
互 cast 的 typed permit/admission reducer，不进入 FeatureStage。normal product shared
gates、real-data overlay 与 capability branch 的 union 均在 §14.3 冻结，不能在 UI/调用点
另算一份 global minimum。`PASS/BLOCKED/FAIL/
INVALID/INCONCLUSIVE/UNSUPPORTED` 是 gate verdict domain，不是 FeatureStage；missing、
unknown 或任何非 `PASS` verdict 都使 `all_prerequisites_pass=false`，绝不能把两个 domain
直接放进同一个 `min()`。

---

## 3. Protocol safety caps 是上界，不是产品预算

安全 cap 在 reader 分配、映射或解密之前检查。BudgetProfile 只能更严格，不能放宽。

### 3.1 ADR 0016 imports

| registry | imported constraint |
|---|---|
| Binder inline envelope | 49,152 bytes（48 KiB），包含 envelope |
| logical pipe/PFD page | 1,048,576 bytes；更大必须分页或 BlobRef |
| Journal frame/header/segment/frontier | 以 ADR 0016 descriptor digest 对应 registry 为唯一 authority |
| Journal frames per segment DEK | `MAX_JOURNAL_FRAMES_PER_SEGMENT_DEK_V1=65,536` |
| Journal GCM authenticated blocks per segment DEK | `MAX_TOTAL_GCM_AUTH_BLOCKS_PER_SEGMENT_DEK_V1=16,777,216 (2^24)`；每 frame charge=`12+ceil(ciphertext_length/16)` |
| Proto required feature | M9.0 未登记 bit 必须拒绝 |
| DurableAck | 只能是 `DURABLE`、`FAILED`、`INDETERMINATE` |

实现不得在本文复制一个与 ADR 0016 descriptor 不同的 Journal cap。若 descriptor digest
不匹配，`WireCompatibilityGateV1=BLOCKED`。frame AAD exact 162 bytes，charge 中 12
blocks 来自 `ceil(162/16)+1 length block`；writer 必须在 Cipher.init 前 checked reserve。
BudgetProfile 只能使 segment 更早 seal，不能提高 invocation/block hard cap。

### 3.2 ADR 0017 exact imports

| item | exact hard cap/registry |
|---|---:|
| KeyAuthorizationProfile | 64 bytes |
| operation frontier slot | 256 bytes |
| reservation block | 1..1,024 operations |
| Keystore wrap GCM encrypt init / alias | `MAX_WRAP_GCM_ENCRYPT_INITIALIZATIONS_PER_ALIAS_V1=65,536`；failed/indeterminate init 也 burn；p1/p2/p5 aggregate 分别 `<2^21/<2^21/<2^29` blocks |
| Keyring slot | 65,536 bytes |
| Keyring clear header / exact AAD / physical ciphertext | 128 / 144 / 65,388 bytes |
| Keyring max canonical payload | 40,768 bytes |
| Keyring payload records | header 64 / generation 96 / key 272 bytes |
| Keyring record caps | 16 generations / 144 key records / exactly 9 records per generation |
| Blob content type | 0..126 bytes canonical ASCII |
| Blob header | fixed 408 bytes |
| chunk header | 24 bytes |
| chunk plaintext size | one of `4096..1048576` 的 2 次幂离散集合 |
| chunk ciphertext size | suite 1 等于 plaintext size |
| chunk tag + commit + CRC | 24 bytes；完整 chunk overhead 合计 48 bytes |
| chunk record | 最大 1,048,624 bytes |
| chunk count | 最大 16,384 |
| BlobDEK aggregate GCM blocks | `7+ceil(ciphertext_length/16)` per chunk；每 physical Blob 最大 `4,308,992 < 2^23` |
| Blob plaintext | 最大 67,108,864 bytes |
| Blob footer | 192 bytes |
| complete physical Blob | 最大 67,895,896 bytes |
| Blob locator slot | 512 bytes |

最大 Blob 算式必须机械验证：

```text
408 + 67,108,864 + 16,384*48 + 192 = 67,895,896
```

purpose 1/2 wrap alias 由 authenticated operation frontier 在 `Cipher.init` 前 reserve/burn，
`reserved_through` 不得超过 65,536。purpose 5 尚无能覆盖 crash 前未提交 init 的
root-scoped attempted-use authority；Keyring slot generation 不能替代。因此
`KeyringBootstrapControlPhaseGateV1`、`KeyringBootstrapCapabilityGateV1` 与 persistent
substrate 继续 BLOCKED，直到 ADR 0017 §5.4 要求的 exact usage-control wire/receipt 被接受
并完成独立实体 kill/reopen evidence。BudgetProfile 不能放宽或掩盖该缺口。

任何 sample 超过 safety cap 是协议失败，不进入“平均性能还可以”的统计。

---

## 4. `BudgetProfileV1`：恰好 99 fields

profile 是 build/device-role 级 canonical JSON artifact。顶层恰好包含 `F001..F099`，未知、
缺失、重复 key、NaN、Infinity、负数、注释和 `null` 都拒绝。canonical digest 使用 RFC
8785 JCS 后 SHA-256。

JCS/JSON number 受 IEEE-754 interoperable precision 约束，不能无损承载全部 u64。因此：

- 只有固定的 F001=`1`、F002=`0` 使用 JSON number；
- F010–F099 中所有 u32/u64/count/bytes/ns/ms/ppm 数值在 JSON 中都编码为 **canonical
  decimal string**，regex 恰好 `0|[1-9][0-9]*`；
- 禁止前导零、`+`、`-`、空白、小数点、指数和非 ASCII digit；validator 再 checked parse
  到声明的 u32/u64；
- F022 rule-set reference 与 digest/enum 继续使用其 closed string grammar；
- literal `"UNSET"` 在 parse 前独立识别，不能参与数字运算。

这样即使值大于 `2^53-1`，JCS 也只 canonicalize string bytes，不经过 binary64 rounding。

Gate 0 的计数是：

```text
9 FIXED / METHOD / BUILD references
+ 90 UNSET required values
= 99 fields
```

`UNSET` 是 JSON string `"UNSET"`，不是 `"0"`、负数、null 或“自动”。0018-B 填值时不增删字段；
需要新字段必须发布新的 profile schema minor/major。

### 4.1 九个固定/方法/build references

| id | name | type/source | Gate 0 class |
|---|---|---|---|
| F001 | `profile_schema_major` | exact integer `1` | FIXED |
| F002 | `profile_schema_minor` | exact integer `0` | FIXED |
| F003 | `protocol_descriptor_digest` | exact ADR 0016 descriptor SHA-256 | FIXED_REF |
| F004 | `security_contract_digest` | SHA-256 of exact committed ADR 0017 UTF-8 file bytes | FIXED_REF |
| F005 | `measurement_contract_digest` | SHA-256 of the exact committed `docs/adr/0018-m9-memory-budget.md` Git blob bytes at pre-result MeasurementContract commit M | FIXED_REF |
| F006 | `build_identity_digest` | SHA-256 of exact canonical `BuildSubjectIdentityV1` unsigned semantic body for source/build commit S and signed APK | BUILD_REF |
| F007 | `device_role_id` | one canonical role from §7 | BUILD_REF |
| F008 | `android_build_fingerprint_digest` | SHA-256 of exact device fingerprint evidence | BUILD_REF |
| F009 | `statistics_method_id` | exact ASCII `SENSE_BUDGET_STATS_V1` | METHOD |

parser grammar 固定如下：

- F003、F004、F005、F006、F008：恰好 64 个 lowercase hex ASCII
  `[0-9a-f]{64}`，解码后恰好 32 bytes；大写、`0x`、空白与错误长度拒绝；
- F007：恰为 `PIXEL_REFERENCE|HYPEROS|MIDRANGE|LOW_RAM`；
- F009：恰为 ASCII `SENSE_BUDGET_STATS_V1`；
- F022 在 schema `1.0` 中**唯一合法值是 `"UNSET"`**。未来若接受 volume rules wire，
  必须提升 profile minor/major，并使用
  `SENSE_VOLUME_RULESET_V1:<64-lowercase-hex-digest>` 指向 exact accepted rules
  descriptor；当前 validator 不得提前接受该前缀。
- F046 在 UNSET profile 中为 `"UNSET"`；0018-B 填值后必须仍是 JSON **string**，且恰为
  canonical decimal member
  `"4096"|"8192"|"16384"|"32768"|"65536"|"131072"|"262144"|"524288"|"1048576"`。
  JSON number、`CHUNK_4096`、前导零、空白、指数或近邻非 member 全拒绝。

F003–F008 不能把字符串 `REF` 当值。身份明确分成：

```text
M = 任何结果可见前冻结的 measurement-contract commit/blob
S = 实际构建 exact signed APK 的 source/tree commit
A = 结果后只接受 external profile/certification/docs 的 acceptance commit
```

F005 只 hash M 中该路径的**整个 Git blob bytes**，不是不存在的“0018-A 文件”、Markdown
标题范围、渲染文本或 A 的后验 blob；文件任一 byte 改变都产生新的 M 与完整新实验。
`MeasurementContractIdentityV1` 还绑定 M 的 exact commit/tree 及 ADR 0016 descriptor、
ADR 0017、ADR 0018 path+Git-blob digests；F003/F004/F005 必须分别等于该 identity中的
exact values，且 signed APK 内 compiled protocol descriptor digest必须等于 F003。同内容
换 path/commit、从另一 commit拼 F004、或 APK descriptor不一致都拒绝。
`BuildSubjectIdentityV1` canonical unsigned body 至少绑定 S commit/tree、build recipe/
toolchain/dependency locks、variant/applicationId/versionCode/versionName/SDK、unsigned build
provenance、exact signed APK length+SHA-256 与 signer index/lineage；它不包含自己的 digest、
profile/certification digest、A 或随机 document signature。F006 恰为该 body SHA-256。
另一个 owner-signed `BuildSubjectAttestationV1` 绑定 body length/digest 与 document
signature；manifest/certification绑定该 attestation document 的 exact length/digest。重签
同 body 不得伪装成新 build identity。

M/S/A 可以不同，但 cross-binding必须明确；A 不能被 attestation 声称为 APK build source，
也不能回填 F005/F006。把结果/profile bytes 嵌进 M、把 profile/certification exact bytes/
digest嵌进其所绑定的 signed APK，或用 A 的 blob 作为 F005 都形成 self-reference，静态
gate 必须拒绝。F001–F009 不是性能阈值，所以不计入 90 个 `UNSET`。

### 4.2 Storage / capacity（20 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F010 | `steady_reachable_soft_cap_bytes` | u64 bytes | UNSET |
| F011 | `journal_soft_cap_bytes` | u64 bytes | UNSET |
| F012 | `blob_soft_cap_bytes` | u64 bytes | UNSET |
| F013 | `index_soft_cap_bytes` | u64 bytes | UNSET |
| F014 | `manifest_soft_cap_bytes` | u64 bytes | UNSET |
| F015 | `quarantine_soft_cap_bytes` | u64 bytes | UNSET |
| F016 | `temp_soft_cap_bytes` | u64 bytes | UNSET |
| F017 | `minimum_free_bytes` | u64 bytes | UNSET |
| F018 | `recovery_reserve_bytes` | u64 bytes | UNSET |
| F019 | `active_duplicate_peak_bytes` | u64 bytes | UNSET |
| F020 | `durable_orphan_or_quarantine_peak_bytes` | u64 bytes | UNSET |
| F021 | `supported_volume_capacity_floor_bytes` | u64 bytes | UNSET |
| F022 | `supported_volume_capacity_rules` | canonical rule-set reference | UNSET |
| F023 | `capture_stop_free_bytes` | u64 bytes | UNSET |
| F024 | `capture_resume_free_bytes` | u64 bytes | UNSET |
| F025 | `low_storage_hysteresis_bytes` | u64 bytes | UNSET |
| F026 | `compaction_trigger_allocated_bytes` | u64 bytes | UNSET |
| F027 | `export_staging_soft_cap_bytes` | u64 bytes | UNSET |
| F028 | `per_source_retained_bytes_cap` | u64 bytes | UNSET |
| F029 | `max_live_blob_count` | u64 count | UNSET |

### 4.3 Journal / capture（15 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F030 | `journal_queue_capacity_records` | u32 count | UNSET |
| F031 | `journal_queue_capacity_bytes` | u64 bytes | UNSET |
| F032 | `terminal_lane_reserved_records` | u32 count | UNSET |
| F033 | `erasure_lane_reserved_records` | u32 count | UNSET |
| F034 | `enqueue_p95_nanoseconds` | u64 ns | UNSET |
| F035 | `flush_batch_records` | u32 count | UNSET |
| F036 | `flush_batch_bytes` | u64 bytes | UNSET |
| F037 | `flush_max_delay_milliseconds` | u64 ms | UNSET |
| F038 | `open_segment_soft_cap_bytes` | u64 bytes | UNSET |
| F039 | `sealed_segment_target_bytes` | u64 bytes | UNSET |
| F040 | `max_open_segments_per_writer` | u32 count | UNSET |
| F041 | `writer_epoch_max_outstanding_records` | u32 count | UNSET |
| F042 | `turn_checkpoint_durable_ack_p95_milliseconds` | u64 ms | UNSET |
| F043 | `turn_checkpoint_durable_ack_p99_milliseconds` | u64 ms | UNSET |
| F044 | `non_durable_or_unresolved_ack_ratio_ppm` | u32 ppm | UNSET |

### 4.4 Blob（11 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F045 | `product_blob_plaintext_cap_bytes` | u64 bytes，≤ ADR 0017 cap | UNSET |
| F046 | `product_blob_chunk_plaintext_bytes` | enum，必须属于离散集合 | UNSET |
| F047 | `max_concurrent_blob_writes` | u32 count | UNSET |
| F048 | `max_inflight_blob_plaintext_bytes` | u64 bytes | UNSET |
| F049 | `blob_publish_p95_milliseconds` | u64 ms | UNSET |
| F050 | `locator_convergence_p95_milliseconds` | u64 ms | UNSET |
| F051 | `blob_orphan_temp_peak_bytes` | u64 bytes | UNSET |
| F052 | `blob_orphan_temp_max_count` | u32 count | UNSET |
| F053 | `blob_full_verify_p95_milliseconds` | u64 ms | UNSET |
| F054 | `blob_rewrite_duplicate_peak_bytes` | u64 bytes | UNSET |
| F055 | `old_physical_lease_drain_p95_milliseconds` | u64 ms | UNSET |

### 4.5 IPC / egress（8 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F056 | `binder_inline_product_cap_bytes` | u32 bytes，≤49,152 | UNSET |
| F057 | `logical_transfer_page_cap_bytes` | u32 bytes，≤1,048,576 | UNSET |
| F058 | `pipe_buffer_bytes` | u32 bytes | UNSET |
| F059 | `max_concurrent_transfers` | u32 count | UNSET |
| F060 | `transfer_first_page_p95_milliseconds` | u64 ms | UNSET |
| F061 | `cancelled_transfer_cleanup_p95_milliseconds` | u64 ms | UNSET |
| F062 | `orphan_egress_peak_bytes` | u64 bytes | UNSET |
| F063 | `memory_fd_high_water_count` | u32 count | UNSET |

### 4.6 Recall latency（9 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F064 | `warm_recall_first_result_p50_milliseconds` | u64 ms | UNSET |
| F065 | `warm_recall_first_result_p95_milliseconds` | u64 ms | UNSET |
| F066 | `warm_recall_first_result_p99_milliseconds` | u64 ms | UNSET |
| F067 | `cold_bind_first_result_p50_milliseconds` | u64 ms | UNSET |
| F068 | `cold_bind_first_result_p95_milliseconds` | u64 ms | UNSET |
| F069 | `cold_bind_first_result_p99_milliseconds` | u64 ms | UNSET |
| F070 | `recall_next_page_p95_milliseconds` | u64 ms | UNSET |
| F071 | `durable_record_to_recall_p95_milliseconds` | u64 ms | UNSET |
| F072 | `recall_cancel_terminal_p95_milliseconds` | u64 ms | UNSET |

### 4.7 CPU / memory（9 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F073 | `ime_incremental_rss_peak_bytes` | u64 bytes | UNSET |
| F074 | `broker_rss_peak_bytes` | u64 bytes | UNSET |
| F075 | `brain_recorder_rss_peak_bytes` | u64 bytes | UNSET |
| F076 | `capture_heap_peak_bytes` | u64 bytes | UNSET |
| F077 | `recall_heap_peak_bytes` | u64 bytes | UNSET |
| F078 | `journal_cpu_nanoseconds_per_logical_kib` | u64 ns/KiB | UNSET |
| F079 | `recall_cpu_p95_nanoseconds` | u64 ns | UNSET |
| F080 | `ime_main_thread_block_max_nanoseconds` | u64 ns | UNSET |
| F081 | `memory_gc_pause_p95_nanoseconds` | u64 ns | UNSET |

### 4.8 Energy / I/O（9 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F082 | `journal_energy_microjoules_per_turn` | u64 µJ | UNSET |
| F083 | `recall_energy_microjoules_per_query` | u64 µJ | UNSET |
| F084 | `maintenance_energy_microjoules_per_mib` | u64 µJ/MiB | UNSET |
| F085 | `logical_work_bytes_per_workload` | u64 bytes | UNSET |
| F086 | `physical_written_bytes_per_workload` | u64 bytes | UNSET |
| F087 | `write_amplification_ratio_ppm` | u64 ppm | UNSET |
| F088 | `fsync_count_per_turn_ppm` | u64 fsync/1M turns | UNSET |
| F089 | `storage_read_bytes_per_recall` | u64 bytes | UNSET |
| F090 | `thermal_severe_sample_ratio_ppm` | u32 ppm | UNSET |

### 4.9 HotSnapshot / index（5 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F091 | `hot_snapshot_generation_cap_bytes` | u64 bytes | UNSET |
| F092 | `hot_snapshot_retained_generation_count` | u32 count | UNSET |
| F093 | `hot_snapshot_publish_p95_milliseconds` | u64 ms | UNSET |
| F094 | `derived_index_bytes_per_record` | u64 bytes | UNSET |
| F095 | `index_rebuild_p95_milliseconds_per_10k_records` | u64 ms | UNSET |

### 4.10 Maintenance / rotation / erasure（4 个 UNSET）

| id | name | unit/type | Gate 0 |
|---|---|---|---|
| F096 | `compaction_duplicate_peak_bytes` | u64 bytes | UNSET |
| F097 | `key_rotation_duplicate_peak_bytes` | u64 bytes | UNSET |
| F098 | `local_erasure_completion_p95_milliseconds` | u64 ms | UNSET |
| F099 | `egress_drain_completion_p95_milliseconds` | u64 ms | UNSET |

---

## 5. Profile structural constraints 与容量公式

所有加减乘使用 checked u64；overflow 不是“大于阈值”，而是
`PROFILE_STRUCTURALLY_INVALID`。

### 5.1 Component sum

```text
checked_sum(
  F011 journal,
  F012 blob,
  F013 index,
  F014 manifest
) <= F010 steady-reachable soft cap
```

F015 quarantine、F016 temp与 F027 export staging不属于 steady component sum：
quarantine属于 `DURABLE_ORPHAN_OR_QUARANTINE`，live staging属于
`ACTIVE_DUPLICATE`，而 temp basename是可横跨后两类的 diagnostic subcap。component
overlap必须在 F022 rules中明确；默认视为互斥并相加，不能为了通过门禁而重复记账。

### 5.2 Current 与 peak

每个 Memory volume 的 physical bytes 必须恰好属于一个、且只能一个
`ByteChargeClassV1`：

```text
STEADY_REACHABLE
ACTIVE_DUPLICATE
DURABLE_ORPHAN_OR_QUARANTINE
```

`STEADY_REACHABLE` 是 current authenticated root namespace拥有的 canonical allocation。
allocation class unit是 whole inode：已发布的 canonical open Journal inode全部 physical
charge（包括 frontier后未获读取 authority的 volatile tail、半 frame与 filesystem slack）
始终计 F010/F011，直到 compaction/unlink或 quarantine；逻辑不可读不等于物理不计费。
每次 in-place frame/footer growth前原子 reserve F010/F011 headroom，按
F035/F036、frame overhead与 F022 allocation unit保守展开；write/force/frontier成功或
不确定都消费并以 authoritative physical counter reconcile，crash retained inode仍只计一次。
只有新 inode在 authority publish前属于 F019。
`ACTIVE_DUPLICATE` 是有 current durable intent/control、仍在 publish/rewrite/rotation/
compaction 的 pre-publication temp 与 old/new coexistence；`DURABLE_ORPHAN_OR_QUARANTINE`
是不能归属 live transaction、cleanup pending/indeterminate 或隔离待 census 的 allocation。
每次 class transition 必须有 append-only charge-ledger entry，满足 old-class debit =
new-class credit；无 durable intent 的 temp 在 crash 后立即归 orphan class，不能从统计消失。
hardlink 禁止；regular file `st_nlink != 1`、unknown inode identity 或一个 inode被重复枚举使
volume unsupported/BLOCKED。

field到 disjoint class的 mapping固定为：

```text
F010 caps STEADY_REACHABLE
F019 caps ACTIVE_DUPLICATE
F020 caps DURABLE_ORPHAN_OR_QUARANTINE

F015 <= F020                         // quarantine subcap
F027 <= F019                         // live export-staging subcap
F016 <= checked_add(F019,F020)       // temp-name cross-class diagnostic cap
F051 <= F020                         // Blob orphan/temp byte subcap
F062 <= F020                         // orphan egress byte subcap
```

F016不是第四个 allocation class，也不加入 peak；每个 temp inode仍必须按 durable intent/
transaction状态恰好计入 F019或 F020一次。F051/F062只是各来源 subcap，不能从 F020扣除后
另算；同一 inode也不能同时以 quarantine/temp/staging 名称重复收费。

F022 的 `VolumeChargeRuleV1` 必须为每个 supported filesystem 冻结 authoritative
`physical_charged_bytes(inode)`：allocation unit、`st_blocks`/filesystem counter source、
sparse/compression/reflink policy、directory与journal metadata reserve、inode identity、
rounding、rename/link semantics、external free-space counter reconciliation与误差 ceiling。
测试设备必须隔离其它 writer，并验证 charge-ledger delta 与 filesystem free-space delta 在
预承诺误差内；Android/API/filesystem 无法提供该 authority、reflink/shared block 无法唯一
归属或 reconciliation 超界时，该 volume不受支持，不能退回 logical length。

F010/F017–F021 是 profile 全局字段，不是可以在每个 volume重复使用的额度。schema 1.0
刻意只支持一个 Memory allocation volume：F022引用的 owner-authenticated
`VolumeCapacityDescriptorV1` 必须令 `supported_volume_count=1`，且
`credential-protected noBackupFilesDir/sense-memory/v1` 下 Journal、Blob、index、
HotSnapshot、control、temp、quarantine与 export staging的 `st_dev`/mount identity全部等于
唯一 `v`。显式 export consumer/destination不属于 Memory allocation class。任何 child落在
另一 volume、bind mount identity不一致或 adoptable/emulated relocation都
UNSUPPORTED/BLOCKED；多 volume必须提升 schema，不能在 v1偷偷分摊。descriptor为唯一 `v`
冻结：

```text
steady_cap_bytes(v)
active_duplicate_cap_bytes(v)
orphan_cap_bytes(v)
capacity_floor_bytes(v)
minimum_free_bytes(v)
recovery_reserve_bytes(v)
component_cap_bytes(v,JOURNAL|BLOB|INDEX|MANIFEST)
```

唯一 canonical mapping为：

```text
checked_sum_v(steady_cap_bytes(v))                 = F010
checked_sum_v(active_duplicate_cap_bytes(v))       = F019
checked_sum_v(orphan_cap_bytes(v))                 = F020
checked_sum_v(capacity_floor_bytes(v))              = F021
checked_sum_v(minimum_free_bytes(v))                = F017
checked_sum_v(recovery_reserve_bytes(v))            = F018
checked_sum_v(component_cap_bytes(v,JOURNAL))       = F011
checked_sum_v(component_cap_bytes(v,BLOB))          = F012
checked_sum_v(component_cap_bytes(v,INDEX))         = F013
checked_sum_v(component_cap_bytes(v,MANIFEST))      = F014
checked_sum_component(component_cap_bytes(v,*))
  <= steady_cap_bytes(v)
```

因此不存在未定义的 `allocated_soft_cap_bytes(v)`，也不能把同一个 F010分别完整授予多个
volume。上面的 sum在 v1恰有一项；保留带 `v` 记号只为了让 filesystem authority明确，不是
多卷授权。descriptor缺 mapping、总和不等、volume identity不一致、某 component没有唯一
volume、或任一 checked sum overflow都使 profile INVALID/BLOCKED。F023/F024/F025也都直接
作用于这个唯一 volume，所以 §5.3 的 scalar hysteresis不会被另一卷 free bytes掩盖。

对每个受支持 volume `v`：

```text
steady_reachable_bytes(v) <= steady_cap_bytes(v)
steady_reachable_bytes(v) + success_destination_reserved_bytes(v)
  <= steady_cap_bytes(v)
active_duplicate_bytes(v) + live_preallocation_reserved_bytes(v)
  <= active_duplicate_cap_bytes(v)
durable_orphan_or_quarantine_bytes(v)
  + orphan_fallback_reserved_bytes(v)
  <= orphan_cap_bytes(v)

active_duplicate_cap_bytes(v) >=
  max(
    aggregate_shared_normal_publish_peak(v),
    hot_snapshot_replace_duplicate_bound_bytes(v),
    projection_store_create_union_bound_bytes(v),
    projection_store_replace_union_bound_bytes(v),
    journal rewrite duplicate,
    index rebuild duplicate,
    F054 blob rewrite duplicate,
    F096 compaction duplicate,
    F097 key rotation duplicate,
    local_erasure_replacement_bound_bytes(v),
    keyring bootstrap staging duplicate
  )

peak_required_bytes(v) =
  steady_cap_bytes(v)
  + active_duplicate_cap_bytes(v)
  + orphan_cap_bytes(v)

peak_required_bytes(v)
  <= capacity_floor_bytes(v)
     - minimum_free_bytes(v)
     - recovery_reserve_bytes(v)

before every NEW-INODE allocating transaction:
  reserve the terminal-mode-specific physical charge against:
    live class F019
    + the exact positive success-destination delta in F010/component caps, if any
    + the exact worst-cut failure fallback in F020, if any
  without counting the same physical bytes as three allocated classes

  current_physical_bytes(v) + conservative_new_worst_case_charge(v)
    <= capacity_floor_bytes(v)
       - minimum_free_bytes(v)
       - recovery_reserve_bytes(v)

  authoritative_available_bytes_lower_bound(v)
    >= conservative_new_worst_case_charge(v)
       + minimum_free_bytes(v)
       + recovery_reserve_bytes(v)
       + available_counter_error_ceiling_bytes(v)

before every in-place canonical-inode growth:
  reserve only its exact positive F010/component physical delta
  do not reserve F019/F020
  current_physical_bytes(v) + conservative_positive_final_delta(v,operation)
    <= capacity_floor_bytes(v)
       - minimum_free_bytes(v)
       - recovery_reserve_bytes(v)
  authoritative_available_bytes_lower_bound(v)
    >= conservative_positive_final_delta(v,operation)
       + conservative_in_place_transient_free_charge(v,operation)
       + minimum_free_bytes(v)
       + recovery_reserve_bytes(v)
       + available_counter_error_ceiling_bytes(v)
```

`hot_snapshot_replace_duplicate_bound_bytes(v)` 按 F091、optional exact victim charge与 fixed
pointer/parent delta推导；`projection_store_create_union_bound_bytes(v)` 和
`projection_store_replace_union_bound_bytes(v)` 按
`ProjectionStoreBootstrapCapacityDescriptorV1` 的 approved WAL/rollback/SHM/migration
closed union、INDEX old/new charge、Journal intent与 MANIFEST lock/directory delta做 checked
展开。三者都必须由 F022/descriptor给出 finite value并纳入 exclusive capacity lease；
unknown SQLite sidecar/temp、漏 HotSnapshot victim fallback或只写 producer registry却不进
本 max使 profile INVALID。

第二个不等式必须在 capacity coordinator内、紧邻 allocation重验；F021静态 floor和 F023
capture hysteresis都不能替代当前 free-space证据。coordinator只能串行 Memory writer，不能
阻止系统/其它 app在 preflight后占空间，因此 `available_bytes_lower_bound`必须由 F022冻结
counter/source/误差，支持时使用 bounded preallocation；随后 ENOSPC/partial allocation仍按
实际 class与 fallback reconcile，不能声称 app拥有未实际保留的块。delete-only不受这个
new-charge检查阻断。

F022还必须为 fixed-slot overwrite/rename/fsync冻结
`conservative_in_place_transient_free_charge`；即使最终 EOF/st_blocks不增长，ext4/f2fs
journal/COW也可能临时需要 free blocks。该 transient只做 available-space liveness preflight，
不计 F019；terminal仍按 authoritative final physical delta消费/释放 F010 reservation。

单卷 runtime subcap还必须满足：

```text
quarantine_actual_bytes <= F015
temp_named_actual_bytes + pending_temp_name_reserved_bytes <= F016
```

任一新 temp basename在 directory mutation前先 reserve F016；不足则零 mutation拒绝。已有
损坏 inode的 `QUARANTINE_RECLASSIFY` 即使越过 F015/F020仍诚实分类、发
`RESOURCE_CAP_BREACH`并只准 cleanup/reset，不能为了维持不等式把它留在 F010。F015/F016
不加入 peak，同一 inode仍只在 F019或F020计一次。

`ActiveDuplicateProducerKindV1` 与 conflict mode是 closed registry：

```text
SHARED_NORMAL_PUBLISH = {
  WRITER_EPOCH_BOOTSTRAP_IME,
  WRITER_EPOCH_BOOTSTRAP_BRAIN,
  WRITER_EPOCH_BOOTSTRAP_MAIN,
  INITIAL_BLOB_PUBLISH,
  RECOVERED_SEAL_PUBLISH,
  HOT_SNAPSHOT_CREATE,
  CONTROL_SIDECAR_PUBLISH,
  EXPORT_STAGING
}

EXCLUSIVE_REWRITE = {
  KEYRING_BOOTSTRAP_STAGING,
  HOT_SNAPSHOT_REPLACE,
  PROJECTION_STORE_CREATE,
  PROJECTION_STORE_REPLACE,
  JOURNAL_REWRITE,
  INDEX_REBUILD,
  BLOB_REWRITE,
  COMPACTION,
  KEY_ROTATION,
  LOCAL_ERASURE_REPLACEMENT
}

PRE_PROFILE_EXCLUSIVE_BOOTSTRAP = {
  AUTHORITY_BOOTSTRAP_ROOT_SHELL,
  KEYRING_BOOTSTRAP_EVIDENCE_STAGING,
  KEYRING_BOOTSTRAP_PRIMARY_STAGING
}
```

同 volume的 shared producers可并发；`INITIAL_BLOB_PUBLISH` 数不超过 F047，且每项按
conservative full physical layout（含 locator pair）charge，不能只算当前已写 bytes。
三个 writer bootstrap按 writer kind各至多一个；recovered seal同样受 owner/epoch lock
约束；HotSnapshot/control sidecar的并发数和 conservative charge、以及 F027覆盖的全部
live export staging bytes必须由 F022 descriptor给出。唯一 baseline：

```text
aggregate_shared_normal_publish_peak(v) =
  checked_sum(all simultaneously allowed shared-producer conservative charges)
```

`EXCLUSIVE_REWRITE` 必须先取得同一 per-volume exclusive capacity lease，与所有 shared
producer及其它 exclusive producer互斥。replacement producer的 duplicate charge恰为
`max(new_publish_charge,old_drain_charge)`，而不是二者之和；原子 authority switch前新 inode
在 F019、旧 inode在 F010，switch后恰好相反。因此 F019 是
`max(aggregate_shared_normal_publish_peak, each exclusive worst-case)`；不是遗漏 ordinary
publish后的 maintenance-only max。unknown producer、缺 concurrency cap/conflict edge、
unbounded control temp或 checked-sum overflow都使 profile INVALID/BLOCKED。
`PRE_PROFILE_EXCLUSIVE_BOOTSTRAP` 不假装持有尚不存在的 product capacity lease；三者在
root bootstrap lock下彼此互斥，并只消费 owner-signed external hard-cap envelope。其
producer/permit/lifetime不可转换为 normal `EXCLUSIVE_REWRITE`。

`ProducerTerminalModeV1` 也是 closed registry：

```text
CREATE_PERSISTENT = {
  WRITER_EPOCH_BOOTSTRAP_IME,
  WRITER_EPOCH_BOOTSTRAP_BRAIN,
  WRITER_EPOCH_BOOTSTRAP_MAIN,
  INITIAL_BLOB_PUBLISH,
  RECOVERED_SEAL_PUBLISH,
  CONTROL_SIDECAR_PUBLISH,
  KEYRING_BOOTSTRAP_STAGING,
  HOT_SNAPSHOT_CREATE,
  PROJECTION_STORE_CREATE
}

REPLACE_PERSISTENT = {
  HOT_SNAPSHOT_REPLACE,
  PROJECTION_STORE_REPLACE,
  JOURNAL_REWRITE,
  INDEX_REBUILD,
  BLOB_REWRITE,
  COMPACTION,
  KEY_ROTATION,
  LOCAL_ERASURE_REPLACEMENT
}

EPHEMERAL_EXPORT = {EXPORT_STAGING}

CANDIDATE_EPHEMERAL_SUBSTRATE = {KEYRING_BOOTSTRAP_EVIDENCE_STAGING}

CANDIDATE_PRIMARY_SUBSTRATE = {KEYRING_BOOTSTRAP_PRIMARY_STAGING}

CONTROL_PLANE_ROOT_CREATE = {AUTHORITY_BOOTSTRAP_ROOT_SHELL}

NON_ACTIVE_DUPLICATE_MODES = {
  CONTROL_PLANE_ADOPTION,
  QUARANTINE_RECLASSIFY,
  IN_PLACE_STEADY_GROWTH,
  LOCAL_ERASURE_DELETE_ONLY,
  WHOLE_RESET_DELETE_ONLY
}
```

HotSnapshot intent把 `lineage_predecessor` 与 optional `eviction_victim` 分开绑定：
lineage predecessor只证明 generation链连续；它通常继续留在 F010作为校验失败 fallback。
当 retained count `< F092` 时 intent不得有 victim，kind=`HOT_SNAPSHOT_CREATE`；达到
retention cap时，intent必须在任何 allocation前 pin exact oldest victim identity、lease与
charge，kind=`HOT_SNAPSHOT_REPLACE`。victim不要求等于直接 predecessor。两字段混淆、
缺 victim、执行中改 mode或 create/replace conflict key不一致都 fail closed。closed
success/failure component mapping为：

| producer | success steady component | failure class/subcap |
|---|---|---|
| writer epoch bootstrap | JOURNAL(F011) + MANIFEST(F014) 的 exact compound vector | F020 |
| initial Blob publish | BLOB(F012) + MANIFEST(F014) | F020 + F051/F052 |
| recovered seal publish | MANIFEST(F014) | F020 |
| HotSnapshot create/replace | INDEX(F013) | F020 |
| control sidecar publish | MANIFEST(F014) | F020 |
| authority bootstrap root shell | external hard-cap MANIFEST-equivalent control charge；不消费尚不存在的 product F014 | external bootstrap orphan/cleanup envelope |
| normal keyring bootstrap staging | MANIFEST(F014)；任何 precreated Journal control slot另列 JOURNAL(F011) | F020 |
| candidate keyring evidence staging | candidate-only MANIFEST+JOURNAL-equivalent charge；不消费 product F010/F011/F014 | candidate orphan envelope；最终强制 wipe/zero-census |
| candidate keyring primary staging | candidate-only MANIFEST+JOURNAL-equivalent charge；不消费 product F010/F011/F014 | candidate orphan envelope；root epoch终态强制 wipe/zero-census |
| export staging | 无 steady destination | F020 + F062 |
| Journal rewrite | JOURNAL(F011) replacement | F020 |
| index rebuild | INDEX(F013) create/replacement，intent固定 | F020 |
| projection store create | INDEX(F013) main DB + approved WAL/rollback/SHM/migration union；intent/lock delta另计 JOURNAL(F011)/MANIFEST(F014) in-place | F020；unknown SQLite temp/sidecar使 branch BLOCKED |
| projection store replace/upgrade | INDEX(F013) old/new replacement + approved WAL/rollback/SHM/migration union；intent/lock delta另计 JOURNAL(F011)/MANIFEST(F014) in-place | F020；old/new与 sidecar charge按 single exclusive contingency |
| Blob rewrite | distinct old/new physical Blob做 BLOB(F012) replacement；fixed locator A/B做 MANIFEST(F014) in-place | F020 + F051/F052 |
| compaction | closed vector subset of JOURNAL/BLOB/INDEX/MANIFEST，逐 inode固定 | F020及适用 Blob subcaps |
| key rotation | fixed keyring A/B做 MANIFEST in-place；新 frontier/control做 CREATE；distinct data generations逐 inode REPLACE | F020及适用 Blob subcaps |
| local-erasure replacement | closed affected component vector，逐 inode固定 | F020及适用 Blob subcaps |

rewrite producer的 source/destination component vector由上表与 sealed operation descriptor
冻结；Journal/Index/Blob分别不得转入其它 component。compaction、rotation和
local-erasure replacement的 subset只能从表列 closed components选择，并必须逐 inode列出
old/new component vector。F022只
提供 volume/layout/physical-charge规则，不能改写这张语义 mapping或把 bytes藏进未命名
component。

`LocalErasureReplacementBoundV1` 也是 F022必需内容：selective erasure replacement在
exclusive lease内一次只允许一个 distinct generation artifact处于 F019，必须等它完成
authority switch及 old drain/fallback后才开始下一个。descriptor按 Journal segment、physical
Blob、index generation、manifest/control artifact的 closed protocol/profile cap展开各自
conservative old/new charge，checked max得到
`local_erasure_replacement_bound_bytes(v)`，并证明 `<=active_duplicate_cap_bytes(v)`。
unknown artifact、并发两个 replacement、漏 component或无法从现有 hard/profile cap推出
有限 charge都使 local-erasure replacement BLOCKED；delete-only不读取该 bound。

bootstrap producer严格分四路，permit/token不可互 cast：

1. `AUTHORITY_BOOTSTRAP_ROOT_SHELL` 只建立 owner/root control shell，不建 Keyring/正文；
   它依赖 owner-signed one-shot authority permit、external volume preflight与 ADR 0016/0017
   固定 hard cap；
2. `KEYRING_BOOTSTRAP_EVIDENCE_STAGING` 只存在于 candidate kill-evidence namespace。它还需
   独立 one-shot keyring-commit evidence permit、sealed
   `ExperimentalMeasurementConfigV1`、attested synthetic corpus、erasure containment与 exact
   cleanup plan，才可建立 candidate-only Keyring/frontier/synthetic Journal substrate。
   class ledger仍使用三类语义，但额度来自 permit中的 external fixed hard-cap envelope而非
   尚不存在的 F010/F019/F020；publish只进入 candidate steady class，绝不 credit product
   F010。每个 evidence attempt的最终 terminal必须 wipe + external zero-census；
   crash/indeterminate先进入
   candidate orphan class再 cleanup，永不转正、永不读用户正文；
3. `KEYRING_BOOTSTRAP_PRIMARY_STAGING` 只消费不可互 cast的
   `CandidatePrimaryAuthorityBootstrapPermitV1 + CandidateKeyringCommitPermitV1 +
   SyntheticMeasurementRootEpochPermitV1`。它使用同一 external envelope，成功后可在
   precommitted primary root epoch内保持 candidate steady substrate；epoch terminal必须
   external wipe + zero-census，绝不转 normal root；
4. normal `KEYRING_BOOTSTRAP_STAGING` 只有 selected role/profile、`BudgetProfileGateV1=PASS`
   与完整 storage closure/capacity preflight后可执行。
   在任何 keyring/frontier/lease staging inode分配前，sealed compound intent绑定 exact
   protocol cap、逐文件 component vector与 cleanup plan；vector必须逐项包含 ADR 0017
   `NamespaceMutationLockMapV1` 的全部 baseline lock与 parent-directory delta、
   `blob-locator/bootstrap-control` fixed control A/B、erasure-control placeholder/ENABLED
   slots和三 writer owner leases，不得只列业务 payload。成功按
   `F019→F010/F014(/F011)`，失败/不确定按 `F019→F020`。

缺任一路各自的完整条件就保持零写；authority permit不能建 candidate Keyring，evidence与
primary permit不能互换或写 normal product root，normal path也不能借 measurement hard cap
绕过 profile。

所有 actual+reserved addition都用 checked u64并在同一 capacity coordinator内原子
reserve，避免多个 pre-ID attempt各自看到同一 headroom。`INITIAL_BLOB_PUBLISH` 还必须
同时满足：

```text
steady_blob_bytes + blob_success_reserved_bytes <= F012
steady_locator_manifest_bytes + locator_success_reserved_bytes <= F014
steady_live_blob_count + blob_success_reserved_count <= F029
blob_orphan_temp_bytes + blob_fallback_reserved_bytes <= F051
blob_orphan_temp_count + blob_fallback_reserved_count <= F052
live_blob_materialization_count <= F047
live_blob_plaintext_reserved_bytes <= F048
```

F012/F014/F029 reservation在成功 `F019→F010/F012/F014` 时消费；F051/F052 reservation在失败
`F019→F020` 时消费。另一终态释放未消费一侧。locator/control metadata charge按 F022映射
到 F014或对应 steady component，不能藏在未列名“metadata”；其它 producer也必须遵守上表
的 success/failure component+subcap mapping与原子 reservation规则。
这里箭头表示 compound transaction的 aggregate debit/credit：Blob physical inode只 credit
F012，locator/control inode只 credit F014，二者之和逐字节等于 F019 debit；单个 inode仍
绝不同时属于两个 class/component。

Blob physical count对 CREATE与 REPLACE不同：

```text
INITIAL_BLOB_PUBLISH:
  steady_live_blob_count + create_success_count_reserved <= F029
  one blob_fallback_charge_id reserves one F052 contingency

BLOB_REWRITE_PHYSICAL:
  pin old physical count unit = 1
  live_count_after = live_count_before - 1 + 1 <= F029
  reserve no additional F029 success count
  one BlobRewriteFallbackContingencyV1 count unit reserves max(
    pre-switch new-physical orphan count,
    post-switch old-drain orphan count
  )
```

atomic switch使 old/new F029 count净变化为零，所以 F029已满不阻断合法 rewrite；两个 cut
互斥，F052 contingency只计一个 count reservation。该 contingency预先绑定不可变
`new_branch_charge_id→new_physical_storage_id` 与
`old_branch_charge_id→old_physical_storage_id`，以及单一
`selected_branch=NONE|NEW|OLD`；switch前只能原子激活 NEW，authority switch在同一 ledger
record释放 NEW并激活 OLD，禁止两 branch同时 active或重绑 physical ID。F051 byte contingency
同样预留 `max(new_charge,old_charge)`并随 selected branch原子转移。把 locator slot计为
Blob physical count、为 rewrite预留 `+1 F029`、重绑同一 charge ID、或对 switch前后各占
一份 F052都会 schema reject。

每个 new-inode intent还必须封存 `NewInodePhysicalChargeVectorV1`，把两种 charge分开：

```text
terminal_child_inode_charge
canonical_parent_directory_positive_delta
```

前者按下述 terminal mode在 F019/F010/F020间转移；后者属于既有 canonical parent
directory的 `IN_PLACE_STEADY_GROWTH(MANIFEST)`，无论 child成功、失败或转 orphan都始终预留
并按 authoritative before/after physical counter消费 F010/F014正增量。只有证明目录零增长
或 physical blocks已确实回收才释放；rename/unlink后 child消失不等于 parent directory收缩。
directory delta缺 counter、把它并入 child F020、或失败时释放全部 F010 reservation均
INVALID。

`FixedAuthorityInodeRuleV1` 是 whole-inode分类的强制规则：已经发布的 keyring/frontier/
locator/pointer A/B slot与其它固定 authority inode，在逻辑 generation变化时仍属于原
F010 component，只能执行 `IN_PLACE_STEADY_GROWTH`；不得把同一 inode伪装成 old/new
`REPLACE_PERSISTENT`。REPLACE只作用于 `(st_dev,st_ino)` 不同的 generation artifact。
每个 compound operation按 inode拆解：

```text
Blob rewrite:
  distinct old/new physical Blob -> REPLACE_PERSISTENT(BLOB)
  fixed locator pair             -> IN_PLACE_STEADY_GROWTH(MANIFEST)
  operation intent/receipt       -> existing fixed control/Journal slot in-place
  old physical drain             -> DELETE_ONLY terminal

Key rotation:
  fixed keyring A/B              -> IN_PLACE_STEADY_GROWTH(MANIFEST)
  new frontier/control inode     -> CREATE_PERSISTENT(MANIFEST or JOURNAL)
  each distinct data generation  -> REPLACE_PERSISTENT(its exact component)
  retired frontier/control       -> DELETE_ONLY

Journal/index/HotSnapshot generation switch:
  distinct generation artifacts  -> CREATE or REPLACE_PERSISTENT(component)
  fixed pointer/frontier A/B      -> IN_PLACE_STEADY_GROWTH(MANIFEST)
  retired generation             -> DELETE_ONLY
```

F097只观察 rotation中真实 F019 new-inode/old-drain duplicate vector；fixed-slot overwrite不进
F019，但其 physical writes仍进入 F086/F087。任一 compound child缺 exact mode/component、
错误把 fixed inode转 F019/F020或漏 parent-directory delta都使 operation INVALID/BLOCKED。

各 terminal mode的唯一 class transition为：

```text
CREATE_PERSISTENT:
  pre-ID reserve F019(new) + F010/component(new) + F020(new)
  durable intent + first allocation -> new=F019
  authority publication -> new F019->exact F010/component; release F020
  fail/cancel/indeterminate -> new F019->F020; release success reservation

REPLACE_PERSISTENT:
  pin exact old authority identity, lease and physical component vector
  pre-ID reserve F019(max(new,old))
    + positive componentwise delta max(0,new-old)
    + F020(max(new,old))
  require steady_after[c] = steady_before[c]-old[c]+new[c] <= component_cap[c]
  first allocation -> new=F019, old remains F010
  atomic authority switch -> new F019->F010 and old F010->F019 in one ledger record
  before switch failure -> new F019->F020, old remains F010
  after switch drain success -> unlink old + parent fsync + reopen census, then debit old F019
  after switch drain failure/owner-loss -> old F019->F020

EPHEMERAL_EXPORT:
  pre-ID reserve F019(staging) + F020/F062(staging); reserve no F010 success
  first allocation -> staging=F019/F027
  successful output drain + staging unlink + parent fsync + reopen census -> debit F019/F027
  fail/cancel/indeterminate -> staging F019->F020/F062

CONTROL_PLANE_ROOT_CREATE:
  external envelope reserves active + steady-control + orphan contingency
  first allocation -> external-control-active
  owner/root shell publication -> external-control-steady
  fail/indeterminate -> external-control-orphan, then authenticated cleanup
  this transition does not consume product F010/F014

CONTROL_PLANE_ADOPTION:
  after a product profile is selected, census exact external-control-steady inode vector
  require F010/F014 headroom and F022 identity match before any normal data-plane allocation
  atomic ledger credit F010/F014 and close the external-control class
  insufficient headroom leaves owner shell external-control-steady and normal Memory BLOCKED
  no file mutation or second owner shell is allowed

CANDIDATE_EPHEMERAL_SUBSTRATE:
  external envelope reserve -> first allocation=candidate-active
  candidate bootstrap commit -> candidate-steady
  fail/crash/indeterminate -> candidate-orphan
  evidence attempt terminal -> external wipe + zero-census, debit whichever candidate class owns it

CANDIDATE_PRIMARY_SUBSTRATE:
  external envelope reserve -> first allocation=candidate-active
  candidate bootstrap commit -> candidate-steady for exactly one precommitted root epoch
  fail/crash/indeterminate -> candidate-orphan
  root-epoch terminal -> external wipe + zero-census, debit whichever candidate class owns it

QUARANTINE_RECLASSIFY:
  existing corrupt/ambiguous inode directly F010/component -> F020/F015
  classification happens even when it breaches F015/F020; emit RESOURCE_CAP_BREACH,
  block every new allocation and allow only cleanup/reset
  if original class/identity/physical charge cannot be established, mark volume
  UNSUPPORTED/BLOCKED and charge the full authoritative inode allocation conservatively

IN_PLACE_STEADY_GROWTH:
  reserve exact F010/component positive delta before write
  known zero-allocation failure releases all reservation
  success, partial write or allocation-indeterminate terminal consumes authoritative
  physical delta and releases only the remainder; no F019/F020 transition

LOCAL_ERASURE_DELETE_ONLY | WHOLE_RESET_DELETE_ONLY:
  allocate no data/temp inode and require no F010/F019 headroom
  seal DeleteOnlyPhysicalChargeVectorV1 before unlink, including a conservative
  parent-directory/filesystem-metadata positive delta
  old inode remains in its current class until unlink + parent fsync + reopen census
  then debit that class directly; unknown cleanup ownership reclassifies to F020
  unknown reclassification happens even above F020 and emits RESOURCE_CAP_BREACH;
  it cannot revoke an already accepted deletion obligation
  LOCAL_ERASURE durable progress uses precreated, physically preallocated
  manifests/erasure-control/slot-a|slot-b; F033 is only its in-memory queue lane
  and F018 only protects bounded ancillary recovery metadata
  any retained directory delta is
  charged F010/F014 even if this breaches the soft cap, without revoking deletion
  WHOLE_RESET uses those slots only through OS handoff; terminal authority/receipt lives
  in sandbox-external owner/control ledger and requires OS/filesystem/Keystore/process
  zero-census after the app root and its local slots have disappeared
  full storage never turns delete into replacement
```

F018是 profile要求保持的 recovery free margin，不是对系统/其它 app的硬空间租约。delete-only
紧邻 mutation做 available-space preflight并优先使用已预分配 block；若 parent metadata仍因
ENOSPC无法 durable，selective path保持 deletion obligation/blocked state并升级到已授权的
whole-reset OS handoff，不能改成分配 replacement temp或伪造完成 receipt。

上述 slot在 Keyring bootstrap时按 future `ErasureControlSlotV1` fixed EOF物理预分配、
file/parent fsync、close/reopen和 block-charge census，永久计 F010/F014；A/B selector、
overwrite/recovery/rotation与 kill matrix由
`LocalErasureControlPhaseGateV1`冻结。该 gate或 slot evidence未 PASS时 selective
erasure和依赖它的 capture都 BLOCKED；F018/F033数值本身不创造磁盘块。

上面三种 external/candidate class只存在于 sealed pre-profile permit ledger，绝不映射或
credit product F010/F019/F020；只有 `CONTROL_PLANE_ADOPTION` 可在 profile PASS后把 exact
normal owner shell一次性纳入 F010/F014。candidate evidence/primary没有 adoption边。

Blob的 “successful authority publication” 必须包括 locator收敛且引用它的 Journal record
被 durable frontier覆盖；仅 final rename或 locator ACTIVE仍是 F019。reservation不是第四
allocation class；每个 inode每一时刻恰属一类，transition append-only debit=credit。

这里的 append-only `ChargeLedgerEntryV1` 不是另一个自由增长文件。它是 producer现有
authenticated durable intent、authority-switch record和 terminal/cleanup receipt中的必需
嵌入语义；这些 control bytes及 parent-directory delta已计入同一 transaction charge。
capacity reducer只能从 preallocated fixed control slots、durable intents/receipts和 inode
census重建唯一 ledger view。若实现希望另建 ledger store，必须先用新 phase ADR冻结固定
slots/rotation/recovery/self-capacity producer；在此之前独立 store写入保持 BLOCKED，不能让
“记录容量”递归创造未记账容量。

HotSnapshot还有以下 closure；F091是单 generation hard cap，F092是 retained steady
generation count，不是总 byte cap，且 schema 1.0 为校验失败 fallback要求 F092至少为 2：

```text
each hot_snapshot_generation_physical_bytes <= F091
authenticated_declared_generation_length
  = no_follow_fstat_st_size
  <= F091
steady_hot_snapshot_bytes + positive_success_delta_reserved <= F013
retained_hot_snapshot_generation_count + create_count_reserved <= F092
```

F022对 HotSnapshot禁止 sparse/reflink/compressed/shared-block ambiguity；pre-map必须先验证
authenticated declared length、no-follow `fstat.st_size`、authoritative physical charge与
regular/nlink/inode identity。`st_size<=F091`和
`physical_charged_bytes<=F091`两项都要独立成立；任一不符都在 mmap/open-to-consumer前
拒绝，不能用较小的 physical charge掩盖巨大 sparse logical mapping。

没有 `eviction_victim` 的 publish按 CREATE消费一个 F092 count并保留 lineage predecessor；
有 victim才按 `REPLACE_PERSISTENT`成对切换，F013只检查 net steady结果，不得要求 F013同时
容纳 victim+new。victim在 lease drain期间计 F019，成功 eviction后 debit，失败转 F020；
直接 predecessor通常仍留在 F010作为 fallback。golden至少覆盖 F092=2 连续发布三代：第二代
后保留 generation 1+2，第三代只淘汰 exact generation 1且 generation 2仍可 fallback。

Export staging还必须同时满足：

```text
live_export_staging_bytes + export_live_reserved_bytes <= F027
orphan_egress_bytes + export_fallback_reserved_bytes <= F062
```

handoff并不把 staging转为 steady；只有 output完整 drain且 staging durable删除后才是成功
终态。局部擦除 replacement与 delete-only是两个不可互 cast的 operation token；delete-only
reducer只读取 F018/F033及固定 hard caps，不因 F010/F019/F020/F026没有普通写入 headroom
而拒绝。F018/F033尚未认证时仍 fail closed，但“设备已满”不是缺失配置。

全局简式为：

```text
steady reachable <= F010
peak = F010 + F019 + F020
peak <= F021 - F017 - F018
```

但只有 F022 明确“哪些目录与 temp 位于哪个 volume、capacity floor 如何选择、adoptable/
emulated storage 是否支持”后，简式才可用于 PASS。F022=`UNSET` 时不能假设所有文件都在
同一大 volume。

schema 1.0 的 owner-authenticated、crash-recoverable per-volume shared/exclusive capacity
lease和上述 conflict matrix是 F019 max证明的一部分。获取 lease及 mode-specific
contingency reservation前不得分配；lease/intent不确定时只恢复/清理，不开始不相容
transaction。若未来增加 producer、terminal mode或并发边，必须提升 schema并重算 checked
aggregate/max，不能沿用旧 F019。F020 是现场聚合
`DURABLE_ORPHAN_OR_QUARANTINE` 的实际 peak cap/metric；只在配置中写一个数、漏 temp/
directory/old alias，或 cleanup 后才采样都不算 PASS。

### 5.3 Low-storage hysteresis

0018-B 必须满足：

```text
ADR0016_DERIVED_GEOMETRY_STATUS_V1 = AVAILABLE
F023 >= F017
F024 >= checked_add(F023, F025)
F026 <= F010
F030 > 0
F031 > 0
F032 > 0
F033 > 0
checked_add(F032, F033) < F030
1 <= F035 <= F030
max(
  MAX_NORMAL_QUEUE_CHARGE_BYTES_V1,
  MAX_TERMINAL_QUEUE_CHARGE_BYTES_V1,
  MAX_ERASURE_QUEUE_CHARGE_BYTES_V1
) <= F036 <= F031
ADR0016_DERIVED_MIN_WRITABLE_SEGMENT_BYTES_V1 <= F038
ADR0016_DERIVED_MIN_WRITABLE_SEGMENT_BYTES_V1 <= F039
checked_add(
  512,
  ADR0016_DERIVED_MAX_SINGLE_ACCEPTED_PHYSICAL_FRAME_BYTES_V1,
  256
) <= F038
F039 <= F038
F039 <= ADR0016_DERIVED_REACHABLE_SEGMENT_BYTES_MAX_V1
F040 = 1
F041 = F030
F045 <= 67,108,864
F046 in ADR0017_BLOB_CAPS_V1.chunk_sizes
F048 >= F045
F056 <= 49,152
F057 <= 1,048,576
F044 <= 1,000,000
F090 <= 1,000,000
F091 > 0
F092 >= 2
```

minor 0 的 semantic payload registry为空，因此
`ADR0016_DERIVED_GEOMETRY_STATUS_V1=UNAVAILABLE_EMPTY_PAYLOAD_REGISTRY`，0018-B 必须
BLOCKED，Journal不得 allocation/admission。只有 M9A-05 提升 protocol minor、冻结首批
payload token/schema/presence/cap与新 descriptor digest，并机械生成非空 accepted set、
五个 numeric geometry值及 constructible witness 后，status才可为 `AVAILABLE`。F003 的
BudgetProfile identity必须绑定这个新 descriptor digest；registry或 geometry变化后旧
profile不可复用。

F030/F031是每个 writer queue各自的 record/byte hard capacity；不得把一份额度在
IME/BRAIN/MAIN之间共享后又让每个 writer完整使用。F032是每 writer独占的 terminal lane，
F033只属于 MAIN erasure coordinator，IME/BRAIN不能借用；各 writer normal admission按其
适用 reserved lane扣减。F041是每 writer epoch已 admission但尚未 durable terminal的总 record
上限，包含 reserved-lane record，故必须 `<=F030`。schema 1.0每 writer恰有一个 open
segment，所以 F040只能为 1；F039 sealed target不能大于 F038 open soft cap，并且 F038/
F039都必须至少容纳 ADR 0016 从冻结 descriptor推导的
`512-byte header + one minimum legal physical frame + 256-byte normal footer`。F035/F036是
每 writer单次 flush batch上限并同时受 queue/segment/ADR0016 protocol hard cap约束；
F036与三种 queue charge使用同一 conservative serialized-byte unit，并必须至少容纳
normal、terminal、erasure三类中最大的单条合法 charge；否则 reserved lane也可能 admission
后永久不能 flush。schema 1.0没有隐式 oversize/bypass flush lane。F041与整个 queue record
capacity相等；normal admission另按下述 reserved-lane
ceiling收紧，不能用较小 F041在 normal outstanding后挡住 terminal/erasure lane。

schema 1.0 明确允许一个 flush batch顺序跨越多个 segment，但同时最多只打开 F040=1
个 segment；rollover时必须先 normal-seal 当前段、durably publish frontier，再打开新段，
batch剩余 record保持原顺序，不能并行打开第二段或重排。每个单帧在 admission前必须证明
`physical_frame_bytes <=
ADR0016_DERIVED_MAX_SINGLE_ACCEPTED_PHYSICAL_FRAME_BYTES_V1`，且 F038必须容纳
`header + one maximum accepted physical frame + footer`，所以任何已接纳的单条 normal、
terminal或 erasure record都至少存在一个可写空段。

F039 是 sealed-target，不是拒绝最大单帧的 hard cap。append规则固定为：

1. 非空段若 `checked_add(current_eof, next_physical_frame, 256) > F039`，先 seal/rollover，
   再在空段处理该 frame；
2. 空段无条件允许一个已通过上述 maximum-single-frame/F038检查的 frame；若它使
   `header + frame + footer > F039`，这是唯一允许的 target overshoot，写后立即 seal；
3. 因此任一 sealed EOF 不超过
   `max(F039, 512 + maximum_single_accepted_physical_frame + 256)`，且始终 `<=F038`；
4. batch跨段不改变每帧 GCM、frame-count、capacity reservation或 ack规则；任一
   seal/frontier结果不确定时整条 writer进入既有 indeterminate恢复路径，不能跳过该帧。

F035/F036只界定一次 dequeue/flush batch，不要求整个 batch装进同一段；capacity coordinator
必须在 dequeue前按 batch逐帧保守展开所有可能 rollover header/footer、F022 allocation
rounding与 crash contingency，并原子 reserve，否则缩小 batch而不是绕过 cap。未来若改为
batch不可跨段，必须提升 schema并把完整 batch physical expansion纳入 F038/F039 validator。

`ADR0016_DERIVED_REACHABLE_SEGMENT_BYTES_MAX_V1` 由 accepted frame/header/footer geometry、
每 segment最多 65,536 frames与 authenticated-block cap `2^24` checked推导；不是 profile
自报常量。F039超过它会形成不可达 target，profile INVALID，不能运行时静默 clamp后仍 PASS。
validator/golden必须覆盖 derived minimum与 maximum-single-frame的
`bound-1/bound/bound+1`、F036 相对 normal/terminal/erasure每个 charge cap的
`cap-1/equal/+1`，以及 F041 的 `F030-1/equal/+1`。model test还必须覆盖 F039
target的 `target-1/equal/target+1` next-frame边界、空段 first-frame overshoot、非空段
rollover、跨多个 segment的最大 F035/F036 batch、maximum normal/terminal/erasure record
各自可 flush，以及任一 seal/frontier kill 后不丢失、不重排、不重复 terminal。

F091只限制单 generation的 authenticated physical length；F092限制 retained count，F013限制
实际 retained total与 positive reservation。v1不要求
`F091*F092<=F013`，因为每代可远小于 per-generation hard cap；每次 admission仍必须用 exact
conservative generation charge证明 F013/F019/F020 closure。IME RSS由 sampled F073及其
capability verdict独立裁决，不属于 F091 structural predicate。

F044/F090 是“子集占总体”的比例 ppm，natural domain 因而是 `[0,1,000,000]`。F087
write amplification 与 F088 fsync-per-million-turns 不是概率，可以合法大于
1,000,000；validator 不得误套比例上界。boundary golden 覆盖
`999999/1000000/1000001`，并覆盖 F087/F088 大于一百万仍按各自结构与 budget 比较。

达到 capture stop 时先停止新 capture/Blob/索引放大，保留 terminal/erasure lane；不得先删
仍在 retention 内的 canonical record。磁盘已满时 gap record 也可能无法写，因此只能在内存
和设置页标记 degraded，恢复后再记录可证明的 gap。

F032 terminal lane 与 F033 erasure lane 是互不重叠的 reserved record slots；两者也不能被
normal admission 借用。slot reservation 还必须有对应的 byte reservation，不能让 normal
records 吃满 F031 后以“尚有 slot”为由伪装 terminal/erasure liveness。M9A-05 payload
registry/descriptor 必须冻结：

```text
MAX_TERMINAL_QUEUE_CHARGE_BYTES_V1
MAX_ERASURE_QUEUE_CHARGE_BYTES_V1
MAX_NORMAL_QUEUE_CHARGE_BYTES_V1

terminal_reserved_bytes =
  checked_mul(F032, MAX_TERMINAL_QUEUE_CHARGE_BYTES_V1)
erasure_reserved_bytes =
  checked_mul(F033, MAX_ERASURE_QUEUE_CHARGE_BYTES_V1)

F031 >= checked_add(
  terminal_reserved_bytes,
  erasure_reserved_bytes,
  MAX_NORMAL_QUEUE_CHARGE_BYTES_V1
)
```

normal byte admission ceiling 恰为
`F031-terminal_reserved_bytes-erasure_reserved_bytes`；terminal 与 erasure 各有独立
slot+byte ledger，互不借用，也不被 normal 借用。descriptor 尚未冻结这些 charge 时，
BudgetProfile 不能 PASS。`MAX_NORMAL_QUEUE_CHARGE_BYTES_V1` 必须大于等于任一合法 normal
record 在 builder 执行前可计算的 conservative serialized charge（含最大 inline envelope
或固定大小 BlobRef，而不是 Blob plaintext）；否则必须有另一个独立、同样预留并记账的
oversize lane，不能让最大合法 attempt 永久无法 admission。model test 必须在 normal
slots/bytes 双饱和后仍分别接受一个最大
terminal 与最大 erasure attempt，并验证 reserved lanes 不能相互透支。

Blob plaintext 使用另一份全局 reservation ledger：每个 accepted writer 在读取 plaintext
第一 byte 前原子 reserve conservative bytes，满足
`live_writer_count <= F047` 且
`sum(live_plaintext_reservations) <= F048`；reservation 释放必须晚于 plaintext buffer/
Cipher/input stream 全部关闭。product body 只允许单遍读入受控 buffer/chunk pipeline，
不得创建 plaintext temp、重复 materialize 或把同一 writer 分拆 reservation 绕过 F048。
F048 `< F045`、并发总和溢出/超 cap、进程死亡后 reservation 无法 census 都使 Blob
capability BLOCKED。

对一个 capability 的 `P(C)` 所包含字段，下列 numeric
structural/config fields 必须大于 0：

```text
F010–F021, F023–F029, F030–F041,
F045, F047–F048, F056–F059, F091–F092
```

其中 F022 是 string rule reference；F015/F016/F027为正使对应 F019/F020也必须为正，
上述 inequalities 和 checked-add必须全部成立。sample metric 是否允许 0 由 evidence决定。
required config 为 0 或不满足约束不是“极小
预算 PASS”，而是 `PROFILE_STRUCTURALLY_INVALID`。只有规范明确允许空对象（例如
zero-byte Blob）时，runtime object size 的 0 才有业务语义。

### 5.4 `BudgetProfileSetV1`

单个 `BudgetProfileV1` 只绑定一个 F007 role 与一个 F008 reference fingerprint，不能同时
代表四个 role。一个可接受的 0018-B artifact 必须是 `BudgetProfileSetV1`：

```text
set schema major/minor
measurement-contract digest
acceptance-policy + SLO-envelope digests
exactly four entries, ordered:
  PIXEL_REFERENCE
  HYPEROS
  MIDRANGE
  LOW_RAM
each entry:
  role_id
  exact BudgetProfileV1 JCS bytes length + SHA-256
owner signature over all preceding canonical body bytes
```

`profile_set_sha256` 由外部 receipt/certification/manifest 对完整 signed document bytes计算并
绑定；document 不把自己的 full-document digest 放回被 hash 的 body。若未来另设 semantic
body digest，必须明确只覆盖 digest字段之前的 canonical body，不能形成 fixed point。

每个 profile 仍恰有 99 fields，且其 F007 必须等于 entry role、F008 绑定该 role 的 exact
confirmatory reference device；四个 entry 必须绑定 **pairwise-distinct** attested physical
device identity、attestation key/device-instance identity 与 F008 fingerprint，禁止同一设备
仅改 role token 充当四个 subject。四个 profile digest 必须不同，role 不得重复/缺失。
`CertifiedDeviceRoleClassifierV1` 还必须机械验证 role predicate：`PIXEL_REFERENCE` 匹配
预签 Pixel SKU/build family且 `isLowRamDevice()==false`；`HYPEROS` 匹配预签
Xiaomi/HyperOS OEM+OS evidence且非 low-RAM；`LOW_RAM` 优先，要求
`ActivityManager.isLowRamDevice()==true` 且 RAM envelope 匹配；`MIDRANGE`
匹配预签 SKU/CPU/UFS/RAM envelope、非 low-RAM、非 Pixel、非 Xiaomi/HyperOS。classifier
顺序恰为 `LOW_RAM → HYPEROS → PIXEL_REFERENCE → MIDRANGE`，这些 predicates 按上述
否定条件必须 disjoint。unknown、多个 predicate 命中、
predicate/role 不符或 device identity 被复用都使 set INVALID。未来若要允许一台设备承担
多个 role，必须提升 contract 并显式冻结例外，Gate 0 无例外。set canonical wire/signature 属于 0018-E，当前
`BudgetEvidenceWirePhaseGateV1=BLOCKED`。

`BudgetProfileSetV1`、`CertifiedDeviceRoleClassifierV1`、
`ProfileAcceptanceReceiptV1` 与 `BuildSubjectAttestationV1` 必须是 signed APK **之外**的
immutable owner-signed sidecars；
APK 只含 stable discovery/trust pins，不能含绑定自身 APK digest 的 sidecar bytes/hash。
final ADR 0015 owner manifest 与 `PlatformCertificationV1` 各自绑定每份 sidecar canonical
`(revision,length,sha256)`、exact BuildSubject/APK/applicationId/signer/contract，runtime 按
ADR 0015 monotonic A/B store/fetch/cache/rollback规则验证 exact bytes。profile set、
classifier 与 receipt 必须作为 coherent all-absent/all-present group；present 后不能退回
absent/较低 revision，同 revision不同 digest是永久 fork，任一 group member改变都要求新
receipt/certification/manifest。缺失、wrong APK、stale/rollback、signature或 receipt
contract/evidence/policy/SLO binding不符时 `BudgetProfileGateV1=BLOCKED`，不得寻找
embedded/default profile。

external binding 必须满足唯一无环依赖：

```text
signed APK
-> BuildSubject(S, APK)
-> BudgetProfileSet(F006) + Classifier(profile-entry refs, device predicates)
-> ProfileAcceptanceReceipt(profile, classifier, confirmatory evidence, M/policy/SLO)
-> PlatformCertification(build, profile, classifier, receipt)
-> Final ReleaseOwnerManifest(all exact tuples)
```

每个 node禁止嵌入自己的 full-document digest和所有 downstream digest。classifier自身 bytes
不得绑定 certification digest；certification只作为 runtime validator的已认证映射输入。
receipt不绑定 certification/manifest，certification不绑定 manifest，APK不嵌四份 external
sidecar。static dependency-graph checker与negative fixtures必须覆盖自环、反向边、profile/
classifier互相 hash、receipt→cert、cert→manifest→cert 和 APK sidecar self-reference。

runtime 不由调用者自报 role。future `CertifiedDeviceRoleClassifierV1` 必须从 exact build
fingerprint、API/OEM/RAM/storage properties和 `PlatformCertificationV1` 的 owner-signed
mapping 唯一选择一个 role/profile digest；unknown、ambiguous、fingerprint/profile mismatch
一律 `BudgetProfileGateV1=BLOCKED`。role classifier/schema 改变是 material subject change，
四 role set 重新 calibration/confirmatory；不得把最快 reference profile套给未知设备。
v1 的 operational mapping 只授权四个 confirmatory **exact attested fingerprints/device
facts**；role predicate用于分类与组织 evidence，不足以把性能/存储结论外推给同类未知
fingerprint。新增 fingerprint 必须做该实体的完整 physical confirmatory，或由后续 Accepted
ADR 给出预承诺、可验证的 dominance proof；否则保持 SCHEMA_ONLY。

F038 是 product per-segment operational ceiling；字段名中的 `soft_cap` 只表示实现可以因
F039 target、容量或调度更早 rollover，它不是扩大 GCM usage的授权。任何 sealed/open
segment都不得超过 F038；effective ceiling同时取 F038与 ADR 0016 两个 segment-DEK hard
cap的更严格结果。即使 F038尚未触发，只要下一 frame 会使 frame count超过 65,536或
authenticated blocks超过 `2^24`，也必须先 normal seal/new-DEK。任何实现把 F038 当作
cryptographic hard cap override 都是 `PROTOCOL_CAP_VIOLATION`。

---

## 6. Measurement workload

所有预算实验使用版本化、公开 digest 的**合成** workload。生产数据不是基准 fixture。
workload generator 必须固定 seed、分布与 expected logical byte count；修改 corpus 即改变
measurement contract digest。

基础 workload：

| id | 内容 |
|---|---|
| W0 | 空 installation/bootstrap、锁定/解锁、无历史 |
| W1 | 中英混输 steady typing，短/中/长 editor context，全部为合成句 |
| W2 | Agent turn：progress、tool request/result、terminal patch、cancel/error |
| W3 | Blob mix：0-byte、边界 chunk、跨 chunk、product cap、protocol-cap negative |
| W4 | M9 Recall corpus：ordered `R100K` + `R1M` exact case plans、冲突 thread、分页与取消 |
| W5 | storage pressure：接近 stop/resume/soft cap，含 orphan/temp |
| W6 | crash schedule：每个 write/force/rename/locator/frontier/barrier 边界 |
| W7 | maintenance：seal、index rebuild、compaction、Blob rewrite |
| W8 | rotation/erasure/egress：累计擦除、多进程重启、本地 adapter/pipe drain；Provider path 仅接 attested `SyntheticProviderTransportV1`，零 Internet |

W8 在 ADR 0017 的 phase schema 未接受前保持 `NOT_RUN_BLOCKED`，不能用 stub latency 代替。

W4 不是“实现自选的大语料”：

```text
R100K.eligible_session_record_count = 100,000
R1M.eligible_session_record_count   = 1,000,000
ordered_required_subplans           = [R100K,R1M]
```

两者都以 M9.0 canonical `SessionRecordV1` 为 corpus unit；每个 subplan在结果可见前绑定
generator version/seed、exact eligible record count、checked logical byte count、sorted
record-commitment digest、conflict-thread/page/cancel case ordinals与 fixed-cut manifest。
所有引用 W4 的 required measured tuple必须完整执行两个 subplan，缺任一、用较小“large”
替代、count±1、bytes/commitment不符或只跑较快 subplan均
`WORKLOAD_COVERAGE=FAIL`。10,000,000 records只能是 desktop format/streaming stress，
不进入设备 BudgetProfile。M9.1 Event cardinality须在 `M91-00` 后另做 recertification；
不得把 Event数量写进 M9 evidence。

---

## 7. Device roles 与 suite manifest

四个 role 都是必选；一个高端 Pixel 不能代表全部：

| role id | 目的 |
|---|---|
| `PIXEL_REFERENCE` | AOSP/Google reference、当前受支持 API 的基准 |
| `HYPEROS` | 小米/澎湃 OS 的 Keystore、进程和存储差异 |
| `MIDRANGE` | 常见中端 CPU/UFS、非旗舰内存 |
| `LOW_RAM` | Android low-RAM class、紧张 heap/后台进程回收 |

role 不是具体设备名。每次 0018-B proposal 必须提交一个未来
`DeviceSuiteManifestV1` wire 所能精确表达并签名的 suite plan，逐 role 固定以下**语义**：

- manufacturer/model、hardware SKU；
- Android release/API（API 36.1 单独标识，不与 API 36 合并）；
- build fingerprint/security patch digest；
- RAM/lowRam flag、CPU/storage type；
- filesystem/volume/capacity floor；
- Keystore observed security level；
- battery health/thermal instrumentation；
- 每个 required tuple 的 exact `warmup_count`、exact `measured_attempt_count`、
  `evidence_run_id` 与 seeded execution order；
- 为什么它代表当前 minimum supported envelope。

上述 bullet 不是 accepted serializer。字段 presence/type/length、unknown-field policy、
canonical bytes、digest/signature 与 trust key 尚待 0018-E 冻结；因此当前不能生成可通过
`BudgetEvidenceWirePhaseGateV1` 的 manifest。设备或系统升级后旧 evidence 不自动继承。
缺少任何 required role，overall 至少 `INCONCLUSIVE`。

---

## 8. 场景 S0–S8

| id | 起点与动作 | 主要观测 |
|---|---|---|
| S0 | fresh install、locked boot、first unlock、cold bootstrap | directBoot/noBackup、keyring、目录、RSS |
| S1 | warm steady capture/typing | enqueue、CPU、RSS、energy、write bytes |
| S2 | Agent turn checkpoint，包含成功/失败/取消 | DurableAck ledger、p95/p99、terminal lane |
| S3 | bounded Blob publish/read/delete | heap、publish、locator、caps、orphan |
| S4 | Broker/DB 已启动的 warm recall | p50/p95/p99、CPU/read bytes、pagination |
| S5 | main process dead，bind 到首个结果 | cold bind、fd/pipe、egress |
| S6 | 容量 floor/stop/resume 附近 | stop safety、hysteresis、reserve、无 silent delete |
| S7 | systematic kill/reboot/recovery | frontier、Ack、orphan、leak、cold census |
| S8 | maintenance、Blob rewrite、rotation、累计 erasure、egress drain | duplicate peak、lease、alias、process restart |

Capture 与 Blob 的晋级证据**必须包含 S8**。原因是 steady-state 快并不能证明 rewrite/擦除时
不越过容量、泄漏旧 mapping 或卡死输入。S8 phase schema 尚未接受时，它们保持 BLOCKED，
不能先用 S1/S3 晋级。

future 0018-E 必须冻结 `ScenarioDependencyDagV1`，不能把 `seeded execution order` 解释成
任意 shuffle。每个 primary run在结果前绑定 closed ordered root-epoch/attempt set及
predecessor edges：

```text
ZERO_ROOT -> S0_AUTHORITY_OWNER_KEYRING_BOOTSTRAP -> SUBSTRATE_READY
SUBSTRATE_READY -> every non-S0 attempt in that root epoch
CORPUS_READY + INDEX_READY -> S4/S5/index-read attempts
BLOB_CORPUS_READY -> Blob rewrite/retire attempts
DATA_READY -> rotation/maintenance/erasure attempts
destructive S8 terminal -> ZERO_CENSUS -> next distinct root epoch (if precommitted)
```

seed只选择该 DAG 的 canonical random linear extension；S0始终第一，缺 predecessor、cycle、
S8提前破坏后继续沿用 root、或看见结果后重排均 INVALID。kill/backup/erasure等破坏性 suite
可在同一 `evidence_run_id` 内使用多个**预承诺** root epochs，但每个 epoch有不同 root
identity，terminal后必须 zero census；run开始后不得新增 root/attempt top-up。golden覆盖
cycle/missing edge、相同 seed确定性、合法 independent-node换序、S8提前与未预承诺新 root
拒绝。

---

## 9. 采样与统计：`SENSE_BUDGET_STATS_V1`

### 9.1 时钟与样本

- same-boot latency 使用 `SystemClock.elapsedRealtimeNanos()`，但每个 stamp 必须同时绑定
  attested `boot_id_digest`；只有 start/terminal boot ID 相同且 terminal>=start 才可相减；
- thread CPU 使用 `Debug.threadCpuTimeNanos()`/Perfetto 对应 clock；
- RSS/PSS、I/O、fsync、energy/thermal 必须注明采集源和分辨率；
- future manifest 必须在执行前为每个 required `SampleTupleKeyV1`
  `(build,device,role,capability,profile_context_digest,writer_scope,scenario,workload,
  case_kind,case_plan_digest,cohort,metric,direction,statistic,ledger_mask)` 承诺 exact
  `warmup_count >= 5` 与 exact `measured_attempt_count >= 30`；
- `SampleTupleKeyV1` 必须与 §13 生成的 required tuple byte-for-byte 同构；v1 中一个
  measured observation ordinal 恰属于一个 key，禁止把相同 S/W/F raw row 隐式复用于另一个
  capability/context/writer/case。若未来允许共享，必须提升 schema 并预承诺一份多 subject
  observation authority 与守恒证明，不能由报告器事后 alias；
- 唯一 closed multi-metric exception是
  `F042_F043_F044_DURABILITY_BUNDLE_V1`：同一
  `P2/TURN_CHECKPOINT/DURABILITY_SLO` operation在结果前绑定 exact
  `{F042,F043,F044}` 三个 member tuple keys、相同 warmup/measured ordinal set与
  `DurabilityMetricBundleObservationAuthorityV1` digest。每 ordinal只有一份 raw
  admission/outcome/timestamp/conservation row，再由 frozen typed projection使
  F042/F043读取 ACK_DURABLE latency、F044读取全 Ack bucket ratio；不是报告器复制三份
  raw row。member set缺/多/顺序错、ordinal sets不等、bundle外复用、只保留某个 projection
  或 bundle raw row invalid都使三个 member一起 INCONCLUSIVE。F044在 P1/P3/P6的其它 rows
  仍各属自己的普通 key，不能借此 bundle泛化共享；
- invariant 使用独立 `InvariantTupleKeyV1`
  `(build,device,role,capability,profile_context_digest,writer_scope,scenario,workload,
  aggregate_case_set_digest,FAULT_INVARIANT,metric,ledger_mask)`；它聚合该 pair 全部
  precommitted cases，不得伪装成某个 sampled case；
- 按 seeded order 执行**全部**已承诺 warm-up 和 measured attempts；同一个
  `evidence_run_id` 不得 early-stop、retry-until-valid、top-up、替换 INVALID attempt 或因
  前 30 个结果有利而停止；
- 每个 required `SampleTupleKeyV1` 的全部且仅全部 precommitted measured ordinals都是
  required membership。只有 exact membership完整、无重复，且**每个** measured row 的
  evidence validity均为 `VALID` 时，才可计算统计；任一 row
  `INVALID/UNSUPPORTED/MISSING` 都使该 tuple/metric直接
  `INCONCLUSIVE/EVIDENCE_INVALID|UNSUPPORTED|MISSING`，不得筛掉后再用剩余 30 条 PASS。
  `measured_attempt_count >= 30` 仍是下限，但不能预签大量 attempts后丢弃不利或无效 rows；
  产品 operation outcome为 FAILED/INDETERMINATE/backpressure不等于采集 row无效，只要其
  identity、环境、ledger和字段完整，它仍是 `VALID` row并进入对应 metric/守恒统计；
- 全部且仅全部 precommitted warm-up ordinals也必须有 protocol-valid
  identity/environment/outcome record并进入 workload-coverage/integrity evidence；它们不
  进入 threshold statistic，但任一 warm-up missing/duplicate/INVALID/UNSUPPORTED都使该
  tuple INCONCLUSIVE。warm-up不得 replacement、top-up、retry-until-valid或静默省略；
- 每个 0018-B proposal 在任何 sample 开始前，必须为每个 verdict device 绑定**唯一**
  `primary_evidence_run_id`；只有该 primary run 的预承诺 rows 进入该 proposal 的统计与
  verdict。不存在“latest run”“best run”或多个 run reducer；
- primary run 缺 valid samples、环境 INVALID、INCONCLUSIVE 或 FAIL 时，该 proposal 就保持
  对应 verdict，不能以同 subject/profile 的第二次 run 改写。额外 exploratory run 必须用
  不同 `evidence_run_id` 完整保留，但永远不影响该 proposal；
- 新 proposal 只有在至少一个 subject identity 发生 material change 时才 eligible：
  F003–F009 任一值、full profile JCS digest、APK digest、exact device fingerprint，或未来
  accepted manifest 中 workload/corpus/instrumentation plan digest。新 build 修复会改变
  F006；单纯“再跑一次”不构成 change。相同 subject identity 不允许 successor proposal；
- 未来 evidence wire 必须让 proposal 在看见结果前签名承诺 primary run 与 subject
  identity。当前该 wire 未冻结，因此不能提交权威 proposal；
- 场景顺序由 manifest seed 决定并记录，不能只把慢场景放在冷却后；
- 不删除 outlier。环境不合格的整个 iteration 标 INVALID并保留原值和 reason；由于上条的
  exact-membership规则，对应 tuple不能产生统计 PASS；
- p50/p95/p99 使用 nearest-rank：
  `sorted_values[ceil(p*n)-1]`；
- 同时报告 count、min、max、median、p95、p99、arithmetic mean、MAD；
- 置信区间使用 `SENSE_BUDGET_CLUSTER_BOOTSTRAP_V1`：DUT same-boot row的 cluster恰为
  `(primary_evidence_run_id,root_epoch_id,boot_id_digest)`；跨 reboot/HARNESS_RUN row的
  cluster恰为 `(primary_evidence_run_id,root_epoch_id,HARNESS_RUN)`。以 cluster为单位有
  放回抽取与原 cluster数相同的 clusters，被抽中 cluster的全部 measured rows按 multiplicity
  一起进入统计，禁止逐 row bootstrap；
- PRNG seed恰为
  `SHA256("sense.budget.cluster-bootstrap.v1" || 0x00 || proposal_subject_digest ||
  sample_tuple_key_digest)`；使用 SHA-256 counter-mode byte stream与 rejection sampling生成
  无 modulo bias的 cluster index。按 replicate ordinal `0..9,999` 连续消费，固定
  10,000次；每次重算原 field statistic。replicate值按 `(exact value,replicate_ordinal)`
  排序，95% percentile interval取 nearest-rank 2.5%/97.5%，即 n=10,000时 ordinal
  rank 250与 9,750（数组 index 249/9,749）。只有一个 cluster时报告 degenerate interval，
  不伪造独立样本；
- CI只作诊断，不替代 profile threshold比较、不改变 PASS/FAIL；exact cluster/seed/counter
  wire仍由 0018-E冻结，但任何未来 wire必须实现上述唯一语义；
- ratio 报告值以 ppm 的整数 `floor(numerator*1_000_000/denominator)` 表示，乘法先
  checked u128 或等价精确实现；该 floor 值只用于展示/证据，MAX gate 比较使用 §12.2
  exact cross-product，不能因余数被截断而 PASS。

`elapsedRealtimeNanos()` 在 DUT 重启后不是同一时间轴，禁止跨不同 `boot_id_digest`
直接相减。S7/S8、F098/F099 或任何跨 reboot interval 必须使用测试进程之外的 attested
`HarnessMonotonicClockV1`，每个 stamp 绑定
`(harness_epoch_id,counter,resolution_ns,uncertainty_ns,DUT_boot_id_digest)`；harness epoch
在整个 primary run 内唯一且不可随 DUT reboot 重置。duration 形成 checked interval：

```text
lower_ns = max(0, (terminal_counter-start_counter)*resolution - uncertainty_sum)
upper_ns = checked_add(
  (terminal_counter-start_counter)*resolution,
  uncertainty_sum
)
```

MAX/p95 latency 使用 `upper_ns`，MIN 使用 `lower_ns`；harness counter 回退、epoch 变化、
stamp 缺失、uncertainty 未预承诺或 arithmetic overflow 都使 row INVALID/metric
INCONCLUSIVE。允许的另一种实现是预承诺的 per-boot segments + external reboot-gap
interval，但 reducer 必须得到同样的 conservative lower/upper bounds，不能把 gap 当 0。
golden 覆盖 same-boot、reboot once/multiple、boot ID mismatch、harness reset/counter
rollback、uncertainty boundary 与“错误直接相减”拒绝。

#### 9.1.1 Millisecond observation boundary registry

所有 sampled `*_milliseconds` field 保留 raw conservative `duration_upper_ns`；不得先转为
ms、floor/round 后排序或判 gate。nearest-rank 直接对
`(duration_upper_ns,case_ordinal)` 排序，duration 相同时用 ordinal 唯一 tie-break。普通
millisecond MAX gate 的唯一比较是 checked
`selected_duration_upper_ns <= field_milliseconds * 1_000_000`；右侧 overflow 使 profile
INVALID。报告展示才可 `ceil(duration_upper_ns/1_000_000)`。以下
`ObservationBoundaryV1` 是 closed registry：

| field | eligible case kind | exact start（先记录 stamp，再允许下一副作用） | exact terminal | clock / grain |
|---|---|---|---|---|
| F042–F043 | TURN_CHECKPOINT | durable append reservation 已原子接受、尚未调用 record builder | 对应 cut 的 mirrored durable frontier 已提交且 `DurableAck=DURABLE` callback 被观察 | DUT same-boot / one accepted checkpoint |
| F049 | BLOB_PUBLISH | conservative Blob reservation/byte charge 已接受、尚未读取第一个 plaintext byte | 最终 physical 全量认证通过、locator 为 adjacent-generation、byte-equal mapping 的 `ACTIVE/ACTIVE` 且 BlobRef 已可被安全引用 | DUT same-boot / one publish |
| F050 | BLOB_PUBLISH | locator 第一份 `PREPARED` bytes 写入前 | adjacent-generation、byte-equal mapping 的双 `ACTIVE` 经 close/reopen/full-reread 验证 | DUT same-boot / one locator convergence |
| F053 | BLOB_PUBLISH | 已认证 BlobRef 的 full-verify request 被接受、尚未打开/读取第一个 chunk | exact length、每 chunk AEAD、root/digest 与 EOF 全部验证且 fd 已关闭 | DUT same-boot / one full verify |
| F055 | BLOB_REWRITE | 新 locator `ACTIVE/ACTIVE` commit 已验证、old physical 首次进入 drain set | old locator generation/lease/reader 归零，old inode unlink + parent fsync 完成且 census 证明不可达 | HARNESS_RUN / one rewritten Blob physical |
| F055 | KEY_ROTATION | new key generation 成为 receipt-chain 唯一 effective CURRENT，old generation 首次进入 RETIRING | old generation 的 data/manifest alias、attempt frontier、lease/Cipher 引用按 ADR 0017 retirement receipt 闭合且 reboot census 不可达 | HARNESS_RUN / one retired key generation |
| F055 | LOCAL_ERASURE | cumulative erasure fence/receipt 使 exact affected mapping 集合进入 drain、尚未 unlink | 集合内全部 locator/alias/lease/reader/inode unlink + parent fsync，reboot cumulative census 证明不可达 | HARNESS_RUN / one erasure mapping set |
| F060 | WARM_RECALL,COLD_RECALL,EXPORT_EGRESS | request admission 与 current `ErasureReadBindingV1` 均成功、尚未查询/绑定/发送 page | 第一页 exact digest/length/lineage 验证后交付给目标 consumer；export 只计本地 pipe consumer | DUT same-boot / one request |
| F061 | WARM_RECALL_CANCEL,COLD_RECALL_CANCEL,EGRESS_DRAIN | current cancel/fence authority 被接受 | request terminal，producer/consumer fd、pipe buffer、lease 与本地 socket 全部闭合并经 census | HARNESS_RUN / one cancelled transfer |
| F064–F066 | WARM_RECALL | warm query admission 与 erasure binding 成功、尚未第一次 lookup | 第一份包含至少一个 verified result 的 page 对 consumer 可见 | DUT same-boot / one query |
| F067–F069 | COLD_RECALL | harness 已证明 Broker/target process 不存在并接受 query、尚未发起 bind | cold bind 后第一份包含至少一个 verified result 的 page 对 consumer 可见 | HARNESS_RUN / one query |
| F070 | WARM_RECALL,COLD_RECALL | 对现有 cursor 的 next-page request 与 erasure binding 重验成功 | 下一页 digest/length/lineage 验证后对 consumer 可见 | DUT same-boot / one page request |
| F071 | WARM_RECALL,COLD_RECALL | 目标 record 所属 cut 的 mirrored durable frontier commit stamp | 第一份包含该 exact record identity 的 verified result 对 consumer 可见 | HARNESS_RUN / one target record |
| F072 | WARM_RECALL_CANCEL,COLD_RECALL_CANCEL | recall cancel/fence authority 被接受 | query/cursor/page producer terminal，全部 fd/lease/buffer 清零并经 census | HARNESS_RUN / one cancelled query |
| F093 | HOT_SNAPSHOT_PUBLISH | generation publish reservation 被接受、尚未写 generation body | authenticated generation 与 pointer pair close/reopen/full-reread 后可被 reader 选择 | DUT same-boot / one generation |
| F095 | INDEX_REBUILD | rebuild admission、fixed cut 与 erasure binding 被接受，尚未读取第一条 record | derived index/pointer durable published，source coverage equality 与 reopen query census PASS | HARNESS_RUN / one rebuild iteration |
| F098 | LOCAL_ERASURE | valid request authority 被接受且任何 fence/write 尚未发生 | committed erasure receipt、unlink/parent-fsync、reboot/reopen cumulative census 全部 PASS | HARNESS_RUN / one erasure transaction |
| F099 | EGRESS_DRAIN | erasure/cancel fence authority 被接受 | 所有属于 scope 的本地 request/fd/pipe/socket 为 exact terminal 且本地 census PASS | HARNESS_RUN / one drain transaction |

F093只测 publish；`HOT_SNAPSHOT_READ` 不属于该 field的 `U(F)`，也不得借用 publish boundary
生成样本。若后续需要独立 read latency budget，必须新增 field/profile schema，而不是把
两种 grain混进同一个 p95。

表中 first result/page 必须包含至少一个 manifest 预承诺 eligible item；empty heartbeat、
connecting/progress UI、未经 digest/lineage 校验的 bytes、Provider ACK 或 remote deletion
claim 都不是 terminal。start/terminal event 必须以 closed event type 和 exact subject
commitment进入 raw row；缺 event、错误 case kind、错误 grain、跨 boot 却使用 DUT clock、
或 duration 无法绑定同一 operation identity均为 INVALID，不得改用邻近 timestamp。

F095 不先为每个 iteration 生成整数“ms/10k”。每个 VALID `INDEX_REBUILD` iteration 保留
rational `(duration_upper_ns*10_000)/exact_rebuilt_record_count`；count=0 为 MISSING，
checked-u128 overflow 为 INVALID。以 exact cross-product 排序 rational，完全相等时以
case ordinal tie-break，取 nearest-rank p95；唯一 gate 比较是：

```text
selected_duration_upper_ns * 10_000
<= F095 * 1_000_000 * selected_exact_rebuilt_record_count
```

展示才可对所选 rational ceil-div。任意先 floor 每个 normalized sample、以 expected count
替代 exact rebuilt count、或把 coverage 未闭合的 iteration 纳入 denominator 都无效。

#### 9.1.2 Non-millisecond observation registry

所有 sampled 非毫秒 field同样使用 closed `NonMillisecondObservationRegistryV1`。manifest
必须在结果可见前绑定 exact window token、source/version、采样 cadence/phase、counter
width/resolution、process/volume/producer attribution与 calibration digest；reporter不能在
运行后选择较有利来源。required layer不可观察、source不支持目标 API/filesystem、counter
wrap/reset、相邻 measured case I/O overlap、漏/重 sample或 process attribution不唯一时，该 row是
`UNSUPPORTED/INVALID`，相应 tuple为 INCONCLUSIVE；绝不能以“注明无法观察”继续 PASS。

closed window registry为：

| window | fields | exact start | exact terminal / grain |
|---|---|---|---|
| `LOCAL_OFFER_WINDOW` | F034 | metadata signal已构造并绑定 operation identity，尚未进入 bounded local offer | 同一次 nonblocking offer返回 accepted/rejected；one offer |
| `CASE_RESOURCE_WINDOW` | F051,F052,F054,F062,F063,F073–F077,F081,F085,F086,F096,F097 | case全部前置已验证、start baseline/ledger census已取，尚未发生该 case第一项 allocation/open/write/side effect | case exact terminal且所有 fd/lease/class transition/unlink/parent-fsync/reopen census闭合；one precommitted case/workload ordinal |
| `JOURNAL_DURABLE_WRITE_WINDOW` | F078,F082 | Journal child operation admission、logical-work manifest与 reservation已接受，尚未调用 builder/读取 body/写 byte | exact Ack/failed/indeterminate terminal及相关 local resource census；one accepted Journal operation |
| `RECALL_QUERY_WINDOW` | F079,F083,F089 | query admission、fixed cut与 erasure binding成功，尚未 first lookup/open/read | all requested pages或 cancel/error terminal，fd/lease/cursor census闭合；one query |
| `IME_MAIN_CALLBACK_WINDOW` | F080 | closed Memory-owned IME-main callback/slice entry | 同一 callback/slice return；one callback |
| `MAINTENANCE_WINDOW` | F084,F094 | exclusive lease、fixed source cut与 exact source census已绑定，尚未 first read/write | publish/swap、old drain与 reopen census全部闭合；one maintenance operation |
| `P2_FSYNC_WINDOW` | F088 | §9.3 precommitted P2 window首个 turn admission前 | 该 window全部 turn terminal与 filesystem trace drain；one exact turn set |
| `CASE_THERMAL_WINDOW` | F090 | 对应 case resource/latency start stamp | 对应 case exact terminal；all scheduled thermal ordinals |

`CASE_RESOURCE_WINDOW` 在 start、每次 reservation/class/ownership/fd transition、预承诺固定
cadence与 terminal都取 observation；peak取这些 observation的最大值。任一 transition未触发
observation、cadence缺样或 terminal census未闭合使 row INVALID。charge-ledger中的
unmaterialized reservation与其变成的 actual physical allocation是同一 charge identity，
同一时刻只计一次：

- F051 是 Blob-tagged F020 actual + still-unmaterialized Blob fallback reservation的 byte
  high-water；不含仍属 F019的 live Blob；
- F052 对 CREATE使用 pre-ID就生成、但不授权文件创建的 `blob_fallback_charge_id`：首次
  physical allocation时一对一、不可变绑定唯一 `physical_storage_id`。对 REWRITE使用
  §5.2 `BlobRewriteFallbackContingencyV1` 的单一 count unit与两个预绑定 branch identity，
  每个 observation最多一个 selected branch；switch不重绑 ID。两者都按 reservation-or-
  actual identity去重一次，不是 logical attempt、locator slot或文件名数，也禁止为了计数
  提前分配 physical storage ID；
- F054/F096/F097 分别是 BLOB_REWRITE/COMPACTION/KEY_ROTATION producer-tagged F019 actual
  + still-unmaterialized live reservation high-water；
- F062 是 EGRESS-tagged F020 actual + egress fallback reservation high-water；不含 F027 live
  staging、pipe buffer或远端声称的 bytes；
- F063 在每个 stamp先对 `RequiredMemoryProcessSetV1` 全部 process incarnation的 Memory-owned
  open fd-table entries作 checked sum，再取 high-water；逐 process ownership ledger须由
  `/proc/<pid>/fd` census核对，dup后的每个 table entry各计一次；
- F086 使用唯一 F022 backing block device在整个 window的**全设备** physical write-sector
  delta，包含系统/其它进程同期写、data、filesystem metadata/journal、WAL、temp、rewrite与
  fsync写，作为保守上界，不做无法证明的 app/inode过滤。source/version必须绑定该 counter
  文档规定的 `counter_sector_unit_bytes`，checked
  `sector_delta*counter_sector_unit_bytes` 得到 bytes；device logical block size只作环境/
  alignment证据，不能代替 counter单位。只观察 app logical writes或 `stat().length`不合格；
- F073–F075 的唯一 RSS source是对应 process
  `/proc/<pid>/smaps_rollup:Rss` 的 canonical decimal `kB` token；解析后 checked
  `value*1024`才是字段 bytes，unknown suffix/overflow INVALID。F073为
  `max(0,window_peak_ime_rss-start_ime_rss)`；F074/F075分别是 Broker/Brain绝对 window peak；
- F076/F077 的唯一 Java heap source是 required process set中每个 process
  `Debug.MemoryInfo.getMemoryStat("summary.java-heap")` 的 canonical decimal kB string；
  每个 stamp checked乘 1024并跨 set求和，再分别对 capture/recall window取绝对 byte peak。source缺 key、
  unknown suffix、负值、不可解析或 overflow为 UNSUPPORTED/INVALID；
- F081使用 required process set全部 process的 Perfetto ART-attributed stop-the-world GC
  pause slice；每个 case取整个 set在 window内完整 pause duration的最大值，再跨 measured
  rows取 p95。全部 process完整 trace明确无 pause时该 case值恰为 0；缺任一 required
  process trace、跨 window slice、trace gap或归属不明均 INVALID。

`RequiredMemoryProcessSetV1` 由 capability/context机械生成，manifest不能删 process：

```text
KEYRING_BOOTSTRAP,INDEX_REBUILD,MAINTENANCE -> {MAIN}
KEY_ROTATION -> {MAIN} union exact live lease-owner process incarnations pinned before effect
LOCAL_ERASURE -> {MAIN} union exact live/discovered process incarnations in
                  ErasureBarrierOutcomeLedgerV1
JOURNAL_WRITE,BLOB_STORE,CAPTURE -> {exact destination writer process}
WARM_RECALL,COLD_RECALL -> {MAIN, exact requesting consumer process} dedup
HOT_SNAPSHOT -> {MAIN,IME}
EXPORT_EGRESS -> {MAIN}
```

case实际出现额外 Memory owner process或 required process incarnation更换却没有闭合旧 incarnation
资源时 INVALID；process未运行只能在 U(F)明确 N/A的 tuple中省略，不能由 reporter选较小集合。

每个 F078/F084/F085/F087 applicable case在第一项副作用前必须封存
`LogicalWorkByteManifestV1`：

```text
operation/effect kind
fixed source cut (or zero for new append/publish)
ordered logical item/range identities
kind = JOURNAL_CANONICAL_PAYLOAD | BLOB_PLAINTEXT | SOURCE_CANONICAL_RANGE
overlap/dedup rule
checked total logical bytes
terminal processed-subset commitment
```

new Journal append/Blob publish只把本 window最终首次成为 durable authority的 canonical
payload/plaintext计入 terminal subset；frame/AEAD/BlobRef/locator overhead不计。rewrite、
rebuild、compaction、rotation、erasure按预绑定 source cut中本 operation实际扫描/重写/退休的
eligible canonical logical ranges计，每个 source identity/range恰一次；Journal中的 BlobRef
metadata与 Blob plaintext是不同 kind，不得用同一 plaintext重复两次。成功 POSITIVE_SLO
要求 terminal subset等于 precommitted set；失败 row保留 exact processed subset和物理
numerator，不得事后挑 denominator。

F078只适用于一个含 actual Journal append child的 operation row，累计 closed Journal worker
thread registry在 `JOURNAL_DURABLE_WRITE_WINDOW` 内的 thread CPU nanos，并且 denominator
只取该 operation最终 `DurableAck=DURABLE` 的 manifest
`JOURNAL_CANONICAL_PAYLOAD` bytes；`SOURCE_CANONICAL_RANGE`、Blob plaintext与 maintenance
case聚合 bytes都不得进入 F078 denominator。每个 row恰好一个 accepted Journal operation，
不得以包含零个或多个 Journal child的 maintenance case作为同一粒度；非 durable terminal
仍保留 outcome/coverage row，但不伪造 denominator。F079同理只累计
Broker recall executor closed thread registry在 `RECALL_QUERY_WINDOW` 的 CPU nanos，不含
IME/UI/Provider。thread迁移必须由 Perfetto sched/thread identity连续追踪；仅在首尾读取某一
thread counter而中途迁移未知时 INVALID。

F082/F083/F084 的唯一 energy semantic是校准过的外部 device power monitor对各自 exact
window作总能量积分，不减“估计 idle baseline”。manifest预绑定 monitor model/serial、
calibration、sample rate/phase与电压/电流积分算法；设备必须在隔离串行 case中运行。F082
grain为 one turn，F083为 one query，F084保留 rational
`integrated_microjoules*MiB/exact_maintained_bytes`；zero denominator、样本 gap、量程饱和或
其它 workload overlap均 INVALID。

F085恰为同一 `CASE_RESOURCE_WINDOW` 的 terminal
`LogicalWorkByteManifestV1.processed_subset` checked byte total；F084 maintenance denominator
与它使用同一 manifest/source identities。F086与 F085因此共享 exact case/workload identity、
start/terminal和 measured ordinal，并生成 F087的 raw rational：

```text
physical_block_written_bytes * 1_000_000 / logical_work_bytes
```

每个 physical sector恰计一次；F085为零时 F087为 MISSING，不允许把 failed operation的物理
写从 numerator移除。F088/F090的 denominator与 cross-product沿用 §9.3，且它们各自窗口不得
与另一个 capability measured window重叠。

F089使用与 F086相同 backing block device的全设备 physical read-sector delta，包含
index/Journal/Blob/manifest、filesystem metadata、readahead与同期系统 read，作为保守上界；
page cache hit可合法为零，但只能由 block trace/counter证明。source/version绑定 documented
`counter_sector_unit_bytes`并 checked换算 bytes，不能乘 device logical block size。F094保留 raw rational
`newly_published_index_physical_charge/exact_eligible_live_record_count`，只计该 rebuild
新发布的 derived index generation，排除 HotSnapshot、source Journal、Room/WAL cleanup和
old generation；zero count为 MISSING。F080只测
`MemoryImeMainSliceKindV1` closed registry中的 callback（apply preview、progress、terminal、
cancel、page handoff），用 Perfetto Looper dispatch entry/return取最大 duration；不能把两次
dispatch间 idle或非 Memory UI工作归入/移出。

F086/F089 window使用 source-specific I/O fence：start counter前，先完成上一 case所有 app
fsync/checkpoint/cleanup，再等待 backing-device in-flight/request queue、dirty/writeback与
sector counters在 manifest固定连续 quiescence interval内稳定；terminal副作用完成后继续
trace到本 case所有已发 read/write/readahead/writeback completion、queue drain和下一稳定
interval，才读取 terminal counter。任一 prior/next case I/O跨 fence、queue无法观察或
quiescence超 deadline使 row INVALID。qualified window内的系统/其它进程 I/O不扣除，全部进入
保守 numerator；但额外前台 workload违反 scenario qualification。

Perfetto/raw-row缓冲、ledger和 harness telemetry在 window内只能驻 RAM并经 host pipe流出；
不得在同 backing device写本地 trace/log/artifact。若 instrumentation自身写入同卷，不能
事后扣除，只能全部计入 F086或将 row判 INVALID（由 manifest预承诺 policy唯一选择）。

F090在每个 `CASE_THERMAL_WINDOW` 以 manifest固定的
`SystemClock.elapsedRealtimeNanos()` cadence/phase调用
`PowerManager.getCurrentThermalStatus()`；status
`>= THERMAL_STATUS_SEVERE`进入 numerator。start前 qualification不替代 window samples；
missing/duplicate/late-over-tolerance ordinal均 INVALID。

所有 block、Perfetto、`/proc`、power与thermal source必须由 attested harness采集；DUT不能
自行回报一个未经核对的聚合值。negative fixtures至少覆盖 counter wrap、漏 transition sample、
reservation/actual双计、F052按 locator误计、RSS错误 baseline、跨 process heap、GC trace gap、
CPU thread migration丢失、energy idle subtraction、错误扣除 background block I/O、
cached read伪报、F094混入 HotSnapshot/WAL、fd dup漏计与 thermal漏样。

### 9.2 写放大

```text
write_amplification_ppm =
  floor(physical_written_bytes * 1_000_000 / logical_work_bytes)
```

`logical_work_bytes` 来自同一 precommitted `LogicalWorkByteManifestV1` 的 terminal
processed subset：new Journal append/Blob publish只计最终首次成为 durable authority的
canonical payload/plaintext；rewrite/rebuild/compaction/rotation/erasure则计 fixed source
cut中实际处理、且在成功终态覆盖全部 precommitted set的 canonical ranges。FAILED/
INDETERMINATE保留 processed subset与物理写 raw row，但不进入 F087 POSITIVE_SLO 分母，也
不得把其物理写从 F086 RESOURCE_ALL 中移除。
physical bytes 包括 Journal、Blob、manifest、locator、Room/WAL、temp、rewrite、fsync metadata
的全部 mandatory layer；唯一 source是 §9.1.2 已预绑定的全 backing-device保守 block
counter/trace upper bound，而不是“exclusive app/inode bytes”。source不可用或 fence/
attribution不闭合使 tuple `UNSUPPORTED/INCONCLUSIVE`，不能用“可测部分”低估 numerator。

### 9.3 无效条件

以下任一情况使相关 iteration INVALID；若影响 suite identity 则整个 bundle INVALID：

- commit/APK/signer/profile/descriptor digest 不匹配；
- dirty tree、debug instrumentation 与声明 variant 不符；
- 设备 role/fingerprint/API（特别是 36 与 36.1）不匹配；
- 采样丢失、clock 回退、counter overflow、root/调试工具改变存储路径；
- 非 workload 的前台 app、系统更新，或**attempt start 前**充电/thermal/environment
  qualification 超出 manifest；
- storage free/capacity 不在场景声明范围；
- crash schedule 没有实际命中目标 boundary；
- Ack request 缺 terminal ledger、重复 terminal 或跨 process epoch 错配；
- 明文/secret 出现在 log、trace 或 artifact；
- 无法判断 safety cap 是否在 allocation 前执行。若已可靠观察到 cap violation，evidence
  本身仍可为 VALID，但对应 capability metric 必须 FAIL。

INVALID 不是 outlier，不能静默重跑后丢弃；bundle 必须保留 invalid count 与原因。
attempt start 后由 workload 引起或同时发生的 thermal 升级、容量压力、GC、backpressure、
失败与取消是被测 outcome，必须保留为 VALID measured row（协议/采集本身无效除外），不能
用上述 qualification 规则删失。特别是达到 `SEVERE` 的采样必须进入 F090 numerator。

F088 的 `SampleTupleKeyV1` 必须在独立 measured window 运行所有预承诺 P2
`TURN_CHECKPOINT|TURN_EXPECTED_FAILURE|TURN_CANCEL` turn，不与其它 capability/sample
window 交叠；不因 durable/failed/cancel/indeterminate outcome 排除。numerator 是该 exact
window 中归属于 Memory filesystem scope 的全部 `FsDurabilitySyscallRegistryV1` kernel
attempt count，每个 observed kernel attempt恰计一次，不做 per-turn rational 分摊。schema
1.0 closed registry只有：

```text
FSYNC      = Linux fsync(2)
FDATASYNC  = Linux fdatasync(2)
```

计数点是同一 process incarnation/thread 的 kernel syscall entry与匹配 return；成功、失败与
`EINTR` attempt都各计一次，userspace retry产生另一个 attempt。entry时必须用 trace中的
process-incarnation、fd与 kernel file/inode lifetime把目标唯一绑定到 Memory volume内的
regular file或 directory；fd close/reuse后不能沿用旧映射，entry/return缺一、inode/volume
未知或 scope交叠使整 row INVALID。`FileChannel.force`、SQLite `xSync`、Java/native wrapper
本身不计；只有它们实际产生的上述 kernel syscall计一次，禁止 wrapper+kernel双计。

schema 1.0 measured path禁止 `sync(2)`、`syncfs(2)`、`msync(MS_SYNC)`、
`io_uring IORING_OP_FSYNC`、F2FS atomic-commit ioctl与任何 unknown durability barrier；
一旦 observed便 INVALID，而不是猜测它等价于几个 fsync。ABI/trace source/version、syscall
number mapping与 fd-attribution算法必须在结果前写入 manifest；target build无法完整观测
closed registry时 row为 UNSUPPORTED。

无法隔离 scope、有背景 Memory writer 或 attribution gap 时整个 row INVALID。denominator
是全部预承诺且 instrumentation VALID 的 P2 measured turn count，不是成功 turn。gate 比较
`fs_durability_syscall_attempt_count*1_000_000 <= F088*exact_turn_count`；零 denominator
为 MISSING。

F090 的 manifest 在结果前冻结 monotonic sampling cadence、phase offset、thermal API/source
与 exact scheduled sample ordinals。denominator 是所有 workload-era scheduled ordinals；
numerator 是其 thermal status `>= THERMAL_STATUS_SEVERE` 的 ordinals。attempt start 后状态
升级仍是 VALID numerator；任一 scheduled sample 缺失/重复、API token unknown、cadence
漂移超预承诺 tolerance 或 attribution 到冷却/warm-up phase 均使 evidence INVALID，不能把
缺样本当 non-severe。gate 以
`severe_count*1_000_000 <= F090*scheduled_sample_count` exact cross-product 判定；零
denominator 为 MISSING。

### 9.4 `BudgetAcceptancePolicyV1`：结果可见前冻结

calibration 不能把“观测值 + 临时决定的 headroom”直接变成会自动 PASS 的阈值。0018-E
必须冻结一个独立签名、版本化的 `BudgetAcceptancePolicyV1` exact wire；当前尚未冻结，
所以 `BudgetEvidenceWirePhaseGateV1` 继续 BLOCKED。policy 必须在 calibration proposal
签名、permit 签发和任何 sample 可见**之前**绑定：

```text
policy major/minor
measurement-contract digest
closed field set F010..F099
ProductSloEnvelopeV1 digest
90 个 FieldDerivationV1（按 field id 排序，恰好一项/field）
cross-field constraint-set digest
deterministic solver/version digest
owner/reviewer signatures
```

`ProductSloEnvelopeV1` 是独立产品/IME 安全边界，不从本次 calibration 数据导出。它为每个
适用字段给出不可协商的 max/min、支持设备/volume floor、普通输入 p95/RSS/掉帧 ceiling 与
舍入单位；未知或 UNSET 使相关 capability 保持 `MEASURED_NO_BUDGET`，不能删除边界来求
PASS。

每个 `FieldDerivationV1` 只能选择 closed mode：

- `FIXED_PRODUCT_BOUND`：直接采用预先签名的 envelope bound；
- `QUANTIZED_MAX_WITH_HEADROOM`：从预声明 tuple/statistic 以 checked rational
  `ceil(statistic*numerator/denominator)` 后按固定 quantum/rounding，再与 product maximum
  取更严格值；
- `QUANTIZED_MIN_WITH_HEADROOM`：对 MIN direction 使用预声明 checked rational、固定
  floor rounding，并与 product minimum 取更严格值；
- `DETERMINISTIC_CANDIDATE_SEARCH`：在结果可见前冻结有限有序 candidate list、objective、
  constraints 与唯一 tie-break；solver 必须从完整 calibration raw rows重算，不能人工挑
  queue/batch/cap。

每项还必须绑定 exact source tuples/metric/statistic、direction、headroom numerator/
denominator、rounding mode、quantum、clamp、overflow disposition 与 required cross-field
constraints；不允许自由表达式、脚本、人工 override、NaN 或“审查者酌情”。90 个 derivation
必须完整覆盖 F010–F099，且 deterministic verifier 从 calibration raw evidence 重算出的
full profile bytes/JCS digest必须与 proposal byte-equal。

calibration 后不得改变 acceptance policy、SLO envelope、candidate set、headroom 或
rounding 来让数据通过。任何 byte 改变都是 material subject change，必须丢弃原 proposal，
以新 policy 重新执行完整 calibration → profile proposal → 独立 confirmatory cycle；旧 rows
只能保留为 exploratory evidence。confirmatory 只判断预先推导出的 profile，不再调阈值。

---

## 10. Outcome ledger 语义（exact wire 尚未接受）

只统计成功延迟会制造 survivorship bias。每个进入 durability pipeline 的 request 在任一
materialized observation cut 必须满足 exact 守恒式：

```text
admitted_durable
= ack_durable
 + failed_proven_excluded
 + indeterminate
 + callback_lost
 + pending_observation_deadline
```

五个右侧 bucket 互斥且覆盖全部 admitted request。`callback_lost` 与
`pending_observation_deadline` 是显式 observation state，不要求为了“让报告好看”在 evidence
cut 前被强制改成 terminal；任一非零都会让相关 metric INCONCLUSIVE。append-only
reconciliation 不能删除旧状态，而是以 request ID、previous state digest 和新 evidence
把它闭合为 `ack_durable`、`failed_proven_excluded` 或 `still_indeterminate`。

本节只冻结状态集合、守恒式、判定与 required semantic dimensions。row framing、字段
presence/type/width、排序、容量、unknown-field policy、previous-state linkage bytes、
ledger digest 与签名 coverage 尚未冻结；当前不得把任意 Proto/JSON/CSV 称为 canonical
ledger，`BudgetEvidenceWirePhaseGateV1` 必须 BLOCKED。

### 10.1 `DurableAckOutcomeLedgerV1`

未来 `DurableAckOutcomeLedgerV1` exact wire 必须无歧义表达：

```text
evidence_run_id
request_id
process_epoch
writer_epoch
requested_sequence_or_cut
logical_bytes
start_elapsed_nanos
terminal_elapsed_nanos
ack_outcome_code = 0(NOT_OBSERVED) | 1(DURABLE) | 2(FAILED) | 3(INDETERMINATE)
reason_code
recovered_after_restart = true | false
callback_delivery = DELIVERED | LOST | NOT_EXPECTED
observation_bucket = ACK_DURABLE | FAILED_PROVEN_EXCLUDED | INDETERMINATE |
                     CALLBACK_LOST | PENDING_OBSERVATION_DEADLINE
reconciliation = NOT_NEEDED | FRONTIER_PROVED_DURABLE |
                 PROVED_FAILED_EXCLUDED | STILL_INDETERMINATE
```

- `ack_outcome_code=0` 只允许 observation bucket 为 CALLBACK_LOST/
  PENDING_OBSERVATION_DEADLINE；terminal time 使用 canonical zero 并由 bucket 判定，不把 0
  当 latency；
- `DURABLE`：可进入 latency distribution 和 logical durable byte 分母；
- `FAILED` 只有在 authenticated frontier/recovery census 证明该 request 不在 durable cut
  时才能进入 `failed_proven_excluded`；否则只能 INDETERMINATE；
- `INDETERMINATE`：单独计数，也进入 F044；不得自动以相同 identity 重试后隐藏；
- callback 丢失时进入 `callback_lost`，coordinator restart 后必须依据 ADR 0016 authenticated
  frontier、RecoveredSeal 与 full recovery census 做 reconciliation；不能引用未冻结的
  “Ack store”，也不能把“没回调”直接算 FAILED；
- evidence cut 中 pending/callback_lost 非零但守恒成立：ledger item 仍可为 VALID，但相关
  metric 必须 `INCONCLUSIVE/LEDGER_RECONCILIATION_PENDING`；
- missing/duplicate membership 或守恒式不成立：evidence validity=`INVALID`，
  metric=`INCONCLUSIVE`；
- durability protocol invariant violation（错误 cut 却报 DURABLE）是 FAIL，不由 F044 容忍。

F042/F043 的 statistic population恰为全部预承诺
`P2/TURN_CHECKPOINT/DURABILITY_SLO` measured ordinals中，守恒 ledger terminal bucket为
`ACK_DURABLE` 且具有 §9.1.1 exact start/terminal stamps的 rows。该 outcome predicate与全体
ordinal set是 contract固定规则，不是结果后 selector；其它
`FAILED_PROVEN_EXCLUDED/INDETERMINATE/CALLBACK_LOST/PENDING` rows仍必须是完整 VALID
bundle membership并经 `F042_F043_F044_DURABILITY_BUNDLE_V1` 进入 F044/coverage
projection，不能删除或标采集 INVALID。F042/F043至少需要
30 个 `ACK_DURABLE` numerical samples，否则
`INCONCLUSIVE/SAMPLE_COUNT_INSUFFICIENT`；`CALLBACK_LOST/PENDING` 未在 deadline内对账时
F042/F043与 F044都按既有规则 INCONCLUSIVE。只有 failed/indeterminate但已闭合、durable
count充足时，latency可对 durable subset计算，而可靠性由 F044独立裁决。

F044 的 exact numerator 是
`failed_proven_excluded + indeterminate + callback_lost + pending_observation_deadline`，
denominator 是 `admitted_durable`；denominator=0 时 evidence MISSING，不定义为 0 ppm。

### 10.2 `TransferOutcomeLedgerV1`

未来 `TransferOutcomeLedgerV1` exact wire 必须为 pipe/PFD/export/Provider egress 的每个
transfer 无歧义表达：

```text
PENDING | CALLBACK_LOST | COMPLETED | CANCELLED | FAILED | INDETERMINATE

admitted_transfer
= completed + cancelled + failed + indeterminate + callback_lost + pending
```

记录双方 fd close、digest/length verdict、cancel fence、owner process epoch、cleanup/restart
census，并满足上述 transfer 守恒与同类 append-only reconciliation。`COMPLETED` 仍需证明无 orphan；
`CANCELLED` 不是失败，但必须在 F061/F099 内 terminal。evidence cut 可以诚实保留 PENDING
或 CALLBACK_LOST，但相关 metric 必须 INCONCLUSIVE，不能丢弃 request。

### 10.3 `ErasureBarrierOutcomeLedgerV1`

未来 `ErasureBarrierOutcomeLedgerV1` exact wire 必须对 S8 每个 live process incarnation
无歧义表达：

```text
DISCOVERED -> FENCE_OBSERVED -> EGRESS_DRAINED -> ACKED
```

coordinator restart 后必须重新发现/handshake。空 ledger、缺进程或仅有旧 PID 不能变成 ACK。
ADR 0017 phase schema 未接受前，此 ledger 只能由合成 lab harness 产生，不能签发产品擦除
receipt。

---

## 11. Evidence bundle 与 attestation（0018-E Proposed / BLOCKED）

未来 `BudgetEvidenceBundleV1` 必须表达以下 semantic content：

- contract/profile/descriptor/security/build/APK/signer SHA-256；
- git commit、tree-clean bit、variant、applicationId；
- DeviceSuiteManifest 和 exact role/fingerprint/API/security patch；
- boot count、process epochs、volume/filesystem/capacity/free pre/post；
- battery/charging/thermal pre/post 与采样源；
- workload generator version、corpus digest、seed、scenario order；
- raw sample rows、invalid rows、三个 outcome ledgers；
- Perfetto/batterystats/fs counters 的 raw artifact digest；
- per-metric verdict、per-capability verdict、canonical reasons；
- evidence generator/verifier version；
- bundle digest 和实验室签名/provenance。

这份清单**不是 canonical schema**，也不授权实现自行选择 JSON/Proto/CSV。以下内容仍
全部未定义：exact descriptor、field numbers/names、required/optional presence、scalar
types/widths、row framing/order、array/map ordering、duplicate/unknown-field policy、每
字段与 bundle 的 size/count caps、raw artifact inclusion/reference rules、bundle digest
覆盖范围、signature algorithm、key identifier、trust root、revocation 与 provenance
verification。两份不同序列化不能仅因都包含上述信息就互认。

0018-E 必须以独立 phase ADR 冻结 descriptors、canonical bytes、golden vectors、round-trip/
negative/fuzz corpus 与 signature/trust policy，之后
`BudgetEvidenceWirePhaseGateV1` 才可能 PASS。当前任何 bundle/manifest/raw row/ledger
只能是非权威研究产物，不得进入 `BuildAttestationGateV1`、`BudgetProfileGateV1` 或
release planner。

`BuildAttestationGateV1` 只裁决 exact build/APK/applicationId/signer/harness/profiler
identity 与可复现 provenance；它不声称预算已通过，因而可在 sample 产生前为 measurement
harness PASS。未来 verifier 必须从 raw rows 重算统计与 verdict，不能信上传的 summary。
缺 raw evidence、bundle digest/signature 不符或 ledger 无法验证时，相关 evidence validity
为 INVALID/MISSING、`BudgetProfileGateV1=BLOCKED`；若 profiler 实际改变 product behavior
或 build provenance 不匹配，`BuildAttestationGateV1` 也 BLOCKED。

证据可记录 wall-clock 作为一次实验事件属性，但 verdict 只依赖 monotonic duration、build/
device identity 和 freshness policy；不得用文件 mtime 决定 generation 或恢复顺序。

---

## 12. Exact verdict model

枚举是 closed registry；实现不得添加 `WARN`、`SKIP`、`N/A` 或把 unknown integer 当 PASS。
bundle 顶层也不得另设 `overall_evidence_validity`；overall 只有 §12.3 一个 authority。

### 12.1 `EvidenceValidityV1`

恰好四值：

```text
VALID | INVALID | UNSUPPORTED | MISSING
```

| value | exact meaning |
|---|---|
| `VALID` | identity、attestation、环境、采样与 ledger 对该 evidence item 均可验证 |
| `INVALID` | item 存在，但违反 schema/attestation/environment/ledger 约束 |
| `UNSUPPORTED` | 已认证的设备/采集器明确不能产生该 metric；不能写成 0 |
| `MISSING` | required item、role、scenario、raw row 或 terminal reconciliation 不存在 |

profile/schema/attestation 是 bundle 顶层结构。它们任一 invalid 时由 §12.3 产生 overall
`INVALID`；单个 metric evidence 的非 VALID 状态则按下节产生 `INCONCLUSIVE`。协议 safety
cap 或运行时 invariant violation 是被测 capability 的确定失败，产生 metric `FAIL`，而不是
用 INVALID evidence 隐藏。

### 12.2 Per-metric mapping

`MetricDirectionV1` 是 closed registry：

```text
MAX | MIN | ENUM_MEMBER | INVARIANT_ZERO
```

profile field 的 evaluation class 与 observed statistic 固定如下，禁止由 lab 自选：

| class | exact field set | evaluation |
|---|---|---|
| profile structural/config | F010–F033, F035–F041, F045, F047–F048, F056–F059, F091–F092 | 非 sample metric；按 §3/§5 safety membership、checked formula、capacity 与 hysteresis 约束验证。值为 UNSET 时依赖它的 capability 是 MEASURED_NO_BUDGET；有值但结构约束失败时 profile INVALID |
| `ENUM_MEMBER` | F046 | exact member of ADR 0017 chunk-size set；UNSET 仍为 MEASURED_NO_BUDGET |
| `MIN` | F085 | 每个 required measured tuple 的 `min(logical_work_bytes)` 必须 `>= F085` |
| `MAX` | F034, F042–F044, F049–F055, F060–F084, F086–F090, F093–F099 | 使用下述 frozen statistic，与对应 field 比较 |

以上集合互斥并恰好覆盖 F010–F099；schema 1.0 没有其它 profile direction。MAX field 的
observed statistic 固定为：

- field name 含 `_p50_`、`_p95_`、`_p99_` 时，分别使用 §9.1 nearest-rank p50/p95/p99；
- 含 `_max_`、`_peak_`、`_high_water_` 时使用全部且仅全部 precommitted measured
  attempts 的 maximum，且其完整集合必须已验证为 VALID；
- F044、F087、F088、F090 的报告值使用各节 exact numerator/denominator 对全部 VALID
  precommitted measured attempts聚合后 floor；这里的“VALID”是对完整 exact set的先决
  验证，不是过滤器。gate 判定必须 checked 比较
  `numerator*1_000_000 <= threshold_ppm*denominator`；
- F078 报告值使用
  `floor(total_journal_thread_cpu_ns * 1024 / total_journal_canonical_payload_bytes)`；gate
  比较
  `total_journal_thread_cpu_ns*1024 <= F078*total_journal_canonical_payload_bytes`；
- F082 报告值使用 `floor(total_attributed_journal_energy_uj / durable_turn_count)`，F083
  使用 `floor(total_attributed_recall_energy_uj / completed_query_count)`，F084 使用
  `floor(total_attributed_maintenance_energy_uj * 1048576 /
  maintained_logical_bytes)`；gate 分别用未除法的 exact cross-products
  `energy <= threshold*count` 与
  `maintenance_energy*1048576 <= F084*maintained_logical_bytes`；
- F086 是每个 workload iteration 的 physical-written-bytes maximum；F089 是每个 completed
  recall query 的 storage-read-bytes maximum；
- F094 使用
  `ceil(total_published_derived_index_bytes / exact_live_indexed_record_count)`；
- 未被上述命名/explicit rule 命中的 MAX field 在 schema 1.0 是
  `PROFILE_SCHEMA_INVALID`，不得默认为 mean。

上述所有 division denominator 为 0 时 evidence=MISSING、metric=INCONCLUSIVE，永远不定义为
0 或尝试除零。ratio/mean MAX verdict 不使用 floor 后的显示值，而使用 exact checked
cross-product；等号 PASS、任意正余数导致真实比率越界时 FAIL。任一侧 checked
multiply/add overflow 使 evidence item INVALID，不能以 saturating arithmetic 或先除法
继续。

`INVARIANT_ZERO` 不对应可填写 profile field，固定用于 closed invariant metric set：
`WIRE_CAP`、`WIRE_INVARIANT`、`ACK_CONSERVATION`、`TRANSFER_CONSERVATION`、
`ERASURE_BARRIER`、`PLAINTEXT_LEAK`、`DUPLICATE_REASON`、
`WORKLOAD_COVERAGE`。其 violation count 来自未来
accepted evidence wire；当前 wire gate BLOCKED。

`MetricGateVerdictV1` 恰好四值：

```text
PASS | FAIL | INCONCLUSIVE | MEASURED_NO_BUDGET
```

| condition | metric verdict |
|---|---|
| protocol safety cap / runtime invariant violation | FAIL |
| evidence validity != VALID | INCONCLUSIVE |
| evidence VALID 但 required outcome ledger 仍有 callback_lost/pending ambiguity | INCONCLUSIVE |
| evidence VALID 且 required profile field=`UNSET` | MEASURED_NO_BUDGET |
| MAX 且 observed upper statistic `<= threshold` | PASS |
| MAX 且 observed upper statistic `> threshold` | FAIL |
| MIN 且 observed lower statistic `>= threshold` | PASS |
| MIN 且 observed lower statistic `< threshold` | FAIL |
| ENUM_MEMBER 且 exact member allowed | PASS，否则 FAIL |
| INVARIANT_ZERO 且 violation count=0 | PASS，否则 FAIL |

边界相等为 PASS。不得用 confidence interval 穿过 threshold 来把确定失败改成
INCONCLUSIVE；CI 作为不确定性附加信息。产品 gate 使用冻结的 observed statistic（例如
p95 字段就比较 nearest-rank p95）。required matrix 不支持 N/A；不能测就是
UNSUPPORTED→INCONCLUSIVE。

ratio/mean boundary golden 必须覆盖：exact equality PASS；仅多一个不可整除 numerator
unit 时 FAIL；denominator=0 为 INCONCLUSIVE；左右任一 cross-product overflow 为 INVALID。
例如 `1/3` 对 `333333 ppm` 必须 FAIL，因为
`1*1,000,000 > 333333*3`，即使展示值 floor 后等于 333333。

### 12.3 Overall lattice

`OverallVerdictV1` 恰好五值：

```text
PASS | FAIL | INVALID | INCONCLUSIVE | MEASURED_NO_BUDGET
```

对 capability 的 required metric set，按以下唯一顺序：

```text
if any metric FAIL
  -> FAIL
else if top-level profile/schema/attestation INVALID
  -> INVALID
else if any metric INCONCLUSIVE
  -> INCONCLUSIVE
else if any metric MEASURED_NO_BUDGET
  -> MEASURED_NO_BUDGET
else if every required metric PASS
  -> PASS
else
  -> INCONCLUSIVE
```

deterministic priority 固定为：

```text
FAIL > INVALID > INCONCLUSIVE > MEASURED_NO_BUDGET > PASS
```

这不是“严重程度文案”，而是 reducer precedence。例如同一 bundle 既有 cap violation 又有
坏签名，结果固定为 FAIL；坏签名且没有确定 FAIL 时为 INVALID。不得另加 top-level
`overall_evidence_validity` 与此 lattice 竞争。

stage derivation：

| overall | 最大影响 |
|---|---|
| PASS | 仍受 dependency DAG 的最低 stage 限制 |
| FAIL | capability=BLOCKED；修复实现/profile 后重新全测 |
| INVALID | capability=BLOCKED；修复 profile/schema/attestation 后重新验证 |
| INCONCLUSIVE | capability=BLOCKED；补齐/修复 evidence |
| MEASURED_NO_BUDGET | capability=BLOCKED；可用于提出 0018-B，但不得晋级 |

### 12.4 Canonical reason ordering

reason code 是 closed `BudgetReasonCodeV1` registry：

| code | symbol | associated precedence |
|---:|---|---|
| 1 | `PROTOCOL_CAP_VIOLATION` | FAIL |
| 2 | `THRESHOLD_MAX_EXCEEDED` | FAIL |
| 3 | `THRESHOLD_MIN_UNDERSHOT` | FAIL |
| 4 | `INVARIANT_NONZERO` | FAIL |
| 10 | `PROFILE_SCHEMA_INVALID` | INVALID |
| 11 | `BUNDLE_SCHEMA_INVALID` | INVALID |
| 12 | `ATTESTATION_INVALID` | INVALID |
| 13 | `IDENTITY_DIGEST_MISMATCH` | INVALID |
| 20 | `EVIDENCE_INVALID` | INCONCLUSIVE |
| 21 | `EVIDENCE_UNSUPPORTED` | INCONCLUSIVE |
| 22 | `EVIDENCE_MISSING` | INCONCLUSIVE |
| 23 | `SAMPLE_COUNT_INSUFFICIENT` | INCONCLUSIVE |
| 24 | `LEDGER_RECONCILIATION_PENDING` | INCONCLUSIVE |
| 25 | `PREREQUISITE_BLOCKED` | INCONCLUSIVE |
| 30 | `REQUIRED_BUDGET_UNSET` | MEASURED_NO_BUDGET |

不得保存任意 `reason_code_ascii`。reason dimension 的 closed grammar：

- `capability_id`：§13 表中的 14 个 exact ASCII token；
- `profile_field_id`：`F001..F099`，无对应 field 时为 sentinel `-`；
- `device_role_id`：`PIXEL_REFERENCE|HYPEROS|MIDRANGE|LOW_RAM`，无时为 `-`；
- `scenario_id`：`S0..S8`，无时为 `-`；
- `workload_id`：`W0..W8`，无时为 `-`；
- `writer_scope`：§13 的 closed `WriterScopeKindV1` token，无时为 `-`；
- `profile_context_digest`：32-byte SHA-256；无 context 维度时为 32-byte zero sentinel，真实
  context digest 禁止全零；
- `case_kind`：§13.1 的 closed `CaseKindV1` token，无 case 维度时为 `-`；
- `case_plan_digest`：sampled reason 为 32-byte SHA-256；无 case 维度时为 32-byte zero
  sentinel，真实 plan digest 禁止全零；invariant aggregate reason 的 `case_kind="-"` 但
  此字段必须是 nonzero `aggregate_case_set_digest`；
- `cohort`：§13.1 的 closed cohort token，无 cohort 维度时为 `-`；
- `metric_id`：`PROFILE:F001..PROFILE:F099`，或 closed invariant set
  `{WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,TRANSFER_CONSERVATION,
  ERASURE_BARRIER,PLAINTEXT_LEAK,DUPLICATE_REASON,WORKLOAD_COVERAGE,
  ATTESTATION,SCHEMA,IDENTITY,
  PREREQUISITE,SAMPLE_COUNT}`；
- `sample_ordinal`：u32，实际 sample 从 1 开始，0 表示无 sample 维度。

`lp16(x) = u16be(ASCII_byte_length(x)) || ASCII_bytes(x)`。每个 reason instance 的 exact
canonical bytes：

```text
u16be(reason_code) ||
lp16(capability_id) ||
lp16(profile_field_id) ||
lp16(device_role_id) ||
lp16(writer_scope) ||
profile_context_digest[32] ||
lp16(scenario_id) ||
lp16(workload_id) ||
lp16(case_kind) ||
case_plan_digest[32] ||
lp16(cohort) ||
lp16(metric_id) ||
u32be(sample_ordinal)
```

数组按 exact canonical bytes 做 unsigned bytewise lexicographic 升序；先拒绝重复 bytes，
再验证排序。重复、未知 token/code、非法 sentinel 或未排序输入使 bundle schema INVALID。
context/writer/case/plan/cohort 均参与 key，故两个 required context 或 case 的同一 ordinal
失败可以同时无歧义表达，不会因 duplicate-byte rule 丢一项。verdict digest只包含排序后的
canonical reason bytes；本地化 human message 不进入 digest。这样不同 runner/locale 不会
因 map iteration 或翻译产生不同 overall evidence。

---

## 13. Capability evidence matrix

本节是 closed semantic tuple registry；profile/phase ADR 若要增加或删除 tuple，必须提升
contract/profile schema minor/major，不能由 lab 临时解释范围。token：

```text
ledger mask:
  D=DURABLE_ACK | T=TRANSFER | E=ERASURE_BARRIER |
  O=OPERATION_KILL | B=BLOB_LEASE | P=PUBLISH | -=NONE
```

closed context aliases。三位依次为 `USES_BLOB/CROSS_WRITER_SOURCE/
USES_DERIVED_INDEX`：

```text
C000 = USES_BLOB=false, CROSS_WRITER_SOURCE=false, USES_DERIVED_INDEX=false
C100 = USES_BLOB=true,  CROSS_WRITER_SOURCE=false, USES_DERIVED_INDEX=false
C010 = USES_BLOB=false, CROSS_WRITER_SOURCE=true,  USES_DERIVED_INDEX=false
C110 = USES_BLOB=true,  CROSS_WRITER_SOURCE=true,  USES_DERIVED_INDEX=false
C001 = USES_BLOB=false, CROSS_WRITER_SOURCE=false, USES_DERIVED_INDEX=true
C101 = USES_BLOB=true,  CROSS_WRITER_SOURCE=false, USES_DERIVED_INDEX=true
C011 = USES_BLOB=false, CROSS_WRITER_SOURCE=true,  USES_DERIVED_INDEX=true
C111 = USES_BLOB=true,  CROSS_WRITER_SOURCE=true,  USES_DERIVED_INDEX=true
```

每个 proposal 的 required `K(C)` 恰为：

```text
SCHEMA_CODEC={C000}
KEYRING_BOOTSTRAP={C000}
JOURNAL_WRITE={C000}
BLOB_STORE={C100}
CAPTURE={C000,C100,C010,C110}
WARM_RECALL={C000,C100,C010,C110,C001,C101,C011,C111}
COLD_RECALL={C000,C100,C010,C110,C001,C101,C011,C111}
HOT_SNAPSHOT={C010,C110}
INDEX_REBUILD={C011,C111}
MAINTENANCE={C010,C110,C011,C111}
KEY_ROTATION={C010,C110,C011,C111}
LOCAL_ERASURE={C010,C110,C011,C111}
EXPORT_EGRESS={C010,C110,C011,C111}
```

`USES_DERIVED_INDEX` 是 resource-closure 维度，不是“当前索引实例健康”的同义词。显式
session/cut 已知时，WARM/COLD 必须能以 `USES_DERIVED_INDEX=false` 直接读取 authenticated
Journal/Blob；当前 index 缺失、损坏或 STALE 只禁止 index discovery，不得反向阻断这条
精确回忆旁路。任何需要由 index 找到候选、读取 index row/cursor 或发布/退休 index 的
operation 才派生 `USES_DERIVED_INDEX=true`，并必须持有当前 index instance receipt。

`WriterScopeKindV1 = NONE|IME|BRAIN|MAIN` 不是 caller、executor、publisher 或 victim
process；它只表示本 operation 用来解释 source closure 的 authenticated logical Journal
destination/current-writer scope。`BROKER` 不能成为 writer scope，因为 ADR 0016
`WriterKindV1` 没有 Broker writer；Broker 只是 component/executor。没有 logical current
writer 的 control operation 使用 `NONE`。required writer-scope set `W(C)` 恰为：

```text
SCHEMA_CODEC={NONE}
KEYRING_BOOTSTRAP={NONE}
JOURNAL_WRITE={IME,BRAIN,MAIN}
BLOB_STORE={IME,BRAIN,MAIN}
CAPTURE={IME,BRAIN,MAIN}
WARM_RECALL={BRAIN,MAIN}
COLD_RECALL={BRAIN,MAIN}
HOT_SNAPSHOT={NONE}
INDEX_REBUILD={NONE}
MAINTENANCE={NONE}
KEY_ROTATION={NONE}
LOCAL_ERASURE={NONE}
EXPORT_EGRESS={NONE}
```

`OperationActorKindV1 = HARNESS|IME|BRAIN|MAIN|BROKER` 是另一 closed domain。每个
`case_plan_digest` 必须绑定 exact ordered actor/victim/executor set；例如 Blob/keyring
physical work 的 executor 是 Broker，planned kill 的 controller 是 HARNESS，但这绝不改变
writer scope。actor 与 writer scope 的 ordinal/cast/默认互转一律 schema INVALID。

`SourceWriterShapeV1` 是 finite 4-bit mask：bit0=IME、bit1=BRAIN、bit2=MAIN、
bit3=IMPORTED_EXTERNAL，canonical token恰为 `S0..SF`（uppercase hex single digit）。
它只表达 source kinds/fan-in，不含具体 ID/epoch。required shape set `H(C,flags,writer)` 由
closed rule生成：

```text
SCHEMA_CODEC|KEYRING_BOOTSTRAP|JOURNAL_WRITE|BLOB_STORE -> {S0}
CAPTURE and CROSS_WRITER_SOURCE=false -> {S0, bit(writer)}
other source-consuming capability and CROSS_WRITER_SOURCE=false -> {bit(writer)}
CROSS_WRITER_SOURCE=true, writer=NONE ->
  every mask with local bits != 0 and (optionally) EXTERNAL bit
CROSS_WRITER_SOURCE=true, writer in IME|BRAIN|MAIN ->
  every mask with local bits != 0 and
  (EXTERNAL bit set OR local popcount>1 OR local bits contain a writer != writer)
```

mask顺序按 numeric `S0..SF`。`bit(NONE)`非法；persistent source consumer不接受
external-only `S8`，external provenance必须最终绑定至少一个 authenticated local writer
record。该规则让 single foreign、two/three-writer fan-in和 imported provenance都有固定
profile evidence，不把它们压成同一个性能样本。

顺序按表书写。每个 advertised capability 必须对
`K(C) × W(C) × H(C,flags,writer)` 的每个 **concrete** profile context产生完整
`P/T/I(C,profile_ctx)` verdict；只测 inline/single-writer、另测 BLOB_STORE 再推断组合，不算
CAPTURE+C100/C110 PASS。required context cardinality 是
`sum(concrete C, flags in K(C), writer in W(C), |H(C,flags,writer)|)`，context 缺失或重复一律
BLOCKED。增加新
context、改变 derivation/closure 或减少 required context 都是 material contract/subject
change，必须新 profile schema 与完整 calibration/confirmatory。

`SHADOW_PLUS` 没有自己的 K/W/H Cartesian profile context，也不能制造
`NONE/C000/S0` 等没有 concrete child支持的组合。它只由
`ShadowPlusAggregateDecisionV1` 消费**所有 concrete capability 的全部 finite profile
decisions/receipts ordered set**，对 stage取 deterministic minimum、对 evidence/prerequisite
取 ordered union；缺任一 child context即 BLOCKED。

两层 context 严格分离：

```text
CHILD_CONTEXT_PROJECTION_RULE_V1 =
  exact ASCII "ADR0018_CHILD_CONTEXT_PROJECTION_V1"

ProfileContextDigestV1 =
  SHA256(ASCII "sense.profile.context.v1" || 0x00 ||
         lp16(capability) || lp16(C flags) || lp16(WriterScopeKindV1) ||
         lp16(SourceWriterShapeV1) || lp16(CHILD_CONTEXT_PROJECTION_RULE_V1))

OperationContextDigestV1 =
  SHA256(ASCII "sense.operation.context.v1" || 0x00 ||
         profile_context_digest ||
         length-delimited exact destination writer ID/epoch or canonical NONE ||
         u32be(source_count) || sorted length-delimited exact source writer IDs/epochs ||
         source-authority/cut/resource digests)
```

`lp16` 取 §12 的 exact definition。projection token在 schema 1.0 恰为上述唯一值；unknown、
另一大小写或调用者自报 version都 INVALID。任何 projection rule改变必须提升 profile
schema并完整重跑，不能只换 token。operation source entry exact canonical wire仍归
0018-E冻结，在此前 OperationContext-dependent runtime gate BLOCKED；不得用裸字符串拼接
hash。

Budget/evidence/reason/StageSnapshot只绑定 finite `ProfileContextDigestV1`。每次 runtime
operation从可信 payload/source closure构造 `OperationContextDigestV1`，机械 derive flags、
writer kind和 source mask，并证明它是 accepted profile context 的合法 instance；具体
writer epoch变化不要求重新 calibration，但 wrong kind/mask、opaque/漏 source、stale
authority/cut或 concrete scope expansion fail closed。one-shot grants、child effect token、
erasure binding和动态 operation receipt必须绑定 operation digest，不能只拿 profile digest。
`NONE` 必须证明没有 logical destination writer且无 concrete destination ID；unknown writer、
把 actor/Broker当 writer或 token/role mismatch fail closed。

对一个 0018-B `BudgetProfileSetV1` proposal，suite 语义必须恰好指定四个 verdict device
entries（每个 F007 role/profile 一个）和四个互异 primary run IDs；额外探索设备必须进入
另一 evidence run，不能混进这四个 entry 选择有利结果。

conditional evidence closure 使用唯一 deterministic ordered
`projectChildContexts(parent_ctx,child)`：

```text
flag projection:
  child=JOURNAL_WRITE -> C000
  child=BLOB_STORE    -> C100
  otherwise          -> preserve parent normalized flags

shape projection:
  child=JOURNAL_WRITE|BLOB_STORE -> S0
  otherwise -> preserve parent SourceWriterShapeV1

edge access:
  DESTINATION_WRITE -> parent authenticated destination/current writer
  SOURCE_READ       -> parent SourceWriterShapeV1 local bits
  SOURCE_REWRITE    -> parent SourceWriterShapeV1 local bits

writer projection:
  W(child)={NONE} -> [NONE]
  DESTINATION_WRITE and destination member of W(child) -> [destination]
  SOURCE_READ|SOURCE_REWRITE -> exact profile source-shape local members in W(child),
                                preserving IME,BRAIN,MAIN registry order
  otherwise, empty/unknown/uncovered source member -> INVALID
```

cross-writer/source conditional gate仍由 parent profile context 导入，不能因为 Journal/Blob internal
child 规范化而消失。投影结果必须属于 `K(child)`，否则 contract/schema INVALID。
投影后的 `(flags,writer,shape)` 必须同时属于 child 的 `K/W/H` registry；parent shape仍驱动
source edge writer fan-out并保留在 parent/operation digest，但 internal storage leaf用 S0
表示“leaf本身不重新分类 source closure”。
edge access由 `ChildEffectKindV1` mechanical derivation：APPEND/PUBLISH 是
`DESTINATION_WRITE`，READ/SCAN 是 `SOURCE_READ`，REWRITE/RETIRE 是
`SOURCE_REWRITE`；不能由 caller选择。evidence tuple/`CapabilityChildDecisionV1` 同时绑定
parent context digest、每个 projected child context digest 与
projection-rule/edge-access/source-shape digest；operation child decision还绑定 concrete
source set与 `OperationContextDigestV1`。禁止拿 BLOB_STORE+C100 的通过结果直接替代整个
CAPTURE+C110，或让 child 继承一个其 K registry 不支持的 C110。八个 parent context 与
Journal/Blob projection、HOT/INDEX/MAINTENANCE/EXPORT 的 exact source-set expansion、漏/
多一个 writer、错误顺序/错误 NONE、wrong projection/cardinality 都必须有 golden/property
tests。特别覆盖“BRAIN 发起 recall，但 source set={IME}”：Blob/Journal child必须投影 IME，
把 BRAIN 当 source writer必须失败。required cardinality按投影后的 ordered set逐项
union/dedup。

每个 capability `C` 与 validated finite `ProfileCapabilityContextV1 profile_ctx`：

1. `P_base(C)`、`T_base(C)`、`I_base(C)` 是下表 exact set；
2. `closure(C,profile_ctx)` 按 §14.3 conditional DAG 与上述 context projection 求值；effective
   evidence requirement 是 base 与每个 conditional child branch 在 projected context 的
   `P/T/I` deterministic ordered union；
   inline/single-writer CAPTURE 不继承 BLOB_STORE/source branch，任何 transitive Blob
   operation 则必须完整继承 BLOB_STORE，不能填零 row；
3. 对每个可分配、替换、增长或 staging的 applicable effect，还必须 deterministic union
   下述 `StorageCapacityFieldClosureV1`；字段为 UNSET/INVALID时该 allocating branch不能
   PASS，禁止依赖另一个 capability“顺便测过”；
4. 下文简写 `P(C,profile_ctx)`、`T(C,profile_ctx)`、`I(C,profile_ctx)` 均指该 union，evidence tuple 必须携带
   trusted `profile_context_digest`；
5. `R(C,profile_ctx)=P(C,profile_ctx) ∩ {F001..F009}`，每 profile 各验证一次 fixed/method/build reference；
6. `M(C,profile_ctx)=P(C,profile_ctx) ∩` §12.2 的 `MAX|MIN` field set；
7. `Q(C,profile_ctx)=P(C,profile_ctx) ∩` §12.2 的 structural/config 或 `ENUM_MEMBER` field set；
8. §13.1 冻结每个 sampled field 的 ordered applicable pair/case-kind/cohort registry
   `U(F)`。对 `T(C,profile_ctx)` 中每个 pair，先求
   `effective_cases(C,profile_ctx,F,pair) =
   pair_allowed(pair) ∩ L(C,profile_ctx) ∩ U(F).case_kinds`，其中 `L(C,profile_ctx)` 是 capability base
   与全部 conditional child 的 ordered union；再从结果可见前签名的 suite manifest
   读取该 `(F,pair,case_kind)` 唯一 `case_plan_digest`。于是
   `A(C,ctx,F)` 是按 pair、`CaseKindV1` registry 顺序排列的
   `(S,W,case_kind,case_plan_digest)` 集合。empty、unknown/重复 kind、同一 key 多个
   plan digest、plan 中零 ordinal 或 digest/ordinal commitment 不匹配均使 bundle
   schema INVALID；这不是 runtime N/A 或 lab 自选；
9. 每个 verdict device 的 required sampled tuple set 恰为
   `{(C,profile_context_digest,S,W,case_kind,case_plan_digest,cohort,F,direction(F),
   statistic(F),ledger_mask(C,ctx)) |
   F in M(C,ctx), (S,W,case_kind,case_plan_digest) in A(C,ctx,F)}`；
   `A(C,ctx,F)=empty` 表示该 field 对这个
   capability/context 在 schema
   中明确不适用，不产生零值/MISSING tuple；
10. 每个 `(C,profile_context_digest,S,W)` 还必须产生一个聚合该 pair 全部预承诺 case ordinal 的
   `I_effective(C,profile_ctx)=I(C,profile_ctx) ∪ {WORKLOAD_COVERAGE}` 中每个 `INVARIANT_ZERO`
   `InvariantTupleKeyV1`。该 key 使用 `case_kind="-"`、`cohort=FAULT_INVARIANT`，并以
   nonzero `aggregate_case_set_digest` 绑定 pair 中按 registry 顺序排列的全部
   `(case_kind,case_plan_digest,ordinal commitments)`；它不是 sampled
   `case_plan_digest`，也不能用 zero sentinel；
   invariant row 不得以未携带 `case_kind` 为由漏掉某个 case；逐 case 的 commitment 与
   closed terminal 由 `WORKLOAD_COVERAGE` 重算并证明完整性；
11. required sampled tuple cardinality 恰为
   `sum(F in M(C,profile_ctx), |A(C,profile_ctx,F)|) + |T(C,profile_ctx)|*|I_effective(C,profile_ctx)|` per verdict device；
   `Q(C,ctx)` 每个 field 每 profile 只验证一次，不乘 scenario/workload，`R(C,ctx)` 同样只验证
   一次；
12. concrete rows的 `SHADOW_PLUS` ordered aggregate中，每个 sampled F010–F099 field必须
    至少有一个 applicable pair；aggregate不新增自己的 profile/sample tuple；
    duplicate tuple、未登记 pair/cohort、把 empty 当 0 或漏掉 required tuple均使 bundle
    schema INVALID。

range 是闭区间、逗号是集合 union、顺序按表中书写顺序；禁止把范围当 wildcard。未来
evidence wire 必须逐 tuple 编码，当前 wire gate BLOCKED。

`StorageCapacityFieldClosureV1` 的 field-token expansion是 closed、机械且纳入 exact
`P(C,profile_ctx)` digest：

```text
COMMON_EXISTING_PHYSICAL = {F010,F015,F016,F017,F018,F019,F020,F021,F022,
                            F023,F024,F025,F026}

IN_PLACE_STEADY_GROWTH(target components)
  = COMMON_EXISTING_PHYSICAL + exact target component fields

CREATE_PERSISTENT(target components, producer subcaps)
  = COMMON_EXISTING_PHYSICAL + exact target component fields + exact producer subcaps

REPLACE_PERSISTENT(source/destination components, producer subcaps)
  = COMMON_EXISTING_PHYSICAL + exact source/destination component fields
    + exact producer subcaps

EPHEMERAL_EXPORT
  = COMMON_EXISTING_PHYSICAL + {F027,F062}

DELETE_ONLY
  = {F018,F033}
```

component expansion固定为 `JOURNAL→F011`、`BLOB→F012`、`INDEX→F013`、
`MANIFEST→F014`。producer subcaps固定为 Blob `{F029,F051,F052}`、HotSnapshot
`{F091,F092}`、export `{F027,F062}`；没有开放 extension map。

effect到 mode/component的唯一 expansion为：

```text
JOURNAL_APPEND          -> IN_PLACE_STEADY_GROWTH(JOURNAL)
JOURNAL_REWRITE         -> REPLACE_PERSISTENT(JOURNAL)
BLOB_PUBLISH            -> CREATE_PERSISTENT(BLOB+MANIFEST,{F029,F051,F052})
BLOB_REWRITE_PHYSICAL   -> REPLACE_PERSISTENT(BLOB,{F029,F051,F052})
                           + IN_PLACE_STEADY_GROWTH(MANIFEST)
                           + DELETE_ONLY(old physical)
BLOB_RETIRE             -> DELETE_ONLY
INDEX_PUBLISH           -> CREATE or REPLACE_PERSISTENT(INDEX), fixed by predecessor intent
INDEX_RETIRE            -> DELETE_ONLY
PROJECTION_STORE_BOOTSTRAP
                        -> IN_PLACE_STEADY_GROWTH(JOURNAL)
                         + CREATE or REPLACE_PERSISTENT(INDEX), fixed before Room open
                         + IN_PLACE_STEADY_GROWTH(MANIFEST);
                           pre-open charge is approved WAL+rollback+SHM+migration union
HOT_SNAPSHOT_PUBLISH    -> CREATE or REPLACE_PERSISTENT(INDEX,{F091,F092}),
                           fixed before allocation by optional eviction_victim,
                           never by lineage_predecessor alone
HOT_SNAPSHOT_RETIRE     -> DELETE_ONLY
EXPORT_STAGING_WRITE    -> EPHEMERAL_EXPORT
EGRESS_DRAIN            -> DELETE_ONLY
```

`KEYRING_BOOTSTRAP` normal branch额外 union
`CONTROL_PLANE_ADOPTION + CREATE_PERSISTENT(JOURNAL+MANIFEST)`；candidate evidence/primary
branch只校验 signed external envelope、pre-allocation free-space invariant、kill/census与
mandatory wipe，不要求尚为 UNSET的 product Q fields先 PASS，所得 metric仍可如实为
`MEASURED_NO_BUDGET`。`KEY_ROTATION` 额外 union
`IN_PLACE_STEADY_GROWTH(MANIFEST)
+ CREATE_PERSISTENT(JOURNAL+MANIFEST)
+ REPLACE_PERSISTENT(applicable distinct data components)
+ DELETE_ONLY`；`LOCAL_ERASURE` evidence按 context union
`REPLACE_PERSISTENT(JOURNAL+MANIFEST [+BLOB] [+INDEX]) + DELETE_ONLY`。实际
`ErasureControlDecisionV1` 的 delete-only分支只消费 `DELETE_ONLY` closure，不被 replacement
字段或普通 headroom阻断。`QUARANTINE_RECLASSIFY` 是 recovery accounting action而非 allocation
admission：无论字段是否有 headroom都必须执行 §5.2 reclassification，然后阻断新写。

candidate/owner-shell 路径使用另一个 sealed、不可转换的
`ExternalBootstrapCapacityClosureV1`，不伪造 product field：

```text
signed external hard-cap envelope
+ exact candidate/owner-shell file, directory, lock and alias charge vector
+ current available-space observation
+ fixed minimum-free and recovery/cleanup reserve from the permit
+ active/steady/orphan branch maximum
+ authenticated cleanup plan and terminal zero-census
```

`AUTHORITY_BOOTSTRAP_ROOT_SHELL`、`KEYRING_BOOTSTRAP_EVIDENCE_STAGING` 与
`KEYRING_BOOTSTRAP_PRIMARY_STAGING` 在第一项 mkdir/alias/I/O 前必须得到
`ExternalBootstrapCapacityDecisionV1(ALLOW)`；缺 charge、overflow、available-space不足、
cleanup envelope不足或 unknown terminal branch均为零写/BLOCKED。该 decision及其 bytes不
进入 F010–F022，也不能转换为 `StorageCapacityFieldClosureV1`。normal owner shell只有在
`BudgetProfileGateV1=PASS` 后通过一次 `CONTROL_PLANE_ADOPTION` 把 exact census vector记入
F010/F014；normal Keyring staging自此只使用 product closure。

若一个 effect有 create/replace两种合法 intent，evidence plan必须在结果前分别承诺两类 case；
只测 create不能证明 replacement。unknown effect/mode/component、漏 field、额外 caller自报
field或 closure digest不一致都使 descriptor INVALID。

| capability | exact `T_base(C)` ordered pairs | exact `P_base(C)` | exact `I_base(C)` | ledger mask | 特别条件 |
|---|---|---|---|---|---|
| `SCHEMA_CODEC` | empty（host golden/fuzz only） | F001–F005 | empty | - | 仅可到 SCHEMA_ONLY；不产生 device metric tuple |
| `KEYRING_BOOTSTRAP` | S0:W0, S7:W6 | F074 | WIRE_CAP,WIRE_INVARIANT,PLAINTEXT_LEAK | O | storage closure增加 F010/F011/F014–F026；API 29–36.1 key safety evidence；只计 Broker bootstrap RSS，不借用 IME/Brain/capture heap |
| `JOURNAL_WRITE` | S1:W1, S2:W2, S6:W5, S7:W6, S8:W7, S8:W8 | F010–F044,F073–F090,F096 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,PLAINTEXT_LEAK | D,O | S8 两个 tuple 都 required |
| `BLOB_STORE` | S3:W3, S6:W5, S7:W6, S8:W7, S8:W8 | F010–F029,F045–F055,F073–F090,F096–F099 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,TRANSFER_CONSERVATION,PLAINTEXT_LEAK | D,T,O,B | locator/old physical census |
| `CAPTURE` | S1:W1, S2:W2, S6:W5, S7:W6, S8:W7, S8:W8 | F010–F044,F073–F090,F096–F099 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,ERASURE_BARRIER,PLAINTEXT_LEAK | D,E,O | inline base；transitive Blob context deterministic union 完整 BLOB_STORE |
| `WARM_RECALL` | S4:W4, S6:W5, S7:W6 | F057–F074,F077,F079,F081,F083,F089,F090 | WIRE_CAP,WIRE_INVARIANT,TRANSFER_CONSERVATION,PLAINTEXT_LEAK | T | fixed-cut/cancel；含 IME coordinator RSS/thermal |
| `COLD_RECALL` | S5:W4, S6:W5, S7:W6 | F057–F074,F077,F079,F081,F083,F089,F090 | WIRE_CAP,WIRE_INVARIANT,TRANSFER_CONSERVATION,PLAINTEXT_LEAK | T | process 真正未运行；含 IME coordinator RSS/thermal |
| `HOT_SNAPSHOT` | S4:W4, S5:W4, S7:W6, S8:W7 | F073,F074,F081,F084,F086,F090–F096 | WIRE_CAP,WIRE_INVARIANT,TRANSFER_CONSERVATION,PLAINTEXT_LEAK | T,P | storage closure增加 F010/F013/F015–F026；不含原文；pre-map cap |
| `INDEX_REBUILD` | S4:W4, S6:W5, S7:W6, S8:W7 | F010–F029,F073–F096 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,PLAINTEXT_LEAK | D,O | Journal 是 authority |
| `MAINTENANCE` | S6:W5, S7:W6, S8:W7, S8:W8 | F010–F029,F051–F055,F073–F099 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,TRANSFER_CONSERVATION,ERASURE_BARRIER,PLAINTEXT_LEAK | D,T,E,O,B,P | peak 与 tombstone gate PASS |
| `KEY_ROTATION` | S7:W6, S8:W8 | F055,F073–F090,F097,F099 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,ERASURE_BARRIER,PLAINTEXT_LEAK | D,E,O,B | storage closure按 context覆盖 F010及所有被重写 component；rotation phase PASS |
| `LOCAL_ERASURE` | S6:W5, S7:W6, S8:W8 | F055,F061–F063,F073–F090,F098,F099 | WIRE_CAP,WIRE_INVARIANT,ACK_CONSERVATION,TRANSFER_CONSERVATION,ERASURE_BARRIER,PLAINTEXT_LEAK | D,T,E,O,B | storage closure分离 replacement/delete-only；其 duplicate由 F019 generic producer peak证明，不冒充 F096 compaction；cumulative request/reboot/tombstone census |
| `EXPORT_EGRESS` | S5:W4, S6:W5, S7:W6, S8:W8 | F056–F063,F073–F090,F099 | WIRE_CAP,WIRE_INVARIANT,TRANSFER_CONSERVATION,ERASURE_BARRIER,PLAINTEXT_LEAK | T,E,O | EPHEMERAL_EXPORT closure增加 F010/F015–F027/F062；source/export phase PASS |
| `SHADOW_PLUS` | all concrete rows ordered union；无自身 tuple | F001–F099 coverage union | all concrete invariants ordered union + DUPLICATE_REASON | D,T,E,O,B,P union | 无 K/W/H；全 concrete context/dependency gate PASS |

Capture/Blob 缺任一 S8 tuple 时 overall 是 INCONCLUSIVE，不是“其它场景 PASS 所以暂时
PASS”。`W8=NOT_RUN_BLOCKED` 会机械地阻断所有要求 S8:W8 的 capability；不能删掉该 tuple
换取局部 PASS。

### 13.1 Sparse metric applicability 与 cohort

canonical pair aliases：

```text
P0=S0:W0  P1=S1:W1  P2=S2:W2  P3=S3:W3  P4=S4:W4
P5=S5:W4  P6=S6:W5  P7=S7:W6  P8A=S8:W7 P8B=S8:W8
```

`CaseKindV1` 是 closed token registry：

```text
KEYRING_BOOTSTRAP
CAPTURE_APPEND
TURN_CHECKPOINT
TURN_EXPECTED_FAILURE
TURN_CANCEL
BLOB_PUBLISH
WARM_RECALL
WARM_RECALL_CANCEL
HOT_SNAPSHOT_READ
COLD_RECALL
COLD_RECALL_CANCEL
EXPORT_EGRESS
CAPACITY_PRESSURE
KILL_KEYRING
KILL_JOURNAL
KILL_BLOB
KILL_RECALL
KILL_INDEX
KILL_MAINTENANCE
HOT_SNAPSHOT_PUBLISH
INDEX_REBUILD
BLOB_REWRITE
COMPACTION
KEY_ROTATION
LOCAL_ERASURE
EGRESS_DRAIN
```

每个 pair 的 allowed cases 恰为：

```text
P0  = {KEYRING_BOOTSTRAP}
P1  = {CAPTURE_APPEND}
P2  = {TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL}
P3  = {BLOB_PUBLISH}
P4  = {WARM_RECALL,WARM_RECALL_CANCEL,HOT_SNAPSHOT_READ}
P5  = {COLD_RECALL,COLD_RECALL_CANCEL,EXPORT_EGRESS}
P6  = {CAPACITY_PRESSURE}
P7  = {KILL_KEYRING,KILL_JOURNAL,KILL_BLOB,KILL_RECALL,KILL_INDEX,KILL_MAINTENANCE}
P8A = {HOT_SNAPSHOT_PUBLISH,INDEX_REBUILD,BLOB_REWRITE,COMPACTION}
P8B = {KEY_ROTATION,LOCAL_ERASURE,EGRESS_DRAIN}
```

capability case closure `L(C)` 恰为：

```text
SCHEMA_CODEC={}
KEYRING_BOOTSTRAP={KEYRING_BOOTSTRAP,KILL_KEYRING}
JOURNAL_WRITE={CAPTURE_APPEND,TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL,
               CAPACITY_PRESSURE,KILL_JOURNAL,INDEX_REBUILD,COMPACTION,LOCAL_ERASURE}
BLOB_STORE={BLOB_PUBLISH,CAPACITY_PRESSURE,KILL_BLOB,BLOB_REWRITE,LOCAL_ERASURE}
CAPTURE={CAPTURE_APPEND,TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL,
         BLOB_PUBLISH,CAPACITY_PRESSURE,KILL_JOURNAL,KILL_BLOB,
         INDEX_REBUILD,BLOB_REWRITE,COMPACTION,KEY_ROTATION,LOCAL_ERASURE,EGRESS_DRAIN}
WARM_RECALL={WARM_RECALL,WARM_RECALL_CANCEL,CAPACITY_PRESSURE,KILL_RECALL}
COLD_RECALL={COLD_RECALL,COLD_RECALL_CANCEL,EXPORT_EGRESS,CAPACITY_PRESSURE,KILL_RECALL}
HOT_SNAPSHOT={WARM_RECALL,HOT_SNAPSHOT_READ,COLD_RECALL,CAPACITY_PRESSURE,
              KILL_INDEX,HOT_SNAPSHOT_PUBLISH}
INDEX_REBUILD={WARM_RECALL,CAPACITY_PRESSURE,KILL_INDEX,INDEX_REBUILD}
MAINTENANCE={CAPACITY_PRESSURE,KILL_MAINTENANCE,HOT_SNAPSHOT_PUBLISH,INDEX_REBUILD,
             BLOB_REWRITE,COMPACTION,KEY_ROTATION,LOCAL_ERASURE,EGRESS_DRAIN}
KEY_ROTATION={KILL_KEYRING,KILL_MAINTENANCE,KEY_ROTATION}
LOCAL_ERASURE={CAPACITY_PRESSURE,KILL_MAINTENANCE,LOCAL_ERASURE,EGRESS_DRAIN}
EXPORT_EGRESS={COLD_RECALL,EXPORT_EGRESS,CAPACITY_PRESSURE,KILL_RECALL,EGRESS_DRAIN}
SHADOW_PLUS=ALL_CASE_KINDS_V1
```

`ALL_CASE_KINDS_V1` 恰为上面 26 个 token 的完整集合，不是 wildcard。每个 manifest case
恰有一个 kind；unknown/多 kind 拒绝。对 field/capability/context 的 exact case set 是
`pair_allowed ∩ L(C) ∩ U(F).case_kinds`；conditional child branch 同时 union child 的
`L(child)`。该交集后，
**该交集的全部且仅全部 precommitted measured case ordinals**都进入 field
denominator/statistic，不能挑其中较快 case。precommitted warm-up ordinals仍按 §9.1全部
执行并要求 protocol-valid coverage/integrity row，但永不进入 denominator/statistic。
proposal 在结果前绑定每个 `(F,pair,case_kind)` sorted measured-ordinal set+digest；tuple key
增加 `case_kind` 与 `case_plan_digest`。交集为空却列出 pair、零 denominator、漏一个
ordinal 或用另 kind 替代都 schema INVALID/WORKLOAD_COVERAGE FAIL。

cohort 是结果可见前由 suite manifest 对 exact case ordinal 冻结的 closed enum：

- `POSITIVE_SLO`：预期完成的 normal operation；negative/cancel/planned-kill case 不在其中；
- `DURABILITY_SLO`：预期被 durable admission接受的 operation；所有 case都必须进入
  conservation ledger，但 terminal允许 closed Ack bucket中的任一项，非 durable/unresolved
  比例只由 F044裁决，不能由 `WORKLOAD_COVERAGE` 提前判掉；
- `CANCEL_SLO`：预先指定的 cancel case，只测 cancel terminal/cleanup；
- `RESOURCE_ALL`：该 pair 所有预承诺 VALID cases，包括 planned failure，用于 peak/cap；
- `FAULT_INVARIANT`：不产生 profile threshold sample，只进入 conservation/recovery/
  coverage invariants。

字段 registry（pair 顺序即 canonical order）：

| exact field set | `U(F).pairs` | `U(F).case_kinds` | cohort |
|---|---|---|---|
| F034 | P1,P2,P6 | CAPTURE_APPEND,TURN_CHECKPOINT,CAPACITY_PRESSURE | POSITIVE_SLO |
| F042–F043 | P2 | TURN_CHECKPOINT | DURABILITY_SLO |
| F044 | P1,P2,P3,P6 | CAPTURE_APPEND,TURN_CHECKPOINT,BLOB_PUBLISH,CAPACITY_PRESSURE | DURABILITY_SLO |
| F049–F050,F053 | P3 | BLOB_PUBLISH | POSITIVE_SLO |
| F051–F052 | P3,P6,P7,P8A,P8B | BLOB_PUBLISH,CAPACITY_PRESSURE,KILL_BLOB,BLOB_REWRITE,LOCAL_ERASURE | RESOURCE_ALL |
| F054 | P8A | BLOB_REWRITE | RESOURCE_ALL |
| F055 | P8A,P8B | BLOB_REWRITE,KEY_ROTATION,LOCAL_ERASURE | POSITIVE_SLO |
| F060 | P4,P5 | WARM_RECALL,COLD_RECALL,EXPORT_EGRESS | POSITIVE_SLO |
| F061 | P4,P5,P8B | WARM_RECALL_CANCEL,COLD_RECALL_CANCEL,EGRESS_DRAIN | CANCEL_SLO |
| F062–F063 | P4,P5,P7,P8B | WARM_RECALL,WARM_RECALL_CANCEL,COLD_RECALL,COLD_RECALL_CANCEL,EXPORT_EGRESS,KILL_RECALL,EGRESS_DRAIN | RESOURCE_ALL |
| F064–F066 | P4 | WARM_RECALL | POSITIVE_SLO |
| F067–F069 | P5 | COLD_RECALL | POSITIVE_SLO |
| F070–F071 | P4,P5 | WARM_RECALL,COLD_RECALL | POSITIVE_SLO |
| F072 | P4,P5 | WARM_RECALL_CANCEL,COLD_RECALL_CANCEL | CANCEL_SLO |
| F073–F074 | P0,P1,P2,P4,P5,P6,P7,P8A,P8B | ALL_CASE_KINDS_V1 | RESOURCE_ALL |
| F075 | P2,P7 | TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL,KILL_JOURNAL | RESOURCE_ALL |
| F076 | P1,P2,P3,P6,P7 | CAPTURE_APPEND,TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL,BLOB_PUBLISH,CAPACITY_PRESSURE,KILL_JOURNAL,KILL_BLOB | RESOURCE_ALL |
| F077 | P4,P5,P7 | WARM_RECALL,WARM_RECALL_CANCEL,COLD_RECALL,COLD_RECALL_CANCEL,EXPORT_EGRESS,KILL_RECALL | RESOURCE_ALL |
| F078 | P1,P2 | CAPTURE_APPEND,TURN_CHECKPOINT | POSITIVE_SLO |
| F079 | P4,P5 | WARM_RECALL,COLD_RECALL | POSITIVE_SLO |
| F080 | P1,P2,P6,P7 | CAPTURE_APPEND,TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL,CAPACITY_PRESSURE,KILL_JOURNAL,KILL_RECALL | RESOURCE_ALL |
| F081 | P1,P2,P3,P4,P5,P6,P7,P8A,P8B | ALL_CASE_KINDS_V1 | RESOURCE_ALL |
| F082 | P2 | TURN_CHECKPOINT | POSITIVE_SLO |
| F083 | P4,P5 | WARM_RECALL,COLD_RECALL | POSITIVE_SLO |
| F084 | P8A,P8B | HOT_SNAPSHOT_PUBLISH,INDEX_REBUILD,BLOB_REWRITE,COMPACTION,KEY_ROTATION,LOCAL_ERASURE | POSITIVE_SLO |
| F085 | P1,P2,P3,P6,P8A,P8B | CAPTURE_APPEND,TURN_CHECKPOINT,BLOB_PUBLISH,CAPACITY_PRESSURE,INDEX_REBUILD,BLOB_REWRITE,COMPACTION,KEY_ROTATION,LOCAL_ERASURE | POSITIVE_SLO |
| F086 | P1,P2,P3,P6,P7,P8A,P8B | ALL_CASE_KINDS_V1 | RESOURCE_ALL |
| F087 | P1,P2,P3,P6,P8A,P8B | CAPTURE_APPEND,TURN_CHECKPOINT,BLOB_PUBLISH,CAPACITY_PRESSURE,INDEX_REBUILD,BLOB_REWRITE,COMPACTION,KEY_ROTATION,LOCAL_ERASURE | POSITIVE_SLO |
| F088 | P2 | TURN_CHECKPOINT,TURN_EXPECTED_FAILURE,TURN_CANCEL | RESOURCE_ALL |
| F089 | P4,P5 | WARM_RECALL,COLD_RECALL | POSITIVE_SLO |
| F090 | P1,P2,P3,P4,P5,P6,P8A,P8B | ALL_CASE_KINDS_V1 | RESOURCE_ALL |
| F093 | P8A | HOT_SNAPSHOT_PUBLISH | POSITIVE_SLO |
| F094–F095 | P8A | INDEX_REBUILD | POSITIVE_SLO |
| F096 | P8A | COMPACTION | RESOURCE_ALL |
| F097 | P8B | KEY_ROTATION | RESOURCE_ALL |
| F098 | P8B | LOCAL_ERASURE | POSITIVE_SLO |
| F099 | P8B | EGRESS_DRAIN | POSITIVE_SLO |

planned W6/P7 的 Ack outcomes 仍全部进入 `ACK_CONSERVATION`、operation-kill ledger、
recovery/census 与 `WORKLOAD_COVERAGE`；它们不进入 F044/F087 等 steady SLO numerator。
cohort membership 由 case ordinal/manifest 决定，不能按 outcome 成功与否事后迁移。

`WORKLOAD_COVERAGE` 的 manifest entry 对每个 `(S,W,case,cohort)` 在结果可见前绑定
unique `case_ordinal`、case commitment、expected generated/eligible record count、logical
bytes、query/page/effect count、expected closed outcome，以及唯一允许的
`POLICY_EXCLUDED_PREDECLARED | NEGATIVE_PREDECLARED | CANCEL_PREDECLARED` exclusions。
case ordinal/commitment 在一个 run 中必须唯一；所有 bucket pairwise-disjoint，membership
不能随 outcome 事后改变。

对 record 与 bytes 分别满足 exact equality：

```text
generated =
  policy_excluded_predeclared + eligible

eligible =
  negative_predeclared + cancel_predeclared + positive_required

positive_required = admitted_positive
admitted_positive =
  processed_positive =
  expected_durable_or_terminal_positive
```

对每个 admitted request，ADR 0016/本 ADR ledger 仍必须满足
`admitted = durable + failed_proven_excluded + indeterminate + callback_lost + pending`；
`POSITIVE_SLO/CANCEL_SLO/RESOURCE_ALL` 的 manifest case除预承诺 exact terminal外任何
bucket 都使 `WORKLOAD_COVERAGE=FAIL`，不能把 unexpected storage rejection、backpressure、
drop、callback loss、pending 或 crash改名为 exclusion后通过。唯一例外是
`DURABILITY_SLO`：manifest 的 expected terminal固定为
`ACK_LEDGER_TERMINAL_ANY_CONSERVING`，要求每个预承诺 case都成功 admission且恰好出现在
守恒 ledger一次；`DURABLE/FAILED_PROVEN_EXCLUDED/INDETERMINATE/CALLBACK_LOST/PENDING`
由 F044 numerator/denominator区分，不触发 coverage fail。callback-lost/pending未在观察
deadline内对账仍按 §10.1使 F044 metric `INCONCLUSIVE`，不是低比率 PASS。协议错误 cut、
重复/漏 case、未 admission或守恒失败仍是 invariant FAIL。negative/cancel case也必须只
进入其预承诺的 closed terminal bucket。

query、page、transfer 与 effect 分别使用相同的 generated/eligible/positive-required
partition，并满足：

```text
eligible_queries = completed_expected_queries
expected_pages = emitted_once_pages
eligible_transfers = exact_terminal_transfers
eligible_effects = exact_terminal_effects
```

所有等式同时约束 count、checked logical bytes 和 sorted unique item commitments；不得只
对总数相等而重复一项/漏另一项。任一未预声明 exclusion、case 重复/缺失、wrong bucket、
少/多 1 条、1 byte、query/page/effect 或 commitment mismatch 都直接 invariant FAIL，
不是 MISSING 或较快样本。F085 只是附加产品 floor，不能替代 coverage equality，也不能从
一个已漏工作的 calibration 调低来洗白。golden/property test 至少覆盖 exact pass、
missing-one、duplicate-one、one-byte delta、wrong-bucket、unexpected-backpressure 与
post-outcome exclusion laundering。

---

## 14. Authoritative dependency DAG

### 14.1 Closed `GateIdV1` registry

以下 exact ASCII token 是 Gate 0 的完整 registry；禁止省略 `V1`、使用近义别名或运行时
临时加 GateId：

```text
WireCompatibilityGateV1
ReleaseIdentityGateV1
ReleaseOwnerContinuityGateV1
ReleaseSigningAuthorityGateV1
PreCertificationCandidateControlPhaseGateV1
ReleasePolicySemanticsPhaseGateV1
PlatformCertificationGateV1
RootBootstrapControlPhaseGateV1
DataRootContinuityGateV1
InstallationKeyringIdentityGateV1
KeyAuthorizationProfileGateV1
UnlockedKeyBehaviorGateV1
CredentialUnlockedRuntimeGateV1
KeyUseSafetyGateV1
KeyringBootstrapControlPhaseGateV1
KeyringBootstrapCapabilityGateV1
JournalFrontierDurabilityGateV1
BlobWireLocatorLeaseGateV1
CapturePolicyConsentGateV1
MemoryUsePolicyGateV1
ProviderMemoryDisclosureGateV1
ExportAuthorizationGateV1
BackupExclusionGateV1
SourceManifestPhaseGateV1
RecordIdentityTombstonePhaseGateV1
DerivedIndexAuthorityGateV1
HotSnapshotPlaintextExclusionGateV1
MaintenancePeakCapacityGateV1
RotationControlPhaseGateV1
RotationReceiptCommittedGateV1
LocalErasureControlPhaseGateV1
LocalErasureCapabilityGateV1
ErasureSafetyReceiptGateV1
CumulativeErasureViewGateV1
EgressDrainEvidenceGateV1
RebootNamespaceCensusGateV1
TransferOutcomeConservationGateV1
StageRevocationFreshnessGateV1
BudgetProfileGateV1
BudgetEvidenceWirePhaseGateV1
SyntheticMeasurementControlPhaseGateV1
BuildAttestationGateV1
StageSnapshotAuthenticationPhaseGateV1
StageSnapshotAuthenticationCapabilityGateV1
SourceErasureAuthorityGateV1
```

关键 scope：

- `DataRootContinuityGateV1` 恰含 canonical root、UID、SELinux context、noBackup、
  credential-encrypted storage 与 device-protected mirror negative census；不存在
  `StorageRoot...` 别名；
- `ReleaseOwnerContinuityGateV1` 只裁决 ADR 0015 DataOwner/owner-ledger continuity；
- `ReleaseIdentityGateV1` 是 installed artifact/runtime identity evaluator；它只依赖
  installed APK/signer identity 与 external owner bundle，可在 local Memory state 尚不存在
  时 PASS，以便参与 narrow authority bootstrap；
- `ReleaseSigningAuthorityGateV1` 裁决 external signer、OIDC、key custody、WORM ledger 与
  disaster recovery，是 release-entry gate，不是设备 runtime identity evaluator；
- `PreCertificationCandidateControlPhaseGateV1` 只裁决 separate candidate
  authorization/permit/run-ledger、candidate-root taint/containment 与 no-adoption state
  machine 的 exact wire/recovery；它不使 normal ReleaseIdentity/owner continuity PASS，
  当前 BLOCKED；
- `ReleasePolicySemanticsPhaseGateV1` 裁决 ADR 0015 opaque release-policy blob 的 exact
  schema/evaluator/caps/unknown-field semantics；当前只有 revision/length/digest continuity，
  没有 accepted policy semantics，因此本 gate BLOCKED；
- `PlatformCertificationGateV1` 裁决实体 device/API/OEM upgrade matrix 与 certification
  evidence；
- `RootBootstrapControlPhaseGateV1` 只裁决 ADR 0017 §8.4 的 authority-bootstrap
  control/lock/owner-state wire 是否已冻结并可恢复；当前 BLOCKED；
- `InstallationKeyringIdentityGateV1`、`KeyAuthorizationProfileGateV1`、
  `UnlockedKeyBehaviorGateV1`、`CredentialUnlockedRuntimeGateV1`、
  `KeyUseSafetyGateV1` 与 `JournalFrontierDurabilityGateV1` 保持独立；其中 KeyUseSafety 是
  static enforcement-capability evidence，current unlock只由 dynamic
  CredentialUnlockedRuntime gate逐 effect裁决；
- `BlobWireLocatorLeaseGateV1` 裁决 Blob physical/locator/lease 与 durable
  materialization-orphan intent/state/cleanup receipt，包括 count/bytes charge 直到
  unlink+parent-fsync+reopen census；ordinary single-writer Blob 不因此依赖
  `SourceManifestPhaseGateV1`，只有 cross-writer/source provenance 才叠加后者；
- `BudgetEvidenceWirePhaseGateV1` 只裁决 §11 exact evidence descriptors/canonical
  bytes/caps/digest/signature trust wire；当前 BLOCKED。
- `SyntheticMeasurementControlPhaseGateV1` 只裁决
  `ExperimentalMeasurementConfigV1`、permit binding/expiry、exact production candidate
  applicationId内的 candidate-only authority/root/corpus与 crash cleanup exact control
  wire；当前 BLOCKED。
- `StageSnapshotAuthenticationPhaseGateV1` 只裁决工程计划 §21.2 scoped gate receipt、
  external profile/role selection、canonical snapshot/authentication/anti-replay wire；当前
  BLOCKED，故 normal cross-process snapshot 只能收缩到 SCHEMA_ONLY。
- `StageSnapshotAuthenticationCapabilityGateV1` 只在 exact candidate APK 上以三进程、
  reboot/kill、wrong-scope/tamper/old-pair/device/profile/context/operation-authority matrix
  证明 publisher/store/watcher/evaluator；它是 measurement输出、当前 BLOCKED，不能成为其
  自身 permit前置。
- `RecordIdentityTombstonePhaseGateV1` 裁决 ADR 0016 future authenticated
  `RecordIdentityTombstone` authority；当前 BLOCKED，不能以 retention 到期或 compaction
  成功直接删除 canonical frame。
- `LocalErasureControlPhaseGateV1` 只裁决 exact control schema/reducer，可在 zero-root PASS；
  `LocalErasureCapabilityGateV1` 只裁决 measurement-only synthetic kill/drain/unlink/reboot
  runtime evidence。后者不得成为 measurement permit 前置；当前二者均 BLOCKED。
- `ErasureSafetyReceiptGateV1` 只在 exact installation/root 上验证
  `ErasureSafetyCapabilityReceiptV1` 已 file/directory durable、close/reopen、与 current
  erasure executor digest exact match或有 authenticated successor proof，并且 root header/
  record lineage 绑定 receipt digest；它是 CAPTURE admission prerequisite，不是 measurement
  自举 prerequisite。当前 BLOCKED。
- `CapturePolicyConsentGateV1` 只授权新增 capture；撤销不能关闭 existing-data erasure。
  `MemoryUsePolicyGateV1` 独立授权 Agent recall/index/snapshot 使用已保留数据；
  `ProviderMemoryDisclosureGateV1` 是 ADR 0017 §12.5 每次把历史 Memory 交给 exact
  Provider destination 的 replay-safe disclosure grant；`ExportAuthorizationGateV1` 是每次
  显式文件/用户导出的 replay-safe grant。四者不可互相替代，当前 exact wire 均未接受的
  gate 保持 BLOCKED。

### 14.2 Capability 级依赖链

```text
WireCompatibilityGateV1
  -> SCHEMA_CODEC

ReleaseIdentityGateV1
+ RootBootstrapControlPhaseGateV1
+ LocalErasureControlPhaseGateV1
+ clean credential-protected root/alias census
  -> one-shot AuthorityBootstrapPermitV1
  -> empty root shell + bootstrap lock + owner-signed local owner A/B/control only
  -> durable close/reopen owner verification
  -> ReleaseOwnerContinuityGateV1 may PASS

ReleaseOwnerContinuityGateV1
+ DataRootContinuityGateV1
+ KeyringBootstrapControlPhaseGateV1
+ KeyringBootstrapCapabilityGateV1
+ selected exact role/profile identity
+ BudgetProfileGateV1
+ ControlPlaneAdoptionReceiptV1(
     CONTROL_PLANE_ADOPTION,
     exact owner-shell inode/physical-charge census,
     F010/F014 debit,
     F022 volume identity
   )
+ StorageCapacityFieldClosureDecisionV1(
     KEYRING_BOOTSTRAP,
     CONTROL_PLANE_ADOPTION + CREATE_PERSISTENT(JOURNAL+MANIFEST),
     PASS
   )
+ StorageCapacityPreflightReceiptV1(
     exact volume, current class/component ledger, available bytes,
     success/failure/indeterminate reservations and cleanup plan
   )
  -> one-shot KEYRING_BOOTSTRAP_STAGING
  -> durable compound intent before first allocation
  -> staged aliases + keyring/frontiers/owner leases, no dependent data

KEYRING_BOOTSTRAP_STAGING
+ InstallationKeyringIdentityGateV1
+ KeyAuthorizationProfileGateV1
+ UnlockedKeyBehaviorGateV1
  -> KeyUseSafetyGateV1

current `CredentialUnlockedRuntimeGateV1` 不进入可缓存的 KeyUseSafety evidence；每个
bootstrap/operation/effect仍通过 normal dynamic admission独立 OBSERVE_AND_REVALIDATE。

KEYRING_BOOTSTRAP_STAGING
+ KeyUseSafetyGateV1
+ KeyringBootstrapControlPhaseGateV1
  -> bootstrap receipt + full close/reopen census PASS
  -> KEYRING_BOOTSTRAP_COMMITTED

ReleaseOwnerContinuityGateV1
+ DataRootContinuityGateV1
+ InstallationKeyringIdentityGateV1
+ KeyUseSafetyGateV1
+ KeyringBootstrapControlPhaseGateV1
+ KeyringBootstrapCapabilityGateV1
+ KEYRING_BOOTSTRAP_COMMITTED
  -> KEYRING_READY
  -> PERSISTENT_SUBSTRATE

exact signer-WORM production candidate APK/applicationId
+ standalone candidate ReleaseIdentity document
+ WireCompatibilityGateV1
+ PreCertificationCandidateControlPhaseGateV1
+ ReleaseSigningAuthorityGateV1
+ ReleasePolicySemanticsPhaseGateV1
+ RootBootstrapControlPhaseGateV1
+ LocalErasureControlPhaseGateV1
+ KeyringBootstrapControlPhaseGateV1
+ BudgetEvidenceWirePhaseGateV1
+ SyntheticMeasurementControlPhaseGateV1
+ StageSnapshotAuthenticationPhaseGateV1
+ CandidateBuildAttestationDecisionV1(ALLOW)
+ pristine lab device/root + external filesystem/Keystore/process/fd/socket zero census
  -> PreCertificationCandidateDecisionV1(ALLOW_MEASUREMENT|NOT_RUN_BLOCKED)

KEYRING_BOOTSTRAP evidence capability uses a sealed control-only decision:
  PreCertificationCandidateDecisionV1(ALLOW_MEASUREMENT)
  + external durable proposal/run/kill-attempt ledger
  -> precommitted ordered KeyringBootstrapEvidenceAttemptSetV1[N], N>=1
  -> for each kill case in exact order:
       new pristine zero root
       + pairwise-distinct CandidateEvidenceAuthorityBootstrapPermitV1[attempt]
       + one-shot KeyringBootstrapEvidencePermitV1[attempt]
       -> CANDIDATE_EVIDENCE_OWNER_CONTROL[attempt]
       -> execute one exact production bootstrap transaction/kill boundary
       -> exactly one sandbox-external
          CandidateKeyringBootstrapEvidenceAttemptTerminalV1[
            BOOTSTRAP_RECEIPT_COMMITTED |
            KILLED_PRE_RECEIPT |
            BLOCKED_PARTIAL |
            INDETERMINATE_RETAINED |
            KNOWN_ABORTED_CLEAN
          ]
       -> external cleanup + zero census
  -> aggregate all attempt terminals and evaluate candidate-scoped
     DataRootContinuityGateV1, InstallationKeyringIdentityGateV1,
     KeyAuthorizationProfileGateV1, UnlockedKeyBehaviorGateV1,
     KeyUseSafetyGateV1 and KeyringBootstrapCapabilityGateV1；attempt row可以记录当次 unlock
     observation，但不能汇总成 persistent `CredentialUnlockedRuntimeGateV1=PASS`
  -> candidate-scoped KEYRING_BOOTSTRAP_EVIDENCE_PASS

CandidateKeyringBootstrapEvidenceAttemptTerminalV1
  binds exact attempt/kill boundary + observed root/alias/lock/receipt census
  + optional CandidateKeyringBootstrapEvidenceReceiptV1 digest
  exists even when no bootstrap receipt was reached
  is neither that receipt nor KEYRING_BOOTSTRAP_COMMITTED and cannot cast to either

PreCertificationCandidateDecisionV1(ALLOW_MEASUREMENT)
+ exact APK static backup/data-extraction rules
+ separate pristine backup/restore/D2D attempt ledger
  -> precommitted ordered CandidateBackupExclusionAttemptSetV1[M], M>=1
  -> each case uses a distinct pristine root/decision, terminal, external wipe
     + filesystem/Keystore/process/fd/socket zero census
  -> CandidateBackupExclusionReceiptV1

candidate receipt不能 cast为 `GateVerdictV1`。final certification issuer只有在 receipt、exact
cached APK、device/role、static manifest与 restore/D2D/cleanup raw evidence全量验证后，才
生成 subject-bound `BackupExclusionGateV1` observation并纳入
`PlatformCertificationV1`/normal stage receipt；wrong APK/device/role、cleanup gap或 receipt
重放均非 PASS。

该 capability 只分类 clean lab bootstrap/kill/reopen evidence，不产生 FeatureStage，也不由
普通 `SyntheticMeasurementAdmissionV1` 在已建 substrate 内“重测”。它使用独立
authority/keyring bootstrap control transaction 与外部 clean/uninstall containment；
normal product reducer 对该 token schema reject；只有
`KeyringBootstrapEvidenceDecisionV1` 可授权，不能激活 UI/runtime。
`KeyringBootstrapControlPhaseGateV1` 只证明 intent/frontier/receipt wire、model/golden/fuzz
与 external-containment recovery semantics；它允许 clean-lab special transaction，但不
证明真实 kill/reopen。`KeyringBootstrapCapabilityGateV1` 才证明实体设备/API/OEM 上每个
init/write/fsync/publish/reopen/alias-census kill point，且只进入 product substrate，绝不
成为测量自身的前置。

`KeyringBootstrapEvidencePermitV1` 不是普通 `SyntheticMeasurementPermitV1`、GateId 或
FeatureStage。每个 attempt token只在 keyring/aliases/dependent-data census 全空时创建一次，绑定 exact
build/device/control/evidence descriptors、bootstrap attempt/kill schedule、owner/root 与
external containment；不读取 corpus/用户数据，也不能进入 normal writer。被测的
`KeyringBootstrapCapabilityGateV1` 以及 installation identity/key-use postcondition gates
明确**不在 permit prerequisite** 中。任一 unplanned state/cleanup failure由外部 harness
clear-data/uninstall 并做 namespace/Keystore zero census；结果可以 FAIL/INCONCLUSIVE，
不能重跑择优。一份 token不能跨 wipe测第二个 kill point；attempt set在任何结果前封闭，
same set不得 top-up。只有该独立 evidence gate PASS 后，普通安装的
`KEYRING_BOOTSTRAP_STAGING` branch 才可消费 capability gate。

candidate root/header/receipt/alias namespace 带不可转换
`PRE_CERTIFICATION_MEASUREMENT_ONLY` authority class、独立 magic/key domain；candidate
control不得写入 normal owner A/B、不得让 `ReleaseOwnerContinuityGateV1` 或 normal
`PERSISTENT_SUBSTRATE` PASS，认证成功后也不得原地“转正”。normal runtime发现 candidate
root一律拒绝/advised external reset。production applicationId 与 production exact code path
才是被测 subject；隔离由 pristine lab device/root 与独立 companion harness applicationId
提供，不能改被测 applicationId 伪造等价。keyring evidence cleanup 后创建 normal-like
candidate substrate必须用新 pristine **zero root**、独立 commit permit，不能复用 kill
suite残留；commit不可在 primary measurement authority 之前发生。

`CandidateBuildAttestationDecisionV1(ALLOW|NOT_RUN_BLOCKED)` 不是
`BuildAttestationGateV1`：它由 exact candidate authorization + signer WORM + device/harness
attestation 产生，只能进入 candidate reducers，不能放进 normal gate map。
`CandidateEvidenceAuthorityBootstrapPermitV1` 与
`CandidatePrimaryAuthorityBootstrapPermitV1` 是不可互 cast 的 sealed types；两者分别绑定
`KEYRING_EVIDENCE|PRIMARY_RUN` purpose、pairwise-distinct permit ID/root epoch/attempt
ordinal。special terminal/wipe销毁 evidence token，primary token此前不得消费；任一 token
跨 wipe/root/phase重放都为永久 security failure。
`CandidateKeyringCommitPermitV1` 绑定 candidate authorization、device、candidate-scoped
keyring evidence digest、pristine-root zero census、single commit ordinal 与 external
monotonic ledger。它不能单独消费；每个预承诺 root epoch各有 pairwise-distinct
`CandidatePrimaryAuthorityBootstrapPermitV1`、`CandidateKeyringCommitPermitV1` 与
`SyntheticMeasurementRootEpochPermitV1`，三者被同一
`CandidatePrimaryRootEpochStartDecisionV1` 原子 durable consume，之后才可写第一 byte。S0 内
完成 authority/owner bootstrap 与 keyring commit 后产生
`CandidateKeyringCommitReceiptV1`，crash只 exact-resume同一 joint decision且不可 reissue。
calibration 与 confirmatory、以及四个 role device各自使用新的 candidate authorization、
ordered root-epoch start set、zero roots、joint decisions/permits/receipts；旧 root不复用，
不能因 profile改变静默继承 substrate。

BudgetEvidenceWirePhaseGateV1
+ ReleaseIdentityGateV1
+ wire-valid build/APK/device/signature provenance
  -> BuildAttestationGateV1

KEYRING_BOOTSTRAP_EVIDENCE_PASS
+ WireCompatibilityGateV1
+ ReleasePolicySemanticsPhaseGateV1
+ CandidateBackupExclusionReceiptV1
+ SourceManifestPhaseGateV1
+ RecordIdentityTombstonePhaseGateV1
+ RotationControlPhaseGateV1
+ LocalErasureControlPhaseGateV1
+ BudgetEvidenceWirePhaseGateV1
+ SyntheticMeasurementControlPhaseGateV1
+ StageSnapshotAuthenticationPhaseGateV1
+ same PreCertificationCandidateDecisionV1 digest
+ same CandidateBuildAttestationDecisionV1 digest
+ precommitted bounded ExperimentalMeasurementConfigV1
+ precommitted closed capabilities_under_test
+ exact production applicationId/APK + known synthetic corpus digest
+ precommitted ordered PrimaryRootEpochPlanV1[N], N>=1
  -> one-shot SyntheticMeasurementPermitV1
  -> external durable consume run permit before first root epoch
  -> for each precommitted epoch in exact order:
       new pristine zero root + external census
       + unconsumed CandidatePrimaryAuthorityBootstrapPermitV1[epoch]
       + unconsumed CandidateKeyringCommitPermitV1[epoch]
       + unconsumed SyntheticMeasurementRootEpochPermitV1[epoch]
       -> atomic durable CandidatePrimaryRootEpochStartDecisionV1
       -> S0 ordered authority bootstrap -> owner control -> keyring commit
       -> CandidateKeyringCommitReceiptV1 + CANDIDATE_PERSISTENT_SUBSTRATE
       -> typed SyntheticMeasurementAdmissionV1 producer
       -> assigned ordered MEASUREMENT_ONLY cases
       -> epoch terminal + external wipe/zero census
  -> run terminal only after all N epoch terminals

SyntheticMeasurementPermitV1
+ MEASUREMENT_ONLY capture/maintenance/egress/erasure paths
+ CumulativeErasureViewGateV1
+ EgressDrainEvidenceGateV1
+ RebootNamespaceCensusGateV1
+ RecordIdentityTombstonePhaseGateV1
  -> LocalErasureCapabilityGateV1

MEASUREMENT_ONLY capability paths
  -> candidate evidence/verdicts for PlatformCertificationGateV1,
     JournalFrontierDurabilityGateV1, BlobWireLocatorLeaseGateV1,
     DerivedIndexAuthorityGateV1, HotSnapshotPlaintextExclusionGateV1,
     MaintenancePeakCapacityGateV1, RotationReceiptCommittedGateV1,
     CumulativeErasureViewGateV1, EgressDrainEvidenceGateV1,
     RebootNamespaceCensusGateV1, TransferOutcomeConservationGateV1,
     StageRevocationFreshnessGateV1, SourceErasureAuthorityGateV1,
     StageSnapshotAuthenticationCapabilityGateV1,
     LocalErasureCapabilityGateV1 and BudgetProfileGateV1

BudgetEvidenceWirePhaseGateV1
+ accepted BudgetProfileV1
+ wire-valid raw evidence and deterministic verifier
  -> BudgetProfileGateV1

SCHEMA_CODEC + PERSISTENT_SUBSTRATE
+ JournalFrontierDurabilityGateV1
+ BudgetProfileGateV1
  -> JOURNAL_WRITE

SCHEMA_CODEC + PERSISTENT_SUBSTRATE
+ BlobWireLocatorLeaseGateV1
+ BudgetProfileGateV1
  -> BLOB_STORE

JOURNAL_WRITE
+ BLOB_STORE (iff validated operation/cut transitively reads, writes or references Blob)
+ CapturePolicyConsentGateV1
+ BackupExclusionGateV1
+ SourceErasureAuthorityGateV1
+ SourceManifestPhaseGateV1 (cross-writer/source only)
+ LocalErasureCapabilityGateV1
+ ErasureSafetyReceiptGateV1
  -> CAPTURE

JOURNAL_WRITE
+ DerivedIndexAuthorityGateV1 (iff validated operation/cut uses current derived index)
+ BudgetProfileGateV1
+ TransferOutcomeConservationGateV1
+ CumulativeErasureViewGateV1
+ SourceErasureAuthorityGateV1
+ MemoryUsePolicyGateV1
  -> WARM_RECALL -> COLD_RECALL

`WARM_RECALL/COLD_RECALL` 在 V1 明确指 Broker/PFD/page transport：warm 是 Broker 已存活，
cold 是先 bind 后交付，因此两者都要求 transfer length/digest/fd/terminal 守恒。
M9A 的 in-process `session_recall` 只是在 typed measurement admission 下验证 fixed-cut
storage/read implementation，不是另一个 normal stage capability，也不能在 M9B
`TransferOutcomeConservationGateV1` 之前单独激活；normal recall首次 activation必须走上述
Broker branch。若未来要独立开放 local exact recall，必须新增 sealed capability及完整
K/W/H/P-T-I/U(F)，不能复用 WARM token。

JOURNAL_WRITE
+ DerivedIndexAuthorityGateV1
+ BudgetProfileGateV1
+ CumulativeErasureViewGateV1
+ SourceErasureAuthorityGateV1
+ MemoryUsePolicyGateV1
  -> INDEX_REBUILD

JOURNAL_WRITE
+ BLOB_STORE (iff validated source closure uses Blob)
+ HotSnapshotPlaintextExclusionGateV1
+ BudgetProfileGateV1
+ CumulativeErasureViewGateV1
+ SourceErasureAuthorityGateV1
+ MemoryUsePolicyGateV1
  -> HOT_SNAPSHOT

HOT_SNAPSHOT是 writer=NONE 的 background/local derived operation，不继承 WARM/COLD 的
Brain/Main consumer route、PFD handoff或 transfer gate。它直接按 authenticated source shape
投影 Journal/Blob read与 snapshot publish/retire；裸 WorkManager不构成 MemoryUse authority，
每次 scheduler transaction仍消费 policy-authorized background grant。

JOURNAL_WRITE
+ BLOB_STORE (iff validated source/rewrite closure uses Blob)
+ DerivedIndexAuthorityGateV1 (iff validated source/rewrite closure uses derived index)
+ MaintenancePeakCapacityGateV1
+ RecordIdentityTombstonePhaseGateV1
+ CumulativeErasureViewGateV1
+ SourceErasureAuthorityGateV1
  -> MAINTENANCE

MAINTENANCE
+ RotationControlPhaseGateV1
+ RotationReceiptCommittedGateV1
  -> KEY_ROTATION -> OLD_ALIAS_DESTRUCTION

`RotationReceiptCommittedGateV1` 是对 exact build/device/rotation implementation 的
subject-bound capability evidence：它证明此前隔离 suite 的 prepare→pair→receipt→frontier→
destruction crash recovery，不是当前 rotation transaction 在结果前必须已经 committed 的
循环前置。每次 live rotation另由 sealed `RotationControlChildDecisionV1` 铸造不可互 cast
的 control ports：

```text
ROTATION_CONTROL_PREPARE | ROTATION_PAIR_SLOT_COMMIT | ROTATION_RECEIPT_COMMIT
ROTATION_FRONTIER_SEAL | ROTATION_ALIAS_DESTROY
```

decision绑定 base/new aliases、control/receipt ordinals、precommitted attempted-use range、
writer cut、data-rewrite closure、exact step predecessor与 destruction census；每个 token
one-shot且只能推进一个相邻 state。data `JOURNAL_REWRITE/BLOB_REWRITE_PHYSICAL/...` child
不能调用 Keystore control，control token也不能读写 record plaintext。没有 alias-destroy
token或引用/lease/frontier/census未闭合时不得删 alias。

LOCAL_ERASURE uses a sealed fail-safe control decision, not a FeatureStage:
  WireCompatibilityGateV1
  + LocalErasureControlPhaseGateV1
  + ReleaseOwnerContinuityGateV1
  + DataRootContinuityGateV1
  + InstallationKeyringIdentityGateV1
  + KeyAuthorizationProfileGateV1
  + UnlockedKeyBehaviorGateV1
  + KeyUseSafetyGateV1
  + CredentialUnlockedRuntimeGateV1 (locked means pending-until-unlock, not denial)
  + SourceManifestPhaseGateV1 (source-selective branch only)
  + SourceErasureAuthorityGateV1
  + CumulativeErasureViewGateV1
  + EgressDrainEvidenceGateV1
  + RebootNamespaceCensusGateV1
  + RecordIdentityTombstonePhaseGateV1
  + ErasureSafetyReceiptGateV1
  + BlobWireLocatorLeaseGateV1 (iff validated cumulative erase scope USES_BLOB)
  -> LOCAL_ERASURE

`ErasureSafetyCapabilityReceiptV1` 只能在 `LocalErasureCapabilityGateV1=PASS` 时、首次允许
CAPTURE 前写入并绑定 installation/root、wire/security descriptor、erasure implementation
build/executor digest 与完整 synthetic kill/reboot evidence digest。执行时 current executor
必须与该 digest exact equality，或提供 owner-authenticated successor proof，证明新 executor
完整保留/收紧旧 receipt 的 delete-only contract；否则只能 whole reset。之后 consent 撤销、release
policy/freshness 离线、BudgetProfile/PlatformCertification/BuildAttestation 降级、低存储
或 normal FeatureStage 变 `SCHEMA_ONLY` 都不能撤销删除能力。它不允许 capture/write；
只授权既有数据的 fence/drain/tombstone/unlink/key destruction/census。

erase request 还必须携带 `LocalErasureControlPhaseGateV1` 冻结的不可伪造、单调/防重放
`ErasureRequestAuthorityV1`，来源只允许显式用户动作、owner-authenticated enterprise
policy 或已接受 retention policy；它绑定 installation、scope、previous cumulative root 与
request nonce/sequence。任意 Binder/process/caller enum 不能触发删除，重复 request 只能
幂等恢复同一 transaction。

如果 owner/root/key authorization/unlocked behavior/key-use/source binding 或 executor
continuity 已经损坏到不能安全做 source-selective erasure，runtime 不得假装
成功；必须保持 capture/egress blocked，并提供独立的 whole-Memory reset 路径，由明确用户
确认后删除整个 app Memory root/全部 installation aliases，再以 OS clear-data/uninstall
及外部 namespace census 收敛。该 fallback 不读取或迁移 plaintext，也不受 consent/budget/
freshness gate 阻断。它的 exact control wire/UX authority 在后续 phase 冻结；在此前只能
报告 `SELECTIVE_ERASURE_BLOCKED_WHOLE_RESET_REQUIRED`。UX 必须明确 OS clear-data/
uninstall 会同时删除 Sense 的**非 Memory**设置、词典/配置与其它 app-private state，不得
把它伪装成仅清 Memory。

Blob-backed selective erase 必须从 authenticated cumulative view/locator closure机械派生
`USES_BLOB=true`，并在每次 tombstone/unlink/lease drain 前验证 current
`BlobWireLocatorLeaseGateV1` authority；跨 writer 同时要求 source authority。locator/
lease gate 非 PASS、closure 不完整或 old physical 无法完成 census 时，不得错误返回
`REJECT_UNAUTHORIZED` 或关闭删除义务，而是对已接受的有效 request 返回
`WHOLE_RESET_REQUIRED`。whole-reset branch 永远不依赖 Blob gate。

`ErasureControlDecisionV1(ALLOW_SELECTIVE)` 不能直接调用 normal leaf ports。它只能铸造
sealed、delete-only `ErasureChildEffectDecisionV1`：

```text
ERASURE_JOURNAL_REWRITE_EXCLUDING_SCOPE
ERASURE_BLOB_REWRITE_EXCLUDING_SCOPE | ERASURE_BLOB_RETIRE
ERASURE_INDEX_REBUILD_EXCLUDING_SCOPE | ERASURE_INDEX_RETIRE
ERASURE_HOT_SNAPSHOT_REBUILD_EXCLUDING_SCOPE | ERASURE_HOT_SNAPSHOT_RETIRE
ERASURE_EGRESS_DRAIN | ERASURE_ALIAS_DESTROY_SCOPE
```

每个 decision绑定已接受 request authority、cumulative scope/root、fence、fixed cut、current
WriterSourceAuthorityManifest digest、exact resource/old-new physical identities、
byte/item/duplicate-reserve cap、one-shot ordinal与 operation validity vector。它只能删除、
drain或重写成“原 closure减去 erased scope”，不能 append/capture、扩大 source、复活
tombstone或产生未绑定派生物。跨 writer manifest缺失/损坏、source closure不完整或任一
typed child无法安全执行时，对已经接受的有效 request收敛
`WHOLE_RESET_REQUIRED`；不得在不完整 closure上宣称 selective commit。
`WHOLE_RESET_REQUIRED` 使用另一不可 cast 的 root-control token，不依赖这些 leaf ports。

JOURNAL_WRITE
+ SourceManifestPhaseGateV1
+ TransferOutcomeConservationGateV1
+ EgressDrainEvidenceGateV1
+ CumulativeErasureViewGateV1
+ SourceErasureAuthorityGateV1
+ ExportAuthorizationGateV1
+ DerivedIndexAuthorityGateV1 (iff validated export discovery/read closure uses derived index)
  -> EXPORT_EGRESS

上述 consumer branch 全部 normative 导入 ADR 0017
`ErasureReadBindingV1(cumulative_view_root,erasure_fence_generation,fixed_cut_digest,
source_authority_digest)` 的 open+逐 page/Blob/byte-handoff revalidation；不是只在 stage
计算时看一次 gate。binding 变化必须停止旧 cursor/lease/PFD/MemoryFrame/export/
Provider handoff并返回 closed `INDETERMINATE` 或 `POLICY_RESTRICTED` outcome，不得混 cut。

M9 registry 只授权本地 recall/use 与显式 file export，**不授权**把 recalled Memory 交给
真实 Provider。`ProviderMemoryDisclosureGateV1` 虽已注册但当前 BLOCKED，且本版本没有
`PROVIDER_MEMORY_DISCLOSURE` capability/evidence matrix；因此
`OpenAiRequestFactory`/任何 Provider body builder 必须拒绝 MemoryFrame/source lineage。
后续必须先由 `M91-00D` 冻结
`ProviderMemoryDisclosureGrantV1` exact wire、其 required current
`MemoryUseGrantV1` binding以及 one-shot store/replay/revoke，再由
`M92-05`（或独立 Accepted ADR）在 `EventCapsule` 已冻结后增加 sealed
`PROVIDER_MEMORY_DISCLOSURE` capability、K/W/H/P-T-I/U(F)/W8 evidence branch；只有
`M92-06` 才可把它接入 Provider。每个 Provider operation在 admission原子 consume一个
parent disclosure grant；每次 body/retry再消费预签 schedule中的 one-shot
`ProviderAttemptDisclosureLeaseV1` 并重验 current `ErasureReadBindingV1`。redirect/rebind/
tenant/model/retention变化必须取得新 parent grant，不能伪装成 retry。不能用 WARM_RECALL
PASS、MemoryUse policy、EXPORT grant或 M91 的本地 MemoryFrame代替。

SHADOW_PLUS is an aggregate evidence/rollout capability:
  union(exact branch closure of
    JOURNAL_WRITE, BLOB_STORE, CAPTURE, WARM_RECALL, COLD_RECALL,
    HOT_SNAPSHOT, INDEX_REBUILD, MAINTENANCE, KEY_ROTATION,
    LOCAL_ERASURE, EXPORT_EGRESS)
  + NORMAL_PRODUCT_SHARED_V1
  + REAL_DATA_OVERLAY_V1
  -> SHADOW_PLUS

这里 union 对 LOCAL_ERASURE 只导入其 **evidence verdict/receipt prerequisite**，不把它
转换成 FeatureStage。Capture activation 必须先证明 erasure safety；capture 之后的
fail-safe deletion 不再反向依赖 Capture consent 或 normal shared gate 的当前 verdict。

exact signed APK bytes + digest/length committed in signer WORM ledger
+ external owner manifest/bundle bytes + digest/length committed in owner WORM ledger
+ sealed ReleaseArtifactIdentityDecisionV1 exact-binding those external bytes
+ ReleaseSigningAuthorityGateV1
+ for every advertised capability with stage != OFF:
     publicationMechanismPrerequisites(
       each advertised finite profile capability/stage/class/context)
     + if stage > SCHEMA_ONLY:
         accepted ProfileAcceptanceReceiptV1
         + PlatformCertificationV1
         + certification evidence that every required dynamic-authority wire/reducer/
           replay-revocation-negative suite is accepted for that capability
  -> PUBLISH_RELEASE
```

`SHADOW_PLUS` 不是一个可绕过底层 branch 的 API；reducer 对它返回上述 deterministic set
union。release-entry branch 不使用未注册的 `RELEASE_PUBLICATION` pseudo-node。schema-only
artifact 不被迫伪造 Memory Budget PASS；但 release metadata 一旦宣称某 capability 高于
SCHEMA_ONLY，就必须对 finite `K×W×H` profile contexts绑定并通过 exact publication
mechanism set。它只声明这个 build/device profile 的 **certified maximum**，绝不宣称尚未
发生的 normal installation/root/keyring/erasure receipt 已 PASS。发布与 StageSnapshot 都
拒绝 live consent、MemoryUse、disclosure、export、source/cumulative-erasure authority；
这些 dynamic gates只在每次 `admitOperation` observe/consume。StageSnapshot还必须等安装
完成后绑定 exact installation-static receipts。Git tag/Release 只能在两份 WORM commit、
dynamic-authority机制认证 evidence 与全部适用 publication mechanism gates后创建。

Gate scope是 closed、不可互 cast 的 `GateScopeKindV1`；45 个 GateId必须恰好出现一次：

```text
RELEASE_BUILD={
  WireCompatibilityGateV1,ReleaseSigningAuthorityGateV1,PlatformCertificationGateV1,
  BackupExclusionGateV1,BudgetProfileGateV1,
  BuildAttestationGateV1
}
PHASE_SCHEMA={
  ReleasePolicySemanticsPhaseGateV1,RootBootstrapControlPhaseGateV1,
  KeyringBootstrapControlPhaseGateV1,SourceManifestPhaseGateV1,
  RecordIdentityTombstonePhaseGateV1,RotationControlPhaseGateV1,
  LocalErasureControlPhaseGateV1,BudgetEvidenceWirePhaseGateV1,
  StageSnapshotAuthenticationPhaseGateV1
}
DEVICE_CAPABILITY={
  UnlockedKeyBehaviorGateV1,KeyUseSafetyGateV1,
  KeyringBootstrapCapabilityGateV1,JournalFrontierDurabilityGateV1,
  BlobWireLocatorLeaseGateV1,DerivedIndexAuthorityGateV1,
  HotSnapshotPlaintextExclusionGateV1,MaintenancePeakCapacityGateV1,
  RotationReceiptCommittedGateV1,LocalErasureCapabilityGateV1,EgressDrainEvidenceGateV1,
  RebootNamespaceCensusGateV1,TransferOutcomeConservationGateV1,
  StageSnapshotAuthenticationCapabilityGateV1
}
INSTALLATION_ROOT={
  ReleaseIdentityGateV1,ReleaseOwnerContinuityGateV1,DataRootContinuityGateV1,
  InstallationKeyringIdentityGateV1,KeyAuthorizationProfileGateV1,
  ErasureSafetyReceiptGateV1,
  StageRevocationFreshnessGateV1
}
DYNAMIC_OPERATION={
  CredentialUnlockedRuntimeGateV1,CapturePolicyConsentGateV1,
  MemoryUsePolicyGateV1,ProviderMemoryDisclosureGateV1,
  ExportAuthorizationGateV1,CumulativeErasureViewGateV1,
  SourceErasureAuthorityGateV1
}
SPECIAL_CANDIDATE={
  PreCertificationCandidateControlPhaseGateV1,
  SyntheticMeasurementControlPhaseGateV1
}
```

唯一机械函数：

```text
publicationMechanismPrerequisites(capability,stage,class,profile_context) =
  staticPrerequisites(capability,stage,class,profile_context)
    ∩ (RELEASE_BUILD ∪ PHASE_SCHEMA ∪ DEVICE_CAPABILITY)
  ∪ {ReleaseSigningAuthorityGateV1}

installationStaticPrerequisites(...) =
  staticPrerequisites(...)

operationDynamicPrerequisites(...) =
  dynamicPrerequisites(...)
```

publication input必须与 `publicationMechanismPrerequisites` exact set equality；它不得含
`INSTALLATION_ROOT` receipt。`ReleaseArtifactIdentityDecisionV1` 只由 signer/owner WORM
上的 exact cached APK与 sidecars计算，不是 GateVerdict，也不能 cast成 installed
`ReleaseIdentityGateV1`。StageSnapshot必须与
`installationStaticPrerequisites` exact set equality并绑定 installation/root scope；它不能
把 candidate receipt、certification summary或 publication PASS铸造成 installed receipt。
runtime effective stage恰为
`min(certified_build_max, installed_static_ceiling, operation_dynamic_admission)`。scope-kind
unknown、duplicate、跨 root/device/install replay或 candidate→installation cast都 fail
closed。

`ReleaseOwnerContinuityGateV1` 不吞并 `DataRootContinuityGateV1`、
`InstallationKeyringIdentityGateV1` 或四个 key safety gates；它们是独立证据和独立
reason。`AuthorityBootstrapPermitV1` 不是 GateId、FeatureStage 或 persistent-data
capability，只允许 ADR 0017 §8.4 的窄 owner bootstrap；它不能创建 alias/keyring/body。
keyring action 也不能因别名“看起来存在”绕过 future canonical intent/receipt recovery。
同样，`LocalErasureControlPhaseGateV1` 只说明 canonical authority/schema 可用，
`LocalErasureCapabilityEvidence` 还必须用 S8 证明 runtime。

`ReleaseSigningAuthorityGateV1` 只约束 release publication，不是已安装设备 runtime 或
real-data prerequisite；`PlatformCertificationGateV1` 才是实体设备 artifact/runtime 的
前提。

`SyntheticMeasurementPermitV1` 不是 GateId、FeatureStage、DARK 或产品 capability。future
`ExperimentalMeasurementConfigV1` 必须在任何 sample/结果可见前绑定 proposal、唯一 primary
run、exact attempts/seed/order、build/device/corpus、允许的 capability paths、per-volume
容量、并发/queue/batch、cleanup reserve 与 expiry；每个 cap 必须不大于 §3 protocol cap，
且 §5 peak/free-space checked preflight 必须 PASS。permit 只对 exact production candidate
applicationId/APK 内绑定的 candidate-only authority/root namespace和 known synthetic bytes
生效；separate applicationId只允许 companion harness。

planned W6/S7/S8 kill/restart/reboot 不关闭 permit：future control wire 必须在测试进程之外
durable 保存 signed permit、planned-kill schedule digest、exact completed/remaining
attempt ordinals、last accepted cut 与 resume token。重启后先重验 primary run/subject/
config/corpus/namespace/fence/kill-boundary，再从 exact remaining attempt 继续；结果不确定的
allocation/ordinal 必须 burn，不能重放成成功。只有 run COMPLETE、harness explicit
revocation/cancel、external monotonic expiry、subject mismatch 或 unplanned integrity
failure 才永久关闭 permit并执行 external census。不得用 wall clock、mtime 或 app 内存
boolean 判断 expiry/resume；exact wire 当前仍由
`SyntheticMeasurementControlPhaseGateV1=BLOCKED`。

`CANDIDATE_PERSISTENT_SUBSTRATE` **不是 permit签发前置**；它只在 joint-start 的 S0
authority/owner/keyring bootstrap成功后，作为同一 permit剩余 S1–S8 admission 的前置，
并传递地证明 candidate root/keyring/key-use safety。permit 的 external
containment 必须能在被测 local-erasure runtime 完全失败时，仍由测试进程之外执行 dedicated
device 上 production applicationId clear-data/uninstall、Keystore alias negative census 与 filesystem namespace
zero census。`capabilities_under_test` 中的 Platform/Journal/Blob/rotation/erasure/transfer/
freshness/budget result gates **都不是 permit prerequisites**；它们可以输出 FAIL 或
INCONCLUSIVE，这正是实验要观察的结果。

permit 不得读取 IME/editor/clipboard/真实 account 数据，不得访问 production root，不得把
byte 交给真实/远端 Provider，不得产生 `BudgetProfileGateV1=PASS` 或 stage 晋级。W8
egress-drain 只能让 production coordinator 与 production HTTP/socket adapter 的 exact
cancel/close/late-callback path 接 attested deterministic **loopback adversarial**
`SyntheticProviderTransportV1`（no Internet、known corpus、no retention）；in-process fake
只能补充 unit test，不能满足 socket/fd gate。它证明本地 operation registry 与 fence，
不证明远端删除。
ordinary CapturePolicy 仍因 budget 非 PASS 而 DENY；measurement path 只能消费 ADR 0017
§12.3 的 typed `SyntheticMeasurementAdmissionV1`，并在每个 allocation/handoff 重验
permit/namespace/corpus/fence。admission type 不能进入 normal capture API，但其后必须运行
与 production 相同的 writer/codec/frontier/locator/Ack implementation；lab 内部产生的
byte-exact BlobRef/Ack 只可作为 permit-scoped evidence，不能进入 normal recall/UI/
production root、计为 product DurableAck 或直接让产品 gate PASS。
calibration 与 confirmatory run 各需独立 permit。任何 prerequisite 非 PASS 都只返回
`NOT_RUN_BLOCKED`；不得用硬编码临时 profile 绕过 control phase。当前
`SyntheticMeasurementControlPhaseGateV1=BLOCKED`，所以 measurement path 也不可执行。
permit preflight 只对预承诺 `ExperimentalMeasurementConfigV1` 和实测 volume 求
checked peak/free-space safety；它不是 `MaintenancePeakCapacityGateV1` verdict，不得反向
要求 `BudgetProfileGateV1`，也不能复用为产品 capacity PASS。

### 14.3 唯一 stage prerequisite reducer

`ADR0018_CAPABILITY_DAG_V1.staticPrerequisites` 先递归展开 §14.2 的 capability branch，
再按下面 closed sets 取 union；这才是机器实现的唯一 authority。

```text
NORMAL_PRODUCT_SHARED_V1 = {
  WireCompatibilityGateV1,
  ReleaseIdentityGateV1,
  ReleasePolicySemanticsPhaseGateV1,
  ReleaseOwnerContinuityGateV1,
  PlatformCertificationGateV1,
  BackupExclusionGateV1,
  LocalErasureControlPhaseGateV1,
  LocalErasureCapabilityGateV1,
  BudgetProfileGateV1,
  BudgetEvidenceWirePhaseGateV1,
  BuildAttestationGateV1,
  StageSnapshotAuthenticationPhaseGateV1,
  StageSnapshotAuthenticationCapabilityGateV1,
  CredentialUnlockedRuntimeGateV1
}

REAL_DATA_OVERLAY_V1 = {
  StageRevocationFreshnessGateV1
}
```

gate evaluation scope 也是 closed registry：

```text
OPERATION_DYNAMIC_GATE_IDS_V1 = {
  CredentialUnlockedRuntimeGateV1,
  CapturePolicyConsentGateV1,
  MemoryUsePolicyGateV1,
  ProviderMemoryDisclosureGateV1,
  ExportAuthorizationGateV1,
  CumulativeErasureViewGateV1,
  SourceErasureAuthorityGateV1
}

DynamicAuthorityModeV1:
  OBSERVE_AND_REVALIDATE = {
    CredentialUnlockedRuntimeGateV1,
    CapturePolicyConsentGateV1,
    CumulativeErasureViewGateV1,
    SourceErasureAuthorityGateV1
  }
  CONSUME_ONE_SHOT = {
    MemoryUsePolicyGateV1,
    ProviderMemoryDisclosureGateV1,
    ExportAuthorizationGateV1
  }
```

normal exact prerequisite 中除上述集合外的 GateId 都是 `STATIC_SNAPSHOT`；release-entry、
candidate/keyring/erasure/measurement special reducers仍用各自 sealed decision，不能塞入
normal snapshot。static gate PASS证明 phase/build/device/profile/capability evidence；dynamic
gate必须在**每次 operation**消费 current unlock/consent/use/disclosure/export/source/
cumulative-erasure authority。两层均须 exact set equality，不能因 static snapshot里曾有
PASS就跳过 one-shot/revocation/replay检查。

三个函数的唯一机械定义是：

```text
fullPrerequisites(capability,stage,class,profile_context) =
  closure(capability branch + applicable shared/real-data/conditional overlays)

staticPrerequisites(...) =
  fullPrerequisites(...) \ OPERATION_DYNAMIC_GATE_IDS_V1

dynamicProfilePrerequisites(...) =
  fullPrerequisites(...) ∩ OPERATION_DYNAMIC_GATE_IDS_V1
```

`dynamicPrerequisites(...,operation_provenance,operation_context)` 先证明 concrete operation
是 profile context合法 instance，再恰好返回 `dynamicProfilePrerequisites` 对应的 scoped
authorities；operation closure若实际需要更严格 flags/source shape，instance proof直接拒绝，
不得临时加 gate洗掉错误 profile。对每个 normal capability×stage×三值 class×finite
K/W/H context，必须证明 static/dynamic disjoint且 union byte-equal full closure。stage
snapshot/publication只接受 static exact set；`admitOperation`只接受 dynamic exact set；
missing/extra/cross-scope或把 live dynamic verdict放入 static receipt均 schema reject。

reducer 规则：

1. `OFF` 的 prerequisite set 为空且 unconditional effective stage=OFF；`SCHEMA_ONLY` 只可
   请求 `SCHEMA_CODEC`，不得创建 persistent substrate；
2. `MEASUREMENT_ONLY` 不返回 FeatureStage prerequisites；它只验证 §14.2 的 exact
   `SyntheticMeasurementPermitV1` prerequisites，`capabilities_under_test` 仍是输出；
3. `LOCAL_ERASURE` 不进入本 reducer；它只走 §14.2 sealed
   `ErasureControlDecisionV1`，不得因 consent、budget、
   policy、platform/build 或 freshness 非 PASS 被关闭；
4. `PRODUCT_SYNTHETIC` 只允许 `requested_stage=DARK`，结果为
   `closure(capability branch) ∪ NORMAL_PRODUCT_SHARED_V1`；
5. `REAL_DATA` 对任何 `DARK/SHADOW/CANARY/DEFAULT` 都返回
   `closure(capability branch) ∪ NORMAL_PRODUCT_SHARED_V1 ∪ REAL_DATA_OVERLAY_V1`；
6. `SHADOW/CANARY/DEFAULT + PRODUCT_SYNTHETIC`、unknown class/bit 或缺失 context 一律
   invalid/fail closed；
7. 对任一 normal capability（`BLOB_STORE` 自身除外），finite
   `profile_context.flags.USES_BLOB=true` 都递归叠加完整 `BLOB_STORE` branch并 deterministic
   dedup；因此 Blob-backed recall/index/hot snapshot/maintenance/export 与 capture 一样
   不能漏 locator/lease/capacity/transfer gates。`LOCAL_ERASURE` 走上面的 fail-safe
   conditional rule，不能套 normal reducer；
   `CROSS_WRITER_SOURCE=true` 叠加 `SourceManifestPhaseGateV1`；false 时不得无条件添加，
   也不得由调用者省略实际发生的条件；
8. 对 K registry允许 index维度的 normal capability，
   `profile_context.flags.USES_DERIVED_INDEX=true` 恰好叠加
   `DerivedIndexAuthorityGateV1` 和 §14.3 对应 `INDEX_*` effects；false 时两者都不得出现。
   `INDEX_REBUILD` 的 registry只允许 true；精确 Journal cut recall 的 false branch不依赖
   current index健康度；
9. maintenance/retention、rotation、export 等其它边只由 §14.2 closed branch
   导入 `RecordIdentityTombstonePhaseGateV1`、source/lease/drain/census gates，不在 shared
   set 偷偷展开。

`ErasureControlDecisionV1` 恰有
`ALLOW_SELECTIVE | PENDING_USER_UNLOCK | WHOLE_RESET_REQUIRED | NO_MATCHING_DATA |
REJECT_UNAUTHORIZED`。
它只扩张 fence/删除，不授予任何 data-plane write。property/device tests 必须覆盖 consent
revoke、offline/stale freshness、profile INVALID/UNSET、platform/build gate 降级、磁盘低于
normal floor、normal stage SCHEMA_ONLY 与 Provider 不回应时，existing-data erasure 仍能
进入 selective flow或诚实 whole-reset fallback；任何 case 都不得恢复 capture。
伪造、stale、forked 或不属于已接受 transaction 的 request authority 一律
`REJECT_UNAUTHORIZED`，零 fence/state mutation、零 reset prompt；exact replay of an
already accepted request 只幂等恢复同一 transaction。只有**有效 authority 已被接受后**
发现 control/root/key/executor continuity 不能安全 selective erase，才
`WHOLE_RESET_REQUIRED`。任何有效删除义务都不能落入一个把所有删除方式关闭的 terminal。

`EvidenceCapabilityIdV1` 包含 §13 的 14 个 evidence token，其中 `SHADOW_PLUS` 是 aggregate。
normal finite-profile stage/evidence reducer使用 sealed `NormalProfileCapabilityIdV1`：

```text
SCHEMA_CODEC | CAPTURE | WARM_RECALL | COLD_RECALL | HOT_SNAPSHOT | INDEX_REBUILD |
MAINTENANCE | KEY_ROTATION | EXPORT_EGRESS
```

真正可提交 operation 的 API使用
`NormalOperationCapabilityIdV1 = NormalProfileCapabilityIdV1 - {SCHEMA_CODEC}`；
`SCHEMA_CODEC`只允许无副作用 SCHEMA_ONLY，不产生 operation。`SHADOW_PLUS` 属独立 sealed
`RolloutAggregateCapabilityIdV1`，只表示 §13
的 deterministic aggregate ceiling/evidence set，不是 profile或operation capability。
`reduceNormalStage`、`admitOperation`、child token issuer 与任何 Binder/public API收到它都
必须 schema reject；只有 `reduceShadowPlusAggregate(all_concrete_profile_decisions)` 可处理。
调用者不能用“selected concrete parent”自由字段把 aggregate变成 wildcard。

`JOURNAL_WRITE`、`BLOB_STORE`、`KEYRING_BOOTSTRAP` 与 `PERSISTENT_SUBSTRATE` 是 internal/
control nodes，普通 Binder/public caller 不能把它们作为 top-level request 以绕过 parent
consent/source/erasure/policy closure。normal parent reducer 只能在 parent 全部 prerequisite
验证后铸造 one-shot `CapabilityChildDecisionV1`，绑定 parent request/capability、child
capability、execution provenance、context digest、DAG closure digest、snapshot generation
与 exact operation identity，以及 parent/projected-child context digests。DAG prerequisite
不等于可执行 child 的任意方法；decision 还必须绑定一个 closed `ChildEffectKindV1`：

```text
JOURNAL_APPEND | JOURNAL_READ_FIXED_CUT | JOURNAL_SCAN_FIXED_CUT | JOURNAL_REWRITE
BLOB_PUBLISH | BLOB_READ | BLOB_REWRITE_PHYSICAL | BLOB_RETIRE
INDEX_READ | INDEX_PUBLISH | INDEX_RETIRE
HOT_SNAPSHOT_READ | HOT_SNAPSHOT_PUBLISH | HOT_SNAPSHOT_RETIRE
RECALL_PAGE_HANDOFF_PFD
EXPORT_STAGING_WRITE | EXPORT_OUTPUT_HANDOFF | EGRESS_DRAIN
```

allowed set是 `applicableEffects(parent, profile_context)`，而不是对所有 profile无条件相同。
non-Blob base恰为：

```text
CAPTURE={JOURNAL_APPEND}
WARM_RECALL={JOURNAL_READ_FIXED_CUT,
             RECALL_PAGE_HANDOFF_PFD}
COLD_RECALL={JOURNAL_READ_FIXED_CUT,
             RECALL_PAGE_HANDOFF_PFD}
HOT_SNAPSHOT={JOURNAL_READ_FIXED_CUT,HOT_SNAPSHOT_READ,
              HOT_SNAPSHOT_PUBLISH,HOT_SNAPSHOT_RETIRE}
INDEX_REBUILD={JOURNAL_SCAN_FIXED_CUT,INDEX_PUBLISH}
MAINTENANCE={JOURNAL_REWRITE}
KEY_ROTATION={JOURNAL_REWRITE}
EXPORT_EGRESS={JOURNAL_READ_FIXED_CUT,
               EXPORT_STAGING_WRITE,EXPORT_OUTPUT_HANDOFF,EGRESS_DRAIN}
SCHEMA_CODEC={}
```

只有 `profile_context.USES_BLOB=true` 才按 parent叠加：

```text
CAPTURE={BLOB_PUBLISH}
WARM_RECALL={BLOB_READ}
COLD_RECALL={BLOB_READ}
HOT_SNAPSHOT={BLOB_READ}
INDEX_REBUILD={BLOB_READ}
MAINTENANCE={BLOB_READ,BLOB_REWRITE_PHYSICAL,BLOB_RETIRE}
KEY_ROTATION={BLOB_READ,BLOB_REWRITE_PHYSICAL,BLOB_RETIRE}
EXPORT_EGRESS={BLOB_READ}
```

只有 `profile_context.USES_DERIVED_INDEX=true` 才按 parent叠加：

```text
WARM_RECALL={INDEX_READ}
COLD_RECALL={INDEX_READ}
INDEX_REBUILD={INDEX_PUBLISH}
MAINTENANCE={INDEX_PUBLISH,INDEX_RETIRE}
KEY_ROTATION={INDEX_PUBLISH,INDEX_RETIRE}
EXPORT_EGRESS={INDEX_READ}
```

`INDEX_REBUILD` 的 K registry只含 `USES_DERIVED_INDEX=true`，所以上述 overlay恰为其
正常 base，不会重复授权。`USES_DERIVED_INDEX=false` 的 context 请求任一 `INDEX_*`
effect，必须在 index generation、cursor、path、open或 I/O 之前拒绝；也不能把一个损坏/
STALE index 的 receipt拿来把精确 Journal cut recall 误判成 unavailable。

`USES_BLOB=false` 的 C000/C010/C001/C011 context不得申请任何 `BLOB_*` effect；必须在 Blob ID、path、
reservation、DEK、open或 I/O之前因 operation-context instance mismatch拒绝。MAINTENANCE/
KEY_ROTATION 的 C010/C011同样不继承 `BLOB_STORE`；只有 C110/C111才递归叠加。对每个
parent×C000/C100/C010/C110/C001/C101/C011/C111，checker按其 K registry验证 applicable
exact set。

decision 同时绑定 exact resource/source/cut、method、byte/item cap、one-shot consume ordinal；
不同 effect 使用不可互 cast的 port token。child 接口只消费对应 authority。direct leaf token、
另一 parent/operation 重放、context contraction 或 extra child 都 fail closed。
对每个 applicable `(parent,effect,profile_context)`，mechanical checker必须证明 effect所需 GateId
是 parent full static+dynamic closure的子集，且 projected child context存在于 child K/W/H；
否则该 allowed edge本身使 descriptor INVALID。可选优化若需要不同 gate/context，必须提升
finite profile method/effect dimension，不能在 runtime临时选一个未认证 effect。
`BLOB_REWRITE_PHYSICAL` 还必须绑定 old/new physical identity、同一 logical Blob ID、
checked locator generation、duplicate/orphan peak reservation、old-reader lease/drain/census；
没有该 token 不得创建 rewrite temp 或推进 locator。`HOT_SNAPSHOT_*` 与 `INDEX_*` 的
resource class/port token 不可互 cast，即使 caller 提供相同 resource ID；wrong-resource-class
必须在任何 open/write 前拒绝。handoff token还绑定 destination class、exact fd/pipe/file
identity、byte/item cap、fixed cut、digest、terminal ledger与 current
`OperationValidityLeaseV1`；每个 chunk前重验。storage READ token本身无权把 plaintext
交给 Binder/PFD/export destination；ExportAuthorization必须在
`EXPORT_OUTPUT_HANDOFF`消费链上仍有效。future Provider body handoff使用另一不可互 cast
effect，当前 registry中不存在。
`SyntheticMeasurementAdmissionV1` 可在 permit 预承诺的 capability set 内通过独立 typed
measurement child decision测试 leaf，但它不能转换为 normal child decision。
`SCHEMA_CODEC` 是唯一 nonpersistent entrypoint，只允许 `requested_stage=SCHEMA_ONLY`；
以 DARK+ 请求它、或用它铸造 persistent child decision 都 schema reject。
negative tests 至少覆盖 recall token→Journal append、export token→Blob publish/retire、
capture token→read、maintenance token跨 parent重放、Blob rewrite无
`BLOB_REWRITE_PHYSICAL` 写 temp/locator、HOT_SNAPSHOT/INDEX port互换、aggregate
`SHADOW_PLUS` operation admission、READ token直送PFD/export、export staging token直送
Provider、handoff中途 fence/revoke、scope/byte cap扩张和一次 token二次消费。
另须逐 parent覆盖 `USES_BLOB=false/true × USES_DERIVED_INDEX=false/true` 的 exact-set
golden，并证明 Blob=false 的 caller即使伪造 BlobRef、资源 ID或 child token也不能在任何
open/ID前取得 `BLOB_*` effect，index=false 的 caller也不能在任何 cursor/open前取得
`INDEX_*` effect。

`KEYRING_BOOTSTRAP` evidence 只由
`KeyringBootstrapEvidenceDecisionV1(ALLOW|NOT_RUN_BLOCKED)` + one-shot permit 调用；
`LOCAL_ERASURE` 只由上述 `ErasureControlDecisionV1` 调用。这两个返回域、normal
FeatureStage、`GateVerdictV1` 与 `PermitDecisionV1` 均 sealed/disjoint；不存在
这两个 token 必须 schema reject，而不是返回某个 stage。

typed reducer 的唯一签名与合成规则是：

```text
NormalStageDecisionV1 reduceNormalStage(
  NormalProfileCapabilityIdV1 capability,
  FeatureStage requested,
  ProfileExecutionClassV1 execution_class,
  ProfileContextDigestV1 profile_context,
  FeatureStage build_profile_max,
  FeatureStage local_requested_stage,
  FeatureStage dependency_stage,
  Map<GateIdV1, GateVerdictV1> exact_gate_verdicts
)

all_pass =
  exact_gate_verdicts.keys ==
    staticPrerequisites(capability, requested, execution_class, profile_context)
  AND every required value is exactly PASS

prerequisite_stage_ceiling =
  requested if all_pass else min(requested, SCHEMA_ONLY)
effective_stage =
  min(build_profile_max, local_requested_stage, dependency_stage,
      prerequisite_stage_ceiling)

PermitDecisionV1 reduceMeasurementPermit(...) =
  ALLOW only if the exact §14.2 permit prerequisite set is present and every verdict is PASS
  else NOT_RUN_BLOCKED
```

static reducer只计算 profile ceiling，不授予副作用。每次 request随后调用：

```text
OperationAdmissionDecisionV1 admitOperation(
  ValidatedFeatureStageSnapshotV1 snapshot,
  NormalOperationCapabilityIdV1 capability,
  TrustedOperationProvenanceV1 operation_provenance,
  OperationContextV1 operation_context,
  DynamicAuthorizationBundleV1 exact_dynamic_authorities
)

require deriveProfileExecutionClass(operation_provenance) == snapshot execution class
require deriveProfileContext(operation_context) == snapshot profile context
require exact_dynamic_authorities.keys ==
        dynamicPrerequisites(capability, snapshot.effective_stage,
                             operation_provenance, operation_context)
require every authority current, authentic and in-scope
require CONSUME_ONE_SHOT authorities unconsumed, then atomically consume;
require OBSERVE_AND_REVALIDATE authorities bind current revision/fence;
require every static receipt fresh against its current monotonic authority;
ALLOW only after consume/revalidation and issuance of OperationValidityLeaseV1;
otherwise DENY | PENDING_USER_UNLOCK | BLOCKED
```

`ProfileExecutionClassV1`/profile provenance只表达 finite
`SCHEMA_ONLY|PRODUCT_SYNTHETIC|REAL_DATA` class与 authority class；`SCHEMA_ONLY` 只可配
SCHEMA_CODEC且不产生 operation effect。exact namespace/root/record/cut/cursor
taint进入 `TrustedOperationProvenanceV1`/operation digest。snapshot receipt不能绑定每次
Run/cut的 dynamic provenance，也不能缓存 consent/grant。operation decision绑定 snapshot
generation/profile digest、operation provenance/context、dynamic authority digest，并且只有
`ALLOW` 才能铸 §14.3 child effect token。`OperationValidityLeaseV1` 是一份不可延长的
authority-bound vector，而不是 wall-clock timeout；它逐项绑定：

```text
snapshot generation + every static receipt digest
every static receipt current monotonic frontier and accepted validity interval
every OBSERVE authority revision/fence and accepted validity interval
every consumed one-shot grant identity/consume ordinal
operation/profile/context/child-closure digests
```

不同 monotonic authority 不得错误地比较成一个 scalar `min`；有效区间取所有分量约束的
逻辑交集。admit 时必须从**当前** external monotonic authority观察并重验所有 static receipt
的 freshness/expiry/revocation，特别是 `StageRevocationFreshnessGateV1`，再签发上述
operation lease。每个 child effect、plaintext/ciphertext byte handoff、cursor page与 durable
commit boundary 前，都必须重新观察并同时重验 static receipt freshness、snapshot
generation、OBSERVE authorities和已消费 one-shot lease。任何分量越过 bound、receipt虽
bytes未变但已 expiry/stale/revoked、source/erasure fence advance、snapshot换代、wrong
profile instance或 one-shot replay，都必须在下一副作用前 contraction/cancel/BLOCKED；
不能只在 admission 检查，也不能等 watcher/file事件。长 operation 跨越任一 bound 的
property test 必须证明后续零 byte/零 commit。

`GateVerdictV1` 与 `FeatureStage` 是不相交的 sealed domains。normal reducer 只在所有
prerequisite 恰为 `PASS` 时产生一个 stage-valued ceiling；missing、unknown、duplicate、
extra gate 或 `BLOCKED/FAIL/INVALID/INCONCLUSIVE/UNSUPPORTED/MEASURED_NO_BUDGET` 均得到
`min(requested,SCHEMA_ONLY)`（requested OFF 仍为 OFF）。measurement reducer 只返回
`PermitDecisionV1`，从不返回或提升
FeatureStage。property test 必须穷举全部 gate verdict、stage 与 execution provenance，
证明没有 cast、ordinal 比较或 unknown fall-through，并证明对所有输入
`effective_stage <= min(requested,build_profile_max,local_requested_stage,dependency_stage)`；
特别覆盖 requested=OFF + missing/unknown gates 永远仍为 OFF。

`profile_execution_class` 不是调用者参数或可序列化 boolean。trusted
`ProfileExecutionClassAuthorityV1` 从 authenticated namespace/root provenance、
record/source taint 与 operation scope机械派生三值：

- `PRODUCT_SYNTHETIC` 只允许 owner-authenticated synthetic-only lab namespace，所有
  Journal/Blob/source records 均来自 synthetic product admission，且 external zero-real-data
  census PASS；
- 任一 production root、real record/source/Blob、unknown或 mixed provenance，以及触及
  mixed/root-wide history 的 recall/index/maintenance/erasure/export，强制为
  `REAL_DATA`；
- taint 随 Journal cut、BlobRef/locator lease、index row、snapshot、cursor/page/PFD/pipe 与
  transfer ledger传播；union 中 `REAL_DATA` 吞并其它 class，unknown fail closed。

caller/Binder/Provider 不能自报或降级 class。上述 exact taint wire/census/reducer 尚未冻结
时，`PRODUCT_SYNTHETIC` normal path BLOCKED；measurement permit 仍走自己的独立 authority。
`MeasurementExecutionAuthorityV1` 只来自不可 cast 的 typed permit/admission、exact
production candidate APK/applicationId + candidate-only root/run/corpus binding；它不属于
`ProfileExecutionClassAuthorityV1`，也不能传给 normal reducer。
测试必须覆盖 synthetic-label laundering、mixed root、stale cursor/Blob、Broker restart 与
跨 Binder 伪造 class。

`OperationContextV1` 同样不是 caller bits。trusted parser 从 validated payload/
operation/cut closure 机械派生 exact operation context，再由它派生 finite profile context：

```text
USES_BLOB = true iff closure transitively reads, writes, references, leases,
                   materializes or transfers any BlobRef/locator/physical Blob
CROSS_WRITER_SOURCE = true iff
  writer scope in IME|BRAIN|MAIN:
    authenticated source/cut closure contains any writer != scope,
    more than one distinct writer, or an imported/external source
  writer scope=NONE:
    source closure contains any local writer or an imported/external source
USES_DERIVED_INDEX = true iff closure transitively discovers through, reads, writes,
                          publishes, retires, references or transfers any derived-index
                          generation/row/cursor/receipt
```

小 body 也可以引用 Blob，不能只用 inline byte threshold 推导 false；oversize body 必须
强制 Blob 且 true。已知 exact session/cut 且闭包没有 index resource时必须机械派生
`USES_DERIVED_INDEX=false`，即使 current index instance缺失或损坏；反之，使用 index
discovery token/cursor 的路径必须为 true，不能在 cursor失效后把位清零并沿用其发现结果。
unknown/opaque schema、closure gap、mixed cursor或无法完整遍历 source
closure 一律取更严格 `USES_BLOB=true/USES_DERIVED_INDEX=true/REAL_DATA`，不能由 caller
清位。tests 覆盖 inline±1、
small-with-BlobRef、transitive Blob、oversize、same-writer、single foreign writer、
multi-writer、imported source、exact-cut no-index、index discovery/read/publish、STALE
index fallback与伪造 false；缺 conditional evidence 时只阻断该 exact
profile context，不得用 inline CAPTURE PASS 洗白。profile digest必须包含 writer-scope
kind/source shape；authenticated concrete writer identity/epoch/source authority必须进入
`OperationContextDigestV1`。二者都不能只 hash几个 boolean或用 actor/executor identity
替代。

`ReleaseSigningAuthorityGateV1` 永远不进入 installed runtime reducer，只属于 release-entry
branch。`StageRevocationFreshnessGateV1` 非 PASS 时最多允许 fully gated
`PRODUCT_SYNTHETIC/DARK`；它绝不允许 REAL_DATA DARK。

`ReleaseOwnerContinuityGateV1` 或 `LocalErasureControlPhaseGateV1` 任一非 PASS 时，
persistent branch closure 不成立，Memory root/Keystore/data plane blocked。其它 shared 或
branch prerequisite 非 PASS 时同样只对该 capability fail closed，不能以 boolean flag
提升。Budget 前唯一 persistent synthetic 路径仍是非 FeatureStage measurement permit。

### 14.4 `StageRevocationFreshnessGateV1`

ADR 0016/0017 中经 MAC/AEAD 认证的 data-plane pointer-free A/B 能检测随机单槽损坏和
不满足 authenticated predecessor 的多数局部回滚，但不能检测完整旧 snapshot pair。
ADR 0015 的无密钥 owner wrapper 还不能检测 old-valid single-slot replay。未来 rollout
ADR 必须冻结并实测一种 verified freshness authority：

- 受认证的外部/硬件单调证据；或
- 每次 boot 的显式、不可由旧 pair 自证的 reconfirmation。

在 authority 缺失、stale、offline、replayed 或无法验证时，gate 非 PASS。A/B generation、
mtime、wall clock 和“文件看起来更新”都不能替代。即使未来 owner/local-erasure 已 PASS，
freshness 未 PASS 时也最多 synthetic DARK；而**当前**
`LocalErasureControlPhaseGateV1=BLOCKED`，
所以 X02/Gate 0 hard maximum 是 SCHEMA_ONLY。

### 14.5 Source/erasure/rotation phase

ADR 0017 明确 `WriterSourceAuthorityManifestV1`、`ErasureManifestV1`、`ErasureReceiptV1` 与
`RotationControlV1` 尚未冻结。当前 verdict不由本节手抄子集充当 authority；§17
`G0-MECH` 生成的非权威 `Gate0StatusReport` 必须与 closed 45 GateId exact set equality。
其中 root/keyring、record/source/erasure/rotation、release/candidate/evidence/snapshot、
MemoryUse/ProviderDisclosure/Export、Budget/profile/platform/build/freshness相关 gate当前都
没有 operational PASS evidence；reducer必须按 closed registry与各 ADR authoritative
current-state assertions fail closed。CI report只校验/展示，不能作为 runtime输入 authority。
0018-B 不能在缺 S8、candidate special evidence或 StageSnapshot capability的情况下“先接受
部分真实数据预算”。

---

## 15. CI 与证据拓扑

这些 job 是后续实现要求；截至 Gate 0 **尚未创建/运行**，不得写成“已通过”。

### 15.1 Host/PR

- descriptor digest、99-field count、90/9 分类；
- fixed layout arithmetic、golden bytes、reserved registry；
- canonical JSON/JCS、reason ordering、verdict reducer property tests；
- decimal-string golden：`2^53-1`、`2^53`、`UINT64_MAX` 接受并精确 round-trip，
  `UINT64_MAX+1`、u32 overflow、前导零、正负号、指数、小数与 whitespace 拒绝；
- parser fuzz、checked u64 overflow、cap-before-allocation；
- Journal GCM usage arithmetic/golden：AAD 162、charge
  `12+ceil(ciphertext_length/16)`、65,536 frames、`2^24` blocks、边界±1 与
  Cipher.init-before-reserve negative；
- wrap alias 65,536 init cap、failed/indeterminate burn、purpose1/2 frontier 边界，以及
  p1=19/p2=27/p5=4097 blocks-per-init arithmetic；p2 aggregate 为
  1,769,472 `<2^21`；purpose5 缺 usage-control 时 Keyring bootstrap fail closed；
- BlobDEK AAD 83、charge `7+ceil(ciphertext_length/16)`、derived aggregate
  `4,308,992 < 2^23` 与 overflow negative；
- synthetic Ack ledgers 的 missing/duplicate/out-of-order；
- sample exact-membership golden：30/30 VALID可计算；29/30+1 INVALID、31中1 INVALID、
  100中70 INVALID、missing/duplicate ordinal均 INCONCLUSIVE且不得从 VALID子集统计；
  operation outcome FAILED/INDETERMINATE但采集与 ledger合法的 row仍为 VALID membership；
  warm-up全部有合法记录但不入 statistic，任一 warm-up missing/invalid或 replacement则
  INCONCLUSIVE；
- closed 45 GateId exact set、K×W×H cardinality/context projection、
  static/dynamic partition、NormalStage/NormalOperation set difference与
  `OperationValidityLeaseV1`长操作越界 property；
- candidate WORM state model：每条 adjacent transition、phase swap/skip/rollback/fork、
  same ordinal 1/2/100 concurrency、special/primary token cross-cast、root reuse、
  ScenarioDependencyDag cycle/missing predecessor与 joint-S0 crash resume；
- sidecar dependency DAG、profile group all-absent/all-present/half-present、acceptance receipt
  replay/fork/profile-only advance、M/S/A acceptance-only tree diff与 APK self-reference scan；
- publication输入 dynamic grant/verdict一律拒绝，dynamic-mechanism certification coverage
  缺一项即 BLOCKED；
- source/static scan：不允许真实 secret/plaintext fixture。

Host 结果只能证明 codec/reducer，不是 Android latency/energy/storage evidence。

### 15.2 Android emulator

- API 29、30、31、当前 API；
- locked/unlocked、process death、Binder death、low storage；
- A/B/locator/frontier kill matrix；
- candidate magic进入 normal runtime拒绝、joint-start相邻状态 crash resume、每个 destructive
  root terminal后的 external zero-census，以及 StageSnapshot auth/tamper/old-pair/watcher
  overflow；
- segment-DEK frame/block cap 达到时 normal seal/new-DEK；reserve/seal/crash 不确定时旧
  DEK 永久退休；
- noBackup/data-extraction manifest negative；
- 不出预算 PASS，不替代 OEM/Keystore/energy 设备。

### 15.3 Physical lab

- PIXEL_REFERENCE、HYPEROS、MIDRANGE、LOW_RAM；
- API 36.1 作为独立 suite branch；
- S0–S8、W0–W8、Perfetto/batterystats/storage evidence；
- reboot/locked boot/process restart、S8 local erasure/egress；
- calibration/confirmatory各自四 role × fresh candidate authorization/root；每个 role独立
  keyring-bootstrap kill special evidence、backup/D2D special evidence、两次 wipe/census与
  joint zero-root S0。calibration root/receipt不得进入 confirmatory；
- calibration/confirmatory 都覆盖 tiny/max frame mix，证明实测 segment 永不超过
  65,536 invocations 或 `2^24` authenticated blocks，BudgetProfile 只能更早 seal；
- 在 0018-E 接受后生成签名 `BudgetEvidenceBundleV1`；当前只能产出非权威研究 artifact。

### 15.4 Release planner

release planner 只消费 `BudgetEvidenceWirePhaseGateV1=PASS` 后已经验证的 bundle digest、
accepted `BudgetProfileSetV1/Classifier/ProfileAcceptanceReceiptV1`、
`PlatformCertificationV1` 与 final manifest。它必须从 signer/owner WORM复读同一 cached
APK/sidecar bytes，验证 byte equality、CandidateSubjectKey/M/S/A与无自引用；禁止从
workspace rebuild/resign。缺值、过期、
build/device 不匹配或任一 dependency 非 PASS 时，输出 BLOCKED，不能由 workflow input
手动覆盖。文档 PR/version 未变不得触发产品 Release。

---

## 16. 0018-B 接受流程

该流程使用外部 monotonic/WORM event frontier；wall clock 只作审计属性，不决定顺序、
expiry 或“最新”。唯一无环状态机是：

```text
SIGNED_APK_WORM
-> RELEASE_SUBJECT_WORM{ReleaseIdentity,BuildSubject}
-> CALIBRATION_CANDIDATE_AUTHORIZATION_SET[4]
-> CALIBRATION_SPECIAL_EVIDENCE_TERMINAL_SET[4]
-> CALIBRATION_PRIMARY_START_SET[4] -> CALIBRATION_TERMINAL_SET[4]
-> PROFILE_PROPOSAL_WORM{BudgetProfileSet,Classifier}
-> CONFIRMATORY_CANDIDATE_AUTHORIZATION_SET[4]
-> CONFIRMATORY_SPECIAL_EVIDENCE_TERMINAL_SET[4]
-> CONFIRMATORY_PRIMARY_START_SET[4] -> CONFIRMATORY_TERMINAL_SET[4]
-> PROFILE_ACCEPTANCE_RECEIPT_WORM
-> PLATFORM_CERTIFICATION_WORM
-> FINAL_OWNER_MANIFEST_WORM
-> PUBLISH_EXACT_CACHED_ARTIFACTS
```

每个 `*_SPECIAL_EVIDENCE_TERMINAL` 是 ordered nested submachine：
candidate authorization/decision →
`KeyringBootstrapEvidenceAttemptSet[N]`（每 kill case新 zero root/permit/terminal/wipe+
census）→ `CandidateBackupExclusionAttemptSet[M]`（每 restore/D2D case新 root/terminal/
wipe+census）。两个 set都在结果前封闭、不可 top-up。每个
`*_PRIMARY_START` 先在 app sandbox外 durable consume一份绑定 closed ordered
`PrimaryRootEpochPlanV1[N]` 的 `SyntheticMeasurementPermitV1`；再对每个 new zero root按序
原子消费
`CandidatePrimaryAuthorityBootstrapPermit + CandidateKeyringCommitPermit +
SyntheticMeasurementRootEpochPermit -> CandidatePrimaryRootEpochStartDecision`。每个 epoch
内部走 S0 authority/owner/keyring bootstrap→substrate→assigned scenario cases→terminal/
zero-census，全部 N terminal后才有 run terminal。任一 nested
state skip、swap、跨 root/phase receipt复用或 half-set都拒绝，不能把简写 set 当作省略
前置。

0018-E / candidate-control phase 必须冻结 canonical `CandidateSubjectIdentityV1` body 与
`CandidateSubjectKeyV1 = SHA256(exact body bytes)`。body 至少逐字节绑定：

```text
applicationId/versionCode/signed APK length+digest/signer index+lineage digest
ReleaseIdentity document digest + BuildSubject attestation digest
M commit+measurement-contract blob digest
S commit+tree
policy/SLO/evidence-contract digests
owner-monotonic candidate_chain_sequence
pre-result DeviceSuiteManifest/device-role/fingerprint plan digest
corpus/config/workload/case/fault/root-epoch plan digests
```

从 SIGNED_APK_WORM 到 publish 的 authorization、permit、run terminal、profile proposal、
acceptance receipt、certification 与 manifest 都必须绑定同一 key；BuildSubject body仍只描述
S/build/APK，不含 A/profile/certification/自身 digest。candidate ledger 只允许
`ABSENT -> COMMITTED`：same key/same ordinal/same bytes 幂等，same ordinal不同 bytes永久
FORK，phase跳跃、回退、half-set和失败/INVALID/INCONCLUSIVE terminal后同 authorization
top-up均拒绝。exact body/signature/caps未由 phase gate冻结前，这段只是 required semantics，
不能产生 candidate permit。
只抬 `candidate_chain_sequence`、换 run/device ID或重跑相同 failed subject不构成 material
change。在任何 authoritative sample可见前，preflight/schema错误可将旧 key终止为
`ABORTED_NO_SAMPLE`，并仅因预结果 contract/plan的 material change提交 successor。任一
sample/outcome一旦可见，同一 APK/implementation equivalence class永久不得靠换设备、OS、
fingerprint、plan、sequence、重签或 no-op version bump重试；意外环境变化使该链
INCONCLUSIVE。successor至少要求 new APK/version **以及**可审计的 implementation/contract
remediation diff，并保留 prior terminal lineage，防止 optional stopping。

1. 在任何 authoritative sample 前分别接受：ADR 0015 signer/owner WORM 与 release-policy
   evaluator；ADR 0017 root/keyring/local-erasure/tombstone、MemoryUse与Export control
   schemas；0018-E evidence/control/snapshot exact wire；`BudgetAcceptancePolicyV1`、
   `ProductSloEnvelopeV1`、closed workload/case/`FaultSchedulePortV1`。F022
   `VolumeChargeRuleV1` descriptor、caps/canonical/signature/unknown policy与 profile schema
   bump 也必须在此时完成；若曾用实验结果设计它，那些只标 exploratory C0，接受后必须从
   authoritative C1 calibration 重来，不能直接 confirm；

   M9 `capabilities_under_test` 不含真实 Provider handoff，因此
   `ProviderMemoryDisclosureGrantV1` 不是本轮 permit前置；它由后续
   `M91-00D -> M92-05 -> M92-06` 独立冻结、测量、认证，不能借 M9 profile PASS；
2. 冻结 M 与 S，构建并签名**预发布 production candidate**：production
   applicationId/versionCode/signer、release exact code path、normal 时 inert 的
   `FaultSchedulePortV1`。signer WORM 原子提交 exact APK length/digest/signer lineage；
   再提交 standalone ReleaseIdentity 与 `BuildSubjectIdentityV1/Attestation`。之后
   calibration、confirmatory、certification 与 final Release 只取这份 cached APK，不
   rebuild/re-sign；
3. 对四个 role/device 按 canonical role 顺序各自 owner 签名、单调提交
   `PreCertificationCandidateAuthorizationV1`，purpose 仅
   `CALIBRATION|CONFIRMATORY`，绑定 candidate sequence、ReleaseIdentity/APK/BuildSubject、
   exact device/role/primary run、M/S、policy/SLO/evidence/config/corpus/test-plan；
   calibration authorization 明确 profile=UNSET proposal，confirmatory authorization
   必须绑定 derived external profile/classifier，
   authority frontier 与 attempt namespace。它使用独立 magic/key/ledger，不是
   `ReleaseOwnerManifestV1`，不能进入 normal owner A/B 或 stage reducer；
4. 每个 pristine lab device 外部 census 证明 production app namespace/Keystore/process/fd/socket
   零状态后签发 one-shot `PreCertificationCandidateDecisionV1`；结果前 durable external
   ledger再冻结 `KeyringBootstrapEvidenceAttemptSetV1[N]`，每个 kill case分别持有
   pairwise-distinct `CandidateEvidenceAuthorityBootstrapPermitV1` 与
   `KeyringBootstrapEvidencePermitV1`/root epoch。token不能跨 wipe或转换为 primary token；
   crash只 exact resume同 attempt，set不能 reissue/top-up。每个 attempt terminal后都必须
   external wipe + zero census，不能把 kill residual当下一 case或 substrate；
5. 每个 device 的独立 pristine backup-exclusion special attempt 用同一 exact APK 验证
   static manifest、
   cloud/D2D/restore negative census。普通 `SyntheticMeasurementPermitV1` 不能把
   `BackupExclusionGateV1` 自证为 prerequisite；它只消费已经提交的 candidate-scoped
   receipt。restore attempt 后再次 external wipe + zero census；随后按结果前
   `PrimaryRootEpochPlanV1[N]` 为 primary run准备 N 个 pairwise-distinct zero-root epoch
   及各自未消费的 `CandidatePrimaryAuthorityBootstrapPermitV1`、
   `CandidateKeyringCommitPermitV1` 与
   `SyntheticMeasurementRootEpochPermitV1`，但尚不创建 owner control、Keyring 或
   `CANDIDATE_PERSISTENT_SUBSTRATE`；
6. 以四份 schema-correct calibration profile（threshold仍 UNSET；F022 等已接受 structural
   reference按新 schema固定）创建 proposal，在任何结果可见前绑定 full profile digest、
   policy/envelope、四个 pairwise-distinct unique primary runs、完整 S0–S8 tuple/case/
   attempt/root-epoch plan 与 exact ordered `SyntheticMeasurementPermitV1[4]`。对每个 device
   先在 app sandbox外 durable consume一份绑定全部 root epochs的 run permit；每个 epoch再由
   `CandidatePrimaryRootEpochStartDecisionV1` 原子绑定并 durable consume authority
   bootstrap、keyring commit与 root-epoch measurement三份 permit。每
   device/epoch/root/decision独立，aggregator 只有在四个 run terminal closed 后运行；
   permit 的 expiry/revoke/consume由 external monotonic frontier决定，不用 wall clock/mtime；
7. 执行四个 calibration primary runs：S0 是 joint decision 后的第一有序 case，在真正 zero
   root 内依次执行 candidate authority/owner bootstrap、first unlock/keyring commit并建立
   substrate，S1–S8 才能继续；任何 S0 前写 byte、S0 后补签 permit或伪造第二次 cold
   bootstrap都使 run INVALID。保留所有 raw/INVALID rows；profile verdict只能是
   `MEASURED_NO_BUDGET`。deterministic verifier按预签 policy机械推导完整 external
   `BudgetProfileSetV1`/classifier proposal，不能临时改 headroom、volume rules、schema、
   workload或 candidate；
8. 为 byte-identical APK 与 proposed external profile/classifier 提交新的 confirmatory
   candidate authorization/proposal/permit **ordered set[4]**；每个 device重新做 candidate
   decision、keyring/backup special evidence后的 wipe/census，并在 fresh root 创建
   joint primary decision，S0内才创建 candidate substrate，旧 calibration root/receipt不能
   直接复用。绑定四个新的唯一 primary runs，执行完整 S0–S8、
   kill/reboot/backup/erasure/egress physical suite，calibration row不进入 statistic，失败/
   INCONCLUSIVE/INVALID 不换 run择优；
9. verifier 从 confirmatory raw evidence重算 profile、公式、metric/capability verdict，
   审查 safety cap、S8 peak、capacity charge、Ack survivorship、erasure receipt 与
   stage-snapshot scope；提交不可重放 `ProfileAcceptanceReceiptV1`；
10. `PlatformCertificationV1` 原子绑定 final ReleaseIdentity、exact APK/BuildSubject、
    profile-set/classifier、M、policy/SLO、evidence/receipt与四 exact fingerprints。之后 final
    `ReleaseOwnerManifestV1` 以 canonical present tuples绑定这些 external sidecars并推进
    owner WORM；profile 不进入 ReleaseIdentity/release-policy digest，任何 profile改变仍
    强制新 acceptance receipt、certification 与 manifest revision；
11. publish planner 只从 signer/owner WORM 重新读取、rehash exact cached APK 与 sidecars；
    final docs/profile acceptance commit A 可以不同于 M/S，但绝不重新构建。tag/Release 前
    两套 WORM、certification、manifest、runtime prerequisite与 artifact equality全 PASS；
12. candidate root永不转 normal。每个 candidate terminal 后由 external clear-data/uninstall
    做 filesystem/Keystore/process/fd/socket/loopback zero census；normal runtime看到
    candidate magic/authority class必须拒绝/reset。失败实现需新 signed APK/versionCode 和
    完整新链；同 subject/run 的环境失败也不能以新 run ID补测；
13. 设备 fingerprint、OS、format、workload、安全/measurement contract、profile/classifier
    或任何 APK code改变时，重新走完整 candidate→calibration→proposal→confirmatory→
    certification→manifest→activation。后续 M91/M92/M93/M10 capability/code默认也使
    F006改变；没有另一个 Accepted component-inheritance ADR 时不得继承 M9 evidence。

`FaultSchedulePortV1` 的 closed hook IDs、single-use ordinal、plan digest与 external ledger
在结果前承诺；只有 candidate decision 可激活。normal/candidate decision缺失或 scope不符时
port必须不可发现/无副作用。candidate roots、permits和 attestation scope不能 cast为
FINAL_RUNTIME/PUBLICATION；从“已认证 N”生成 N+1 时 final N+1 manifest缺失是允许启动上述
candidate branch的预期状态，但普通 N+1 runtime仍 blocked。

run-level `SyntheticMeasurementPermitV1` 只在第一个 root epoch 之前消费一次；随后仅对
`PrimaryRootEpochPlanV1` 中**当前预承诺 epoch**，在该 epoch 的 reboot/locked-boot 之前由
app sandbox之外的 owner WORM ledger原子提交/consume
`CandidatePrimaryRootEpochStartDecisionV1` 及该 epoch 的 authority-bootstrap、keyring-
commit、measurement-root 三份 permit。后续 epoch 的 decision/permit 必须按 plan 顺序
just-in-time consume，不能在首 epoch 前一起消费，也不能跨 wipe/root复用。decision 不写入
待证明的 zero root。

stock Android不允许 shell 为 nonTestOnly APK切换单个 component；candidate control因此
**不得**依赖 `pm enable package/.Service`、run-as、Device Owner假设或 whole-package toggle。
唯一冻结的可实现 baseline 是：

```text
service.enabled=true
service.exported=true
service.directBootAware=false
service.process=:candidate_control
service.intentFilters=empty
service.permission=<unique candidate-control permission>
permission.protectionLevel=signature|knownSigner
permission.knownCerts=<exact separate companion signer pins>
MAX_CANDIDATE_CONTROL_PARCEL_BYTES_V1=8192
MAX_CANDIDATE_CONTROL_BODY_BYTES_V1=4096
```

companion 使用独立 applicationId/签名；device matrix逐项为
`API 29,30,31,32,33,34,35,36,36.1`。其中 API 31–36.1由
`signature|knownSigner`/`knownCerts` 在进程启动前阻断 wrong signer。API 29–30只保留 base
`signature`，因此 separate-signer companion必须 bind denied，candidate persistent phase为
`UNSUPPORTED/BLOCKED`，不得降级；这与该 API 范围的 persistent Memory SCHEMA_ONLY floor
一致；runtime在任何 decision parsing/effect前还必须硬判
`SDK_INT < 31 => TRANSPORT_UNSUPPORTED`，不能依赖 OEM是否意外授予 permission。
pinned signer必须专用于该 companion，certification对设备上持有该 signer/lineage的
package做 census；manifest permission只按 signer授权，不按 package授权，因此
same-pinned-cert/wrong-package可能启动 control process，随后必须被 Binder exact-package
检查拒绝。service显式可发现是已知 DoS surface，文档不谎称“不可发现”；wrong-signer
caller至多触发 OS permission检查，permission层授权但 package/decision无效的 caller至多
启动短命 dedicated control process。该 process
的 `Application` guard禁止初始化 main/IME/Brain/Memory/Provider/WorkManager/网络或
FaultSchedule；无合法 decision时零 app-created persistent Memory file、零 Keystore alias、
零 app network socket和零 data-plane副作用，不声称零 PID/Android runtime内部文件。

手写 raw Binder只接受 `QUERY_STATUS` 与 `DELIVER_DECISION` 两个同步 transaction，拒绝
oneway、FD、unknown/DUMP/SHELL code。它在 AIDL/domain unmarshal 之前按序验证
`Parcel.dataSize<=8192`、无 FD、calling UID/user、expected package ownership、无 shared-UID
歧义、`SigningInfo.apkContentsSigners` 恰一 current signer且其 DER SHA-256命中 current
config pin、fixed interface code、primitive fixed header、
body length `<=4096`、exact EOF/no trailing bytes；禁止 `Parcelable`、`Serializable`、
`Bundle`、`Intent`、`ClipData` 或自由对象图。之后才校验 owner signature以及
device/APK/run/root/epoch/ordinal/decision digest、unlock state与 external replay frontier，
再做 canonical domain decode。历史 signing lineage只作轮换 evidence，绝不授予当前
admission。caller不能自报 package/PID/WorkSource UID；service还必须忽略 bind Intent的
extras/data/categories。首次 bind/每 transaction还以 `PermissionInfo` 验证 custom
permission owner恰为 Sense、base protection恰为 SIGNATURE且 API31+有 KNOWN_SIGNER flag；
protocol冻结 `KNOWN_SIGNER_PROTECTION_BIT_V1=0x08000000`，只从 public
`protectionLevel/getProtectionFlags()`按位验证；禁止反射或 hidden API依赖。不符即拒绝。
`knownCerts` pin是 signer DER certificate SHA-256，不是 APK digest/key id，
由 exact APK manifest/aapt bytes与独立 `apksigner --print-certs` oracle验证
canonical/unique。`CandidateSubjectIdentityV1`/config绑定 custom
permission name/protection bits/known cert digests、service manifest/process descriptor、
companion package/current signer policy、controller attestation与 transport descriptor digest。

external delivery ledger 使用
`COMMITTED -> DELIVERING -> ACCEPTED -> STARTED -> TERMINAL`。controller在 uncertain reply
后先以 exact subject/run/root/epoch/ordinal/digest执行 `QUERY_STATUS`；ABSENT才重送同一
decision。same decision在 ACCEPTED/STARTED/TERMINAL 的 duplicate delivery只返回 current
durable/resumable receipt，绝不重执行；different digest/root/epoch/ordinal、stale/revoked
decision永久拒绝。ACK只能在 state可由 external ledger + candidate root/control reducer
精确重建后返回；任何首 root byte之前再次重验全部 binding与 unlock lease。

manifest/aapt/dumpsys static scan、API 29/30 bind-denied与 API 31–36.1 knownSigner grant、
wrong signer、same pinned cert wrong package、wrong user/profile/shared UID、permission predefinition/install order、signer
rotation、locked boot、`pm enable component` 必须失败且状态不变的 sentinel、oversize/
negative length/truncated/trailing/FD/object/oneway fuzz、concurrency/DoS资源界、lost-reply
query/resume、每个 decision transition kill点与 normal no-decision零 data-plane副作用，
缺一项时 phase gate BLOCKED。

candidate special evidence与 primary S0 不消费 normal
`CredentialUnlockedRuntimeGateV1`，也不得把 candidate observation cast成 normal gate。
first unlock 后只能为当前 run/root epoch签发不可互 cast的
`CandidateCredentialUnlockedLeaseV1`，绑定 candidate subject key、device、boot id、
unlock generation、root epoch、decision digest与 bounded operation kind。每次 alias/key
initialization、Cipher init、每段 byte写入、fsync/publish/commit和 receipt前都必须重验该
lease；relock/reboot立即失效并在下一 effect前停止。locked 返回
`PENDING_UNTIL_UNLOCK` 且零副作用；再次 unlock 只能按预承诺 kill/resume plan恢复同一已消费
epoch decision，不能 reissue、top-up或越过未完成 ordinal。该 lease不是 GateVerdict、
normal operation authority或 publication/snapshot输入。

不允许：

- 根据一台开发机填值；
- 把 0 当 UNSET；
- 只测平均值；
- 删除慢样本；
- 把 GitHub runner M0–M6 基准当手机功耗/RSS；
- 先发布 CANARY，后补擦除和容量证据。
- 用另一个被测 applicationId/debug signer替代 production candidate；
- 把 external profile/certification嵌进 APK造成 self-hash，或从 A rebuild；
- 把 candidate control/root/attestation当 normal owner state、normal stage或 publication
  evidence；
- permit clock rollback/forward、wrong-digest nonce replay、`STARTED`/terminal replay，
  或 consume 后 crash时重新签一份 attempt；仅 `DELIVERING` 丢 ACK 的 same-decision exact
  resume允许幂等。

---

## 17. Gate 0 mechanical acceptance

Gate 0 对本文的可机械断言：

- authoritative `GateIdV1` registry 恰有 45 个唯一 token；所有 branch/status/work-package
  引用都属于该 closed set，registry每项至少被一个 scope/branch引用，unknown/duplicate/
  missing均失败；
- 顶层 profile fields 恰好 99；
- F001–F009 恰好 9 个 FIXED/METHOD/BUILD references；
- F010–F099 恰好 90 个 `UNSET`；
- `supported_volume_capacity_rules` 明确存在且 UNSET；
- Blob cap 算式等于 67,895,896；
- Journal segment DEK hard cap 为 65,536 frames 与 `2^24` authenticated blocks，exact
  charge 是 `12+ceil(ciphertext_length/16)` 且在 Cipher.init 前 reserve；
- wrap alias hard cap 为 65,536 encryption initializations，purpose 5 usage-control wire
  缺失时 Keyring bootstrap 明确 BLOCKED；
- BlobDEK derived hard bound 为 16,384 invocations、4,308,992 authenticated blocks；
- peak 公式包含 soft cap、maintenance duplicate peak、residual orphan/temp；
- 每 volume 公式包含 capacity floor、minimum free 与 recovery reserve；
- Capture 与 Blob exact tuple registry 都要求 S8:W7 和/或 S8:W8；
- 90 个 profile fields 的 structural/enum/MAX/MIN partition、observed statistic、verdict
  validity、overall lattice 与 reason ordering 已冻结；
- Ack 的 FAILED/INDETERMINATE 不从 latency/ratio 中消失；
- precommitted exact attempt count、no top-up/no optional stopping 与 valid-count floor 已冻结；
- `BudgetAcceptancePolicyV1`/`ProductSloEnvelopeV1` 必须先于 calibration 绑定，90 个
  derivation closed/complete，policy 变化强制完整重跑；
- 每个 capability 的 finite `K(C)×W(C)×H(C)` cardinality、context projection与 required
  tuple count可重算；`ProfileContextDigestV1` 与 concrete
  `OperationContextDigestV1`分离，旧 two-bit `CapabilityRequestContextV1` 不得出现；
- `NormalProfileCapabilityIdV1`、`NormalOperationCapabilityIdV1` 与
  `RolloutAggregateCapabilityIdV1` 三个 set exact/disjoint；operation set只比 profile set
  少 `SCHEMA_CODEC`，aggregate恰为 `SHADOW_PLUS`；aggregate、keyring/erasure special token
  都不可 cast进 normal operation；
- static/dynamic GateId partition exact/disjoint/complete；StageSnapshot/publication只收
  `STATIC_SNAPSHOT` receipt，任何 live dynamic grant/verdict输入都 schema reject；每次
  operation exact observe/consume并在 effect/byte boundary重验
  `OperationValidityLeaseV1`；
- dependency DAG 包含 narrow owner/keyring authority bootstrap、candidate keyring/backup
  special evidence、joint zero-root S0 start、StageSnapshot phase+capability、
  MemoryUse/Export authority、record/source/tombstone、rotation与 erasure typed child
  effects、release policy/build/profile/acceptance receipt/certification/final manifest；
- candidate WORM状态、四 role calibration/confirmatory ordered sets、M/S/A、external
  sidecar无环依赖与 exact cached APK equality可机械验证；phase swap/skip、half-set、
  root reuse、candidate→normal cast和 self-reference均失败；
- Provider handoff capability在 M9 registry中不存在；
  `ProviderMemoryDisclosureGateV1` 当前只能是 BLOCKED，直到
  `M91-00D -> M92-05 -> M92-06`；
- `DeviceSuiteManifestV1`、`BudgetEvidenceBundleV1`、raw rows 与三个 ledgers 的 exact
  wire 未接受，`BudgetEvidenceWirePhaseGateV1=BLOCKED`；
- 0018-B 仍 Pending，本文未声称任何 Android budget test 已运行。

`G0-MECH` 必须把上述断言做成 repository executable checker，并生成非权威、CI-derived
`Gate0StatusReport`：closed registry 45 项 exact set equality、每项显式 closed
`GateVerdictV1`、reason文本与 evidence pointer。report没有签名/transition authority，
不得作为 runtime/release wire；authority仍是 closed registry与各 ADR 的 current-state
assertions，未来若要持久/分发状态须另行冻结 exact manifest。

---

## 18. 反例

- “48 KiB 是 Binder 安全 cap，所以 queue 也设 48 KiB”：协议上限不是设备预算；
- “平均 4 ms，所以 p95 一定可以”：分布和 tail 未被测量；
- 只对 DURABLE request 计时：FAILED/INDETERMINATE 被隐藏；
- HyperOS 结果沿用 Pixel：OEM Keystore、进程和存储路径不同；
- capacity 只看当前 40 MiB：compaction/rotation 同时保留新旧副本会越界；
- `F010 <= total capacity`：没有扣 minimum free/recovery reserve，也没按 volume；
- calibration p95 后临时写成 `p95*10`：没有预签 acceptance policy/SLO envelope，属于
  tautological PASS；
- Capture 不测 S8：擦除/维护时可继续分配旧 generation；
- local A/B generation 当 rollout freshness：完整旧 pair rollback 仍看起来 self-consistent；
- owner continuity 失败后生成新 keyring 并“继续”：会产生两个删除 authority；
- profile 缺值时把 capability 标 FAIL 后人工 override：VALID measurement 的正确 verdict 是
  `MEASURED_NO_BUDGET`，stage 仍 BLOCKED；
- invalid sample 重跑后删除：破坏失败率和环境可复核性；
- CI artifact 只有 summary、没有 raw rows：verifier 无法重算。

---

## 19. 当前结论

0018-A 只接受 profile JSON、metric/workload/tuple、统计停止规则、verdict/reason 与 DAG 的
semantic/mechanical contract。它**没有**接受可互操作 evidence artifact：两间实验室当前
仍可能把相同 semantic rows 序列化、签名为不同且无法互验的 bytes。

0018-E 未接受，因为 `DeviceSuiteManifestV1`、`BudgetEvidenceBundleV1`、raw rows 与三个
outcome ledgers 的 exact schema/encoding/caps/digest/signature trust wire 未冻结。0018-B
也未接受，因为 90 个 budget values 全部 `UNSET`，S8 phase schema/runtime 与实体设备证据
不存在。

因此当前：

- 文档/codec 工作可继续；
- CI-derived `Gate0StatusReport` 必须覆盖 closed registry 45/45；当前没有任何 operational
  PASS evidence，因此所有需要 PASS 才可授权的 phase/capability/runtime/publication gate
  都使用 closed `verdict=BLOCKED`（或 ADR 明确的其它非 PASS verdict），并把
  `UNPROVISIONED|ABSENT|NOT_RUN` 仅作为 reason，绝不能 cast成 GateVerdict。reducer一律视为
  非 PASS。关键新增阻断至少包括
  `PreCertificationCandidateControlPhaseGateV1`、
  `StageSnapshotAuthenticationPhaseGateV1`、
  `StageSnapshotAuthenticationCapabilityGateV1`、`MemoryUsePolicyGateV1`、
  `ProviderMemoryDisclosureGateV1`、`ExportAuthorizationGateV1`，但这不是手抄穷举表；
  只允许 SCHEMA_ONLY，measurement-only 与 persistent synthetic DARK均不可运行；
- 真实数据持久化和 SHADOW+ 仍严格 BLOCKED；
- 任何后续 PR 若声称“预算已通过”，必须先接受 0018-E，再同时提交 accepted profile、
  wire-valid raw attested evidence、verifier 输出与全部 dependency gate 证明。

---

## 20. 参考资料

- Android Developers, [Measure app performance with Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- Android Developers, [Perfetto](https://developer.android.com/tools/perfetto)
- Android Developers, [Memory Profiler / memory management overview](https://developer.android.com/topic/performance/memory-overview)
- Android Developers, [`Debug.MemoryInfo.getMemoryStat`](https://developer.android.com/reference/android/os/Debug.MemoryInfo#getMemoryStat(java.lang.String))
- Linux kernel, [`/proc/<pid>/smaps_rollup`](https://docs.kernel.org/filesystems/proc.html)
- Linux kernel, [block layer statistics](https://docs.kernel.org/block/stat.html)
- Android Developers, [App startup time](https://developer.android.com/topic/performance/vitals/launch-time)
- Android Developers, [Manage device storage](https://developer.android.com/training/data-storage/manage-storage)
- Android Developers, [Test power usage](https://developer.android.com/topic/performance/power/test-power)
- Android Developers, [`RoomDatabase.JournalMode`](https://developer.android.com/reference/androidx/room/RoomDatabase.JournalMode)
- SQLite, [Write-Ahead Logging](https://www.sqlite.org/wal.html)
- SQLite, [`PRAGMA journal_size_limit`](https://www.sqlite.org/pragma.html#pragma_journal_size_limit)
- NIST SP 800-90B, [Entropy Sources](https://csrc.nist.gov/pubs/sp/800/90/b/final)
- RFC 8785, [JSON Canonicalization Scheme（含大整数以 string 表达的互操作建议）](https://www.rfc-editor.org/rfc/rfc8785)
- Gil Tene, [How NOT to Measure Latency](https://www.infoq.com/presentations/latency-response-time/)
- Georges et al., [Statistically Rigorous Java Performance Evaluation](https://doi.org/10.1145/1297027.1297033)
