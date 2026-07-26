# ADR 0015：Release identity 与长期数据连续性

- 状态：**Accepted as Gate 0 policy；operational gates BLOCKED**
- 日期：2026-07-26
- 适用范围：Sense Android 的 production、canary、nightly、debug 与 benchmark 发行轨道
- 关联：
  [`Agent 工程开发方案`](../development/agent-engineering-plan-v1.0.md)、
  [`ADR 0016`](0016-m9-memory-wire-and-durability.md)、
  [`ADR 0017`](0017-m9-memory-security-and-erasure.md)、
  [`ADR 0018`](0018-m9-memory-budget.md)

## 1. 决策摘要

本 ADR 接受以下 **Gate 0 policy**，但不声称运行条件已经满足：

1. 稳定 `DataOwnerIdentityV1` 只由 release track、`applicationId` 与 Android signing
   lineage 的 oldest certificate anchor 定义；可演进 `ReleaseIdentityV1` 再绑定某一份
   exact signed APK、当前授权 lineage 与 release policy。版本名、Git tag、CI run、文件名
   和发布时间都不是身份。
2. production APK 只能由隔离的外部 signer service 签名。服务对
   `(applicationId, versionCode)` 建立不可变账本；首次成功后永久返回同一份、逐字节相同的
   signed APK，禁止再次签出不同结果。
3. signed APK 完成后，外部 owner ledger 才发布 `ReleaseOwnerManifestV1` 与其按
   digest/length 绑定的 immutable sidecar bundle；二者共同覆盖
   当前授权 signer lineage、完整 accepted `ReleaseIdentityV1`、已接受的最大
   `versionCode`/signed APK digest、release policy 和平台认证。设备只接受可验证的单调
   前进，并允许从旧状态直接验证跨版本、跨一轮或多轮证书旋转后的新状态。
4. 实体设备验证产生 `PlatformCertificationV1`。普通 CI 成功、APK 可安装或
   `apksigner verify` 成功都不能替代平台认证。
5. 设备端未来必须用经过单独 wire ADR 冻结的 owner-signed-inner A/B integrity cache 保存
   已接受 manifest；在此之前不实现临时格式，也不声明长期连续性。无密钥 wrapper
   CRC/digest 不是 authentication。
6. A/B 可以处理 torn write 与随机单槽损坏，但**不能检测攻击者重放任意仍有合法 owner
   signature 的旧 single slot 或完整旧 pair，并重算 wrapper generation/CRC/digest**。
   owner gate 只裁决 stable owner/signature/lineage 的 scoped authenticity，不宣称 globally
   latest；real-data 路径必须另过 `StageRevocationFreshnessGateV1`。

APK 内不得嵌入绑定该 APK 完整 signed bytes digest 的 ReleaseIdentity/Manifest/
Certification；否则会产生“asset 改变 APK digest、又必须重新签名”的自引用。APK 只内置
稳定 DataOwner identity、owner root SPKI pin 与不绑定 artifact digest 的 owner-ledger
discovery policy。release bundle 必须在 APK 签名后外部发布。

当前判定：

```text
ReleaseIdentityGateV1        = BLOCKED
ReleaseOwnerContinuityGateV1 = BLOCKED
ReleaseSigningAuthorityGateV1 = BLOCKED
PlatformCertificationGateV1   = BLOCKED
RootBootstrapControlPhaseGateV1 = BLOCKED
LocalErasureControlPhaseGateV1 = BLOCKED
LocalErasureCapabilityGateV1 = BLOCKED
ReleasePolicySemanticsPhaseGateV1 = BLOCKED
maximum_persistent_memory_stage = SCHEMA_ONLY
identity_gate_stage_authority   = NONE
```

当前 owner continuity 未通过，因此连 persistent synthetic product `DARK` 也禁止；只允许
不创建 Memory root/Key/record 的 schema、codec、fake、fuzz 与纯逻辑测试。identity gate
本身不授予 FeatureStage；未来 synthetic/lab product `DARK` 仍须满足 ADR 0018 对目标
capability 的完整 authoritative DAG。

## 2. 为什么必须先冻结身份

Android 接受覆盖更新至少要求：

- application ID 相同；
- signing certificate 相同，或新 APK 携带平台可验证的 proof-of-rotation；
- version code 不低于已安装版本。

只要其中任一条件失效，用户通常只能卸载后重装。卸载会删除 app-specific 文件、
preferences 与数据库；依赖应用 UID/Keystore 的密钥也不能被当作可继续使用。此时 Journal、
Blob、索引、Provider 配置和个性化词库即使还有残片，也不能被诚实地称作同一条长期记忆。

仓库当前 `benchmark` build type 使用 debug signing config，现有 Release 工作流又选择该
benchmark APK 作为发布资产。因此当前 `v0.4.2` 资产不构成本 ADR 的 production release
identity，也没有长期 Memory continuity 承诺；更早产物也不能在缺少逐份 certificate、
APK bytes 与 signer key evidence 时被自动追认。若某份历史 debug 私钥及其精确 lineage
不能恢复，就不存在把新 production key “旋转”为它的合法后继的密码学捷径。正确做法是：

1. 在任何真实 Memory capture 前建立首次稳定 production identity；
2. 把此前安装视为明确的一次性 reinstall boundary；
3. 不把旧本地数据静默收编为新 `DataOwnerIdentityV1` 的数据。

## 3. 规范用语与共同编码

文中的“必须 / MUST”“不得 / MUST NOT”“应 / SHOULD”具有规范含义。

所有 `*V1` canonical document 使用以下共同规则：

- 文档是严格 7-bit ASCII；无 BOM、NUL、CR、空行、注释、缩进或尾随空格；
- 每行是 `key=value`，只用一个字节 `0x0a` 结尾；文档也必须以 `0x0a` 结束；
- magic line 没有 `=`；字段必须按本 ADR 给出的顺序且恰好出现一次；
- 未知、重复、缺失、乱序字段一律拒绝；parser 不做 Unicode、大小写或空白规范化；
- `dec` 是 `0` 或 `[1-9][0-9]*`，禁止前导零；解析时使用 checked arithmetic；
- `u32` 范围为 `0..4294967295`，`u63` 范围为 `0..9223372036854775807`；
- Android `versionCode` 在本协议中范围为 `1..2100000000`；
- `h256` 是 64 个 lowercase hex，表示 32 个原始字节；
- `sig64` 是 128 个 lowercase hex，表示 `r || s`，每项各 32-byte big-endian；
- `ZERO256` 是 64 个字符 `0`，只可用于明确规定的 absent sentinel；
- `SHA256(x)` 指 SHA-256 对**精确原始 bytes**的摘要，不对内容做转码；
- `SHA256_EMPTY` 是
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`；
- `application_id` 必须匹配
  `[a-z][a-z0-9_]{0,62}(\.[a-z][a-z0-9_]{0,62})+`，总长不超过 255 bytes；
- `release_track` 只能是 `production`、`canary`、`nightly`、`debug` 或
  `benchmark`。

后文 grammar 中的 `<name>`、`...` 与 `{count-1}` 都是规范的元语法，不是文件 bytes。
有 count 的 repeated field 必须展开成从 0 到 count-1 的十进制索引行；索引本身也使用
canonical `dec`，所以不得写成 `00`。

Owner 文档统一用 `ECDSA_P256_SHA256_P1363_LOW_S`：

1. 公钥是 X.509 SubjectPublicKeyInfo DER，曲线只能是 NIST P-256；
2. `release_owner_root_spki_sha256 = SHA256(exact SPKI DER)`；
3. 签名输入是从 magic line 开始、到签名字段之前最后一个 LF 为止的精确 bytes；
4. 签名为 SHA-256 with ECDSA，编码为固定 64-byte IEEE P1363 `r || s`；
5. `1 <= r,s < n` 且 `s <= n/2`；不满足 low-S 的签名即使数学上可验证也拒绝；
6. verifier 必须从独立 provisioning 获得并 pin 精确 root SPKI bytes。不能信任文档内部自称
   的 SPKI digest。

私钥算法或 canonical encoding 的任何变化都需要新的 major schema；不能让 provider
默认值改变 V1 bytes。

## 4. 发行轨道必须隔离

目标映射如下；尚不存在的 build variant 必须在使用前显式实现：

| 轨道 | applicationId | Android signer lineage | DataOwner / owner root / ledger |
|---|---|---|---|
| production | `io.github.ethanbird.senseime` | production only | production only |
| canary | `io.github.ethanbird.senseime.canary` | canary only | canary only |
| nightly | `io.github.ethanbird.senseime.nightly` | nightly only | nightly only |
| debug | `io.github.ethanbird.senseime.debug` | debug only | debug only |
| benchmark | `io.github.ethanbird.senseime.benchmark` | benchmark only | benchmark only |

规则：

- 不同轨道不得复用 Android signing private key、owner private key、DataOwner identity/
  oldest signer anchor、ledger namespace、Keystore alias 或 Memory 目录；
- production 不得安装 debug/benchmark 签名的同 application ID APK；
- 不使用 `sharedUserId`，不靠同证书 UID sharing 跨轨道读数据；
- benchmark 若暂时不能使用独立 ID，只能作为 CI artifact，禁止发布和安装到保存真实
  production 数据的设备；
- 跨轨道迁移只能是用户显式导出/导入，并受独立格式、授权、审计和 erasure 规则约束。

## 5. `DataOwnerIdentityV1`

### 5.1 语义

Data owner 的 canonical identity 恰由三项组成：

```text
(release_track, application_id, oldest_android_signer_cert_sha256)
```

它不含时间、设备、Git SHA、当前版本、当前 signer、owner service key 或用户数据。
`oldest_android_signer_cert_sha256` 是该 track 合法 lineage 的 index 0 anchor；APK version
jump 和在其后的合法 signer rotation 不改变 data owner。不同 track 必须使用不同
application ID 与 oldest anchor，因此不能把随机本地 ID、可变 policy 或 owner service
重部署偷偷变成数据所有权。

### 5.2 Exact canonical grammar

```text
sense.data.owner.identity.v1
release_track=<release_track>
application_id=<application_id>
oldest_android_signer_cert_sha256=<h256>
owner_signature_p1363=<sig64>
```

`oldest_android_signer_cert_sha256` 是初始 Android app signing certificate 的完整 X.509
DER 摘要；后续 rotation 不改变它。`owner_signature_p1363` 按第 3 节由独立 provisioning
pin 的 owner root 签名此前全部行；owner root SPKI 不进入 identity body，避免把签名服务
实现细节混成 data owner。

identity body 是从 magic line 到
`oldest_android_signer_cert_sha256` 行末 LF 为止的 exact bytes；签名是认证证明，不是 owner
语义的一部分：

```text
data_owner_identity_sha256 =
    SHA256(exact DataOwnerIdentityV1 identity body bytes)
data_owner_signed_document_sha256 =
    SHA256(exact complete DataOwnerIdentityV1 bytes including signature)
```

该 exact file 必须进入离线归档、signer service trust store、source-controlled public
pin asset 和 release evidence。相同 body 的合法 ECDSA 签名即使 bytes 不同，也不能创建
第二个 owner namespace；owner service 对 `data_owner_identity_sha256` WORM 绑定首次接受的
exact signed document，后续不同 document digest 是 fork。

## 6. `ReleaseIdentityV1`

`ReleaseIdentityV1` 是可演进的 release/artifact 身份。artifact、授权 lineage 或 policy
任一前进都会产生新的 exact bytes/digest；它不取代稳定 `DataOwnerIdentityV1`。

### 6.1 Exact canonical grammar

```text
sense.release.identity.v1
data_owner_identity_sha256=<h256>
application_id=<application_id>
release_track=<release_track>
artifact_version_code=<versionCode>
artifact_signed_apk_length=<u63>
artifact_signed_apk_sha256=<h256>
artifact_signer_index=<u32>
authorized_lineage_commitment_sha256=<h256>
release_policy_revision=<u63>
release_policy_sha256=<h256>
owner_signature_p1363=<sig64>
```

`application_id`、`release_track` 必须与已验证的 `DataOwnerIdentityV1` 对应字段
byte-for-byte 一致；
`data_owner_identity_sha256` 必须等于该 DataOwner identity body 的计算摘要，并且对应
signed document 必须通过 owner root 验签。
`artifact_version_code/length/digest` 必须绑定 signer WORM ledger 中的 exact cached signed
APK；`artifact_signer_index` 必须是授权 lineage 的有效 index，且该 lineage index 0 必须
等于 data owner 的 oldest signer anchor。policy revision 从 1 开始，同 revision 只允许同
digest。

release identity body 是从 magic line 到 `release_policy_sha256` 行末 LF 为止的 exact
bytes：

```text
release_identity_sha256 =
    SHA256(exact ReleaseIdentityV1 identity body bytes)
release_identity_document_sha256 =
    SHA256(exact complete ReleaseIdentityV1 bytes including signature)
```

owner ledger 对同一 `release_identity_sha256` WORM 绑定首次 exact document；重签得到另一
document digest 是 fork，不是第二个 release identity。

`ReleaseIdentityV1` 不包含构建时间或 Git ref；它必须随 version/artifact、授权 lineage 或
policy 前进而变化。platform certification 不嵌入其中，避免与“certification 绑定 release
identity digest”形成循环；certification 由 owner manifest 单独单调接受。Release identity
也不是 Android 平台签名校验的替代品；两者必须同时通过。

V1 只把 release policy 当作由 revision、exact length 与 digest 约束的 **opaque continuity
blob**：identity/owner validator 可以证明“接受了哪一份 exact policy bytes”及其单调性，
但不得把未知 JSON、Proto 或自由文本解释成 capability/stage 语义。任何 gate 若依赖 policy
内容，必须先由后续 ADR 冻结 closed schema、canonical encoding、字段上限、evaluator、
unknown-field 规则和测试向量，并使 `ReleasePolicySemanticsPhaseGateV1` PASS；在此之前该
gate 必须 BLOCKED，不能由实现自行发明 parser 或默认值。

## 7. Android signer lineage commitment

V1 只接受单 current signer 加 proof-of-rotation，不接受 Android 的 multi-signer package。
证书摘要始终按 original 到 newest-authorized 的顺序排列。

Canonical commitment body：

```text
sense.android.signer.lineage.v1
signer_count=<u32>
signer_0_cert_sha256=<h256>
...
signer_{count-1}_cert_sha256=<h256>
lineage_proof_length=<u63>
lineage_proof_sha256=<h256>
```

约束：

- `signer_count` 为 `1..32`，索引行连续且无缺口；
- index 0 必须等于 DataOwner identity 中的 `oldest_android_signer_cert_sha256`；
- 所有证书 digest 唯一；
- `lineage_proof_sha256 = SHA256(exact proof blob)`；
- 只有 count=1 时 proof blob 才可为空，此时 length=0、digest=`SHA256_EMPTY`；
- count>1 时 proof 必须非空，并由 Gate 固定版本的 Android `apksigner`/验证库证明每个新
  certificate 是前一个的合法 rotation 后继；
- `lineage_commitment_sha256 = SHA256(exact canonical commitment body)`。

proof blob 作为 immutable sidecar 按 digest 保存。V1 不重新解释、重编码或“优化”它。
设备观察到的 `SigningInfo.getSigningCertificateHistory()` 必须与 APK 实际使用的授权前缀一致；
返回 multi-signer、null、乱序或额外 certificate 都 fail closed。

## 8. `PlatformCertificationV1`

### 8.1 含义

这是 Sense 项目对**某一份精确 signed APK**做过真实平台验收的签名记录，不是 Google、
OEM 或 Android 官方认证。失败或未运行的矩阵只进入 evidence，不能生成 PASS certification。

### 8.2 Exact canonical grammar

```text
sense.platform.certification.v1
certification_sequence=<u63>
release_identity_sha256=<h256>
data_owner_identity_sha256=<h256>
signed_apk_version_code=<versionCode>
signed_apk_length=<u63>
signed_apk_sha256=<h256>
artifact_signer_index=<u32>
lineage_commitment_sha256=<h256>
build_subject_identity_sha256=<h256>
build_subject_attestation_revision=<u63>
build_subject_attestation_length=<u63>
build_subject_attestation_sha256=<h256>
budget_profile_set_revision=<u63>
budget_profile_set_length=<u63>
budget_profile_set_sha256=<h256>
device_role_classifier_revision=<u63>
device_role_classifier_length=<u63>
device_role_classifier_sha256=<h256>
profile_acceptance_receipt_revision=<u63>
profile_acceptance_receipt_length=<u63>
profile_acceptance_receipt_sha256=<h256>
measurement_contract_sha256=<h256>
budget_acceptance_policy_sha256=<h256>
product_slo_envelope_sha256=<h256>
certified_compile_sdk_major=<u32>
certified_compile_sdk_minor=<u32>
certified_target_sdk_major=<u32>
certified_min_sdk_major=<u32>
certified_platform_count=<u32>
certified_platform_0_sdk_major=<u32>
certified_platform_0_sdk_minor=<u32>
certified_platform_0_observed_sdk_int_full=<u32>
...
certified_platform_{count-1}_sdk_major=<u32>
certified_platform_{count-1}_sdk_minor=<u32>
certified_platform_{count-1}_observed_sdk_int_full=<u32>
device_matrix_sha256=<h256>
upgrade_matrix_sha256=<h256>
test_plan_sha256=<h256>
evidence_bundle_sha256=<h256>
verifier_toolchain_sha256=<h256>
result=PASS
owner_signature_p1363=<sig64>
```

约束：

- `certification_sequence >= 1`；
- platform count 为 `1..64`，`(sdk_major,sdk_minor)` 按 unsigned lexicographic
  严格递增且唯一；API `<36` 的 minor 必须为 `0`；
- compile 使用 `(certified_compile_sdk_major, certified_compile_sdk_minor)`；manifest
  min/target 仍是 major-only，并必须与 APK、源码门禁和 evidence 一致，且
  `min_sdk_major <= target_sdk_major`、
  `(target_sdk_major,0) <= (compile_sdk_major,compile_sdk_minor)`；
- API 36+ 必须读取 `Build.VERSION.SDK_INT_FULL`，再仅用
  `Build.getMajorSdkVersion()`/`Build.getMinorSdkVersion()` 得到 tuple；不得把当前整数编码
  当协议公式。`observed_sdk_int_full` 保存原始 observed evidence。API `<36` 因字段不可用，
  canonical observed value 为 `0`，tuple 来自 `SDK_INT` 且 minor=0；
- `artifact_signer_index` 必须小于由 `lineage_commitment_sha256` 指向的 signer count；
- APK length/digest/version、lineage commitment 和 identity 必须与被测 bytes 一致；
- build subject必须 present并指向 exact external owner-signed attestation，逐字节绑定该
  APK/applicationId/signer/contract。Memory profile group
  `(BudgetProfileSet,Classifier,ProfileAcceptanceReceipt)` 必须 all canonical absent或 all
  present；半 present拒绝。group absent时 measurement/acceptance/SLO digests全为 ZERO256，
  只可签基本 APK/platform certification且 Memory stage保持 SCHEMA_ONLY；group present时
  receipt精确绑定 profile/classifier、confirmatory evidence/verifier/M/policy/SLO，三份
  sidecar不得嵌入其绑定的 APK，任一 wrong APK/role/fingerprint/self-reference拒绝；
- device matrix 至少覆盖 policy 指定的低端/主流/OEM/最新 API 角色；
- upgrade matrix 至少覆盖最低仍受支持 predecessor、当前最大 predecessor、同 signer
  version jump，以及出现 rotation 时的一轮与多轮跳跃；
- test/evidence/toolchain 都是 immutable bundle 的精确 SHA-256；
- 只有所有 required case PASS，owner service 才签发该文档。

官方 Android 文档明确 `SDK_INT` 只记录 major，而 minor release 必须看 API 36 新增的
`SDK_INT_FULL`；`VERSION_CODES_FULL` 的数值编码是 implementation detail，未来可改变。
因此 36.0 与 36.1 必须是两个不同 tuple，不能都序列化为整数 `36`，也不能靠
`sdk_int_full / 100000` 自行解码。golden/negative test 覆盖 35.0、36.0、36.1、tuple
倒序/重复、原始 full 值与官方 helper 解码不一致，以及未知 minor fail closed。

```text
platform_certification_sha256 =
    SHA256(exact complete PlatformCertificationV1 bytes)
```

## 9. `ReleaseOwnerManifestV1`

### 9.1 Exact canonical grammar

```text
sense.release.owner.manifest.v1
data_owner_identity_sha256=<h256>
release_identity_length=<u63>
release_identity_sha256=<h256>
release_identity_document_sha256=<h256>
manifest_sequence=<u63>
authorized_signer_count=<u32>
authorized_signer_0_cert_sha256=<h256>
...
authorized_signer_{count-1}_cert_sha256=<h256>
authorized_lineage_proof_length=<u63>
authorized_lineage_proof_sha256=<h256>
authorized_lineage_commitment_sha256=<h256>
max_artifact_version_code=<versionCode>
max_artifact_signed_apk_length=<u63>
max_artifact_signed_apk_sha256=<h256>
max_artifact_signer_index=<u32>
release_policy_revision=<u63>
release_policy_length=<u63>
release_policy_sha256=<h256>
platform_certification_revision=<u63>
platform_certification_length=<u63>
platform_certification_sha256=<h256>
build_subject_attestation_revision=<u63>
build_subject_attestation_length=<u63>
build_subject_attestation_sha256=<h256>
budget_profile_set_revision=<u63>
budget_profile_set_length=<u63>
budget_profile_set_sha256=<h256>
device_role_classifier_revision=<u63>
device_role_classifier_length=<u63>
device_role_classifier_sha256=<h256>
profile_acceptance_receipt_revision=<u63>
profile_acceptance_receipt_length=<u63>
profile_acceptance_receipt_sha256=<h256>
memory_schema_generation=<u63>
owner_signature_p1363=<sig64>
```

manifest 是 canonical index，不把可变长度 proof/policy/certification bytes 内联为无限文档。
同一 release bundle 必须同时提供下列 exact immutable sidecars，且在解析任何内部语义前先
验证 manifest 的 length/digest：

```text
ReleaseIdentityV1 exact bytes
authorized lineage proof exact bytes
release policy exact bytes
PlatformCertificationV1 exact bytes（或 absent）
BuildSubjectAttestationV1 exact bytes（或 absent）
BudgetProfileSetV1 exact bytes（或 absent）
CertifiedDeviceRoleClassifierV1 exact bytes（或 absent）
ProfileAcceptanceReceiptV1 exact bytes（或 absent）
```

future store phase ADR 必须冻结每类 sidecar 的硬 cap、文件名/路径、publish/fsync 与全量复读
规则；installed APK bytes 不作为自己的 sidecar，而是从 `ApplicationInfo.sourceDir`（及
未来 phase 明确接受的 split paths）读取并计算 length/digest，再与 manifest 和外部 signer
WORM ledger 比较。该 ADR 未接受前 operational gate 继续 BLOCKED。

约束：

- identity digests 必须指向已验证、互相一致的 V1 identities；
- `release_identity_length/release_identity_document_sha256` 必须等于 exact
  `ReleaseIdentityV1` sidecar；`release_identity_sha256` 必须等于其 unsigned identity
  body digest，且其
  data-owner/application/track、artifact、lineage 与 policy 字段逐项等于 manifest；
- `manifest_sequence >= 1`；
- authorized signer 数量、证书、proof 和 commitment 必须满足第 7 节；
- `max_artifact_signer_index < authorized_signer_count`；
- max artifact 必须是 signer ledger 中已提交的精确 signed APK；
- `release_policy_revision >= 1`、length 大于 0，policy exact bytes 必须按 length/digest
  取得；
- certification `(revision,length,digest)` 可为 `(0,0,ZERO256)`，表示尚未认证且所有
  real-data operational gate 必须 BLOCKED；除此之外三者都必须非零，digest 不得为
  ZERO256，并指向有效 `PlatformCertificationV1`；revision 必须严格等于该 document 的
  `certification_sequence`；
- build-subject/profile-set/classifier/profile-acceptance-receipt各自使用同样 canonical
  absent-or-present triple：只有 `(0,0,ZERO256)` 表示 absent；present时三值都非零、
  revision与document一致、length/digest/signature/cross-binding全验证。任一 normal
  advertised capability stage `>SCHEMA_ONLY` 时四份都必须 present，并被 current non-absent
  certification exact绑定；不得寻找 APK内/default副本；
- non-absent certification始终要求 build subject present。Memory profile group
  profile-set/classifier/receipt必须 all absent或 all present；half-present拒绝。group absent
  只允许 manifest声明 Memory最高 SCHEMA_ONLY，group present时 receipt必须绑定另两份与
  exact evidence/contract/policy/SLO；
- `memory_schema_generation` 从 1 开始，只表示 release-wide 已接受的 wire/schema
  generation，不代替 schema、migration 或 stage phase gate；installation-local 的
  data/key/erasure generation 禁止进入这个 shared release manifest；
- signature 覆盖签名行之前的全部 canonical bytes。

```text
release_owner_manifest_sha256 =
    SHA256(exact complete ReleaseOwnerManifestV1 bytes)
```

Manifest 有意不包含时间。顺序来自 owner ledger 的 monotonic sequence、artifact
version、lineage prefix 和 policy/certification revision，而不是 wall clock。

## 10. 广义单调前进规则

给定本地已接受状态 `L` 和候选 `C`，validator 必须先完成 canonical parse、identity pin、
owner signature、lineage proof、artifact 与 certification 验证，再执行以下规则。任一 checked
comparison、sidecar 读取或验证失败都拒绝。

### 10.1 Idempotent same

- 若 `C.manifest_sequence == L.manifest_sequence`，只有**整份 manifest bytes 完全相同**
  才是幂等成功；
- 同 sequence 不同 bytes/digest 是 ledger fork，永久拒绝并报警；
- `C.manifest_sequence < L.manifest_sequence` 是 rollback，拒绝。

### 10.2 Sequence skip

新状态要求 `C.manifest_sequence > L.manifest_sequence`，但允许从 `s` 直接到 `s+k`。
设备不需要下载中间 manifests；central owner ledger 仍必须保存每一条。sequence 增大本身
不是语义前进，下面至少一项必须严格前进。

候选 exact `ReleaseIdentityV1` 必须先按 length/digest 取得并验证。若 artifact、authorized
lineage commitment 或 release policy 任一变化，候选 release identity digest 必须变化；
三者都相同时，release identity bytes/digest 必须保持相同。platform certification-only、
memory schema-generation-only advance 不要求伪造新 release identity。installation-local
data/key/erasure generation不属于 shared manifest wire，不能推动本 sequence；它们只由
future local `OwnerStateV1`/control reducer按各自 authority前进。

### 10.3 Lineage

- `L.authorized_signers` 必须是 `C.authorized_signers` 的完整 prefix；
- 相同 lineage 时 proof length/digest/commitment 必须完全相同；
- 扩展可一次追加一个或多个 certificate；候选的完整 proof 必须逐 hop 验证；
- 删除、替换、重排或分叉 certificate 一律拒绝。

这同时覆盖：

- **cert-only advance**：artifact 保持不变，只预先授权一个或多个后继 signer；
- **one-rotation skip**：旧设备直接接受追加一个 signer 的新 manifest/APK；
- **multi-rotation skip**：旧设备直接接受追加多个 signer 且 proof 全链有效的新 manifest/APK。

### 10.4 Max artifact

```text
C.max_artifact_version_code >= L.max_artifact_version_code
```

- 版本相同：signed APK length/digest 和 artifact signer index 必须完全相同；
- `N -> N+k`：`k >= 1`，允许版本号有空洞；候选 APK 必须存在于 immutable signer
  ledger，且其 application ID、version、exact bytes digest/length 都匹配；这是
  release-entry issuance proof。设备离线 transition 验证 owner-signed exact binding 与
  installed APK bytes，不在线查询 signer ledger；
- 新 artifact signer index 不得小于旧 index，且其平台报告 signing history 必须等于
  authorized lineage 的 prefix `[0..index]`；
- 版本更高却复用旧 signed APK digest 表示 bytes/version 自相矛盾或摘要碰撞，必须拒绝；
  版本相同而 digest 改变也始终是冲突。

### 10.5 Policy

```text
C.release_policy_revision >= L.release_policy_revision
```

- revision 相同则 policy digest 必须相同；
- revision 增大时允许 policy-only semantic advance，也允许与 version/cert/certification
  同时前进；已有 certification 时仍必须按 10.6 为新 ReleaseIdentity 重新认证；
- revision 回退、相同 revision 换 digest 或找不到 exact policy bytes 都拒绝。

### 10.6 Platform certification

```text
C.platform_certification_revision >= L.platform_certification_revision
```

- revision 相同则 certification digest 必须相同；
- revision 相同则 certification length 也必须相同；
- revision 增大时，新 certification 必须签名有效、sequence 等于 revision，并精确绑定
  candidate release identity、artifact 与 lineage；
- 若 `L` 与 `C` 的 certification 都是 canonical absent `(0,0,ZERO256)`，artifact、lineage
  或 policy 可以前进，但 `PlatformCertificationGateV1` 与所有 real-data stage 继续
  BLOCKED；
- 一旦 `L` 已接受 non-absent certification，candidate release identity digest 因
  artifact、lineage 或 policy 变化时，`C` certification 必须同时严格前进并绑定新
  identity；不得回到 absent，也不得沿用旧 certification；
- certification-only advance 允许用于补充更完整、但仍针对同一 artifact/lineage 的矩阵；
- `(0,0,ZERO256)` 可以过渡到有效认证，反向不允许。

build-subject/profile-set/classifier/profile-acceptance-receipt 四个 sidecar tuple 分别使用
相同 monotonic reducer：
revision不得下降；同 revision必须 length/digest byte-equal；revision前进必须是签名有效且
语义前进的 exact document；present后不得退 absent，同 revision different digest是 fork。
profile/classifier/receipt任一 tuple改变都必须作为 all-present coherent group前进，并同时
产生严格前进、精确绑定新 tuple 的
`PlatformCertificationV1` 与 `ReleaseOwnerManifestV1`，即使 signed APK/ReleaseIdentity
不变；profile-only advance不能沿用旧 certification。

这些 profile/classifier/receipt tuples 不进入 `ReleaseIdentityV1` 或 release-policy body，
避免 profile 含 F006/APK digest 时形成 hash cycle。它们只由 certification+final
manifest 外部绑定。
device local A/B cache验证 exact owner signature、tuple与 installed APK，local generation
只提供 scoped recovery，不自证 globally latest；缺失/rollback/fork使 normal Memory stage
SCHEMA_ONLY。

`PreCertificationCandidateAuthorizationV1` 是另一 magic/key namespace/owner WORM ledger 的
release-entry control document，不是 `ReleaseOwnerManifestV1`，不能写入 normal owner A/B、
不能满足本节 reducer或任何 normal FeatureStage。它只允许 ADR 0018 对 signer-WORM exact
production candidate在 pristine candidate root上执行 measurement。final certification/
manifest缺失不会阻断 candidate branch，但始终阻断该 candidate的 normal runtime；candidate
root/receipt永不原地转正。

### 10.7 Memory schema generation 与 local generation 隔离

```text
C.memory_schema_generation >= L.memory_schema_generation
```

同 generation 不携带另一份隐藏 state；schema generation 前进必须由对应 migration/schema
phase evidence 支持。local-erasure、rotation、source/tombstone 与 data/key generation 是
每个 installation 独立的 authenticated local state，必须绑定 local `installation_id`、
keyring/control root 与 receipt，不能由一台设备的 receipt 推进所有设备共享的 release
manifest，也不能由无 installation binding 的 owner field 证明本地 fence。

除 exact idempotent same 外，`lineage / max artifact / policy / certification /
memory schema generation` 至少一项必须严格前进。上述规则允许任意
合法组合，不把“证书轮换”和“版本发布”错误绑定为一对一。

## 11. 外部 signer service 与不可变账本

### 11.1 信任边界

production Android signing private key 与 release-owner private key：

- 只存在于受访问控制、审计、备份和恢复演练约束的 HSM/KMS/外部 signer；
- 不进入 GitHub repository、Actions secret、runner filesystem、Gradle properties、
  environment dump、命令行、日志、cache 或普通 artifact；
- GitHub Actions 通过受保护 environment 和严格 claim policy 的 OIDC 换取单 job、短寿命、
  最小权限凭证；
- pull request、fork、Dependabot、未保护 branch/tag 和可修改的第三方 action 无签名权限；
- signer 对授权 repository、workflow identity、commit、track、application ID 和 requested
  version 做独立验证，不能只相信 runner 传来的字符串。

owner root 可以由阈值/双人控制的 HSM 操作。若 root 或 Android lineage 的必要旧 key
丢失/泄露，服务必须冻结；本 ADR 不允许静默换根。

### 11.2 `SignedApkLedgerV1`

账本主键恰好是：

```text
(application_id, version_code)
```

首次签名请求必须携带 unsigned APK exact bytes、其 digest/length、track、signing config
digest、lineage commitment、source/evidence digest 和幂等 request id。服务必须：

1. 解包并独立验证 APK 内 application ID/version 与请求主键一致；
2. 拒绝 production 以外 key namespace、debuggable、错误 variant、错误 manifest 或
   `versionCode <= current_max` 的新签名；version gap 允许；
3. 在每个 application ID 上串行化首次提交；
4. 完成 APK signing、全量 `apksigner verify`、certificate history、digest/length 复核；
5. 在任何 signed bytes 离开服务前，把 request digest、config/lineage、exact signed APK
   bytes、signed digest/length 和审计 receipt 原子写入 append-only/WORM ledger；
6. commit 成功后才返回 signed APK。

对已存在主键：

- 请求元数据完全相同或仅请求 fetch 时，返回 ledger 缓存的**同一份 exact signed APK
  bytes**和原 receipt；
- unsigned digest、config、lineage 或任何被绑定输入不同，返回永久
  `VERSION_IDENTITY_CONFLICT`；
- 不重新运行签名，即使 signer 算法通常看似 deterministic；
- 不允许覆盖、删除、重绑或“修复”旧行。

并发请求由唯一约束和事务 compare-and-set 收敛。若进程在 commit 前死亡，不得外泄输出；
若 commit 后响应丢失，重试必须读回缓存。这样同一 `(applicationId,versionCode)` 不会因重试
出现两个 signed APK digest。

### 11.3 Owner ledger

`ReleaseIdentityV1`、`ReleaseOwnerManifestV1` 与 `PlatformCertificationV1` 也进入不可变
owner ledger，但只有实际具有 sequence 字段的 document 才进入 sequence namespace：

- `ReleaseOwnerManifestV1.manifest_sequence` 在
  `(MANIFEST, data_owner_identity_sha256)` 内唯一；
  `PlatformCertificationV1.certification_sequence` 在
  `(CERTIFICATION, data_owner_identity_sha256)` 内唯一；同 namespace/sequence 不同 digest
  是不可恢复 fork；
- `ReleaseIdentityV1` 没有 sequence 字段。它按
  `(data_owner_identity_sha256, release_identity_sha256)`/exact signed-document digest 做
  WORM 首签唯一绑定，并只能由 manifest 的 exact length/digest 引用；不得伪造 sequence
  或借 manifest sequence 重新签出不同 identity bytes；
- owner service 签发 manifest 时必须使用
  `manifest_sequence = checked_add(last_manifest_sequence, 1)`；设备可以因为未收到中间记录
  而观察到 `s -> s+k`，但 central ledger 本身不能跳号；
- policy 与 platform certification revision 是各自 authority 的单调编号，可以一次跳过
  一个或多个值；相同 revision 仍必须绑定相同 digest；
- 服务针对最后状态执行第 10 节 transition，再签名、commit、返回；
- central ledger 不删除中间状态；device 可以验证合法 skip；
- artifact publication、Git tag 与 Release 创建只能发生在 signed APK、certification 和
  external owner bundle 三者都已 durable commit 之后；
- release 页面只发布 ledger 中 exact cached APK，不能从 CI workspace 重新复制一个
  “等价”文件；owner bundle 作为独立 asset/endpoint object 发布，不写回 APK。

ADR 0018 的 source/build commit S 与结果后 acceptance/tag commit A 可以不同，但 publication
gate必须用 canonical tree walk证明 `diff(S,A)` 只触及结果前已预承诺的 docs、external
sidecar pointer与acceptance metadata paths。任一 Kotlin/Java/resources/Gradle/Manifest、
dependency lock、generated source、native binary、signing config或 workflow build logic
变化都使 A 不是 acceptance-only，必须新 S/APK/version/evidence链。校验基于 Git object
mode/path/blob/submodule/LFS pointer与 resolved generator inputs，不使用文件扩展名 allowlist；
rename、symlink、path case/Unicode别名、submodule/LFS/generated-file替换都拒绝。
release metadata 必须独立公开 immutable `BuildSubjectIdentityV1` 所绑定的 S 与 acceptance
commit A；BuildSubject 只描述 S/build/APK，绝不包含或预测 A。tag 指 A 不表示 asset 由 A
构建。

## 12. 设备端 continuity protocol

### 12.1 启动检查

在打开 Memory key、Journal、Blob 或 index 之前，进程必须：

1. 从 APK 只读资源读取 exact stable `DataOwnerIdentityV1`、owner root SPKI pin 与
   owner-ledger discovery policy；这些资源都不得包含当前 full signed APK digest；
2. 从 future local A/B state 读取 owner-signed inner external owner bundle cache；若它不覆盖
   当前 artifact，通过受控 resolver 取得外部 owner ledger 的 current
   `ReleaseIdentityV1`、`ReleaseOwnerManifestV1`、lineage proof、policy、
   `PlatformCertificationV1`、`BuildSubjectAttestationV1`、`BudgetProfileSetV1`、
   `CertifiedDeviceRoleClassifierV1` 与 `ProfileAcceptanceReceiptV1` exact bytes；canonical
   absent tuple 对应的 sidecar 不得请求或合成。网络/endpoint 只负责 transport，owner
   signatures 与 pins 才是 authority。即使 cache 覆盖 artifact，本地 wrapper 也只能证明
   完整性，不能证明该 slot/pair globally latest；
3. 逐字节 canonical parse、验证 owner signature、length/digest、sidecar all-or-none 约束和
   所有 cross-binding。若 Memory profile group present，还必须验证 acceptance receipt
   绑定 exact profile set、classifier、confirmatory evidence、measurement contract、
   acceptance policy 与 SLO envelope，并由 classifier 对 current installed build/device
   facts 唯一选择 exact fingerprint、role 与 profile digest；unknown、ambiguous、未认证
   fingerprint 或 selection 与 certification 不符都使 Memory profile blocked；
4. 读取 `PackageManager` 的 current signer/history，拒绝 multi-signer，并验证为 manifest
   授权 prefix；
5. 从 `ApplicationInfo.sourceDir` 读取当前 base APK exact bytes，验证 application ID、
   version、length/digest 与 owner-signed ReleaseIdentity/manifest 接受的 artifact 相符；
   WORM signer ledger 是 release-publication authority，不是设备离线时必须查询的服务；
   若未来引入 split APK/AAB，
   须先新增 phase ADR，V1 不猜测 split identity；
6. 从未来 A/B store 选择一个 locally valid cache candidate，再按第 10 节比较任何已取得的
   external candidate；只有 owner-signed inner、可观察 transition、持久化与复读验证后，
   owner gate 才能按其 scoped semantics 输出。是否足够打开 real-data Memory 还必须独立
   通过 `StageRevocationFreshnessGateV1`；
7. 分别计算 device-runtime 的 `ReleaseIdentityGateV1`、
   `ReleaseOwnerContinuityGateV1` 与 `PlatformCertificationGateV1`。identity/owner 的任何
   缺失、stale、fork、rollback、I/O、digest 或验证错误都 fail closed 到
   `SCHEMA_ONLY`；platform certification 非 PASS 时每个 normal FeatureStage persistent
   path（包括 PRODUCT_SYNTHETIC DARK）都 blocked。只有 ADR 0018 非 FeatureStage 的
   one-shot measurement/keyring-evidence permit 可在外部 containment 下运行并产生 candidate
   platform evidence，不能称为 synthetic DARK。
   `ReleaseSigningAuthorityGateV1` 只在发布入口计算，不作为设备在线依赖。只有本地
   cached bundle 不覆盖 current artifact、因而本次启动必须 fetch 时，resolver 不可达才是
   artifact/owner-binding failure；已经覆盖 current artifact 的 owner-signed inner cache
   不因 signer service 临时离线而失去 scoped authenticity，但其 freshness 仍是
   `UNPROVEN`。real-data path 不能因“APK 已由 Android 安装”或 cache 看似完整而跳过独立
   freshness gate。

首次安装只有在 Memory namespace 确认全空、Keystore/Journal/Blob/manifest 均无旧残留，
且外部 release bundle 覆盖已安装 APK并全部验证时，才能 bootstrap local owner state。
首次启动离线或 resolver 不可达时保持 `SCHEMA_ONLY`，不能先写数据后补 owner。发现数据但
没有可验证 owner state 时只能隔离并提示显式 reset，不能自动生成 owner identity 或默认
认领。

这里不能把“尚无 local owner state”直接解释为 owner continuity PASS，否则会形成
bootstrap 自证。未来 `RootBootstrapControlPhaseGateV1` 必须冻结一个一次性
`AuthorityBootstrapPermitV1`。它不是 FeatureStage，也不在
`SCHEMA_ONLY < DARK < ...` 的全序中；只有 current `ReleaseIdentityGateV1` 已验证外部
owner bundle/installed APK、root 与 alias census 全空，且
`LocalErasureControlPhaseGateV1=PASS` 时才可签发。permit 只允许创建固定 root
shell/bootstrap lock、
owner-signed local owner A/B 与必要 control metadata；不得创建 Keyring/data-generation
key、purpose 13、record、Journal、Blob、index、Provider egress 或读取正文。owner A/B
durable close/reopen 后，`ReleaseOwnerContinuityGateV1` 才可能 PASS，之后才进入独立
Keyring bootstrap gate。Gate 0 尚未冻结该 permit/wire，所以当前仍然连 root shell 都不
创建。

### 12.2 升级、降级、卸载与恢复

- 合法覆盖升级保留 Android sandbox 时，仍要通过 manifest monotonic check 才能打开数据；
- 通过 `adb -d` 等手段降级后，只要 installed artifact 对应的 external owner state
  max/sequence 低于本地已接受状态就拒绝 Memory；
- certificate mismatch 不尝试导出明文绕过 Android 安装规则；
- 卸载、清除数据、Keystore loss、owner identity mismatch 都终止本地 continuity；
- Auto Backup、D2D transfer 与 cloud backup 不是密钥/manifest 连续性的 authority。Memory、
  Keyring、Journal、Blob、locator、manifest 和 Provider secret 必须在 backup/data-extraction
  rules 中逐项排除，并在目标 OEM 实测；
- Android 12+ 某些 OEM 的 D2D 行为不能仅靠 `allowBackup=false` 推断，因此恢复后必须通过
  identity/key/manifest 联合检查；无法解密或缺 key 时 fail closed；
- 灾难恢复只恢复 signer/owner 服务和 immutable ledger，不承诺恢复用户已卸载的本地数据。

## 13. Future A/B wire gate

本 ADR 只冻结 continuity policy，**不冻结一个未经 kill-point 审核的本地 slot wire**。
`ReleaseOwnerStateV1` 的 exact bytes 必须在首个实现 PR 前由独立 phase ADR 冻结，并至少包含：

- wire major/minor、required-features；
- stable `data_owner_identity_sha256`；
- slot generation；
- exact complete owner manifest bytes、length 与 digest；
- exact accepted `ReleaseIdentityV1` bytes、length、identity-body digest 与 signed-document
  digest；
- exact accepted `PlatformCertificationV1` bytes、length 与 digest，或 canonical absent；
- exact lineage proof 与 release policy sidecar bytes、length 与 digest；
- exact accepted `BuildSubjectAttestationV1` bytes、length、revision/digest tuple 与
  `build_subject_identity_sha256`；
- exact accepted `BudgetProfileSetV1`、`CertifiedDeviceRoleClassifierV1` 与
  `ProfileAcceptanceReceiptV1` bytes、各自 length/revision/digest tuple，或三者共同
  canonical absent；present 时还须保存 runtime 唯一选择的 exact fingerprint、role 与
  profile document digest；
- accepted max artifact versionCode、signed APK length/digest 与 signer index；
- memory schema/data generation；
- owner-signed inner documents、wrapper digest/CRC、以及“slot wrapper 绝不替代 owner
  signature”的验证顺序；baseline owner bootstrap 不依赖尚未存在的 local Keyring key；
- selector 输出必须区分 `SCOPED_OWNER_AUTHENTICITY` 与
  `EXTERNAL_FRESHNESS_UNPROVEN`；无密钥 wrapper generation 不得填充后者；
- 固定 slot size、A/B 路径、UNUSED 编码、上限、selector、write/fsync/close/reopen/full-reread
  顺序；
- stable identity 与 mutable versioned state 的明确分离。

slot generation 只用于 torn-write/local write ordering，不能覆盖 owner-signed
`manifest_sequence` 的 authority。只有另一个 valid slot 或已验证 external candidate
提供比较基准时，wrapper generation 较高而 inner signed state 较旧才可拒绝；sole
old-valid slot 的 wrapper generation 可被重算，不能据此证明最新。这样 owner bootstrap
可先验证公开签名材料，再在 owner continuity PASS 后创建 Keyring，避免用尚未存在的 key
认证自己的创建许可。若未来选择专用 control-only local key，
必须由新的 phase ADR 单独授权 alias/profile/bootstrap/erasure，不得复用 data-generation
或 purpose 13 key。

最低协议要求：

1. 先写非 selected slot，fsync file 与 parent（如创建目录项），close/reopen 后全槽复读；
2. 只有 canonical wrapper、CRC/digest、inner owner signatures、identity 与 transition
   全通过才可选择新 generation；
3. 每次本地接受（包括从 central manifest sequence `s` 跳到 `s+k`）都只写
   `new_slot_generation = checked_add(selected_slot_generation, 1)`；本地 generation 不允许
   跳号，也不能复制 remote sequence。policy/certification/remote manifest revision 仍可按
   第 10 节单调跳跃；
4. 选择后再次 close/reopen 并全量复读 A/B；随机单槽损坏可以由另一槽恢复 scoped owner
   cache，但不能由此声称没有 adversarial old-slot replay；
5. 任何修复只向前写，不原地修改 selected slot；没有 external/rollback-resistant
   freshness 时 repair 后仍输出 `EXTERNAL_FRESHNESS_UNPROVEN`；
6. kill point 必须覆盖每个 write/fsync/reopen/selection 边界；
7. local slot wire、inner signature/transition 或 durable selection 未冻结/未通过时，
   `ReleaseOwnerContinuityGateV1` 必须 BLOCKED；external rollback freshness 是独立
   `StageRevocationFreshnessGateV1`，不得折叠进本 gate。

baseline owner A/B 只验证 owner-signed inner document 与无密钥 wrapper integrity，不要求
尚未存在的 local key，也不提供 local anti-replay。只有未来另行引入 control-only local
key/monotonic anchor 时，“独立 key purpose 与 bootstrap/erasure 已冻结”才可加强其
freshness；不得把 data Keyring 反向用来认证自己的 owner bootstrap，重新制造 owner →
Keyring → owner 循环。

### 13.1 明确的不可检测边界

如果攻击者、错误恢复工具或设备快照放回过去某个 owner-signed inner document，无论是
sole valid slot 还是完整 old pair，都可以重新选择 wrapper generation 并重算无密钥
CRC/digest。本地 evaluator 无法仅凭 cache 判断它曾经接受过更高 policy/certification/
manifest。若旧 APK/app bytes、旧 Keystore/文件系统状态也一起被完整回放，本地 identity、
signature 与 monotonic 比较更可能全部自洽。A/B 不是 rollback-resistant counter，Android
普通 app storage 也不是外部 freshness witness。

所以：

- 文档不得声称 A/B 单独提供 rollback detection；
- “旧 single slot 或完整旧 A/B + 可重算 wrapper”都是 V1 的本地不可检测边界；current
  external candidate 更新时必须由第 10 节拒绝/前进，external evidence 缺失时不得把旧
  cache 当作 real-data freshness；
- 对 revocation、stage 授权和 real-data `SHADOW+`，必须另有已验证 freshness：
  rollback-resistant hardware anchor、可信远端 monotonic witness，或每次启动的明确重新确认；
- offline、stale、replay、witness 不可达时，真实数据能力降为 `SCHEMA_ONLY`。只有独立
  lab subject 满足 ADR 0018 `NORMAL_PRODUCT_SHARED_V1` 与目标 capability branch 时，
  canonical `PRODUCT_SYNTHETIC + DARK` 才可能成立；Budget 前唯一持久实验是
  measurement permit，不能只凭 owner/erasure 沿用旧授权；
- 该 freshness/StageRevocation 机制须由后续 ADR 冻结；本 ADR 不虚构已经解决。

## 14. 独立 operational gates

本节把“当前 APK 是哪一个 artifact”“谁有权签发 production artifact”“该 artifact 是否已
通过实体平台矩阵”拆成独立 evidence domain。设备 runtime 不得把 signer-service 运维状态
塞进 artifact identity evaluator；stage/release evaluator 在这些 gate 之外取严格交集。

### 14.1 `ReleaseIdentityGateV1`

只有以下条件全部有机器可读 PASS evidence 才可 PASS：

1. stable `DataOwnerIdentityV1` 与 current external `ReleaseIdentityV1` 的 canonical
   body、exact signed document、digest、owner-root pin 和 track/application ID 全部一致；
2. production/canary/nightly/debug/benchmark application ID、DataOwner 与 Android signer
   namespace 隔离，candidate lineage 的 index 0 与 owner anchor 相同；
3. 从 `ApplicationInfo.sourceDir` 读取的 installed base APK version、length、digest、current
   signer index 与 authorized lineage 精确匹配 ReleaseIdentity/manifest；
4. owner-signed ReleaseIdentity/manifest 对 installed artifact 的 exact binding 无
   version/digest/lineage fork；WORM 行的存在性、并发唯一性与 cached APK equality 由独立
   `ReleaseSigningAuthorityGateV1` 在发布入口证明，runtime 不在线查询 signer ledger；
5. canonical parse、lineage proof、PackageManager history 或任何 cross-binding
   缺失、unknown、过期、冲突或 I/O 错误时 fail closed。若本地已接受的 exact
   owner bundle 覆盖 current installed artifact，则只需本地 exact bytes、digest、signature
   与 binding，resolver/网络临时不可达不得撤销该 artifact；只有本地 bundle 不覆盖
   candidate 时才必须 fetch，且该次 fetch 的缺失、网络错误或不完整结果 fail closed。

本 gate **不**裁决 signer service 的 OIDC/私钥运维、platform certification、backup
exclusion、build attestation、local-erasure control 或 runtime capability；它们各有独立
GateId。

### 14.2 `ReleaseOwnerContinuityGateV1`

只有以下条件全部通过才可 PASS：

1. stable `DataOwnerIdentityV1`、owner root pin 与 track namespace 已完成受控
   provisioning；这项证明 owner-state continuity，不等同于 production
   `ReleaseIdentityGateV1=PASS`；
2. 第 10 节 transition validator 对所有**可观察** local/external candidates 通过；本地
   accepted bundle 覆盖 current installed artifact 时，exact inner manifest、policy、
   certification、BuildSubject、profile set、classifier 与 acceptance receipt bytes/digests
   （含 canonical absent group）及 owner signatures 足以证明 scoped owner authenticity，
   但 gate evidence 必须显式输出 `EXTERNAL_FRESHNESS_UNPROVEN`，不能声称 selected state
   globally latest。只有本地 bundle 不覆盖 candidate 时，才要求 central owner ledger
   exact fetch 成功并持久化完整新 bundle；
3. future A/B wire 已由 phase ADR 冻结，并通过 codec、fuzz、torn-write、kill-point 和
   close/reopen recovery；
4. 启动、覆盖升级、版本跳跃、cert-only、policy-only、一轮/多轮 rotation skip、降级、
   reinstall 和 Keystore loss 设备测试通过；
5. old-valid single/pair replay scope 被明确记录，evidence 明示是否存在 external
   freshness；本 Gate 不把不可检测边界伪装成已解决，real-data stage 另行要求
   `StageRevocationFreshnessGateV1`；
6. gate evaluator 对缺失/unknown/rollback/fork fail closed，并能独立输出，不把
   `ReleaseIdentityGateV1`、`LocalErasureControlPhaseGateV1`、
   `LocalErasureCapabilityGateV1`、BudgetProfile 或 stage policy 的结果折叠进本 Gate。

### 14.3 `ReleaseSigningAuthorityGateV1`

这是 **release-entry gate**，不是设备 runtime identity evaluator。只有外部
signer/owner service、OIDC claim policy、track/key isolation、双人恢复、私钥备份、审计，
以及 `(applicationId,versionCode)` exact-cache ledger 的并发、崩溃、冲突和灾难恢复演练
全部有机器可读 PASS evidence 时才可 PASS。它控制能否发布新的 production artifact；设备
仍只信任 pinned owner signature、installed APK bytes 和已接受 owner state。

### 14.4 `PlatformCertificationGateV1`

该 gate 分两层，不能让设备为验证已接受 artifact 下载整套实验室原始数据：

1. certification issuer/release-entry evaluator 必须先验证实体 device/upgrade matrix 与
   test/evidence/toolchain immutable bundles，之后才可签发 `result=PASS` 的
   `PlatformCertificationV1`；
2. device runtime 验证 exact certification bytes、owner signature、sequence、`result=PASS`、
   certified SDK 与 artifact/identity/lineage/BuildSubject cross-binding；profile group
   present 时还验证 profile set/classifier/acceptance receipt 的 exact tuple、receipt
   contract/evidence/policy/SLO binding 与 current fingerprint/role/profile 唯一选择，group
   absent 时 Memory ceiling 必须保持 `SCHEMA_ONLY`。runtime 可再验证随 owner bundle内联的
   fixed-size signed gate receipt，但不在线 fetch raw lab bundles。

普通 CI、host test、`apksigner verify` 或“能安装”均不能替代 issuer 层；已接受的 local
certification 也不因 lab storage 临时离线而失效。

`BackupExclusionGateV1` 与 `BuildAttestationGateV1` 由 ADR 0018 的 closed GateId registry
独立裁决；前者只证明 backup/D2D 排除，后者覆盖 variant/signing scheme 与
secret/log/artifact scan。任何一个 gate 的 PASS 都不能隐含另一个。

所有 gate 输出必须携带 `evidence_bundle_sha256` 和 canonical reason codes；不得仅返回布尔
值后丢失依据。release-publication evaluator 额外 AND
`ReleaseSigningAuthorityGateV1` 与 `ReleasePolicySemanticsPhaseGateV1`；
device capability evaluator 不因 signer service 当前离线而撤销一份已经接受的 artifact，
它只验证该 artifact 的 immutable owner/identity/certification evidence，并在各 gate 之外
再与 `ReleasePolicySemanticsPhaseGateV1`、`LocalErasureControlPhaseGateV1`、
`LocalErasureCapabilityGateV1`、budget、consent、backup、build attestation 与 freshness
取最严格交集。

### 14.5 `ReleasePolicySemanticsPhaseGateV1`

本 gate 不阻止 identity/owner evaluator 对 opaque policy 的 exact
revision/length/digest continuity 做判断；它只裁决 capability/release evaluator 是否有权
解释 policy 内容。只有 §6 后要求的 closed schema、canonical encoding、caps、unknown-field
规则、deterministic evaluator、golden/fuzz 和 downgrade policy 全部 Accepted 且有机器可读
evidence 时才可 PASS。当前没有该 schema，所以本 gate BLOCKED；任何 release publication
与 real-data capability 都不得从 opaque bytes 猜出 stage、设备范围、开关或默认值。
closed GateId 与 capability DAG 以 ADR 0018 为 authority。

### 14.6 当前证据

| Required evidence | 当前状态 |
|---|---|
| production Android signer / lineage | `UNPROVISIONED` |
| production owner root / DataOwner identity | `UNPROVISIONED` |
| external signer + immutable exact APK ledger | `UNPROVISIONED` |
| max production version / signed APK digest | `UNSET` |
| `PlatformCertificationV1` | `ABSENT` |
| `BuildSubjectIdentityV1/AttestationV1` exact wire / sidecar | `NOT_FROZEN / ABSENT` |
| `BudgetProfileSetV1` exact wire / sidecar | `NOT_FROZEN / ABSENT` |
| `CertifiedDeviceRoleClassifierV1` exact wire / sidecar | `NOT_FROZEN / ABSENT` |
| `ProfileAcceptanceReceiptV1` exact wire / sidecar | `NOT_FROZEN / ABSENT` |
| certified SDK / physical device matrix | `UNSET / NOT_RUN` |
| ReleaseOwner central ledger | `UNPROVISIONED` |
| device A/B state wire / implementation | `NOT_FROZEN / NOT_IMPLEMENTED` |
| `RootBootstrapControlPhaseGateV1` | **`BLOCKED`** |
| `LocalErasureControlPhaseGateV1` | **`BLOCKED`** |
| `LocalErasureCapabilityGateV1` | **`BLOCKED`** |
| `StageRevocationFreshnessGateV1` | `BLOCKED`（reason/authority state=`NOT_DESIGNED`） |
| `ReleaseIdentityGateV1` | **`BLOCKED`** |
| `ReleaseOwnerContinuityGateV1` | **`BLOCKED`** |
| `ReleaseSigningAuthorityGateV1` | **`BLOCKED`** |
| `ReleasePolicySemanticsPhaseGateV1` | **`BLOCKED`** |
| `PreCertificationCandidateControlPhaseGateV1` | **`BLOCKED`** |
| `BudgetEvidenceWirePhaseGateV1` | **`BLOCKED`** |
| `PlatformCertificationGateV1` | **`BLOCKED`** |
| `BackupExclusionGateV1` | **`BLOCKED`** |
| `BuildAttestationGateV1` | **`BLOCKED`** |
| `StageSnapshotAuthenticationPhaseGateV1` | **`BLOCKED`** |
| `StageSnapshotAuthenticationCapabilityGateV1` | **`BLOCKED`** |

Gate 0 接受的是本 ADR 的决策和格式，不是上述 operational evidence。任何代码评审都不得把
“ADR Accepted”改写成“production release identity 已建立”。

本 ADR 只冻结 manifest/certification 对四份 external sidecar 的引用 tuple 与 cross-binding
规则，不冻结这些 sidecar 自身的 exact body、caps、signature coverage、issuer 或 verifier
wire。`BuildSubjectIdentityV1/AttestationV1` 与 candidate authorization/control wire 归
`PreCertificationCandidateControlPhaseGateV1`，profile set/classifier/acceptance receipt
与 measurement evidence wire 归 `BudgetEvidenceWirePhaseGateV1`。两项 phase gate 当前均
BLOCKED，因此 `BuildAttestationGateV1`、`BudgetProfileGateV1`、
`PlatformCertificationGateV1` 与 normal Memory stage 不可能 PASS；文中“必须绑定”不是对
尚未冻结 wire 的隐式 Accepted。

## 15. Stage 边界

| Stage | 在 `ReleaseOwnerContinuityGateV1` BLOCKED 时 |
|---|---|
| `SCHEMA_ONLY` | 允许；只冻结 API/schema/descriptor |
| `PRODUCT_SYNTHETIC + DARK` | 禁止；未满足 ADR 0018 normal DAG |
| real-data `DARK` / `SHADOW` | 禁止 |
| user `CANARY` | 禁止 |
| `DEFAULT` | 禁止 |

`ReleaseIdentityGateV1` BLOCKED 时 normal product `DARK` 一律不可达。BudgetProfile 前的
唯一持久实验必须使用 signer WORM 中的 **exact production applicationId/versionCode/signer
candidate APK**、standalone ReleaseIdentity/BuildSubject 与独立 candidate-only
magic/root/authority。它只能在 ADR 0018 的
`PreCertificationCandidateDecisionV1 + SyntheticMeasurementPermitV1` 下运行
measurement-only known-synthetic bytes；candidate decision/receipt 不可 cast 为 normal
`ReleaseIdentityGateV1=PASS`，permit 也不是 `PRODUCT_SYNTHETIC + DARK` 或任何
FeatureStage。独立 lab track、debug applicationId 或另一个近似 artifact 都不能替代该
production candidate subject。

未来 `AuthorityBootstrapPermitV1` 是上述表格唯一的 control-plane 破环手段，但它既不授权
persistent Memory data path，也不把 stage 提升到 DARK。当前
`RootBootstrapControlPhaseGateV1=BLOCKED`，因此没有这一例外的运行权限。

任何真实用户输入、会话 transcript、Tool/Effect、Provider secret、个性化统计或可关联标识都算
real data。不能以“功能不可见”“只在本地”“已加密”或“抽样很少”为理由绕过 Gate。

## 16. Mandatory test plan

以下是未来 operational exit criteria，当前没有被本 ADR 声称为已运行。

### 16.1 Canonical codec

- 每类 V1 的 golden bytes、digest 和 signature vector；
- CRLF、BOM、Unicode、空白、大小写 hex、前导零、字段乱序/重复/缺失/未知、超长和整数
  overflow 全拒绝；
- P1363 `r/s` 边界、high-S、wrong root、wrong domain、bit flip 全拒绝；
- 同一 DataOwner/ReleaseIdentity body 的两个合法 low-S signature 产生相同 semantic identity
  digest、不同 document digest；WORM ledger 只接受首次 exact signed document；
- streaming/chunked parser 与一次性 parser 结果一致；
- JVM property/fuzz 至少覆盖长度、count 与 repeated index 组合。

### 16.2 Signer/ledger

- 同主键 1、2、100 个并发请求只产生一个 committed signed digest；
- commit 前后每个 kill point 重试，只能“无输出”或返回 exact cached bytes；
- 同版本不同 unsigned digest/config/lineage 永久 conflict；
- `N -> N+k` 成功，`N -> N` 新内容和 `N -> N-k` 拒绝；
- service/ledger backup restore 后 exact APK、receipt 和 max state 不变；
- runner 日志、process list、cache、artifact、crash dump 和 PR job 不出现私钥/长期凭证。

### 16.3 Manifest transition

表驱动和 property test 必须覆盖：

- exact same；
- version `N -> N+1` 与 `N -> N+k`；
- cert-only append 1 与 append m；
- policy-only、certification-only；
- schema-generation-only；
- installation-local data/key/erasure generation-only只进入 future local
  `OwnerStateV1`/control reducer测试，且必须证明不会改变 shared manifest bytes/sequence；
- version + policy、version + one rotation、version + multi-rotation 的组合；
- intermediate manifest 缺失的合法 skip；
- sequence fork、lineage truncate/reorder/replace、version rollback、同版本换 digest、
  policy/certification revision 换 digest、错误 proof、错误 artifact signer index；
- candidate sequence 增加但没有任何语义字段前进；
- artifact/lineage/policy 变化却复用旧 ReleaseIdentity，或 certification-only 错误换
  ReleaseIdentity；
- certification revision 与 document sequence 不同、absent triple 不 canonical；
- build subject始终 present的 non-absent certification、profile group全 absent与全 present
  两条合法路径，以及任一半 present组合拒绝；
- acceptance receipt exact绑定 profile/classifier/confirmatory evidence/verifier/M/policy/SLO，
  wrong role/fingerprint、旧 receipt重放、同 revision不同 digest fork均拒绝；
- profile/classifier/receipt 任一单独前进都要求 coherent group、新 receipt、新
  certification与新 manifest；profile-only advance沿用旧 certification拒绝；
- checked arithmetic、sidecar length/cap/缺失和 digest mismatch。

### 16.4 Android upgrade/device matrix

在 policy 指定的真实 API/OEM/设备角色上验证：

- clean install、进程/设备重启、正常覆盖升级、version gap；
- current signer、单 rotation、multi-rotation skip；
- cert-only 预授权后升级、policy-only 后升级；
- `SigningInfo` history 顺序与 PackageManager 行为；
- wrong signer、multi-signer、downgrade、stale external owner candidate、两个 ledger fork；
- app data/Keystore/Journal sentinel 在合法升级后保持，在 uninstall/clear-data 后明确丢失；
- cloud backup 与 OEM D2D 不会恢复可被误认领的 Memory；
- signed APK 的 min/target/compile SDK、ABI、API 29/rotation boundary 和当前 API；
- evidence bundle 由 exact published APK 产生，不是重建的近似 APK。
- calibration与confirmatory必须使用 signer WORM中同一 cached production APK exact bytes；
  rebuild、resign、debug/lab applicationId、candidate receipt跨 normal gate cast均拒绝；
- M/S/A identity验证：F005只取预结果 M，BuildSubject/F006只取 S，A 只能修改预承诺的
  acceptance-only paths；BuildSubject包含/预测 A、A 修改 build-affecting path或 tag asset
  不等于 cached APK均拒绝；
- 四 role calibration/confirmatory candidate authorization、permit、terminal set必须各自
  完整且有 pairwise-distinct device/run/root；candidate root转 normal、跨 phase/run重放、
  失败后择优换 run与未 wipe/zero-census 均拒绝；
- 已认证 N 后，N+1 exact production candidate可在 candidate-only branch执行；N+1 final
  certification/manifest缺失时其 normal runtime仍必须 blocked；
- APK assets 只含 stable DataOwner/root/discovery pins，不含 current full signed APK
  digest、ReleaseIdentity、OwnerManifest、Certification、BuildSubjectAttestation、
  BudgetProfileSet、Classifier 或 ProfileAcceptanceReceipt 的 bytes/digests；构建检查拒绝
  任一自引用或 sidecar 嵌入；
- device 从 `ApplicationInfo.sourceDir` 计算 installed APK exact length/digest；首次启动
  offline/owner resolver 不可达时保持 `SCHEMA_ONLY` 且不创建 Memory root/Key。

### 16.5 Future A/B

- 每个 write/fsync/close/reopen/full-reread 边界断电；
- A 坏、B 坏、selected 坏、new slot 坏、owner signature/digest/CRC 坏、padding 坏、
  generation overflow；
- 同 generation 不同 manifest、identity mismatch、valid old + valid new；
- BuildSubject/profile/classifier/receipt exact bytes、tuple或 selected role/profile只存在于
  一槽/被截断/half-present/签名错误时该槽无效；profile-only合法前进写入新 generation，
  old slot不得把旧 receipt重新提升为 current；
- sole old-valid slot 重算更高 wrapper generation、以及两槽共同回滚实验，都必须证明
  “无法仅凭本地 cache 检出”，并验证 real-data freshness 缺失时降级；
- 完整旧 A/B、旧 APK 与旧 app/Keystore 快照联合回放必须记录为预期不可检测，不得伪造
  freshness PASS；另测“只回放旧 single/pair、当前 external candidate 保持更新”必须
  fail closed/advance；
- 设备升级和 Keystore invalidation 后 fail closed，不自动 re-bootstrap。

## 17. 运维与灾难恢复

- 至少保留受地域隔离、访问审计和定期恢复演练的 Android signer/owner key backup；
- recovery ceremony 必须双人批准，恢复到隔离 signer，先用非 production namespace 验证；
- 每次演练签出的测试 artifact 永不占用 production `(applicationId,versionCode)`；
- 泄露怀疑立即冻结 signer、owner ledger 和发布；没有 verified rotation path 时不发布“修复版”
  来假装连续；
- Android signing root 无法恢复且没有合法后继 proof 时，必须宣布 reinstall/data discontinuity；
- owner root 无法恢复时，旧 manifest 可读但不能安全前进；Gate BLOCKED；
- immutable ledger、identity、policy、lineage proof、certification 和 published APK 必须能按
  digest 独立恢复并交叉核对。

## 18. 明确不做

- 不把 Git tag、GitHub Release、workflow run、versionName 或文件名当 release identity；
- 不依赖可变的 hosted-runner debug keystore；
- 不为同一个 production version 重新签名；
- 不用 wall clock 判定哪个 manifest 更新；
- 不把 RAG、数据库可读或“APK 能装”当 continuity 证据；
- 不在 Gate 0 擅自冻结未经验证的 A/B slot bytes；
- 不宣称本地 A/B 能检测完整双槽回滚；
- 不因担心数据丢失而在身份失败时明文导出、降级加密或静默认领旧数据。

## 19. 参考资料

- [Android Developers — How app updates work](https://developer.android.com/google/play/app-updates)
  （application ID、certificate/proof-of-rotation 与 version code）
- [Android Developers — Sign your app](https://developer.android.com/studio/publish/app-signing)
  （长期 app signing identity 与升级约束）
- [AOSP — APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3)
  （proof-of-rotation 结构与验证）
- [Android `SigningInfo`](https://developer.android.com/reference/android/content/pm/SigningInfo)
  （current signer、历史证书和 multi-signer 边界）
- [Android `Build.VERSION`](https://developer.android.com/reference/android/os/Build.VERSION)
  （`SDK_INT` 是 major；API 36 起 `SDK_INT_FULL` 同时表示 major/minor）
- [Android `Build.VERSION_CODES_FULL`](https://developer.android.com/reference/kotlin/android/os/Build.VERSION_CODES_FULL.html)
  （full version 有序但整数编码是 implementation detail）
- [Android data and file storage overview](https://developer.android.com/training/data-storage/)
  （app-specific 数据在卸载时删除）
- [Android `<application>` manifest reference](https://developer.android.com/guide/topics/manifest/application-element)
  （backup 与 data-extraction 行为及 OEM 差异）
- [GitHub Actions — OpenID Connect](https://docs.github.com/en/actions/concepts/security/openid-connect)
  （短生命周期 workflow credential）
- [GitHub Actions — Using secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
  （日志遮蔽与 secret 边界）
- [NIST SP 800-57 Part 1 Rev. 5](https://doi.org/10.6028/NIST.SP.800-57pt1r5)
  （密钥生命周期、备份、恢复与 compromise 管理）
- [SLSA Provenance](https://slsa.dev/spec/v1.0/provenance)
  （以不可变 digest 绑定 artifact 和构建证据）
