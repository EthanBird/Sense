# ADR 0017：M9 Memory 安全、密钥、Blob 与本地擦除

- 状态：**Gate 0 安全策略与 M9A primitive wire 已接受；本地擦除、来源清单与轮换控制 schema 尚未冻结，相关能力 BLOCKED**
- 决策日期：2026-07-26
- 适用范围：M9.0/M9A 本地 Memory Journal、Blob、派生索引、跨进程传输、导出与擦除
- 依赖：ADR 0015、ADR 0016
- 后继：ADR 0018、M9A phase schema gate

> 本 ADR 接受的是可实现、可审计的安全边界和底层字节契约，不是“安全功能已经实现”的声明。
> 当前仓库没有 Memory runtime、没有设备证据，也没有可供真实用户数据使用的本地擦除控制
> wire。任何未被本 ADR 明确接受的路径都必须 fail closed。

---

## 1. 决策摘要

Sense 的长期记忆采用以下不可分割的约束：

1. 所有持久化 Memory 数据只能位于 credential-protected
   `Context.noBackupFilesDir` 的固定根目录；Direct Boot、Auto Backup、设备迁移和调试日志
   都不能得到明文；
2. Journal frame 与 Blob chunk 使用独立 DEK 逐记录 AEAD；DEK 由用途隔离的 Android
   Keystore key 包装；metadata、frontier、locator 和 recovery seal 使用独立 MAC key；
3. Keystore alias、授权意图、操作高水位、keyring、Blob header/chunk/footer 与 locator
   都有冻结的 M9.0 字节表示；未知或不一致状态不得“猜测修复”；
4. Blob 的逻辑 ID 是随机 128-bit ID，不是内容地址。可变物理版本只能通过认证的
   pointer-free A/B locator 找到；
5. CapturePolicy 在读取正文前和读取一次受限正文后分别判定。拒绝路径不得分配持久 ID、
   sequence、DEK、Cipher、队列槽或临时文件；
6. 本地擦除是一个先冻结、再切代、再排空、再销毁、最后证明的状态机。进程重启后的空内存
   集合不等于“所有 egress 已排空”；
7. NAND/闪存的物理单元清零只能 best effort；Sense 能承诺的是认证密钥不可用、命名空间
   不可达、目录项已 durable unlink、重启后 census 不可见以及可验证的协议证据；
8. `WriterSourceAuthorityManifestV1`、`ErasureManifestV1`、`ErasureReceiptV1` 与
   `RotationControlV1` 的字段尚未冻结。其 API 必须返回 `FEATURE_STAGE_BLOCKED`，
   不能用临时 JSON 或 Room row 代替；
9. normal product/root在 `ReleaseOwnerContinuityGateV1` 或
   `LocalErasureControlPhaseGateV1` 任一未 PASS 时，最高只能 `SCHEMA_ONLY`，不得创建
   Memory keyring/key、record 或 Blob。§8.4 的一次性、非 FeatureStage
   `AuthorityBootstrapPermitV1` 只在
   `RootBootstrapControlPhaseGateV1` 等先决条件 PASS 后创建空 root shell/bootstrap lock
   与 owner-signed A/B control metadata；当前
   `RootBootstrapControlPhaseGateV1=BLOCKED`，所以当前仍是零 root。
   另一个 future exception是 ADR 0018 pre-cert decision、candidate-only authority/root、
   keyring/local-erasure/evidence control与每 root epoch三 permit全部闭合后的
   measurement-only branch；它不能 cast/转正为 normal owner/root/stage，且当前相关 phase
   均 BLOCKED，所以现时同样零写。
   owner 与 erasure gates 都 PASS 后，才由 ADR 0015 release identity、ADR 0018
   budget/freshness 等其余门禁决定能否进入隔离合成数据 `DARK`；真实数据与 `SHADOW+`
   仍要求全部 real-data gate PASS。

---

## 2. 威胁模型与非目标

### 2.1 必须抵抗

- 丢失设备后的离线文件读取；
- 同 UID 的 `:brain`、IME 与 main/Broker 多进程竞态、迟到 Binder 回调和进程死亡；
- frame/chunk/manifest 的截断、位翻转、替换、跨文件拼接、同根目录内错配与单槽回滚；
- 应用升级、异常关机、短写、`fsync`/`force` 结果不确定和目录项未持久化；
- Auto Backup、设备迁移、Direct Boot、tombstone、logcat、crash report、测试 artifact、
  PFD 临时文件和数据库 WAL 泄漏；
- 已撤销来源在重试、恢复、索引重建、导入、导出或后续擦除请求中复活；
- 旧进程在擦除 fence 之后继续分配 ID、sequence、Cipher 或把 byte 交给下游；
- 恶意或损坏文件宣称巨型长度，引起整数溢出、内存分配或磁盘放大。

### 2.2 不承诺

- 已解锁、已 root 且能读取进程内存或控制同 UID 代码的设备；
- 模型已经看到、远端 Provider 已接收或用户已经导出的外部副本；
- 闪存控制器磨损均衡后的逐 NAND cell 可证清零；
- Android Keystore/TEE 本身的实现漏洞；
- 仅依靠本地 A/B 两槽识别历史上完整有效的旧状态 replay。

最后一项是明确限制：Keystore-MAC/AEAD 认证的 data-plane A/B 可以检测随机单槽损坏、
非法代差、交叉身份以及不满足 authenticated predecessor 的局部回放，但仍不能检测完整旧
authenticated pair。ADR 0015 的 baseline owner A/B 外层只有无密钥 integrity wrapper，
连 old-valid single slot 都可被重算 generation 后回放；不得把 data-plane 结论套到 owner
cache。因此未来真实数据 `SHADOW+` 必须另有 `StageRevocationFreshnessGateV1`，不能把
任一 A/B generation 冒充外部单调时钟。

---

## 3. 规范语言、整数与 canonical bytes

本文的 MUST、MUST NOT、SHOULD、MAY 具有规范意义。

- ADR 0017 手写 physical/fixed-layout 中的多字节整数均为 unsigned big-endian；实现必须
  在转换到 Kotlin/Java signed 类型前做 checked arithmetic。imported/future Proto 仍按
  Protobuf 标准编码，`fixed32/fixed64` 是 little-endian、varint 使用标准 wire rules；
- 固定宽度保留位必须为零；reader 遇到非零保留位即拒绝；
- `canonical ASCII` 只允许 byte `0x21..0x7e`，不允许空白、NUL、非 ASCII、Unicode
  normalization、case folding 或 locale 转换；
- ADR 0016 `id128` 在 Proto/API 中是恰好 32 个 lowercase hex 的 `string`；physical fixed
  layout 中才是 raw 16 bytes。future control wire 若需要 `bytes16`，必须给字段独立类型名
  并显式定义 hex/raw conversion，不能把泛称 “Proto id128” 改成 bytes；
- 文件名中的 128-bit ID 使用 RFC 4648 base32hex alphabet
  `0123456789abcdefghijklmnopqrstuv`、无 padding、恰好 26 字符。编码输入为 16 bytes
  network order；最后字符只有其高 3 bit 有效，低 2 bit 必须为零。decoder 必须拒绝大写、
  `=`、错误长度和非 canonical tail bits；
- digest 为 SHA-256，MAC 为 HMAC-SHA-256，除非表格明确指定；
- format `major=1, minor=0`；`required_features=0`。M9.0 primitive feature registry 为空，
  任一非零 required bit 都 fail closed。

---

## 4. 文件根、上下文与所有者连续性

### 4.1 唯一持久化根

```text
credentialProtectedContext.noBackupFilesDir/sense-memory/v1/
├── control/
│   ├── .sense-memory.bootstrap.lock
│   ├── owner-a
│   └── owner-b
├── journal/
├── blobs/
├── manifests/
│   ├── keyring/
│   ├── operation-frontier/
│   ├── blob-locator/
│   │   └── bootstrap-control/
│   └── erasure-control/
│       ├── slot-a
│       └── slot-b
├── index/
│   ├── projection/
│   └── hot-snapshot/
├── quarantine/
└── temp/
```

该图列 semantic parents/fixed payload；每个 `.sense-memory.namespace.lock`、dynamic child
lock与 `bootstrap-control/control-a|control-b` 的唯一合法位置由 §4.3
`NamespaceMutationLockMapV1`/§10.0展开，属于同一 closed layout而不是“额外 child”。

`control/`及其三个 fixed children只可由 future
`AuthorityBootstrapPermitV1` 创建；owner A/B 的 exact bytes/selector仍由
`RootBootstrapControlPhaseGateV1` 冻结。它们不是 data-plane record容器，任何额外 child、
临时自由文件、第二 bootstrap lock或把 Journal/Blob放入其中都 fail closed。其余
data-plane parents只由后续 Keyring bootstrap transaction创建。

`manifests/erasure-control/slot-a|slot-b` 只有在 §5.1.1 purpose-6 ENABLED joint commit后
才是 local selective-erasure唯一 persistent progress authority；在此之前只是不可读取的
全零/UNUSED placeholder。Keyring bootstrap必须在允许 capture前创建、物理预分配到 future
`ErasureControlSlotV1` fixed EOF、file/parent fsync并 close/reopen验证 exact block charge，
再以 purpose 6初始化并 joint commit，且永久计入 F010/F014。其 exact bytes/A/B selector/rotation/recovery由
`LocalErasureControlPhaseGateV1`冻结；在该 gate PASS及 slots实测预分配前，selective
erasure/capture都 BLOCKED。F018 free margin与 F033内存 queue lane都不能冒充 persistent
slot。whole-reset terminal仍由 sandbox外 owner/control authority证明。

Room/FTS投影与 HotSnapshot只能位于 `index/projection` 与 `index/hot-snapshot`；禁止另建
top-level `db/`、`snapshots/`。两 parent的固定 lock/generation grammar、capacity class与
phase wire由 index/HotSnapshot gate冻结，extra root child fail closed。

实现必须从默认 credential-encrypted context（或显式
`Context.createCredentialProtectedStorageContext()`）的 `noBackupFilesDir` 获得根目录，
并同时证明 `Context.isDeviceProtectedStorage == false`、组件
`android:directBootAware="false"`、`UserManager.isUserUnlocked == true`。随后把该 trusted
Context返回的 root作为 anchor，以 `O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW` 打开并持有 opaque
dirfd handle，`fstat`验证 UID/mode/device/inode，再对固定单段 ASCII child逐级
`openat/fstatat`。canonical path字符串只作诊断 provenance，不是身份或安全 authority。
不得自行拼接 `filesDir`、`cacheDir`、
external storage 或 device-protected storage。`allowBackup=false` 及
`data_extraction_rules.xml` 是第二道门，不是替代根目录的理由。

用户尚未首次解锁时：

- 不创建 Keystore alias；
- 不读取 keyring、Journal、Blob 或 index；
- 不把状态复制到 device-protected storage；
- 对 device-protected context 做 census，必须不存在 `sense-memory/v1` mirror；
- Broker 只返回 `USER_LOCKED`，不以空库冒充“没有记忆”。

### 4.2 `ReleaseOwnerContinuityGateV1`

该 gate 只引用 ADR 0015 的稳定 `DataOwnerIdentityV1` 证据。其语义恰由
`(release_track, application_id, oldest_android_signer_cert_sha256)` 决定；identity bytes、
owner signature 与 `data_owner_identity_sha256` 必须复用 ADR 0015 的 exact canonical
grammar，本文不定义第二种摘要。Gate 必须证明：

1. exact DataOwner ID 在该 track 内稳定；
2. 相对 last accepted/本次已观察 external owner-signed candidate 的 transition 可验证，
   在**已观察 candidates** 中没有分叉或可观察回退；本 gate 不声称没有未观察到的更新；
3. local owner state 与 exact last accepted/observed owner-signed cut、track、applicationId、
   oldest anchor 精确绑定；globally latest/head 由独立 freshness gate 裁决；
4. 没有第二个相冲突但自称当前 authority 的 owner state。

当前 artifact 的 production signer、platform/API certification、签名输入与 release build
验证属于独立 `ReleaseIdentityGateV1`，不得折叠进本 gate。pre-certification measurement
使用 ADR 0018 独立 candidate authority/root class，**不能**靠 lab track让本 gate PASS，
也不能写入 normal owner A/B；candidate decision不授权真实数据或 synthetic DARK。

以下是**独立 subgate**，不属于 `ReleaseOwnerContinuityGateV1` 的 PASS 条件，也不能被它替代：

- `DataRootContinuityGateV1`：root UID、SELinux context、canonical path、noBackup/credential
  context 与“无第二 root”；
- `InstallationKeyringIdentityGateV1`：decrypted `installation_id/keyring_id` 唯一稳定，
  导入包不能自称当前 installation；
- `KeyAuthorizationProfileGateV1`、`UnlockedKeyBehaviorGateV1`、
  `CredentialUnlockedRuntimeGateV1` 与聚合 `KeyUseSafetyGateV1`：§6；
- `SourceErasureAuthorityGateV1`：source owner、累计 erasure authority 与当前 installation
  的绑定。

上述任一 required gate 非 PASS 都 fail closed。尤其
`ReleaseOwnerContinuityGateV1=BLOCKED` 时除 §8.4 的 narrow authority bootstrap 外，不允许
创建 Memory root/key，也不允许 capture、Blob publish、导出、轮换、alias 删除或签发擦除
完成证明。当前 bootstrap phase BLOCKED，故例外不可用。卸载会清除应用数据与 Keystore，
重装是一个新 installation，绝不能复用旧 installation 身份。

### 4.3 `FileIdentitySafetyV1`

ADR 0017 的所有 authoritative file 都导入同一 inode/path contract；“MAC/AEAD 正确”不能
替代文件身份：

- parent directory 从 §4.1 trusted Context anchor逐级以 dirfd/openat no-follow打开并保持
  opaque native handle；所有 child open
  相对该 parent，拒绝 symlink、非 regular file、device/FIFO/socket；
- namespace mutation必须在非主线程持有 `NamespaceMutationLockMapV1` 为该 parent选出的
  immutable lock inode。没有 schema专用 lease的普通 Blob/control parent使用同 parent内
  fixed basename `.sense-memory.namespace.lock`；该 lock file只在 parent bootstrap时以
  `openat(O_NOFOLLOW|O_CLOEXEC|O_CREAT|O_EXCL,0600)` 创建、file fsync、parent fsync并
  close/reopen验证；之后永不 rename/unlink。normal operation只能 open existing，验证
  regular/nlink=1与 path↔descriptor inode一致，再 `flock(LOCK_EX|LOCK_NB)`，并持有到 final
  parent fsync/reopen验证完成。它是 advisory lock；`EWOULDBLOCK/EAGAIN` 是正常 contention，
  返回 `CONTENDED`（调用层可显示 busy）且零 mutation，不把 capability永久判 BLOCKED；`flock` unsupported、其它
  error、lock path replacement/unlink、inode mismatch或任一 same-UID writer可绕过该
  port时对应 capability gate保持 BLOCKED，不能创建一个新 lock inode后继续；
- zero-root/control-plane唯一例外是 ADR 0015 authority transaction创建的
  `control/.sense-memory.bootstrap.lock`。它只保护 root shell、owner A/B以及
  Keyring/bootstrap staging创建 data-plane ancestors；普通 Memory operation不得使用。
  全局顺序恰为 `root bootstrap lock → journal/open data-plane namespace lock →
  owner.lease → epoch lease`，任何反向获取、同 role平行 inode或在祖先不存在时先打开
  descendant lock都 fail closed；
- Journal 的 fixed-basename例外由 `NamespaceMutationLockMapV1` 封闭：
  `journal/open/<writer>` 中发布 bootstrap epoch目录时复用已认证且已持有的 immutable
  `owner.lease` inode；未发布的
  `.bootstrap-<epoch>` 内最初 `segment/frontier-a/frontier-b/lease` 创建仍处于同一 outer
  lock的单一 transaction，创建并验证 immutable `lease` 后立即取得它的 exclusive lock；
  此后该 temp/final epoch内的 sidecar temp/final publish、unlink和 fsync一律以**同一个
  epoch `lease` inode**作为 namespace lock。epoch目录不得再创建
  `.sense-memory.namespace.lock`，writer parent也不得另建该文件；这样避免 bootstrap递归
  且不改变 ADR 0016 的 exact entry set。跨 temp→final rename必须证明持有的 lease
  descriptor inode与 final
  `<epoch>/lease` no-follow reopen仍相同；任何 split-lock/new-inode路径 fail closed；
- `owner.lease` 的唯一 self-bootstrap例外是在仍持 root bootstrap lock时创建并 durability
  验证 `journal/open/.sense-memory.namespace.lock`，取得该 data-plane outer handle后，在
  其下创建/复验 fixed
  writer parent和其唯一 `owner.lease`，file fsync、writer-parent fsync、close/reopen完成
  structure/inode/CRC验证后，按 IME→BRAIN→MAIN取得同一 inode的 bootstrap-only
  `ProvisionalOwnerLeaseHandleV1`。它不要求尚不可得的 purpose-13 MAC、也不授予普通 owner
  API；随后才可按 rank 40写/select Keyring pair，并从 selected payload复验 MAC/profile后
  原地 promotion为 `AuthenticatedOwnerLeaseHandleV1`。不得 unlock/reopen/reacquire或取得
  rank40后再新取 rank20。`owner.lease` 此后永不 replace/unlink；已存在但无法逐字节/identity验证
  时不得创建平行 lease。除此 transaction外不存在“尚无 lock所以先 mutation”的例外；
- 所有 ownership/namespace锁只能经 `LeaseLockPortV1` 使用。port输入是已验证的 opaque
  parent/child handles和 closed lock role，不接受 canonical path或 raw fd integer；它以
  `openat(O_NOFOLLOW|O_CLOEXEC)`、`fstat`和 path↔descriptor identity revalidation打开
  immutable lock，`tryAcquireExclusive` 只返回
  `ACQUIRED(handle)|CONTENDED|UNSUPPORTED|INTEGRITY_FAILURE`。acquired handle持有到显式
  close或进程死亡；dup必须禁止或进入同一受审计 ref-count lifetime，不能让隐藏 duplicate
  越过 release。Java `FileChannel` 若被采用，只能封装一个已由 port验证的 descriptor，
  不能自行按 path打开或成为跨模块 authority。唯一额外 API 是只对
  `OperationLockPlanV1=KEYRING_BOOTSTRAP` 可见的
  `tryAcquireProvisionalOwnerLease(...)` 与
  `promoteSameHandle(selectedKeyringEvidence)`；前者返回不可传给普通 owner API的 sealed
  `ProvisionalOwnerLeaseHandleV1`，后者只可消费同一 live handle并返回
  `AuthenticatedOwnerLeaseHandleV1|INTEGRITY_FAILURE`，绝不重新 open/flock；
- Keyring A/B 各 exact EOF=65,536，operation-frontier A/B 各 exact EOF=256，Blob locator
  A/B 各 exact EOF=512；任何 trailing byte 都 invalid；
- 每个 authoritative file/temp/final 均要求 `st_nlink=1`；同一 A/B pair 的
  `(st_dev,st_ino)` 必须互异，也不得与其它 control slot、Blob physical 或 Journal entry
  共 inode；
- 已持 descriptor 的 `fstat(st_dev,st_ino)` 必须与从受信 parent 对 exact child name 做
  no-follow reopen 后的值相等；path replacement、hardlink、descriptor/path mismatch 一律
  fail closed；
- 每个 slot write/force/close-reopen/full-pair selection 前后重复上述检查，不能只在首次
  bootstrap lstat；
- M9.0 Blob temp 与 final 都直接位于唯一 parent
  `credentialProtectedContext.noBackupFilesDir/sense-memory/v1/blobs/`，不启用未定义的
  shard/subdirectory。temp exact basename 为
  `.<physical-id-base32hex26>.smb.tmp.<attempt-id-base32hex26>`；两个 ID 都是独立 CSPRNG
  id128、lowercase canonical base32hex26，physical ID 必须等于 Blob header，attempt ID
  必须由 §9 durable materialization/cleanup authority 绑定到该 physical ID。final basename
  唯一为 `<physical-id-base32hex26>.smb`；
- temp 以 `CREATE_NEW` 打开，必须 regular、`st_nlink=1`；完成 header/chunks/footer 后，
  exact EOF 必须等于 checked physical layout 计算值，full reread/digest/tag/cap 全通过才可
  同目录 publish-no-replace；
- Blob publish 后必须 parent fsync；原 temp path 必须 `ENOENT`，已持 descriptor inode
  必须与 no-follow 打开的 final path 相同，final 仍为 regular、`st_nlink=1`、exact
  EOF；随后再做完整复读。copy-then-delete、跨文件系统 move 或 final 已存在但 inode/bytes
  不同均拒绝；
- 删除/隔离默认使用**同一 parent**内不可猜测 tombstone name：
  `renameat2(RENAME_NOREPLACE)` → 验证同 inode/path → parent fsync → `unlinkat` →
  parent fsync → reopen census；每个 boundary均可恢复。若 future使用跨目录 quarantine，
  必须在 rename后同时 fsync source/destination两个 dirfd，不能只写“parent fsync”；
- future rotation/erasure/source control files 必须显式导入本节并冻结各自 exact EOF 与
  peer-inode rules，否则对应 phase gate 保持 BLOCKED。

`NamespaceMutationLockMapV1` 是 closed table；未列 parent/mutation没有 namespace或
in-place authority：

| rank | protected scope / mutation | exact lock inode | bootstrap rule | steady component |
|---:|---|---|---|---|
| 0 | root shell、`control/owner-a\|owner-b`、整个 Keyring bootstrap transaction | `control/.sense-memory.bootstrap.lock` | authority-only root bootstrap create；normal完成 adoption前属 external envelope | adoption后 MANIFEST/F014 |
| 10 | `journal/open` 下 writer-parent create/census | `journal/open/.sense-memory.namespace.lock` | 持 rank 0 create/fsync/reopen后取得 | JOURNAL/F011 |
| 20 | `journal/open/<writer>` 下 epoch publish/reap | 该 parent 的 `owner.lease` | rank 10 下先取得 bootstrap-only provisional inode lock；rank 40 staged pair selected后原地认证 promotion | JOURNAL/F011 |
| 30 | `.bootstrap-<epoch>` 与 final epoch内 fixed children/sidecar | 同一 epoch `lease` inode | rank 20 transaction创建，temp→final后 inode不变 | JOURNAL/F011 |
| 40 | `manifests/keyring` fixed A/B/control in-place write | `manifests/keyring/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | MANIFEST/F014 |
| 50 | `manifests/operation-frontier/<alias-digest>` child create/delete | `manifests/operation-frontier/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | MANIFEST/F014 |
| 51 | 一个已发布 operation-frontier child的 A/B in-place write | `<alias-digest>/.sense-memory.namespace.lock` | 在 rank 50 下与 child A/B同一 transaction创建后才发布 child | MANIFEST/F014 |
| 60 | Blob physical temp/final publish/unlink | `blobs/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | lock=MANIFEST/F014；Blob inode=BLOB/F012 |
| 70 | logical locator child create/delete与 `bootstrap-control` child census | `manifests/blob-locator/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | MANIFEST/F014 |
| 71 | locator bootstrap fixed intent/receipt A/B in-place write | `manifests/blob-locator/bootstrap-control/.sense-memory.namespace.lock` | rank 70 下与 fixed control slots同一 bootstrap transaction创建 | MANIFEST/F014 |
| 72 | 一个已发布 logical locator child的 A/B in-place write | `<logical-id>/.sense-memory.namespace.lock` | 在 rank 70 下与 UNUSED A/B同一 transaction创建后才发布 child | MANIFEST/F014 |
| 80 | erasure-control slot A/B in-place write/recovery | `manifests/erasure-control/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | MANIFEST/F014 |
| 90 | Room/FTS DB、WAL/SHM与 derived projection generations | `index/projection/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | lock=MANIFEST/F014；data=INDEX/F013 |
| 91 | HotSnapshot generations/pointer in-place write | `index/hot-snapshot/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | lock=MANIFEST/F014；data=INDEX/F013 |
| 100 | quarantine child publish/unlink | `quarantine/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | lock=MANIFEST/F014；payload=F015/F020 |
| 110 | bounded non-authoritative temp child publish/unlink | `temp/.sense-memory.namespace.lock` | rank 0 Keyring bootstrap一次创建 | lock=MANIFEST/F014；payload=F016/F019/F020 |

fixed A/B overwrite不是 namespace mutation，但仍必须持表中 exact lock直到 file fsync、
close/reopen pair selection与 terminal receipt完成。创建 dynamic child时，先持 ancestor
lock，在未发布 child内创建/fsync/reopen child lock与全部 mandatory children，取得 child
lock后才 atomic publish；这就是唯一 self-bootstrap，不允许先发布无锁 child再补锁。

同时持多个锁只允许 durable intent预先封存完整 `OperationLockPlanV1`，严格按 rank递增取得、
逆序释放；同 rank dynamic lock再按 canonical raw child identity bytes升序。释放高 rank后
若要取得更低 rank，必须先释放本 plan全部锁并重新 admission，不能 lock coupling反向走。
unknown lock、跳 rank、同 role平行 inode、未列普通 parent、锁 acquisition后临时扩大 plan
或 fixed-slot无锁写均 fail closed。model/fault test覆盖每一合法 edge、任意相邻 rank反转、
contention、process death、fd dup、path replacement与每个 fsync/reopen边界。

唯一不算“rank 40→20 acquisition”的动作是上述 bootstrap promotion：rank20 OS lock在
rank40之前已经取得且从未释放，promotion只把 selected Keyring证明附到同一
`(st_dev,st_ino,open-file-description,lock-lifetime)`，不得调用第二次 `flock`、open新 fd或
换 inode。`OperationLockPlanV1=KEYRING_BOOTSTRAP` 必须预列
`0→10→20(IME,BRAIN,MAIN provisional)→40` 与三次 in-place promotion；任一 provisional
contention为零 rank40 side effect，任一 promotion失败则不发布 joint receipt并按 partial
bootstrap recovery处理。

容量上，rank 0 inode在 adoption前由 external bootstrap envelope支付，adoption时一次计入
F010/F014；rank 10/20/30计 F010/F011；其余 ordinary lock与其 directory positive delta计
F010/F014。Keyring bootstrap compound intent必须在首个 mkdir前纳入表中所有 baseline
parent/lock/control-slot physical charge及 success/failure/indeterminate reservation；
不得把固定锁称为“零字节元数据”绕过 F019/F020。

directory创建同样只能经 `FileIdentitySafetyPortV1.mkdirChildNoFollowNoReplace`：native
实现使用 trusted dirfd相对 `mkdirat`，将 `EEXIST` 作为需要 no-follow reopen/closed
entry-set核验的结果而非成功，随后 child dirfd `fstat`、parent fsync、close/reopen identity
复验。不得用 `File.mkdirs()`/字符串 canonicalization补回 authority。

只有 MemoryBroker 持有 `BlobWireLocatorLeaseGateV1` 后续冻结的 canonical physical-attempt
lease，且同一 gate 下的 durable materialization/cleanup authority 证明 attempt 处于可清理
terminal，才可删除残留 temp；清理后 parent fsync + close/reopen census。该
attempt/lease/cleanup exact wire 尚未冻结，所以 `BlobWireLocatorLeaseGateV1` 与 persistent
Blob path 均 BLOCKED。unknown temp、名字不 canonical、attempt binding 缺失或并发 owner
不明一律 quarantine，不得按 PID、mtime、进程列表或“看起来很旧”删除。

任何 Android/Java API 无法提供上述 no-follow/fstat/parent-relative proof 时，本设备
`DataRootContinuityGateV1` 或对应 wire/locator gate 为 UNSUPPORTED/BLOCKED；不得退化为
“canonical path 字符串看起来相同”。kill/fault matrix 必须覆盖 hardlink A↔B、外部
hardlink、symlink swap、rename 后 temp 仍存在、final inode 替换和 trailing bytes。

---

## 5. Key purpose registry 与算法

M9.0 registry 不得重编号：

| id | token | 算法/用途 | M9.0 |
|---:|---|---|---|
| 1 | `journal-wrap` | AES-256-GCM，包装 segment DEK | ACTIVE |
| 2 | `blob-wrap` | AES-256-GCM，包装 Blob DEK | ACTIVE |
| 3 | `index-wrap` | HMAC-SHA-256，派生/认证索引 token | ACTIVE |
| 4 | `blob-id-wrap` | 预留给未来 keyed Blob ID | RESERVED；不得生成 |
| 5 | `manifest-seal` | AES-256-GCM，密封 Keyring payload | ACTIVE |
| 6 | `erasure-audit-seal` | HMAC-SHA-256，擦除控制 A/B | installation/root-scoped conditional；control wire BLOCKED |
| 7 | `operation-frontier-auth` | HMAC-SHA-256，认证 crypto operation frontier | ACTIVE |
| 8 | `segment-meta-mac` | HMAC-SHA-256，segment metadata | ACTIVE |
| 9 | `blob-meta-mac` | HMAC-SHA-256，Blob metadata domain | ACTIVE |
| 10 | `frontier-mac` | HMAC-SHA-256，Journal durable frontier | ACTIVE |
| 11 | `recovery-seal-mac` | HMAC-SHA-256，RecoveredSeal | ACTIVE |
| 12 | `blob-locator-auth` | HMAC-SHA-256，Blob locator | ACTIVE |
| 13 | `owner-lease-auth` | HMAC-SHA-256，writer owner.lease | installation/root-scoped ACTIVE |

一个 purpose 只能使用表中算法；不得让同一个 Keystore alias 同时服务两个 purpose、两个
generation 或两个算法。HMAC token 也必须带 domain separator，不能把“都是 HMAC”当成
可复用理由。

registry 的 ACTIVE 不等于“都放进 baseline generation”：

- baseline generation-scoped records 的 exact set 是
  `{1,2,3,7,8,9,10,11,12}`，每 generation 恰好 9 records；
- purpose 5 `MANIFEST_SEAL` 是 installation/root-scoped alias，由 clear Keyring header
  选择和认证，不占 generation records；
- purpose 4 永不生成；
- purpose 6 `ERASURE_AUDIT_SEAL` 是 installation/root-scoped、non-generation conditional
  alias，不占 baseline；只有 `LocalErasureControlPhaseGateV1=PASS` 的 exact descriptor与
  Keyring bootstrap receipt可激活；
- purpose 13 `OWNER_LEASE_AUTH` 是 installation/root-scoped、non-generation 独立 alias，
  不占 baseline；它只认证 owner.lease，不得复用 purpose 5。

### 5.1 Alias

generation-scoped `KeyGenerationId` 与 purpose 5 root alias ID 是 CSPRNG 生成的 16 bytes，
不是时间和计数器。alias 的统一语法为：

```text
sense.memory.v1.k.<purpose-token>.<generation-base32hex26>
```

不得截断、转大写、替换 `-`、隐藏 generation 或根据 locale 改写。alias UTF-8 bytes 的
SHA-256 是 `alias_digest`。manifest/path 中不存可由用户文字影响的 alias。

purpose 6 与 13 是仅有的 suffix 例外：两者 suffix都固定为 stable `installation_id` 的
base32hex26，即：

```text
sense.memory.v1.k.erasure-audit-seal.<base32hex26(installation_id)>
sense.memory.v1.k.owner-lease-auth.<base32hex26(installation_id)>
```

两者仍是互异 Keystore alias/key material，只是 root-scoped、无 data generation。purpose
13总在 Keyring bootstrap生成；purpose 6只在 accepted local-erasure control descriptor
下条件生成。两者各自独立验证 `KeyAuthorizationProfileV1`；普通 data-key/
MANIFEST_SEAL rotation、source erasure和 compaction都不得删除。仅 OS uninstall，或未来
显式 whole-installation destruction在所有 owner locks/进程 drain且 durable proof完成后，
才可销毁。
ADR 0016 `owner.lease` 的 exact MAC 为：

```text
HMAC-SHA-256(
  K_OWNER_LEASE_AUTH,
  ASCII("sense.memory.v1/writer-owner-lease/v1") ||
  0x00 ||
  exact_owner_lease_bytes[0,96)
)
```

不得再以 purpose 5 MANIFEST_SEAL 计算该 MAC。

### 5.1.1 purpose 6 activation 与 erasure-control authority

`LocalErasureControlPhaseGateV1` 必须冻结 purpose 6 的 exact
`KeyAuthorizationProfileV1`、alias reconstruction、`ErasureControlSlotV1` A/B wire、
selector/previous-digest anti-fork、operation/receipt state machine与 kill matrix；缺任一项
都保持 BLOCKED。purpose 6 是 HMAC，不创建 purpose-7 crypto-use frontier，closed frontier
census中出现 purpose-6 frontier反而失败；其单调 authority来自持 rank-80 lock的 fixed
erasure A/B generations、previous selected-slot digest和 durable erasure
intent/receipt chain，不能用 wall clock/mtime替代。

Keyring/data-plane bootstrap有且只有两种 root-alias census：

```text
ERASURE_CONTROL_DISABLED  -> root aliases {purpose 5, purpose 13}
ERASURE_CONTROL_ENABLED   -> root aliases {purpose 5, purpose 6, purpose 13}
```

两种 profile的 generation record都仍恰有九个 baseline purposes，故
`K=9*G<=144` 与 40,768-byte payload cap不变。profile choice、purpose-6 exact alias/profile
digest与 erasure-slot initial full-pair digest必须进入同一个 Keyring bootstrap
intent/receipt；normal capture/selective erasure只接受 ENABLED。authority-only root
bootstrap绝不创建 purpose 6。

在 ENABLED commit之前，`manifests/erasure-control/slot-a|slot-b` 即使已按 future fixed EOF
物理预分配，也只是全零/UNUSED placeholder：不得解析为 progress、不得签擦除 receipt、
不得授权 capture。Keyring bootstrap必须先创建并验证 purpose-6 alias，再在 rank-80 lock下
把两槽初始化为 identity/profile-bound、byte-exact mirrored `ERASURE_IDLE`，file fsync、
parent fsync、close/reopen/full-pair select，最后与 Keyring/alias census一起 joint commit；
任何 partial pair或 alias/profile/installation mismatch均为
`BLOCKED_PARTIAL_BOOTSTRAP`。

purpose 6不随 generation rotation更换；rotation receipt必须证明 alias/profile与 selected
erasure A/B identity保持不变。selective source erasure不得删除它；alias缺失、KeyInfo变化、
old-valid pair、同 generation fork或 slot↔alias digest不一致时停止所有新 capture/rewrite，
只允许已授权 whole-installation reset。Android backup/restore/D2D不得导出 alias；恢复出
slots但无同一 alias时不能补 key或伪造完成。whole reset由 sandbox外 authority在 root、
purpose 5/6/13和全部 generation alias均消失后做 Keystore/filesystem/process zero-census，
不能依赖已经删除的 purpose 6为自身终态签名。

golden/model/fault tests至少覆盖 DISABLED/ENABLED root-alias cardinality、purpose 5/7/13
替代 purpose 6、意外 purpose-6 frontier、alias create/KeyInfo/slot A/slot B/joint receipt
每个 kill点、single/old/fork pair、profile mismatch、rotation保持、backup restore缺 alias、
selective erasure误删 alias与 whole-reset外部终态。

### 5.2 Keystore AEAD

- 本节只适用于 purpose 1/2/5 alias 的 DEK/manifest wrapping，不适用于 §9.3 Blob data
  chunks；
- AES key 为 256 bit；GCM tag 为 128 bit；wrapping IV 必须由 Android Keystore provider
  在每次新 encryption operation 中随机产生且恰好 12 bytes；
- `setRandomizedEncryptionRequired(true)`；不得接受 caller-provided IV；
- operation 在取得 IV 后若发生 crash、异常或结果不确定，整个 ciphertext/object attempt
  永久退休。不得以新 IV 对同一物理 object identity “续写”；
- decrypt 必须先验证完整 tag，再释放 plaintext；streaming 实现只能把已认证的独立
  frame/chunk 交给上层；
- 同一 immutable ciphertext 的底层短写可以继续 `writeFully`，但不得重新调用
  `Cipher.doFinal`。

### 5.3 Journal segment DEK 的 GCM 总用量上限

nonce injective 只证明不复用 nonce，不等于同一 GCM key 可以无限处理数据。ADR 0016 是
Journal frame AAD、payload cap 与下列 registry 的唯一 wire authority；本文导入且不得重定义：

```text
MAX_JOURNAL_FRAMES_PER_SEGMENT_DEK_V1 = 65,536
MAX_TOTAL_GCM_AUTH_BLOCKS_PER_SEGMENT_DEK_V1 = 16,777,216  // 2^24
```

suite 1 frame AAD exact 162 bytes：

```text
ASCII("sense-memory-journal-frame-aad-v1")[33] || 0x00[1] ||
segment_header_digest[32] || exact_frame_header[96]
```

因此每次 frame encryption 的 authenticated-block charge 恰为：

```text
frame_gcm_auth_blocks =
  ceil_div(162, 16)
  + ceil_div(ciphertext_length, 16)
  + 1
= 12 + ceil_div(ciphertext_length, 16)
```

最后 `+1` 是 GCM GHASH length block；suite 1 的
`ciphertext_length == plaintext_length`。所有计算使用 checked u64。writer 必须在
`Cipher.init` **之前**原子预留一次 frame invocation 与 exact block charge；只有满足

```text
next_frame_count <= MAX_JOURNAL_FRAMES_PER_SEGMENT_DEK_V1
AND next_total_gcm_auth_blocks <= MAX_TOTAL_GCM_AUTH_BLOCKS_PER_SEGMENT_DEK_V1
```

才可初始化。达到任一上限或下一 frame 将越界时，正常 seal 当前 segment，创建新
segment identity 与 independent DEK 后继续；seal/计数结果不确定时永久退休该
segment/DEK，不能用相同 DEK 重试。reader/recovery 必须从 exact committed frame bytes
重算 invocation/block totals，任一越界都 quarantine；BudgetProfile 只能要求更早 seal，
不能提高两个 hard caps。

这两个 cap 只约束每个随机 segment DEK 的 frame AEAD usage。它们不替代 purpose-1 wrap
alias 的 operation frontier、nonce reservation 或未来独立的 per-alias usage ceiling，也
不能把 u64 writer sequence 空间解释成密码学安全预算。NIST SP 800-38D Appendix B 要求限制
每 key 的总数据；NIST 2026 年第二次 preliminary call 也把 total data、message length 与
invocations per key 明确列为 usage-bound 维度。本地 `2^24` block cap 是更保守的产品
security ceiling，不是对完整 GCM advantage 的新证明。

每个新 segment在 raw key generation前必须先 durable consume/burn一个 purpose-1
JOURNAL_WRAP operation ordinal，随后才由独立均匀 CSPRNG单次生成 256-bit DEK；
generate/wrap/bootstrap失败、取消或不确定都保留 burn并退休 segment/DEK，不能再 draw
一个 key沿用 ordinal。V1 Keyring
最多 16 generations，每 generation恰一个 purpose-1 alias，每 alias最多 65,536 ordinals，
所以 installation lifetime segment-DEK draws `q <= 16*65,536 = 2^20`。sequence nonce在
每个 DEK内注入；跨 segment raw DEK相等不是数学不可能，而有 birthday union bound
`Pr[任意 segment-DEK collision] <= q(q-1)/2^257 < 2^-217`。recovery结构保证同一已知
DEK handle/ordinal永不复用；future提高 generation/ordinal cap或改变 generator必须重算
并接受新的 bound。

### 5.4 Keystore wrap alias 的随机 IV 用量上限

purpose 1/2/5 使用 provider-random 96-bit GCM IV，必须另有 per-alias invocation ceiling：

```text
MAX_WRAP_GCM_ENCRYPT_INITIALIZATIONS_PER_ALIAS_V1 = 65,536
```

该计数覆盖每次已经开始的 encryption `Cipher.init`，不只统计最终 slot/object commit；
失败、取消、process death 和不确定结果都 burn 一次。`65,536=2^16` 时，独立均匀 96-bit
IV 的 birthday collision 项约小于 `2^-65`，也严于 NIST 对 random-IV construction 的
`2^32` invocation 上限。BudgetProfile 只能提前 rotation/block，不能放宽此常量。

每次 wrap authenticated-block charge（AAD blocks + exact plaintext/ciphertext blocks +
GCM length block）也有固定上界；purpose 1 plaintext 是 32 bytes，purpose 2 的 encrypted
`BlobWrapPayloadV1` 是 256 bytes，purpose 5 的 plaintext/ciphertext 是 65,388 bytes：

```text
purpose 1 JOURNAL_WRAP: 19 blocks/init
  65,536 * 19    = 1,245,184 < 2^21
purpose 2 BLOB_WRAP: 27 blocks/init
  AAD=154 bytes，ciphertext=256 bytes
  65,536 * 27    = 1,769,472 < 2^21
purpose 5 MANIFEST_SEAL: 4,097 blocks/init
  65,536 * 4,097 = 268,500,992 < 2^29
```

实现必须从 exact AAD/ciphertext caps 机械重算这些值；purpose 5 的 bound 只有在后述
attempted-use authority 存在后才可声称可执行。

purpose 1/2 必须在 §7 authenticated operation frontier 中先 reserve/burn ordinal，且
`reserved_through` 永不得超过 65,536；cap 检查发生在 `Cipher.init` 前，达到上限就轮换
alias/generation，rotation authority 不可用则 BLOCKED。u64 field 只是 wire 容器，不是许可。

purpose 5 当前只有 Keyring slot generation，它只证明 committed slot，无法计算 crash 前
已经消耗但未提交的 provider IV。Gate 0 不假装这一点已经解决：未来
`KeyringBootstrapControlPhaseGateV1` 的 exact wire 必须增加 root-scoped、authenticated
MANIFEST_SEAL attempted-use frontier/receipt，冻结其 MAC key、bootstrap/recovery、burn、
ceiling 与 alias rotation；在此之前 purpose 5 encryption runtime、
`KeyringBootstrapControlPhaseGateV1`、`KeyringBootstrapCapabilityGateV1` 和
`PERSISTENT_SUBSTRATE` 均保持 BLOCKED。不得把
slot generation、process-local counter或“碰撞概率很小”替代该 authority。

future `ManifestSealAliasLifecycleV1` 对每个 root-scoped purpose-5 alias 必须冻结
`PREPARED|CURRENT|RETIRING|DESTROYED`、alias identity/profile digest 与一份独立、可恢复的
attempted-use frontier。frontier control authority 必须与被计数的 purpose-5 encryption key
及 purpose-13 owner-lease key domain separation；若需要新 purpose，先提升本 ADR registry，
不得偷偷复用 5/13。每次 encryption `Cipher.init` 前先 durable reserve并永久 burn ordinal；
slot generation与成功 slot数不能代表 failed/indeterminate init。old CURRENT 在 committed
rotation receipt后才 RETIRING、frontier永久封口，之后零新 reserve/init。

rotation/bootstrap 的目标是两个 independently valid slot commits，不是“恰好两次 init”。
future control在结果前绑定 closed `max_slot_attempts`、ordinal range与 attempt schedule
（不超过 alias剩余 65,536）；每个 attempt绑定 label、slot generation、ordinal、IV/
ciphertext/full-slot digest与 closed disposition。failed/indeterminate attempt burn，budget
耗尽则受控 BLOCKED/abort，不能临时 top-up。exact constant/wire未冻结前相关 gates继续
BLOCKED。

NIST SP 800-38D Appendix B 还要求系统监控并在必要时限制 unsuccessful authentication
attempt。future control必须为每 alias冻结 authenticated-decryption failure/tamper latch或
等价 bounded policy：同一 invalid physical digest最多做一次 full verification，失败后
durable quarantine/fail closed，重启不得无限重试；exact cap、authority与recovery尚未接受，
因此 Gate 0 不声称完整 GCM authenticity-advantage bound。测试覆盖重复 invalid inode/
digest、跨 reboot、OEM provider异常与 latch corruption。

---

## 6. `KeyAuthorizationProfileV1`

它是**请求授权契约的 canonical 64 bytes**，用于 digest 和差异审计；它不是
`KeyInfo` 的序列化，也不能因为 OEM 少一个 getter 就伪造“observed equals requested”。

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 4 | magic ASCII `SKAP` |
| 4 | 2 | profile major = 1 |
| 6 | 2 | profile minor = 0 |
| 8 | 2 | memory key purpose id |
| 10 | 1 | algorithm：1=AES，2=HMAC |
| 11 | 1 | key-size code：1=256 bit |
| 12 | 1 | block mode：0=none，1=GCM |
| 13 | 1 | padding：0=none |
| 14 | 1 | digest policy：0=none，1=SHA-256 |
| 15 | 1 | origin requirement：1=AndroidKeyStore generated |
| 16 | 4 | requested flags |
| 20 | 4 | auth timeout seconds；无 auth 时为 `0xffffffff` |
| 24 | 4 | requested user-auth type；无 auth 时 0 |
| 28 | 2 | minimum Android API = 29 |
| 30 | 2 | highest certified API；Gate 0 为 36 |
| 32 | 4 | Android KeyProperties purposes bitmap |
| 36 | 28 | reserved zero |

requested flags：

| bit | 含义 |
|---:|---|
| 0 | user authentication required |
| 1 | unlocked device required |
| 2 | randomized encryption required |
| 3 | StrongBox required |
| 4 | rollback resistance requested |
| 5 | biometric-enrollment invalidation requested |
| 6..31 | reserved zero |

AES profile 必须设置 bit 1、2，清除 bit 0、5；HMAC profile 必须设置 bit 1，清除 bit 0、2、5。
StrongBox 和 rollback resistance 只能由具体 build profile 显式要求；不能在设备不支持时
悄悄降级。`authorization_profile_digest = SHA-256(exact_64_bytes)`。

M9.0 accepted profile classes 恰好如下；`KeyProperties` bitmap 使用 Android 常量值
`ENCRYPT=1, DECRYPT=2, SIGN=4, VERIFY=8`：

| purposes | algorithm | block_mode | padding | digest_policy | requested flags | KeyProperties purposes bitmap |
|---|---:|---:|---:|---:|---:|---:|
| `{1,2,5}` | 1=AES | 1=GCM | 0=NONE | 0=NONE | bits 1+2 = `0x00000006` | `1\|2 = 0x00000003` |
| `{3,6,7,8,9,10,11,12,13}` | 2=HMAC | 0=NONE | 0=NONE | 1=SHA-256 | bit 1 = `0x00000002` | `4\|8 = 0x0000000c` |
| `{4}` | 无 | 无 | 无 | 无 | 无 | RESERVED；任何 profile 非法 |

两类的 key-size code 都是 1（256 bit）、origin requirement=1、auth timeout=`0xffffffff`、
user-auth type=0、minimum API=29、highest certified base API=36；reserved bytes 全零。
baseline 不请求 StrongBox/rollback resistance（bits 3/4=0）。未来若 build profile 要求
bits 3/4，必须创建新 alias/profile digest 并重新做设备 gate，不能原地改变这 64 bytes。
purpose 13 使用 HMAC 行，profile digest 写入 owner.lease；purpose 5 使用 AES 行，二者
不可互换。

### 6.1 requested 与 observed 的双重验证

创建 alias 后立刻取 `SecretKeyFactory("AndroidKeyStore")` 的 `KeyInfo` 并做功能负测。每个
授权属性必须分别保存：

```text
requested = TRUE | FALSE
observed  = TRUE | FALSE | UNKNOWN
observation_source = KEY_INFO | FUNCTIONAL_TEST | UNOBSERVABLE
comparison = MATCH | MISMATCH | UNOBSERVABLE
```

`KeyAuthorizationProfileV1` 只是 requested receipt。它绝不等于 observed equality，也不能
把 `UNKNOWN` 写成 false、true，或从另一个 API/OEM 的结果继承。

| API | 必须直接核验 | 补充/限制 |
|---|---|---|
| 29–30 | `KeyInfo` 当时公开的 algorithm、key size、purposes、block modes、paddings、digests、origin、user-auth、`isInsideSecureHardware` | `setUnlockedDeviceRequired` 自 API 28 可请求，但 `isUnlockedDeviceRequired` 不可观察，必须为 UNKNOWN。官方 Builder 说明 API 30 及以下对 symmetric encryption/verification 存在不受该限制的兼容行为，不能要求或伪造锁屏失败 |
| 31–34 | 上述公开字段；API 支持时记录 `securityLevel` | unlocked-device requirement 仍无 getter，必须 UNKNOWN；官方 Builder 记录 API 34 及以下已知兼容缺陷，功能实测只记录行为，不提升为 observed equality |
| 35–36.0 | 上述公开字段与锁定/解锁功能实测 | unlocked-device requirement 仍无 `KeyInfo` getter，必须 UNKNOWN；即使一次功能负测通过，也不能把 requested receipt 改成 OBSERVED |
| 36.1+ | 单独 capability/build-fingerprint 分支；直接读取 `KeyInfo.isUnlockedDeviceRequired()`，并重复功能负测 | 只有 getter 返回 true、其它关键授权均可验证且功能负测通过，才可能为该设备 evidence 提供 observed equality；不得仅凭 `SDK_INT=36` 归入 36.1 |

### 6.2 `KeyAuthorizationProfileGateV1`

该 gate 只裁决 canonical requested receipt 与平台可观察字段：

- observable 且相等 → MATCH；
- observable 且不等 → MISMATCH，gate BLOCKED；
- 平台没有 getter → observed=UNKNOWN、comparison=UNOBSERVABLE；
- UNOBSERVABLE 被诚实记录且其它 observable 字段无 mismatch 时，本 gate 可以 PASS，
  但它**没有**证明锁定行为；不得把 UNOBSERVABLE 改名 MATCH。

API 36.1+ 的 unlocked getter 必须 MATCH；API 29–36.0 保持 UNOBSERVABLE。

### 6.3 `UnlockedKeyBehaviorGateV1`

每个 exact `(APK digest, signer identity, build fingerprint, API/extension, OEM, key profile
digest)` 必须在实体设备上执行：

1. 记录锁定前、锁定后、再次解锁后的 encryption/HMAC 实际结果；API 29–34 的兼容行为不得
   被 test harness 当成 profile mismatch，API 31–36.0 的 getter comparison 仍保持
   UNOBSERVABLE；
2. AES 以 caller IV 初始化 encryption 必须失败；
3. wrong AAD、wrong tag、wrong alias、wrong generation 必须失败且不释放 plaintext；
4. alias 删除后新 operation 必须失败；
5. reboot-before-first-unlock、first unlock、screen/device lock、unlock、secure lock removal、
   weak-biometric-only attempt 与进程重启；
6. required AES purposes `{1,2,5}` 与所有实际创建的 HMAC purposes（包含 13）逐一覆盖；
7. 36.1+ 把 getter evidence 与 behavior evidence 分开保存，二者冲突即 BLOCKED。

expected result matrix 固定如下：

| condition | AES encrypt/decrypt 与 HMAC sign/verify init/use | gate result |
|---|---|---|
| `KeyguardManager.isDeviceLocked == true` | 每个已创建 alias 都必须拒绝 init 或 use；不得有任何成功 output | 任一成功即 security FAIL |
| strong device credential unlock，runtime fence/current unlock generation 均有效 | 每个 required alias 的正向 vector 必须成功，negative AAD/tag/purpose vector 仍失败 | 全部符合才可 PASS |
| secure lock 被移除且 Keystore 删除/永久 invalidates alias | 不得重新生成 alias 或 installation，不得把 `KeyPermanentlyInvalidatedException` 当首次安装 | installation BLOCKED，等待显式受审恢复/清除流程 |
| API 31–34 仅 weak biometric 后 alias 仍不可用 | 不重建 key、不降低授权、不判 device certification FAIL | `USER_REAUTH_REQUIRED`，persistent Memory 保持 BLOCKED，直到 strong credential |
| locked/unknown state、runtime fence 改变或无法区分 strong unlock | 不得尝试“先用一次看看”并保留结果 | BLOCKED |

lock transition 必须覆盖 alias 已取得、Cipher/Mac 已初始化以及 operation 正在执行三种时序；
任何在 lock fence 后产生或提交的 ciphertext/MAC 都是 security FAIL，即使稍后再次解锁。

API 29–30 因官方 symmetric-operation 例外固定为 `UNSUPPORTED`，Memory persistent stage
最高 SCHEMA_ONLY，除非未来 alternate-crypto ADR 改变设计。API 31–36.0 虽 unlocked getter
UNOBSERVABLE，但 exact-device behavior matrix 可独立 PASS；证据不得跨 fingerprint/OEM/升级
复用。36.1+ 还要求 getter MATCH。

### 6.4 `CredentialUnlockedRuntimeGateV1`

runtime gate 与 profile/实验 gate 独立。每次下列动作前必须同步检查：

```text
credential-encrypted context
AND directBootAware == false
AND UserManager.isUserUnlocked == true
AND KeyguardManager.isDeviceLocked == false
AND no device-protected mirror
AND current unlock_generation/fence unchanged
```

动作包括 open root/file、persistent ID/sequence/ordinal allocation、Cipher init、queue
admission、frame/chunk serialization、Binder/pipe/Provider byte handoff 与 publish。收到 lock/
user-stop/credential unavailable 后立即推进 unlock generation/fence，拒绝迟到 callback，
取消并清理未发布 attempt；不得等下一次 Activity resume。

### 6.5 `KeyUseSafetyGateV1`

```text
KeyUseSafetyGateV1 = PASS iff
  KeyAuthorizationProfileGateV1 == PASS
  AND UnlockedKeyBehaviorGateV1 == PASS
```

这是 subject-bound **static enforcement capability evidence**：UnlockedKeyBehavior evidence
必须覆盖 §6.3/§6.4 runtime-fence implementation在锁定、transition、in-flight operation与
迟到 callback上的实体 negative suite，但不缓存“用户当前已解锁”这个瞬时事实。
`CredentialUnlockedRuntimeGateV1` 始终是每次 operation OBSERVE_AND_REVALIDATE 的独立
dynamic gate；锁屏后即使 static KeyUseSafety receipt未变，下一 effect/byte也必须拒绝。
这允许 API 31–36.0 在不伪造 getter MATCH 的情况下，由独立 behavior evidence + current
runtime fence提供可落地的锁定安全；API 29–30 明确保持 SCHEMA_ONLY。只有
`ReleaseOwnerContinuityGateV1`、`LocalErasureControlPhaseGateV1` 与其它持久化 subgates 也 PASS
后，KeyUseSafety PASS 才可能允许 synthetic DARK；当前 Gate 0 仍未获准创建 key/root。

---

## 7. Crypto operation frontier

每个 generation-scoped purpose 1/2 AES alias 都有独立 frontier pair；其 MAC 使用同一
GenerationRecord 内的 purpose 7 alias。root-scoped purpose 5 的提交高水位由 Keyring
slot generation/A-B protocol 承担，但它不覆盖 failed/indeterminate init；因此 §5.4
明确把 purpose 5 runtime 与 Keyring bootstrap gate 保持 BLOCKED，不能把本节的 absence
理解为已解决：

```text
manifests/operation-frontier/.sense-memory.namespace.lock
manifests/operation-frontier/<lowercase-hex-SHA256(alias)>/.sense-memory.namespace.lock
manifests/operation-frontier/<lowercase-hex-SHA256(alias)>/frontier-a
manifests/operation-frontier/<lowercase-hex-SHA256(alias)>/frontier-b
```

目录名必须恰好 64 lowercase hex。frontier 文件固定 256 bytes：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 8 | magic ASCII `SMOPF001` |
| 8 | 2 | major=1 |
| 10 | 2 | minor=0 |
| 12 | 4 | slot length=256 |
| 16 | 16 | installation_id |
| 32 | 16 | keyring_id |
| 48 | 16 | key_generation_id |
| 64 | 2 | key purpose id |
| 66 | 2 | state：0=UNUSED，1=COMMITTED |
| 68 | 4 | reserved zero |
| 72 | 8 | slot_generation |
| 80 | 8 | reserved_through operation ordinal |
| 88 | 8 | reservation_block_size；0 only for initial baseline，otherwise 1..1024 |
| 96 | 32 | alias_digest |
| 128 | 32 | authorization_profile_digest |
| 160 | 32 | previous_committed_slot_digest；bootstrap 为零 |
| 192 | 32 | reserved zero |
| 224 | 32 | HMAC-SHA-256，purpose 7 key |

frontier identity digest 固定为：

```text
SHA-256(
  ASCII("sense-memory-operation-frontier-identity-v1") ||
  0x00 ||
  installation_id || keyring_id ||
  u16be(key_purpose_id) || key_generation_id ||
  alias_digest || authorization_profile_digest
)
```

MAC exact 输入：

```text
HMAC-SHA-256(
  K_PURPOSE_7_SAME_GENERATION,
  ASCII("sense-memory-operation-frontier-v1") ||
  0x00 ||
  frontier_identity_digest[32] ||
  exact_slot_bytes[0,224)
)
```

`[0,224)` 不包含 MAC field。UNUSED slot 仍具有完整 magic/version/identity，
其 state、generation、高水位、block size、previous digest 为零，并具有有效 MAC；
全零文件不是 UNUSED。

`committed_slot_digest = SHA-256(exact_256_slot_bytes)`。

bootstrap 只允许在 Keyring bootstrap lock、全空 dependent-data census 和刚创建 target/purpose7
aliases 下执行：

```text
frontier-a =
  COMMITTED(slot_generation=1,
            reserved_through=0,
            reservation_block_size=0,
            previous_digest=ZERO32)
frontier-b =
  identity-bound canonical UNUSED
```

两槽分别 file fsync、parent fsync、close/reopen/full reread 后，selector 必须选 A。首个
reservation `n` 写 B generation=2、through=n、block=n、previous=SHA256(exact A)，因此首次
发放区间精确为 `(0,n]`。

selector/state 规则：

| valid pair | rule |
|---|---|
| initial COMMITTED g1 / canonical UNUSED | 选 g1 baseline |
| two COMMITTED, generations adjacent | 高槽 `through=low.through+high.block`，block 1..1024，previous digest 必须等于 exact low slot digest；选高槽 |
| two COMMITTED, same generation/exact bytes | 可只读选择；下一次 reservation 以 deterministic A 作为 low、写 B generation+1 |
| two COMMITTED, same generation/different bytes | fail closed |
| one valid COMMITTED + one invalid | `FRONTIER_SINGLE_SLOT_UNCERTAIN`；该 alias 永久禁止 reserve/init/repair，仅可由受控 rotation 切到全新 alias 或保持 BLOCKED |
| one valid COMMITTED + canonical UNUSED | 仅合法 initial g1；其它 generation fail closed |
| no valid COMMITTED | fail closed；只有全空 bootstrap authority 可重新开始 |
| identity mismatch、generation gap>1、through 回退、previous 错 | fail closed |

单个 MAC-valid 槽不能证明自己是 latest：攻击者、恢复错误或旧 snapshot 可以提供任意旧
valid slot 并使 peer invalid；丢失 peer 可能代表的不只是“下一代至多 1,024 个 ordinal”。
因此 v1 不做 burn-forward，也不允许基于单槽继续使用原 alias。只有完整 adjacent pair
或 canonical bootstrap `g1/UNUSED` 才能授权 reserve。future 若要恢复单槽，必须引入独立、
rollback-resistant attempted-use head witness，证明 source exact slot digest 是 current；
仅增加 repair intent、fresh IV 或多 burn 1,024 都不足以证明 freshness。

正常 reservation 在跨进程 exclusive lock 内把
`reserved_through = old + n`（`1 <= n <= 1024`）写入 inactive/较低 generation 槽，
`slot_generation=old+1`、`block=n`、`previous=digest(old)`，file `force(true)`，关闭并重新
打开，然后完整复读两个槽。只有 selector 的高水位精确等于预期，调用者才取得
`(old.through, new.through]`。crash 后未用 ordinal 永久 burn；不得回收空洞。任一 checked
u64 addition overflow，或 purpose 1/2 的 new `reserved_through > 65,536`，永久阻断该
alias，必须走受控 rotation；cap 检查必须先于 reservation commit 和 `Cipher.init`。

一个 operation ordinal 只标识尝试与审计高水位，不替代 Keystore 随机 IV。单槽或两槽
不可验证都 fail closed；单槽仍可用于只读诊断，但绝不能授权该 alias 的下一次
`Cipher.init`。完整旧 pair 回滚仍是第 2.2 节的已知限制，故 v1 usage bound 的安全声明以
“没有完整旧 pair replay，且每次 init 前 full-pair selector 通过”为前提，不得通过
“再加一”宣称已检测。

---

## 8. Keyring fixed-slot wire

路径固定为：

```text
manifests/keyring/.sense-memory.namespace.lock
manifests/keyring/keyring-a
manifests/keyring/keyring-b
```

每槽恰好 65,536 bytes：

| 区域 | bytes | 说明 |
|---|---:|---|
| clear header | 128 | 明文、canonical |
| ciphertext | 65,388 | 固定宽度 encrypted payload region |
| tag | 16 | GCM tag |
| commit | 4 | ASCII `KRCM`；不替代 tag |

物理和必须机械等于：

```text
128 + 65,388 + 16 + 4 = 65,536
```

exact AAD 恰好 144 bytes：

```text
ASCII("sense-keyring-v1")[16] || exact_clear_header[128]
```

`keyring_slot_digest = SHA-256(exact_65,536_physical_bytes)`；rotation control/receipt 中的
`g+1/g+2` digest 均指该值。

### 8.1 Clear header 128 bytes

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 8 | magic ASCII `SMKRNG01` |
| 8 | 2 | major=1 |
| 10 | 2 | minor=0 |
| 12 | 4 | slot length=65536 |
| 16 | 1 | slot label：0=A，1=B |
| 17 | 1 | state：0=UNUSED，1=COMMITTED |
| 18 | 2 | flags=0 |
| 20 | 8 | required_features=0 |
| 28 | 8 | slot_generation |
| 36 | 16 | clear installation_id |
| 52 | 16 | clear keyring_id |
| 68 | 16 | manifest-seal root alias generation ID；不对应 GenerationRecord |
| 84 | 32 | manifest-seal alias_digest |
| 116 | 12 | provider-generated GCM IV |

UNUSED 槽的 IV、generation、ciphertext、tag 均为零；它只在 bootstrap
锁内存在，不能被 generic selector 选为 manifest。

### 8.2 Encrypted payload region

解密后固定 65,388 bytes。前 `payload_length` 是 canonical payload，剩余 byte 必须全零；
`payload_length > 40,768` 或小于 64 必须拒绝。

`KeyringPayloadHeaderV1` 恰好 64 bytes：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 8 | magic `SMKRP001` |
| 8 | 2 | major=1 |
| 10 | 2 | minor=0 |
| 12 | 4 | header_length=64 |
| 16 | 4 | payload_length |
| 20 | 8 | required_features=0 |
| 28 | 16 | decrypted installation_id |
| 44 | 16 | decrypted keyring_id |
| 60 | 2 | generation_record_count |
| 62 | 2 | key_record_count |

随后先放全部 96-byte `GenerationRecordV1`，再放全部 272-byte `KeyRecordV1`；长度必须精确
等于 `64 + 96*G + 272*K`，不得有 extension。M9.0 每 generation 恰有九个 baseline
KeyRecord，所以 `G <= 16`、`K == 9*G`、`K <= 144`，最大 canonical
payload 精确为：

```text
64 + 16*96 + 144*272 = 40,768
```

baseline 的每个 GenerationRecord 必须恰好关联 9 个、purpose 严格升序的 KeyRecord：
`{1,2,3,7,8,9,10,11,12}`。purpose 5 由 clear header 绑定的 root alias 管理，purpose 4/6
不得混入 baseline。

`GenerationRecordV1`：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 16 | key_generation_id |
| 16 | 8 | generation_ordinal；初始为 1，之后 checked `+1` |
| 24 | 2 | state：1=PREPARED，2=ACTIVE，3=RETIRING |
| 26 | 2 | flags=0 |
| 28 | 8 | reserved zero；不得解释为 created_operation_ordinal |
| 36 | 8 | purpose bitmap；bit `(purpose_id-1)` |
| 44 | 32 | generation_digest |
| 76 | 20 | reserved zero |

`KeyRecordV1`：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 16 | key_generation_id |
| 16 | 2 | purpose id |
| 18 | 2 | algorithm id |
| 20 | 2 | alias_length；`1..96` |
| 22 | 2 | state，与 generation 一致 |
| 24 | 64 | exact KeyAuthorizationProfileV1 |
| 88 | 32 | alias_digest |
| 120 | 32 | reference_digest，M9.0 必须全零 |
| 152 | 8 | reference_count，M9.0 必须为零 |
| 160 | 96 | alias canonical ASCII，尾部补零 |
| 256 | 16 | reserved zero |

`reference_digest/reference_count` 在 M9.0 只是保留字段，**不是 alias 删除权限**。删除必须
由全命名空间 census、locator 收敛、lease drain、erasure/rotation control 和重启验证共同
授权。

`generation_digest` 的 exact 输入为：

```text
SHA-256(
  ASCII("sense-keyring-generation-v1") ||
  key_generation_id ||
  u64be(generation_ordinal) ||
  u16be(state) ||
  u64be(purpose_bitmap) ||
  for each exact KeyRecord sorted by purpose_id:
    SHA-256(exact_272_byte_KeyRecord)
)
```

purpose 重复、次序不严格升序、bitmap 与 records 不一致都拒绝。物理 commit 只识别短写；
真实性仅由 exact 144-byte AAD 的 GCM tag 决定。

canonical global order/identity 还必须满足：

- GenerationRecord 按 `generation_ordinal` unsigned 严格递增；ordinal 与
  `key_generation_id` 各自唯一，初始为 1、无 duplicate；
- KeyRecord 按其 GenerationRecord ordinal、再按 `purpose_id` 严格递增；每个
  `key_generation_id` 必须恰好映射一个 GenerationRecord，不接受 orphan；
- 每个 generation 恰有 `{1,2,3,7,8,9,10,11,12}` 九条，state 与 GenerationRecord
  physical state 相同；缺失、额外、重复、跨 generation 混排全部拒绝；
- `key_record_count == 9*generation_record_count` 必须 checked 计算后再验证。

golden/fuzz 必须覆盖 generation/key permutation、duplicate ID/ordinal/purpose、orphan、
state mismatch、count product overflow/不等与 canonical ordering。

reader 顺序固定：先检查 exact file length 与末尾 `KRCM`，再 canonical-parse 128-byte clear
header；按 root alias generation ID 重建 purpose 5 alias 并比对 alias digest；以 exact AAD
验证全部 65,388-byte ciphertext/tag；最后读取 decrypted payload length/counts、验证
`G<=16`、`K==9*G<=144`、`payload_length<=40768` 与尾部全零。任何一步失败都不能把未认证 count 用于分配。

唯一命名 digest：

```text
keyring_payload_region_digest =
  SHA-256(exact decrypted 65,388-byte region, including required zero padding)
```

它不是只 hash `payload_length` bytes；这样 padding bitflip 也不能在 selector/repair intent
中被忽略。下文所有 “payload digest” 均指
`keyring_payload_region_digest`，禁止实现另造近似摘要。

### 8.3 Stable identity 与 A/B selector

generic selector 的 stable identity **只有**：

```text
(format major/minor, decrypted installation_id, decrypted keyring_id)
```

clear IDs 必须与 decrypted IDs byte-equal，但 clear slot generation、slot label、seal alias、
seal generation、IV、record count 和 payload 都是可变 versioned state，
不得加入 stable identity。

两槽处理：

1. 独立检查长度、canonical 字段、alias、authorization profile、tag、payload 和 commit；
2. 不因一槽失败而从其字段推断另一槽；
3. 两个 valid committed 槽必须 stable identity 相同；
4. generation 相同则 `keyring_payload_region_digest` 必须相同，且 clear semantic version
   fields（magic/version/length/state/flags/required_features/slot_generation、clear
   installation/keyring ID、manifest-seal alias generation ID 与 alias digest）全部
   byte-equal；只允许 slot label、provider IV、ciphertext/tag/commit 因 independent
   encryption 不同。满足时 deterministic 选择 A 作为 semantic base；mixed purpose-5
   alias/generation/digest 即使 payload 相同也 fail closed。generation 不同只能相差 1；
5. 选择较高 generation；只有一槽 valid 时只返回
   `READ_ONLY_SINGLE_SLOT_UNCERTAIN + KEYRING_REPAIR_PHASE_BLOCKED`，禁止 writer、rotation、alias
   delete、Keyring commit 或把该状态计作 `PERSISTENT_SUBSTRATE` PASS；
6. 写入 inactive/较低槽后必须 file fsync，selected 文件 fsync，关闭，重新打开，再完整复读
   pair；内存中的刚写 bytes 不是提交证据；
7. identity 冲突、非法代差、两个 invalid 或无法认证都 fail closed；
8. 选中 payload 含 PREPARED/RETIRING 时，裸 Keyring selector result 必须携带
   `ROTATION_CONTROL_REQUIRED`，不能自行返回 writable current；只有 §8.5 的
   rotation-aware reducer 可在 exact COMMITTED receipt 下产生 effective ACTIVE；
9. selector 不能检测完整旧 pair rollback，必须向上暴露 freshness evidence 不足。

single-valid source 不能证明自己是 current：一个旧但 GCM-valid 的 slot 加 invalid peer
与随机单槽损坏不可区分。复制其 payload 会把旧 generations/aliases 固化成 current；fresh
IV、purpose-5 新 ordinal 与 repair receipt 只能证明新写，不能证明 source freshness。
因此当前 runtime 没有 repair API，“后台安排 repair”也不得偷偷执行 encryption。

future `KeyringBootstrapControlPhaseGateV1` 只有同时冻结以下 exact transaction，且
`KeyringBootstrapCapabilityGateV1` 已以实体 kill/reopen 证据通过，才能把
该状态从 BLOCKED 改为可修复：

1. 独立 rollback-resistant keyring-head witness 先证明 source full-slot digest 是当前唯一
   accepted head；普通 A/B slot、mtime、wall clock、slot generation 或 owner wrapper 都
   不能充当该 witness。不存在 witness 时永久 read-only/BLOCKED；
2. canonical repair intent 绑定 stable identity、witness digest、source full-slot digest、target slot label、
   checked `old_generation+1`、purpose-5 alias generation、expected
   `keyring_payload_region_digest` 与 root-scoped attempted-use ordinal；
3. 在 `Cipher.init` 前从 authenticated purpose-5 frontier reserve/burn ordinal，并证明
   ordinal `<= MAX_WRAP_GCM_ENCRYPT_INITIALIZATIONS_PER_ALIAS_V1`；generation/cap
   exhausted 时零 init、零 slot write，进入 controlled rotation 或 BLOCKED；
4. 使用 fresh provider IV 和 target AAD 重新加密同一 canonical payload，写 inactive peer，
   file fsync、close/reopen、full-pair reread/reselect；
5. durable receipt 绑定 intent、witness、burned ordinal、new full-slot digest 与 selected pair；
   init 后的 crash/取消/不确定结果永久 burn ordinal，不得按 slot generation 猜测未使用。

repair 不改变 decrypted stable identity/payload 语义，不删除唯一 source slot；任一步无法
证明时仍保持 single-slot read-only/BLOCKED。

### 8.4 Authority bootstrap 与 keyring bootstrap 的不可合并边界

owner continuity 不能依赖“先创建 keyring”，keyring 也不能在 owner authority 不明时自行
创建。为避免循环依赖，Gate 0 只接受下面这个**窄语义边界**，没有接受它的持久化 wire：

```text
ReleaseIdentityGateV1 == PASS
AND RootBootstrapControlPhaseGateV1 == PASS
AND LocalErasureControlPhaseGateV1 == PASS
AND clean credential-protected root/alias census == PASS
  -> one-shot AuthorityBootstrapPermitV1
```

`AuthorityBootstrapPermitV1` 不是 `FeatureStage`，不能由 UI flag、实验开关或 synthetic
`DARK` 获得。它的一次性权限只能创建：

1. 固定的空 Memory root shell；
2. 单一 bootstrap lock/control shell；
3. 由 ADR 0015 external `DataOwnerIdentityV1` 签名并绑定 installed artifact 的 local owner
   A/B/control metadata。

它**不得**创建 purpose 5/13 或任何 generation alias，不得生成
`installation_id/keyring_id/generation_id`，不得写 keyring、operation frontier、owner
lease、Journal、Blob、index、正文、DEK 或其它 dependent data。owner A/B/control 必须
close/reopen 后仍独立验证，并与 exact accepted/observed owner-signed cut byte-exact 绑定，
且所有 observed candidates 无 fork、installed artifact binding 有效；之后
`ReleaseOwnerContinuityGateV1` 才可能按 scoped semantics PASS。globally latest/head
仍只由 `StageRevocationFreshnessGateV1` 裁决。

当前 `RootBootstrapControlPhaseGateV1=BLOCKED`：`RootBootstrapControlV1`、
`OwnerStateV1`、`AuthorityBootstrapIntentV1` 的 exact path、固定布局、锁语义、inode
identity、commit/recovery selector、digest/signature coverage、容量 cap 与 golden/fuzz
尚未冻结。因此当前不得创建 root shell 或 owner state，root/alias census 必须为零。任何
已存在的 partial root、alias 或不完整 owner state 都进入 `BLOCKED_PARTIAL_BOOTSTRAP`：
reader 不得枚举后猜测 alias、删除未知状态、补写另一槽或创建第二 installation。

只有 durable reopen 后 `ReleaseOwnerContinuityGateV1=PASS`，未来独立接受
`KeyringBootstrapControlPhaseGateV1`，且 normal product 已有
`KeyringBootstrapCapabilityGateV1=PASS`，才可授权 product keyring bootstrap。clean-lab
special transaction 只以前者和外部 containment 为前置，并负责产出后者的证据，不能把
capability 自己作为实验前置。其**目标顺序**如下，但当前不是可执行协议：

1. 取得未来冻结的 keyring/bootstrap intent 与 root bootstrap跨进程锁，重新证明 owner
   continuity、空 dependent-data census 和 exact partial-state recovery disposition；
2. 仍持 root lock，按 parent→child创建并 durability验证 fixed data-plane parents：
   `journal/open`、`journal/open/.sense-memory.namespace.lock`、三个 writer parent、
   `blobs`、`manifests/{keyring,operation-frontier,blob-locator,erasure-control}`、
   `manifests/blob-locator/bootstrap-control`及其 fixed control A/B、
   `index/{projection,hot-snapshot}`、`quarantine`、`temp`，以及
   `NamespaceMutationLockMapV1`要求的每个 fixed ordinary-parent lock；每个 mkdir/lock都
   file/child/parent fsync并 close/reopen，禁止在祖先缺失时先写 child；
3. CSPRNG 生成一次 `installation_id`、`keyring_id` 与 generation ID，创建 root-scoped
   purpose 5/13 aliases 与 baseline exact 九用途 generation aliases，
   逐个通过 `KeyUseSafetyGateV1`；purpose 4永不创建。若且仅若
   `LocalErasureControlPhaseGateV1=PASS` 且 intent选择
   `ERASURE_CONTROL_ENABLED`，还创建 §5.1.1 root-scoped purpose 6并验证独立 profile；
4. 为 baseline purpose 1/2 aliases 创建由同 generation purpose 7 认证的 operation
   frontier；purpose 6不得创建 operation frontier；
5. 仍持 root lock并取得 data-plane namespace lock，为每个 writer写出由同一 durable
   bootstrap intent预承诺的 purpose-13 `owner.lease` bytes；close/reopen structural/
   inode/CRC验证后按 canonical writer顺序取得且持续持有三个
   `ProvisionalOwnerLeaseHandleV1`，但暂不授予普通 owner authority。然后构造 ACTIVE
   generation ordinal=1，取得 rank-40 keyring lock，写完整 keyring A/B pair，分别 file fsync、
   close/reopen并在仍持两级 bootstrap locks时选出 staged pair；
6. 只从该 selected staged payload重建 installation ID、purpose-13 alias/profile，要求与
   intent/lease逐字节一致，再验证每个 lease MAC并把同一三个 provisional handles原地
   promotion为 authenticated owner handles，禁止 unlock/reopen/reacquire；随后物理预分配
   `manifests/erasure-control/slot-a|slot-b` fixed EOF；ENABLED
   branch必须在 rank-80 lock下以 purpose 6初始化 mirrored `ERASURE_IDLE`，DISABLED branch
   保持全零/UNUSED且不能授权 capture。再对文件、
   parent directories、alias census、frontier、lease 与 A/B pair 做
   fsync/close-reopen/full-census/physical-charge验证；只有未来 bootstrap receipt 把 exact
   intent、owner state、aliases、pair digests、frontiers、完整 parent tree、erasure slots、
   两级 lock与 leases 全部绑定并发布
   `KEYRING_BOOTSTRAP_COMMITTED` 后，dependent data/epoch bootstrap 才可用。

任何 crash 后不能由未来 canonical intent 唯一恢复上述 exact step 时都保持 BLOCKED；不得
重复生成 ID、删除 alias 或沿用本节旧版的“`A generation=1 + B UNUSED` 自动补 B”规则。
`installation_id/keyring_id` 一旦由成功 receipt 提交就永久稳定，generation rotation
不能改变它们。

future model/golden必须覆盖 lease先写、A-only、B-only、两槽完成但未 select、selected pair
与 lease installation/profile不等、purpose-13 alias/profile不等、lease已占用、逐个 owner
lock后 crash、receipt前后 crash。joint receipt前 staged pair只能被 bootstrap reducer在
仍持 root/data-plane locks时读取，不能进入 normal selector、writer、recall或
`PERSISTENT_SUBSTRATE`；partial pair只能按同一 intent/attempt ledger exact-resume，不能
重抽 ID/IV/ordinal或把 single-valid slot当 authority。

### 8.5 Rotation 与 PREPARED veto

只有 main `MemoryBroker` 的未来 `RotationControlV1` authority 可以创建 PREPARED generation。
writer、IME、`:brain` 和 recovery scanner 都不能把“看起来较新”的 PREPARED 设为 current。

在 phase schema 尚未接受的当前阶段：

- `prepareRotation()`、`activateRotation()`、`retireAlias()` 一律
  `FEATURE_STAGE_BLOCKED`；
- Keyring 中出现 PREPARED/RETIRING 记录时，只允许读取旧 ACTIVE，所有新写与删除阻断；
- 不允许临时 JSON、SharedPreferences flag 或时间戳充当 rotation control。

未来 rotation 至少要证明：

1. 在写 `RotationControlV1` request、生成 generation/alias ID、创建 alias/frontier、reserve
   purpose-5 use、调用 `Cipher.init` 或写 slot **之前**，对 selected keyring pair 一次性
   checked 证明：

   ```text
   slot_generation g <= UINT64_MAX - 2
   g1 = checked_add(g, 1)
   g2 = checked_add(g, 2)

   active_generation_ordinal < UINT64_MAX
   new_generation_ordinal = checked_add(active_generation_ordinal, 1)

   generation_record_count + 1 <= 16
   key_record_count + 9 <= 144
   new purpose-5 usage authority has the precommitted bounded attempt range
   needed to obtain 2 successful slot commits
   ```

   任一失败返回 `ROTATION_GENERATION_EXHAUSTED` 或
   `KEYRING_CAPACITY_EXHAUSTED`，保持 old ACTIVE read-only，零 control/ID/alias/frontier/
   init/slot side effect；不得 wrap/reset generation。base `g=MAX-2` 可完成 terminal
   `g+1/g+2` pair，active ordinal `MAX-1` 可产生 terminal ordinal MAX；该 terminal pair
   后续不得再 rotation，只能走 future format migration 或显式受审 reset/erasure。
2. **先恢复** canonical `RotationControlV1`；control 不可恢复时，generic keyring selector
   即使能读取旧 ACTIVE，也必须 veto writer、rotation 与 delete；
3. durable PREPARED request 精确绑定 base **effective ACTIVE** generation、new generation、
   previous committed receipt digest/head 与全部 base/new aliases；首次 rotation 的 base
   physical bytes 可为 baseline ACTIVE，后续 rotation 的 base physical bytes通常仍为
   PREPARED、仅由前一 committed receipt映射为 effective ACTIVE，不能硬要求 physical
   ACTIVE；
4. 新 alias requested/observed PASS；root-scoped purpose-5 attempted-use authority 在每次
   init 前 reserve/burn；control预承诺 bounded `max_slot_attempts` 与 ordinal range，每个
   attempt/disposition都绑定 control，直到得到两个成功 slot commit或 budget耗尽；
5. 用 new purpose-5 MANIFEST_SEAL alias 分别写出并完整复读 keyring slot generation
   `g+1`、`g+2`；control 必须绑定两个 exact full-slot digests，两槽均可仅凭自身 clear
   header + new alias independently decrypt/validate、stable identity 相同，且每槽 digest
   分别与 control 中对应的 `g+1`/`g+2` digest byte-equal；两个 slot digests 本身不要求相等；
6. generic highest-generation selector 一旦看到 PREPARED 就 veto；不得只因 `g+2` 较大
   把它解释成 ACTIVE；
7. durable 写入 `RotationReceiptV1=COMMITTED` chain entry，至少绑定 installation/keyring、
   checked rotation ordinal、`previous_committed_receipt_digest`（首个指 bootstrap receipt）、
   control digest、base selected pair ordered slot digests/generations/effective-state proof、
   new generation ID/ordinal、new `g+1/g+2` ordered slot digests、old/new purpose-5 alias
   identities、各 frontier head/attempt-set digest与 writer cut；
8. COMMITTED receipt close/reopen 后重新验证，rotation-aware reducer 才把 payload 中
   exact `new_generation_id` 的 PREPARED bytes 映射为 **effective ACTIVE** 并解锁 writer：

   ```text
   effective ACTIVE iff
     selected pair digests == receipt.g_plus_1/g_plus_2 digests
     AND receipt.control_digest == recovered control digest
     AND receipt.base/new aliases == payload base/new aliases
     AND receipt.state == COMMITTED
   ```

   GenerationRecord/KeyRecord 的物理 bytes 保持 PREPARED；receipt 是唯一状态 transition
   authority，不再写一套未绑定的 ACTIVE slots。receipt 成功后 reducer 必须产生**唯一**
   effective state：

   ```text
   current_generation_id = exact new_generation_id
   new physical PREPARED              -> effective ACTIVE, write-authorized
   base physical ACTIVE or PREPARED   -> effective RETIRING, read-only
   every other generation -> read-only according to retained-reference census
   exactly one effective ACTIVE
   ```

   从该 cut 起，任何新 ID/sequence/Blob/DEK/wrap/index token/Cipher initialization 都不得
   使用 base alias；base 只服务已存在对象的验证/重加密。重启必须先恢复 control+receipt，
   再一次性求 effective state；出现零个/多个 effective ACTIVE、new/base 身份冲突或仍能
   从 base 取得新 allocation token 时全部 BLOCKED。两个新槽 valid 但 receipt 缺失/未
   COMMITTED/不匹配时仍然 BLOCKED；
9. 每个 successor receipt必须消费前一 entry 的 exact output pair/head，new ordinal =
   previous+1；receipt/control ID、ordinal、previous digest不可 fork/gap/reorder。当前 pair
   恰为 latest committed output时，才沿完整链求唯一 effective ACTIVE；存在可恢复 pending
   control时 previous effective ACTIVE只读，writer/rotation/delete veto；
10. writer 切代与旧 data/manifest lease/Cipher/reference drain；全根 census不再引用旧
    generation/purpose-5 alias。前一个 purpose-5 retirement receipt COMMITTED 前禁止下一次
    rotation，以限制 root alias并发；data-generation alias另按 reference/lease生命周期；
11. purpose-5 old frontier先永久 seal，terminal head/attempt-set写入
    `ManifestSealAliasRetirementReceiptV1`；证明无 current/pending slot/control/reader/Cipher
    引用后，unlink其 frontier/control并 parent fsync（仅在 accepted phase wire允许时），再
    Keystore delete，close/reopen+reboot alias census与 old-slot negative decrypt；结果不确定
    保持 RETIRING/BLOCKED，不得重建同 alias；
12. data-generation purpose-1/2 frontier同样先封存 terminal digests。无 data/lease/Cipher
    引用后按逐 alias delete + negative-use/reboot census写
    `GenerationAliasDestructionReceiptV1`；只有 receipt精确覆盖的 destroyed KeyRecord 可在
    Keystore缺 alias且 reader仍验证 keyring。rotation-aware reducer把它们映射
    effective DESTROYED、永不分配/解密；physical records仍占 16-generation/144-key容量，
    直到 future format migration，不能删除一半后把 keyring判坏或虚假回收容量。

receipt chain任何缺失/fork/reorder、latest pair不匹配、old frontier未封口、alias删除不确定、
或 destroyed receipt缺失但 alias已不存在都 BLOCKED。purpose-5 与 data-generation retirement
各有独立 receipt，不能用一个“rotation complete”boolean吞并。

`RotationControlV1`、receipt chain、purpose-5 retirement 与 generation destruction receipts
的 canonical bytes必须由独立 root-scoped rotation-control authority认证，其生命周期至少
覆盖整个 root/format migration。previous-digest chain不替代 MAC/signature；该 authority
不能使用即将删除的 old purpose-5/7 alias，也不能复用 purpose-13 owner-lease key。若需新
Keystore purpose，先提升本 ADR registry/authorization profile并完成 device evidence。
wrong authority/key generation、alias销毁后无法 cold-verify整条 chain或 control key
rollback均 BLOCKED；测试必须在 old aliases 全毁后重启并从 root重新验证全部 chain。

rotation property/kill test 必须覆盖 receipt 前后每个 cut、重启 reducer 幂等、base
physical ACTIVE/PREPARED→effective RETIRING、new PREPARED→effective ACTIVE、至少两次连续
rotation、exactly-one current、receipt missing/fork/gap/reorder、每个 reserve/init/write/
fsync/receipt/frontier-seal/alias-delete kill、attempt>2 burn与 cap边界、旧代新 allocation
zero，以及 drain/census 前 base read 仍可用、destruction 后不可用。删除一半、old alias
重现、无 destruction receipt却缺 alias与第16 generation容量耗尽也必须覆盖。

---

## 9. Blob wire

### 9.1 Identity

- `logical_blob_id`：CSPRNG 16 bytes，Proto/诊断为 32 lowercase hex；在逻辑 Blob 生命周期内
  稳定；
- `physical_storage_id`：每次物理写尝试新生成的 CSPRNG 16 bytes；
- 文件名：`<physical-id-base32hex26>.smb`；
- M9.0 不生成 purpose 4 keyed/content-addressed ID；
- physical rewrite、compaction、失败重试或 crash recovery 必须换 physical ID、Blob DEK、
  operation ordinal 和物理 generation ordinal；不得复用失败 attempt。

`BlobRefV1` 只有五个逻辑字段：

1. `blob_id`
2. `plaintext_length`
3. `content_type`
4. `plaintext_digest`
5. `required_features`

key generation、physical ID、chunking 和 filename 不得进入 BlobRef。`content_type` 是
0..126 bytes canonical ASCII；空值表示 opaque。BlobRef 只存在于加密 Journal frame；
物理 header/footer/locator **不得**明文复制 content type、plaintext digest 或下述
unkeyed commitment。

`blob_ref_commitment` 只用于解密后的 logical equality：

```text
SHA-256(
  ASCII("sense-memory-blob-logical-v1") ||
  logical_blob_id[16] ||
  u64be(plaintext_length) ||
  u16be(content_type_length) || content_type ||
  plaintext_digest[32] ||
  u64be(required_features)
)
```

它不能出现在清晰物理 metadata，因为 public logical ID 只是 salt，不是 secret；把低熵候选
逐一 SHA-256 仍会构成离线字典 oracle。

### 9.2 Blob header 与加密 wrap payload

Blob header 固定 408 bytes：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 4 | magic `SMB1` |
| 4 | 2 | major=1 |
| 6 | 2 | minor=0 |
| 8 | 2 | header_length=408 |
| 10 | 2 | flags=0 |
| 12 | 8 | required_features=0 |
| 20 | 16 | logical_blob_id |
| 36 | 16 | physical_storage_id |
| 52 | 16 | BLOB_WRAP key_generation_id |
| 68 | 2 | chunking_version=1 |
| 70 | 2 | wrap_suite=1 |
| 72 | 4 | chunk_plaintext_size |
| 76 | 4 | chunk_count |
| 80 | 8 | plaintext_length |
| 88 | 32 | `blob_metadata_commitment`，purpose 9 HMAC |
| 120 | 12 | wrap-payload provider IV |
| 132 | 256 | encrypted `BlobWrapPayloadV1` ciphertext |
| 388 | 16 | wrap-payload GCM tag |
| 404 | 4 | reserved zero |

`BlobWrapPayloadV1` 是固定 256-byte plaintext；`content_type` 后未使用的 bytes 必须为零：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 8 | magic `SMBWPL01` |
| 8 | 2 | major=1 |
| 10 | 2 | minor=0 |
| 12 | 4 | payload_length=256 |
| 16 | 32 | BlobDEK |
| 48 | 16 | logical_blob_id |
| 64 | 16 | physical_storage_id |
| 80 | 8 | plaintext_length |
| 88 | 8 | required_features=0 |
| 96 | 2 | content_type_length；0..126 |
| 98 | 126 | fixed content_type area：前 length bytes canonical ASCII，其余 zero |
| 224 | 32 | plaintext_digest |

purpose 9 commitment exact 输入：

```text
HMAC-SHA-256(
  K_PURPOSE_9_SAME_GENERATION,
  ASCII("sense-memory-blob-metadata-commitment-v1") ||
  0x00 ||
  exact_blob_header_bytes[20,88) ||
  exact_BlobWrapPayloadV1_bytes[0,256)
)
```

输入包含独立随机 physical ID、generation ID、chunk shape 和 BlobDEK，因此同内容的两个
physical attempts 不产生可关联的 clear commitment；没有 purpose-9 key 的离线观察者不能
验证 content/digest 字典。purpose-9 key generation 必须等于 header/locator 的
`BLOB_WRAP key_generation_id`。

wrap-payload exact AAD：

```text
ASCII("sense-memory-blob-wrap-payload-v1") ||
0x00 ||
exact_blob_header_bytes[0,120)
```

AAD 长度固定 `33+1+120=154` bytes；`[0,120)` 包含 keyed commitment，不包含
IV/ciphertext/tag。writer在 raw BlobDEK generation前先 durable reserve/burn purpose-2
ordinal；随后单次生成 DEK、构造 canonical payload/header structural prefix、计算
purpose-9 commitment并执行 wrap encryption。generate/wrap/publish任一步失败或不确定都
保留该 burn并退休 DEK/attempt，不能再 draw一个 key沿用 ordinal。

reader 在任何分配/解密前只使用 clear header 检查固定 header length、chunk
size/count、plaintext length 和物理文件硬上限；clear metadata 不能提供 content type/
digest verdict。之后按 purpose-2 frontier/cap 选择 alias、验证 GCM、canonical parse
256-byte payload，再验证 purpose-9 commitment与所有重复 ID/length/features。只有此后
才能分配 plaintext digest state、解密 chunks，并将 payload 中 content type/digest 与加密
Journal `BlobRefV1` byte-equal 比较。

V1 明确保留的 metadata leakage 是：文件存在、exact plaintext length、chunk size/count、
随机 logical/physical ID、key/physical generation 与同 logical ID 的 rewrite lifetime。
因此长度本身可区分 `"no"`/`"yes"` 这类不同长度候选；purpose-9 commitment 只阻止 raw
digest/content-type 泄漏以及对**相同长度候选**的离线内容/equality 验证，不承诺隐藏大小或
访问图。若产品要求 size confidentiality，必须另立 padding/bucketing profile 与空间预算
ADR；不得在本 gate 声称已经提供。

### 9.3 Chunk

每个 chunk：

```text
chunk_header(24) || ciphertext(N) || gcm_tag(16) || commit_magic(4) || crc32c(4)
```

24-byte header：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 4 | chunk_index，从 0 连续 |
| 4 | 8 | plaintext_offset |
| 12 | 4 | plaintext_length |
| 16 | 4 | ciphertext_length；suite 1 必须等于 plaintext_length |
| 20 | 4 | flags=0 |

chunk shape validator 固定为：

```text
if blob_plaintext_length == 0:
  chunk_count = 0
else:
  chunk_count =
    checked_add(checked_div(blob_plaintext_length - 1, chunk_plaintext_size), 1)
```

- header `chunk_count` 必须等于计算值且 `<=16,384`；
- zero-length Blob 恰有 0 chunks；非零 Blob 不允许 zero-length chunk；
- 对 `0 <= i < chunk_count-1`：
  `offset = checked_mul(i, chunk_plaintext_size)` 且
  `plaintext_length = chunk_plaintext_size`；
- 最后一块：
  `offset = checked_mul(chunk_count-1, chunk_plaintext_size)`，
  `plaintext_length = blob_plaintext_length-offset`，范围 `1..chunk_plaintext_size`；
- 每块 `ciphertext_length=plaintext_length`，index 恰为 i；
- ordered chunk plaintext length sum 必须精确等于 Blob header/footer plaintext length。

任一 checked arithmetic overflow、短的非末块、额外空块、offset gap/overlap 或 header/footer
count 不一致都在分配/解密前拒绝。

chunk AEAD AAD 为：

```text
ASCII("sense-memory-blob-chunk-v1") ||
0x00 ||
SHA-256(exact_blob_header) ||
exact_chunk_header_bytes[0,24)
```

suite 1 **只使用一种**、可证明 injective 的 nonce 方案，不调用 provider random IV，也不
截断 hash/HMAC：

```text
nonce[12] = ASCII("SMB1")[4] || u64be(zero_extend_u32(chunk_index))
```

Blob data encryption key 不是 Android Keystore alias。每个 physical Blob attempt 必须生成
全新 256-bit BlobDEK，并且该 key 不跨 physical ID/attempt 复用；chunk index 从 0 严格连续，
同一 DEK 下恰出现一次。因此**给定一个 BlobDEK**，nonce uniqueness由 injective index
encoding机械保证；不同 attempt的 DEK相等风险仍是独立均匀 256-bit key generation的概率
事件，不能写成绝对不碰撞。V1 每个 physical attempt必须在 raw key generation前先
durable consume一个 purpose-2
BLOB_WRAP ordinal；16 个 generation × 每 alias 65,536 ordinals使 installation lifetime
attempt数 `q <= 2^20`，故 birthday union bound
`Pr[任意 BlobDEK collision] <= q(q-1)/2^257 < 2^-217`。任一 attempt不确定就同时退休
BlobDEK/physical ID/ordinal，不能靠换 index继续；future提升 generation/ordinal cap或改变
key generator必须重算并接受新的 bound。

BlobDEK 的 aggregate usage 也必须机械证明。chunk AAD exact length 为：

```text
26 + 1 + 32 + 24 = 83 bytes
```

所以每 chunk 的 GCM authenticated-block charge 是
`ceil_div(83,16)+ceil_div(ciphertext_length,16)+1`
`= 7+ceil_div(ciphertext_length,16)`。结合 plaintext `≤67,108,864` 与
`chunk_count≤16,384`，每个 physical BlobDEK 的最坏 aggregate 恰有上界：

```text
ceil_div(total_ciphertext_bytes,16) + 7*chunk_count
<= 4,194,304 + 114,688
= 4,308,992 < 2^23
```

writer 必须在第一个 chunk `Cipher.init` 前以 checked arithmetic 验证 header 声明的完整
shape 满足该 derived bound，并在每个 chunk init 前累计实际 charge；reader 独立重算。
任何越界、overflow 或 shape 改变都拒绝整个 physical attempt；BudgetProfile 只能收紧
plaintext/chunk count，不能放宽该不变量。

commit magic 是 ASCII `BCMT`。CRC32C 覆盖 exact chunk header、ciphertext、tag、commit，
只用于截断诊断；GCM tag 才提供真实性。

### 9.4 Footer：192 bytes

ordered chunk transcript digest 唯一计算为：

```text
SHA-256(
  ASCII("sense-memory-blob-chunk-transcript-v1") ||
  0x00 ||
  for chunk_index = 0..chunk_count-1:
    exact physical chunk record bytes
      (24-byte header || ciphertext || 16-byte tag || "BCMT" || CRC32C)
)
```

每个 chunk record 已由 validated header/ciphertext length 自分界；实现按 index 顺序流式更新
一个 accumulator，不把全部 Blob 载入内存。zero-chunk Blob 的 digest 恰为
`SHA-256(domain || 0x00)`；footer 不把自己加入 transcript。

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 8 | magic `SMBFTR01` |
| 8 | 2 | major=1 |
| 10 | 2 | minor=0 |
| 12 | 4 | footer_length=192 |
| 16 | 8 | required_features=0 |
| 24 | 16 | logical_blob_id |
| 40 | 16 | physical_storage_id |
| 56 | 4 | chunk_count |
| 60 | 4 | reserved zero |
| 64 | 8 | plaintext_length |
| 72 | 8 | total chunk ciphertext bytes |
| 80 | 32 | `blob_metadata_commitment`，必须等于 header offset 88 |
| 112 | 32 | SHA-256(exact header) |
| 144 | 32 | ordered chunk transcript digest |
| 176 | 8 | commit magic `SMBFCMIT` |
| 184 | 4 | CRC32C(bytes 0..183) |
| 188 | 4 | reserved zero |

Footer 只有在全部 chunk tag、offset、length、解密 payload metadata 与 plaintext digest
已验证后才写。physical file digest 是整个 header/chunks/footer 的 SHA-256。footer 不得
出现 raw plaintext digest/content type；CRC 只诊断 torn write，不能替代 purpose-9 HMAC
或 AEAD。

### 9.5 `ADR0017_BLOB_CAPS_V1`

| 项 | 硬上限 |
|---|---:|
| encrypted content_type bytes | 126 |
| header | 408 bytes |
| chunk plaintext size | 仅 `{4096,8192,16384,32768,65536,131072,262144,524288,1048576}` |
| chunk count | 16,384 |
| Blob plaintext | 67,108,864 bytes（64 MiB） |
| chunk header/tag/commit/CRC overhead | 48 bytes |
| single physical chunk record | 1,048,624 bytes |
| footer | 192 bytes |
| complete physical file | 67,895,896 bytes |

最大文件算式必须用 checked u64：

```text
408 + 67,108,864 + 16,384*48 + 192 = 67,895,896
```

profile 可以设置更小值，不能放宽这些 protocol caps。

### 9.6 Publish

```text
bounded source
→ same-directory new physical temp
→ encrypt/write chunks
→ file fsync
→ close/reopen full parse + tag/digest/length verification
→ atomic publish final physical filename
→ parent directory fsync
→ close/reopen final full verification
→ locator PREPARED/ACTIVE convergence
→ BlobRef may become journal-visible
```

不允许 plaintext temp、跨目录 rename、先发布 BlobRef、只验长度或“发生异常后继续原文件”。
任何 I/O 结果不确定都永久退休 temp/final physical ID、DEK、chunk ordinal 和 operation
ordinal；cleanup 只能在 ownership 与 locator census 证明不再可达后执行。

---

## 10. Blob locator A/B wire

每个 logical Blob：

```text
manifests/blob-locator/.sense-memory.namespace.lock
manifests/blob-locator/<logical-id-base32hex26>/.sense-memory.namespace.lock
manifests/blob-locator/<logical-id-base32hex26>/locator-a
manifests/blob-locator/<logical-id-base32hex26>/locator-b
```

### 10.0 locator pair 首次创建事务

pair 不得通过“先建 A，使用后再补 B”建立。`manifests/blob-locator/bootstrap-control/`
是 **Keyring/data-plane bootstrap** transaction预创建并 durable 复读的固定 control
parent；authority-only root bootstrap必须保持该目录不存在。其 closed entry set恰为：

```text
.sense-memory.namespace.lock
control-a
control-b
```

`control-a|control-b` 是固定 EOF 的 `LocatorPairBootstrapControlV1` A/B pair，EOF/canonical
wire由 `BlobWireLocatorLeaseGateV1` descriptor冻结；未冻结前 persistent locator仍
BLOCKED。pair在同一时刻最多承载一个 operation，generation单调；每槽同时编码 intent state
与 `READY|ABORTED_CLEAN|INDETERMINATE_RETAINED` terminal，不存在 append log、自由文件或
第三槽。terminal经过 A/B durable commit且 previous operation的 logical temp/final/charge
census闭合后，下一 operation才可覆写较低 generation槽。

该 parent不位于尚未创建的 logical target目录。持有
`bootstrap-control/.sense-memory.namespace.lock` 时，先 checked reserve locator metadata
count+worst-case byte charge，再向 fixed A/B pair durable commit
`LocatorPairBootstrapIntentV1` state，绑定 installation/keyring/logical ID、canonical
temp/final names、current key generation、owner/writer epoch、worst-case child+directory+
lock charge 与 intent nonce；control file fsync、control parent fsync、
close/reopen/full-pair selection **全部成功后**才可开始：

```text
CREATE_NEW `.bootstrap-<logical-id-base32hex26>` directory
  → CREATE_NEW .sense-memory.namespace.lock / locator-a / locator-b
  → 分别写 identity-bound canonical UNUSED（只允许 slot label 不同）
  → 三文件分别 file fsync、close/reopen，取得 child lock
  → temp directory fsync
  → atomic publish-no-replace temp directory 为 <logical-id-base32hex26>
  → final parent directory fsync
  → 从 final path close/reopen directory 与两文件
  → 验证 regular/st_nlink=1、inode 不互相 alias、exact EOF=512、full-pair canonical
  → 才返回 LOCATOR_PAIR_READY
```

final target 已存在时 publish 不得覆盖。temp-only、final-only、temp+final、单文件/短写、
rename 后 parent fsync 未知与 path/inode replacement 都进入 closed bootstrap recovery
matrix：在同一 owner lock 下，final full pair 有效则只接受 final；只有 temp full pair
有效且 final absent 才可继续原 publish；invalid/被 final 取代的 temp 只能在 durable
cleanup receipt、unlink、temp/parent directory fsync 和 close/reopen census 后删除。
parent fsync outcome unknown 时返回 `INDETERMINATE_LOCATOR_BOOTSTRAP`，不得创建 physical
Blob、发 operation ordinal、安装 fence 或发布 BlobRef，直到 recovery 唯一收敛。

每个 terminal（pair ready、known aborted cleanup、indeterminate retained）都必须在 fixed
control A/B pair commit `LocatorPairBootstrapReceiptV1` state并 close/reopen；metadata charge 从
intent后的首次 allocation起恰计 F019 `ACTIVE_DUPLICATE`。`LOCATOR_PAIR_READY` 只表示该
pair已可被后续 Journal record安全引用，不是 steady publication terminal；只有 physical
Blob/locator收敛且引用它的 exact Journal record进入 durable frontier后，compound transaction
才原子 `F019→F010/F012/F014`。在此之前取消、indeterminate、owner-loss或最终无 durable
Journal引用都使 pair与 physical Blob按各自 inode `F019→F020`，并释放 F012/F014/F029
success reservation。pre-mkdir必须同时持有 success/failure contingency headroom，reservation不
重复计算 physical bytes；F020只有 temp/final全部
unlink+directory-fsync+reopen census后才释放。intent/charge reserve失败发生在 mkdir前且
零目录副作用。这样 kill在 mkdir、
单槽或任一 fsync 后都存在可恢复 owner/charge，不会留下“无人拥有”的目录。

该 temp grammar、intent/receipt wire、directory durability port 与所有 kill-point 由
`BlobWireLocatorLeaseGateV1` 冻结；在它 PASS 前 persistent locator 创建保持 BLOCKED。
M9A-03 必须覆盖每个 byte/write/fsync/publish/parent-fsync/reopen kill point、两个并发
bootstrap、hard link/symlink/path replacement、final collision，以及恢复后恰有一个
canonical full pair、零悬空未计费目录。

每槽固定 512 bytes：

| offset | bytes | 字段 |
|---:|---:|---|
| 0 | 8 | magic `SMBLOC01` |
| 8 | 2 | major=1 |
| 10 | 2 | minor=0 |
| 12 | 4 | slot_length=512 |
| 16 | 1 | slot label：0=A，1=B |
| 17 | 1 | state：0=UNUSED，1=PREPARED，2=ACTIVE |
| 18 | 2 | flags=0 |
| 20 | 8 | required_features=0 |
| 28 | 8 | slot_generation |
| 36 | 200 | canonical mapping bytes，见下 |
| 236 | 32 | SHA-256(bytes 36..235) |
| 268 | 32 | previous mapping digest；首次为零 |
| 300 | 16 | writer_epoch |
| 316 | 8 | operation ordinal |
| 324 | 4 | locator owner role；1=MemoryBroker |
| 328 | 144 | reserved zero |
| 472 | 32 | HMAC-SHA-256，purpose 12 |
| 504 | 4 | commit magic `LCMT` |
| 508 | 4 | CRC32C(bytes 0..507) |

mapping bytes `36..235` 必须逐 byte 比较：

| offset | bytes | 字段 |
|---:|---:|---|
| 36 | 16 | installation_id |
| 52 | 16 | keyring_id |
| 68 | 16 | logical_blob_id |
| 84 | 16 | physical_storage_id |
| 100 | 8 | physical_generation_ordinal；初始 1，之后 checked +1 |
| 108 | 16 | BLOB_WRAP key_generation_id |
| 124 | 2 | chunking_version=1 |
| 126 | 2 | wrap_suite=1 |
| 128 | 4 | chunk_plaintext_size |
| 132 | 4 | chunk_count |
| 136 | 8 | plaintext_length |
| 144 | 8 | physical_file_length |
| 152 | 32 | `blob_metadata_commitment`（purpose 9；等于 physical header/footer） |
| 184 | 32 | physical_file_digest |
| 216 | 16 | lease_fence_token |
| 232 | 4 | reserved zero |

mapping digest 是 `SHA-256(exact_slot_bytes[36,236))`。MAC exact 输入：

```text
HMAC-SHA-256(
  K_PURPOSE_12_SAME_GENERATION,
  ASCII("sense-memory-blob-locator-v1") ||
  0x00 ||
  exact_slot_bytes[0,472)
)
```

mapping equality 只指 bytes `[36,236)` 全相等，不能只比较 digest。

canonical UNUSED locator：

- clear header magic/version/slot length/slot label canonical，state=UNUSED、flags/features/
  slot_generation=0；
- mapping 中 installation/keyring/logical Blob ID 已绑定；
- physical ID、physical generation、chunk fields、lengths、commitments、file digest 与
  lease fence 全零；
- key_generation_id 是创建 locator pair 时 current ACTIVE generation ID，用同 generation
  purpose 12 认证；它不是物理 mapping；
- mapping digest 按上式计算，previous digest/writer epoch/operation ordinal 全零，
  owner role=1，reserved zero，MAC/commit/CRC 有效；
- 两个 UNUSED 只因 slot label 不同；全零 512-byte file 永远非法。

PREPARED/ACTIVE mapping 必须满足：

- `lease_fence_token` 是 coordinator 在该 mapping attempt 前 CSPRNG 生成的非零 16 bytes；
  initial publish、rewrite、crash retry 都不得复用，UNUSED 才为零；
- `operation_ordinal > 0`，且恰等于 purpose 2 BLOB_WRAP operation frontier 为该 physical
  Blob DEK wrap attempt 发放的 ordinal；同 mapping 的 PREPARED/ACTIVE 两槽保持相同值；
- locator key generation、Blob header BLOB_WRAP generation、该 operation frontier target
  generation、purpose 9 metadata-MAC generation 与 purpose 12 locator-MAC generation
  必须相同；
- locator `blob_metadata_commitment` 必须等于 full-verified physical header/footer；
  unwrap 后 purpose-9 HMAC、payload metadata、chunk plaintext digest 与 encrypted Journal
  `BlobRefV1` 必须形成同一验证链，不能从 clear mapping 猜 content type/digest；
- writer epoch 非零且在同 mapping transition 中稳定；initial previous digest 为 ZERO32，
  rewrite 时等于旧 authority 的 exact mapping digest。

fence 必须在写 PREPARED 前登记；在第一份 new ACTIVE 前再次验证仍为 current。reader acquire
后按 §10.2 用该 token revalidate。

每次 mapping transition 的 `g` 都是开始该 transition 时 selected old authority 的
`slot_generation`；初始 publish 的 base `g=0`。实现必须在安装 fence、创建/写入新
physical Blob、发放 operation ordinal 或修改任一 locator byte **之前**，用 checked
arithmetic 一次性证明：

```text
g <= UINT64_MAX - 3
g1 = checked_add(g, 1)
g2 = checked_add(g, 2)
g3 = checked_add(g, 3)

initial publish:
  new_physical_generation_ordinal = 1

rewrite/remap:
  old_physical_generation_ordinal < UINT64_MAX
  new_physical_generation_ordinal =
    checked_add(old_physical_generation_ordinal, 1)
```

只有该证明成功，才可执行下述固定 `g1/g2/g3` 三写。若 selected old authority 的
generation 大于 `UINT64_MAX-3`，该 logical Blob locator 对任何 publish/rewrite/remap
永久返回 `LOCATOR_GENERATION_EXHAUSTED`：仍可按 lease 读取 current ACTIVE mapping，
也仍必须响应显式 erasure 的 drain/unlink/census，但不得 wrap、回零、截断 generation，
不得换 logical ID 后声称是同一 Blob，也不得先安装 fence 或留下新 physical/orphan。

若 slot-generation proof 通过但 selected mapping 的 physical generation 已为
`UINT64_MAX`，返回独立 `PHYSICAL_GENERATION_EXHAUSTED`，同样必须发生在 fence、new
physical/logical ID、BlobDEK、purpose-2 ordinal、Cipher 或 I/O 之前；current mapping
保持可读并可显式 erasure，但不得 rewrite/remap、wrap/reset 或留下 orphan。

从 base `UINT64_MAX-3` 合法开始的 transition 可以在 crash 后继续其尚未完成的
`MAX-2/MAX-1/MAX` 步骤；恢复器必须从 adjacent slots、same mapping、previous digest 与
current operation ownership 证明它属于该已开始 transition，不能把任意高 generation
单槽当成新的 base。收敛到 `ACTIVE(MAX-1)/ACTIVE(MAX)` 后该 locator 只读，下一次 remap
必然拒绝。

### 10.1 状态选择表

`g` 表示 slot generation；valid 包括长度、MAC、CRC、canonical、身份和引用物理文件的完整
复读验证。

| A/B 可见状态 | 约束 | 读 authority | 写/修复 |
|---|---|---|---|
| UNUSED / UNUSED | 两槽均为 identity-bound canonical UNUSED | 不存在 Blob | 可开始全新 publish |
| PREPARED(new,g) / UNUSED | 仅初始 publish，physical valid | 不可见 | 只有仍持原 writer/operation ownership、I/O 确定且 full reread 通过时可继续 peer ACTIVE；crash/unknown 时整个 record/logical Blob attempt终止，禁止同 logical ID重试 |
| ACTIVE(old,g) / PREPARED(new,g+1)（或镜像） | mapping 不同、previous digest 指向 old | old ACTIVE | 原 writer且I/O确定可继续；crash/unknown后 old只读、rewrite永久 uncertain，禁止新 remap |
| PREPARED(new,g) / ACTIVE(new,g+1)（或镜像） | mapping 必须 byte-equal | 只有 exact physical/BlobRef chain可交叉验证时有界只读 | 仅原 writer连续持有 same operation ownership、fence、descriptor且 I/O outcome确定时可完成；crash/process death/unknown后 locator永久 uncertain，零 peer write、零 old delete、零 remap |
| ACTIVE(new,g) / ACTIVE(new,g+1)（或镜像） | mapping 必须 byte-equal | 较高 generation | converged；旧 physical 仍需 lease/delete 流程 |
| 仅一个 valid ACTIVE | 另一槽 invalid/UNUSED | 仅在 Journal BlobRef、physical commitment 与累计擦除 view 全部交叉验证后只读 | `LOCATOR_SINGLE_SLOT_UNCERTAIN`；禁止 generic repair/remap/maintenance delete |
| 仅一个 valid PREPARED | 另一槽 invalid/UNUSED | 不可见 | fail closed；只可 orphan cleanup |
| ACTIVE / ACTIVE 不同 mapping | 任意代差 | 无 | fail closed |
| PREPARED / PREPARED | 任意 | 无 | fail closed |
| 代差非 0/1、identity 冲突、previous digest 错、非零保留位 | 任意 | 无 | fail closed/quarantine |

V1 不存在把 unknown PREPARED“覆盖成下一次尝试”的隐式 transition。future
`BlobMaterializationAbortReceiptV1` exact wire未冻结前采取最保守 terminal：

- initial `PREPARED/UNUSED` crash/unknown 时，burn record/logical/physical IDs、DEK与所有
  ordinals；BlobRef永不发布。只有 durable abort/cleanup authority能在 full physical/
  locator census后 unlink physical与整个未引用 logical locator pair，并逐层 parent fsync/
  reopen；清理不确定则 quarantine。新 capture必须取得全新 record/logical Blob ID，不能只
  换 physical ID；
- `ACTIVE(old)/PREPARED(new)` crash/unknown 时，old ACTIVE只作为有界 read authority；
  new physical/DEK/ordinal永久退休，可在有证明的 cleanup receipt下清理，但 PREPARED slot
  不得被 reset/replace，logical locator不得再 maintenance/rewrite/remap。显式 whole-logical
  erasure仍必须可收敛；
- exact same-process、same operation ownership、I/O outcome确定的 interrupted transition
  可以继续既定 g1/g2/g3，不签发新 attempt。commit不确定、进程死亡或 owner/fence变化都
  落入上述 terminal，不得猜测；
- 后续若要在同 logical ID上 abort→retry，必须提升 locator wire并冻结 abort state/receipt、
  generation/attempt frontier与每个 crash cut；当前表不预留这种能力。

发布新 mapping 的固定动作：

1. 在 coordinator 中先安装新 `lease_fence_token`，阻止新 reader 获得旧 mapping lease；
2. 把较低/目标槽写为 PREPARED(new, `g+1`) 并 fsync/close/reopen/full-pair-read；
3. 把 peer 写为 ACTIVE(new, `g+2`) 并重复完整 durability/read 验证；
4. 把 PREPARED 槽写为 ACTIVE(new, `g+3`) 并再次完整验证；
5. 只有得到 adjacent-generation、same-mapping 的 ACTIVE/ACTIVE 后才能进入旧 physical
   delete 流程。

单一 ACTIVE 也不能证明自己是 latest：任意旧但 MAC-valid 的 slot 加 invalid peer 与随机
损坏不可区分；full physical verification 只证明旧对象自洽，不证明当前 mapping freshness。
因此 v1 没有 generic peer-repair API，不得通过写 `active+1` 把旧 mapping 固化成 current。
该状态只允许在 exact encrypted Journal `BlobRefV1` 五字段/commitment、locator mapping、
解密后的 wrap payload、physical header/footer/chunk transcript/file digest、key generation、
累计擦除 view 与 fixed cut 构成完整 byte-equal 验证链时提供有界只读；`BlobRefV1` 本身
不含 physical ID，physical identity 只能从已验证 locator→wrap/header 链取得。不匹配即
`POLICY_RESTRICTED/quarantine`。显式 whole-logical-Blob erasure 仍可按独立
drain/unlink/census authority 删除所有枚举 physical candidates。

future repair 只有在故障前已经 durable 的 canonical transition/repair intent 精确绑定
expected source slot digest、target peer、expected latest mapping witness、generation 与
operation ownership，且独立 rollback-resistant mapping-head witness 证明 source 是 current
时才可继续；普通 A/B generation、mtime、wall clock 或新写 repair receipt 都不能充当该
witness。缺任一证明永久 `LOCATOR_SINGLE_SLOT_UNCERTAIN`。single ACTIVE generation
`UINT64_MAX` 同样零写，只可受限读取或显式 erasure；不得 remap、maintenance delete、wrap
或 reset generation。

### 10.2 Reader lease 与删除

reader acquire 必须：

1. 选择 locator authority；
2. 在同一 coordinator 登记 `(logical_id, physical_id, generation, fence_token, process_epoch)`；
3. 登记成功后重新读取 locator；
4. 只有 logical ID、physical ID、physical generation 和 fence token 仍与预期相等才返回
   handle；否则注销并重试。

删除旧 physical 必须按顺序：

1. fence 已安装且没有新旧 reader 竞态；
2. 所有旧 mapping lease 已由 live process barrier 证明 drain；
3. locator ACTIVE/ACTIVE 已收敛到新 mapping；
4. unlink 明确的旧文件；
5. fsync 旧文件所在 parent directory；
6. close/reopen 根目录并执行完整 physical/locator/key-reference census；
7. 只有 census 不再引用旧 physical/key，才允许清零 manifest 引用或删除 alias。

“调用 unlink 成功”但 parent fsync/重启 census 未完成时，状态仍是
`INDETERMINATE_DELETE`，不得宣称擦除完成。

---

## 11. Journal、索引与明文面

### 11.1 Journal

- 每个 segment 使用全新 256-bit DEK；逐 frame AEAD，nonce/sequence 规则由 ADR 0016
  冻结；
- segment DEK 只能由 purpose 1 alias 包装；
- frontier、segment metadata、recovery seal 分别使用 purpose 10、8、11；
- durable frontier 后的 bytes 不是 durable，恢复不得把“可解密尾巴”自动升格；
- orphan recovery 不以旧 DEK 继续 append，不原地截断；只可发 RecoveredSeal 或 quarantine。

### 11.2 派生索引

Room/WAL/FTS 只允许：

- opaque ID、ordinal、type/status enum；
- digest、MAC、长度、非敏感数值分数；
- purpose 3 生成的 keyed token；
- 可从 canonical Journal 重建且 CapturePolicy 允许的最小投影。

禁止存：

- Session/事件/工具输出原文；
- prompt、Provider request/response、私有 reasoning；
- 用户词句、联系人、package、URL、文件路径的明文；
- DEK、unwrapped key、IV+key 对、完整 Blob plaintext；
- 为调试复制的 JSON。

M9.0 keyed token：

```text
token = first16(
  HMAC-SHA-256(
    INDEX_WRAP_key,
    ASCII("sense-memory-index-token-v1") ||
    u16be(normalizer_version) ||
    u16be(token_type) ||
    u32be(normalized_utf8_length) ||
    normalized_utf8
  )
)
```

它隐藏 token 内容但不隐藏重复与频率；因此索引仍属于敏感持久化数据，受 capture、预算、
擦除和 owner continuity 全部门禁。

### 11.3 零明文临时面

- 大对象跨进程只允许 reliable pipe 的 bounded encrypted/plain-in-flight stream 或已发布
  encrypted BlobRef；
- M9.0 禁止 plaintext temp、ashmem dump、PFD-backed plaintext file；
- pipe 必须携带 length/digest/content type、取消、双方 close 和 broker-instance ownership；
- crash report/logcat/metrics 只记录 enum、大小 bucket、耗时和 opaque trace ID；
- 测试失败 artifact 默认不收集正文。需要 fixture 时只用仓库内合成 corpus。

---

## 12. CapturePolicy

Capture 是两阶段授权，不是一个可缓存的 boolean。

```text
preflight(CapturePreflight) -> PreflightDecision
boundedReadExactlyOnce()
finalize(FinalCaptureInput) -> CaptureDecision
```

### 12.1 Preflight 之前/拒绝之后的零副作用

preflight PASS 前不得：

- 读取正文；
- 分配 record/event/blob ID、writer sequence 或 operation ordinal；
- 创建 DEK/Cipher、打开 Journal/Blob/index writer；
- 占用持久队列槽、创建 temp、发 Binder/PFD/pipe；
- 记录包含 package/editor 内容的诊断。

正文只能按 preflight 给出的 byte/codepoint 上限读取一次；`finalize` 必须同时看到实际
classification、截断状态、用户动作、来源与当前全部 gate snapshot。finalize DENY 后也必须
保持零持久副作用。

### 12.2 必拒绝

- password、PIN、credit-card、visible/invisible sensitive input variation；
- `IME_FLAG_NO_PERSONALIZED_LEARNING`；
- incognito/private editor、系统安全输入面；
- 用户/企业策略 deny list；
- owner continuity、release identity、backup exclusion、erasure phase、revocation freshness
  或 `BudgetProfileGateV1` 非 PASS；
- `ErasureSafetyReceiptGateV1` 非 PASS，或 receipt 尚未 file/directory durable、
  close/reopen、未与 exact installation/root/current executor 交叉验证；
- 当前处于 erasure PREPARING 以后，或 fence/version 已变化；
- 来源不明、正文超界、分类失败、policy snapshot stale。

允许列表不能覆盖系统敏感 flag。应用 package 仅用于内存内 policy lookup，不写入文件名或
明文日志。

### 12.3 独立的 `SyntheticMeasurementAdmissionV1`

上一节普通 CapturePolicy 的 deny 规则保持不变；`BudgetProfileGateV1` 未 PASS 时绝不能
签发 normal capture authorization。ADR 0018 measurement-only path 使用独立 typed
`SyntheticMeasurementAdmissionV1`，不能复用、继承、cast 或序列化为 normal token。

该 admission 只有在有效 `SyntheticMeasurementPermitV1` 下，由 external companion harness
控制的 exact production candidate process 从
permit-bound corpus 生成器产生。生成器输入只能是 exact corpus digest、seed、workload/
scenario、attempt ordinal 与预承诺 case parameters；输出必须在写入前匹配 expected
length/digest。它不得调用或持有 `InputConnection`，不得读取 editor、selection、
clipboard、notification、account、production database/root 或网络响应，也不得把 bytes
交给真实/远端 Provider。W8 只允许 §13.5 定义的 production adapter control path 接
`SyntheticProviderTransportV1`；该 transport 不是 Provider/network destination，且只接收
permit-bound known synthetic bytes。

typed admission 至少在语义上绑定：

```text
permit/candidate-authorization digest / primary evidence_run_id
exact production applicationId/APK + candidate-only root/authority-class identity
external companion harness identity
corpus/generator/config digests
scenario/workload/attempt/case ordinals
capabilities_under_test
expected plaintext length + digest
permit generation/fence/expiry
```

exact in-memory type/control wire、field caps 与 producer API 尚待
`SyntheticMeasurementControlPhaseGateV1` 接受；当前不得实现临时 token。未来每个 ID/
sequence/ordinal allocation、Cipher init、queue admission、serialization、Binder/PFD/pipe
handoff、publish 与 cleanup 都必须重验同一 permit、namespace、corpus commitment、fence 和
expiry。它只能产生 measurement outcome/ledger row，不能产生 product DurableAck、
normal CapturePolicy token 或 stage 晋级。

source admission 是独立 typed path，但被测 writer/codec/frontier/locator/Ack 实现必须与
production exact implementation 相同，不得用 mock 或 measurement-only algorithm。它可在
exact signer-WORM production candidate applicationId/APK 内的 candidate-only
authority/root namespace生成 byte-exact `BlobRefV1`、Journal frame、locator 与
DurableAck；这些值由 permit/run/root taint scope 包围，只能进入 evidence rows，不能进入
normal capture/recall API、production root、用户 UI，不能被计为 product DurableAck 或让
产品 gate PASS。separate applicationId只允许 companion harness，不是被测实现。任何
source/type/binding/namespace 不匹配立即 security FAIL并触发 external
clear-data/uninstall containment。

### 12.4 每个副作用点再检查

preflight/finalize 不是永久许可。以下每一步前都必须核对同一 capture authorization token
的 policy generation、erasure fence、owner generation 和 stage freshness：

1. persistent ID allocation；
2. writer sequence allocation；
3. Blob physical ID/DEK allocation；
4. Cipher initialization；
5. queue enqueue；
6. frame serialization；
7. pipe/Binder/Provider byte handoff；
8. Journal/Blob publish。

token 变化就停止；已分配但未发布的 identity 永久 burn，不能换新 policy 后复用。
`ErasureSafetyReceiptGateV1` 必须在 preflight 读取 body 前、finalize 后、queue reservation/
ID/Blob/Cipher 前及每次 byte handoff 重验；root header 与首个/每个 record lineage 都绑定
receipt digest。receipt 写入或复读失败时 capture 保持零 body/ID/queue/persistent side
effect，不能“先存数据以后再补删除能力”。

### 12.5 已保留 Memory 的 use、Provider disclosure 与 export 是三份 authority

capture consent 只授权新增持久数据，绝不自动授权以后读取、发给模型或导出。后续 phase
必须冻结三种 sealed authority，且当前不得用设置 boolean/调用者参数代替：

三种 V1 authority 的安全顺序/有效期统一只使用 owner/policy authenticated monotonic
revision、durable issue/consume ordinal、validity interval与 revoke frontier。wall-clock只作
审计/UX，不能决定 admission或延长授权；运行 lease另绑定 boot id +
`elapsedRealtime` deadline，reboot立即失效并要求新 grant。系统时间回拨、未知或校时失败
一律不能把 expired/revoked grant变 current。

1. `MemoryUsePolicyV1` 是 owner/user-authenticated、单调 revision 的本地使用政策，绑定
   installation/owner/root、previous policy digest、允许的 capability/purpose、source
   scope、data class/projection class、record/byte/token ceilings、issued/expiry/freshness
   authority与 revoke frontier。每个 recall/index/HotSnapshot operation 再取得 one-shot
   `MemoryUseGrantV1`。交互式 grant绑定显式 Run/user action；background
   index/snapshot/rebuild只能来自 policy中预先 owner/user-authenticated 的 closed
   background purpose，并由每次 scheduler transaction取得新 grant，绑定 job generation、
   query/purpose、fixed cut、source manifest、cumulative erasure view、projection digest、
   limits、consume ordinal/validity/revoke frontier 和 policy revision。裸 WorkManager job、长期 bearer grant、
   replay、scope expansion、stale/revoked/unknown purpose均拒绝。它只允许 Sense 本地使用；
2. `ProviderMemoryDisclosureGrantV1` 是独立 per-Run、per-destination grant，绑定 user
   disclosure action、source scopes/fixed cut/projection digest、Provider identity、normalized
   endpoint、tenant/account、model、retention/data-use policy digest、exact byte/token cap、
   closed max-attempt/retry schedule、monotonic validity/revoke frontier、nonce/sequence 与 underlying
   `MemoryUseGrantV1` digest。parent grant在 operation admission原子 durable consume一次；
   每个 HTTP body attempt再铸 one-shot
   `ProviderAttemptDisclosureLeaseV1(attempt_ordinal)`，ordinal必须属于预签 schedule，并在每
   byte前同时重验 parent、lease与 `ErasureReadBindingV1`。crash/retry消费下一个预承诺
   ordinal，不能复用 attempt lease或 top-up；redirect/rebind、destination/tenant/model/
   retention变化必须取得新 parent grant，不算普通 retry，也不能沿用“模型功能已开启”。
   无 grant时 MemoryFrame只能留在本地；
3. `ExportAuthorizationV1` 是每个显式 user export 的 one-shot grant，绑定 installation、
   source scope/fixed cut/cumulative view、format/projection、destination class、staging/output
   byte cap、monotonic validity/revoke frontier、nonce/sequence 与 expected terminal。它在 admission 时 durable consume；
   exact replay 只能恢复同一 transaction，不能产生第二份 output。cancel、late pipe close、
   destination change 或 source/view advance均要求 terminal/新授权。

三种 wire 的 canonical bytes、signature/MAC key purpose、caps、monotonic/replay reducer、
freshness、A/B store/recovery、redaction/disclosure UX、golden/fuzz 尚未接受，因此
`MemoryUsePolicyGateV1`、`ProviderMemoryDisclosureGateV1` 与
`ExportAuthorizationGateV1` 当前均 BLOCKED。`CapturePolicyConsentGateV1`、三者以及
`ErasureRequestAuthorityV1` 互不蕴含；删除路径不依赖任何 use/disclosure/export grant。

如果只持久化 `model_input_digest`/projection commitment，历史 Run 只能证明“后来提供的
input 与当时 commitment 一致”，不能据此重建 exact model input。若未来选择持久化
MemoryFrame/Provider body Blob，必须先提升 phase/schema，逐 range 保存 source lineage、
上述 disclosure grant、retention 与 cumulative-erasure propagation，并证明 source erase
会覆盖这份副本；在此之前禁止复制正文来换取 replay convenience。

---

## 13. 本地擦除状态机

### 13.1 Phase schema 尚未接受

以下 canonical schema 在 M9A 后续 phase gate 前是**有意未定义**：

- `RootBootstrapControlV1`
- `OwnerStateV1`
- `AuthorityBootstrapIntentV1`
- `KeyringBootstrapIntentV1`
- `KeyringBootstrapReceiptV1`
- ADR 0016 future authenticated `RecordIdentityTombstoneV1`
- `WriterSourceAuthorityManifestV1`
- `ErasureManifestV1`
- `ErasureReceiptV1`
- `ErasureSafetyCapabilityReceiptV1`
- `ErasureRequestAuthorityV1`
- `MemoryUsePolicyV1`
- `MemoryUseGrantV1`
- `ProviderMemoryDisclosureGrantV1`
- `ExportAuthorizationV1`
- `ManifestSealUseAuthorityV1`
- `RotationControlV1`
- `RotationReceiptV1`
- `ManifestSealAliasRetirementReceiptV1`
- `GenerationAliasDestructionReceiptV1`

因此当前实现不得持久化它们的“临时版本”。`requestErasure`、跨来源 capture、真实导出、
rotation 与“擦除完成收据”全部返回 `FEATURE_STAGE_BLOCKED`。本节冻结状态语义、顺序和
安全不变量，不能被解释为 wire 已冻结。
两项新增 erasure authority/receipt 的 exact descriptors、签名/MAC、caps、monotonic/
replay reducer、publish/recovery 与 golden/fuzz 同样归
`LocalErasureControlPhaseGateV1`；当前 BLOCKED，禁止 JSON/SharedPreferences/Binder
boolean 等 ad-hoc 代用品。

`RecordIdentityTombstonePhaseGateV1=BLOCKED` 时，retention、dedup、compaction 与 local
erasure 都不得删除 canonical Journal frame。exact retry 找到原 frame 时返回原 durable
outcome；既找不到 frame、又找不到未来 authenticated tombstone authority 时必须返回
`INDETERMINATE`，不得 append 一个同 identity 的新 record。只有未来 tombstone wire 把
record identity、原 durable cut、删除原因、cumulative erasure view 与 compaction output
commitment 认证绑定并 close/reopen 后，该 gate 才可能授权 frame 删除。

### 13.2 状态

```text
IDLE
  -> PREPARING
  -> ERASURE_REQUESTED
  -> DRAINING
  -> DESTROYING
  -> COMPACTING
  -> PROVING
  -> COMPLETE
```

任一步异常进入 `FAILED_BLOCKED`；只能从 durable request/cut/fence 恢复，不能退回 IDLE
重新开始并遗忘旧 request。`FAILED_BLOCKED` 只终止当前 **selective** transaction，不是
删除义务的最终关闭：request、fence、cumulative authority与已知 physical closure继续
durable，capture/egress保持 blocked。若 selective recovery不能安全继续，状态必须暴露
`WHOLE_RESET_REQUIRED`，由 ADR 0018 不可互 cast 的 whole-root control在明确用户确认后
接管；它删除整个 Memory root/installation aliases并做外部 census，不依赖 Provider、
网络、Brain成功或当前 normal FeatureStage。不得从 FAILED_BLOCKED恢复 capture，也不得
把“没有可安全 selective删除的路径”解释为 request已完成。

### 13.3 PREPARING 是线性化边界

在宣布 `ERASURE_REQUESTED` 前必须依次完成：

1. 在未来 canonical control store durable 写入 pending request 与前一 cumulative view；
2. 安装新 erasure fence，所有 writer/egress path 可见；
3. 禁止 old generation 的 ID、sequence、Blob、Cipher 与 queue allocation；
4. 提升全新 write generation；新写天然继承累计排除集合；
5. seal/retire old writer generation，并开始 drain old reader/writer lease；
6. 对当前 live process incarnation 发 barrier，取得每个进程对 fence 的 ACK；
7. 固定 cut，并构造 sorted cumulative exclusion root；
8. durable 提交 request/fence/cut/root；
9. 才能把状态变成 `ERASURE_REQUESTED`。

这保证 request 与并发 capture 有明确先后。不能先展示“已请求”，再异步补 fence。

### 13.4 累计请求

请求严格串行并有 durable queue。若 request A 排除 source A，随后 request B 排除 source B，
B 的有效 view 必须仍排除 A：

```text
Excluded(B) = Excluded(A) union {B 的新增目标}
```

cumulative root 对 canonical sorted exclusion entries、前一 root 和 fixed cut 承诺。重试、
compaction、索引重建、导入和远端回放都必须读取最新 cumulative view；不存在“只看最后一条
request”的优化。

### 13.4.1 动态读取/物化/egress binding

每次 warm/cold recall、search/index materialization、HotSnapshot map、pagination cursor、
Blob lease、MemoryFrame、PFD/pipe、export、maintenance/rebuild 或 Provider handoff 都必须
先从 authenticated control/source authority取得一个不可由 caller 构造的：

```text
ErasureReadBindingV1(
  cumulative_view_root,
  erasure_fence_generation,
  fixed_cut_digest,
  source_authority_digest
)
```

open 时验证一次不够。任何 page emission、Blob chunk/plaintext byte、cursor resume、
PFD/pipe write、MemoryFrame build、export file write、index/snapshot publish、maintenance
copy 和 Provider/socket byte handoff **之前**都必须在 coordinator 上复验 exact binding。
binding 变化、gate 降级、Broker restart 或 source authority 不再覆盖 fixed cut 时：

1. 停止后续读取/解密/物化与 byte handoff；
2. 丢弃尚未交付的 page、Blob plaintext、MemoryFrame、snapshot/index candidate 与 export
   staging；
3. 关闭 lease/fd/pipe/request，拒绝旧 generation 的迟到 callback；
4. terminal 返回 `POLICY_RESTRICTED`（若结果边界不明则
   ADR 0018 closed transfer outcome `INDETERMINATE`），不得继续旧 cursor；
5. 如需重试，只能重新求新 fixed cut/new binding，绝不把旧页与新页拼接。

已经越过外部 trust boundary 的 bytes 不能被本地擦除“召回”；本 binding 证明的是 fence
之后不再放行。cursor/PFD/pipe/Blob handle 不能缓存成永久授权。测试必须覆盖 erase 恰在
page1/page2、cursor resume、Blob chunk、PFD write、MemoryFrame build、Provider handoff、
HotSnapshot map、export staging、Broker death/rebind 与 maintenance publish 之间发生，并
断言旧 binding 零新增 bytes、零混 cut、所有 unreleased material 被清理。缺
`CumulativeErasureViewGateV1` 或 `SourceErasureAuthorityGateV1` 时上述所有消费者
fail closed。

### 13.5 多进程 barrier 与 egress drain

每个进程启动时生成随机 `process_epoch`，向 coordinator 注册 role、Binder death recipient
与其已持有的 writer/reader/pipe/provider operation。coordinator restart 后：

- 空 heap **不等于 drained**；
- 必须重新 bind/handshake 当前可发现的 IME、`:brain`、main/Broker；
- 旧 durable process registration 只有在 Binder death、PID/incarnation mismatch 与资源
  owner census 都闭合后才能退休；
- 无法联系、状态不明或存在 orphan fd/pipe/provider request 时保持 DRAINING/BLOCKED。

barrier ACK 必须证明：

- 没有 old-fence capture token；
- 没有 old generation ID/sequence/Cipher/queue；
- UI→Brain、Tool、pipe/PFD、export staging 与 Brain→Provider 的**本地**
  coordinator/request/socket ownership均已 fence、cancel/close 并进入本地 terminal；
- 迟到回调受 run/process generation gate 拒绝；
- 所有 fd 已关闭或由明确 owner lease 追踪。

仅停止 Journal writer 不等于 egress drain。

`EgressDrainEvidenceGateV1` 的边界止于 Sense 控制的本地进程、fd/socket/request registry
与最后一次 byte handoff。远端服务可能忽略 cancel、永不返回，或已保留此前接收的 bytes；
这属于 §2.2 非目标，不能让本地擦除永远卡在 DRAINING。fence 必须阻止任何新 byte handoff，
本地 adapter 必须 cancel/close request/socket、清除 unreleased buffer、拒绝迟到 callback，
并在 ADR 0018 closed transfer outcome 中记 `INDETERMINATE`，reason semantic 为
“remote outcome unknown”（exact code 留给 0018-E wire 冻结）；本地 key/file destruction不等待远端
ACK，也不得把该状态表述为远端删除证明。

measurement suite 不连 Internet/真实 Provider。它用 production coordinator 与 production
HTTP/socket adapter 的 exact cancel/close/callback generation path，接一个 attested
deterministic **loopback adversarial** `SyntheticProviderTransportV1`（known synthetic
bytes、no retention、可模拟不读/迟到/永不回应/半关闭，运行后外部清场）。需要证明 socket/fd
ownership 的 Provider path 不接受 in-process fake 作为 PASS evidence；in-process transport
只能是补充 unit test。evidence 必须绑定 transport mode/adapter build digest。该证据只证明
本地 operation registry/fence/late-callback/terminal ledger。真实 key/HTTP adapter
conformance 是独立、显式 opt-in 的 provider
integration suite，不进入 local-erasure proof，也不打印 key/正文/私有推理。

### 13.6 销毁与证明顺序

对每个受影响命名空间：

1. 写出不含被排除来源的新 canonical segment/index/Blob mapping；
2. 完整 tag/digest/coverage 验证并 durable publish；
3. locator/frontier/keyring 收敛；
4. drain 旧 mapping/segment leases；
5. unlink 明确列举的旧 Journal、Blob、index、temp/export staging；
6. 对每个 parent directory fsync；
7. close/reopen 根并做完整 census，证明旧 physical ID/alias/source 不可达；
8. 保留完整 durable request/fence/cut/cumulative tombstone/control authority；
9. 删除不再被任何可验证对象引用的 data-generation Keystore alias；purpose 13 owner-lease
   root alias 不因 source erasure 删除；
10. 再次进程重启、锁屏/解锁和 cold recall 负测；
11. 未来 schema 接受后 durable 写入并 close/reopen 验证 receipt；receipt 必须承诺 request、
    cumulative root、alias deletion 与 reboot census evidence；
12. receipt COMMITTED 后才可把 bulky control history compact 为 receipt commitment；累计
    tombstone/view root 与 receipt commitment 必须继续保留，不能“清除 control 引用”后
    失去防复活 authority。

alias delete 失败、结果不确定或 census 不完整都进入 `FAILED_BLOCKED`，并按 §13.2保留
有效 request/fence/cumulative authority；selective路径不能恢复时转为
`WHOLE_RESET_REQUIRED`，等待显式用户确认的独立 whole-root control，而不是关闭删除义务。
不能因为“内容已经从 UI 搜不到”就完成。文件 unlink/directory durability 是 MUST；底层 NAND cell 清零、TRIM
时机和控制器 remap 只能记录为 `BEST_EFFORT_MEDIA_SANITIZATION`，不能提升完成等级。

`LocalErasureControlPhaseGateV1` 与 `LocalErasureCapabilityGateV1` 是两个不可合并的 GateId：

- `LocalErasureControlPhaseGateV1` 只裁决 `ErasureManifestV1/ReceiptV1` exact control
  wire、状态/fence/cumulative authority、recovery reducer、caps、golden/fuzz 与 zero-root
  model。它可以在零 root 上 PASS，不声称 unlink/drain/reboot runtime 已工作；authority
  bootstrap 只依赖这个 control phase gate；
- `LocalErasureCapabilityGateV1` 必须在 ADR 0018
  `SyntheticMeasurementPermitV1` 下，以已知 synthetic records 完成 kill matrix、process
  restart egress drain、cumulative request、alias destruction、unlink/parent-fsync 与 reboot
  census evidence 后才可 PASS。它不能成为 measurement permit 的前置，否则重新形成自举
  环。

`LocalErasureControlPhaseGateV1` 非 PASS 时不得创建 Memory root、Keystore key、record、Journal
或 Blob，stage 最高 `SCHEMA_ONLY`。phase PASS 但 capability 非 PASS 时，只允许 ADR 0018
窄 measurement permit；任何真实用户 capture（包括 DARK）以及常规 persistent synthetic
DARK、`SHADOW/CANARY/DEFAULT` 均不可达，UI 也不得出现“可永久忘记”的承诺。

两 gate PASS 后也不自动允许 DARK。常规 product-synthetic DARK 还必须按 ADR 0018 唯一
reducer通过 `NORMAL_PRODUCT_SHARED_V1`（含 owner/release/policy/budget/build/snapshot
authentication）与 capability branch；它**不**要求 real-data
`StageRevocationFreshnessGateV1`。任何真实/unknown/mixed data class 的 DARK/SHADOW/
CANARY/DEFAULT 才额外要求该 freshness overlay。本文不得维护另一份 conflicting stage list。

唯一不依赖已通过 `BudgetProfileGateV1` 的路径是 ADR 0018
`SyntheticMeasurementPermitV1`：它不是 FeatureStage/DARK，只能以 exact production
candidate APK/applicationId 在 pristine candidate-only root、known synthetic corpus、
预承诺有界 experimental config 与全部 candidate root/key/use/local-erasure/evidence-wire/
harness decisions/receipts PASS 后执行 calibration/
confirmatory measurement；不得接触真实正文、生产 root、真实/远端 Provider 或产生产品晋级。
W8 local drain 仅可使用 §13.5 的 attested synthetic transport。当前
`ReleasePolicySemanticsPhaseGateV1`、`BudgetEvidenceWirePhaseGateV1` 与
`SyntheticMeasurementControlPhaseGateV1` 均 BLOCKED，所以该窄旁路也不可用。

---

## 14. Backup、Direct Boot 与泄漏负测

每个受支持 API/OEM 角色必须有以下 negative evidence：

1. 新安装、未首次解锁：Memory root/alias 不创建，Broker 返回 `USER_LOCKED`；
2. 已使用后重启并保持锁定：无法读取 Journal/Blob/keyring 明文或生成新 capture；
3. `Context.isDeviceProtectedStorage == true` 时 constructor 拒绝；
4. `noBackupFilesDir` 之外不存在 Memory mirror、SharedPreferences snapshot、Room WAL、
   plaintext temp；
5. Auto Backup/data extraction dry run 的 manifest 中无 `sense-memory/v1`、alias、provider key；
6. adb/debuggable 测试只使用专用 build/applicationId，不把生产根打包成 artifact；
7. symlink、path traversal、hard-link-like 替换、大小写变体和非 canonical base32hex 被拒绝；
8. crash/tombstone/logcat 中只见 opaque ID、enum 与 bucket，不见 fixture plaintext；
9. 卸载/重装产生新 installation；旧文件若人为恢复也因 key/owner continuity 不成立而
   fail closed；
10. API 29、HyperOS 与 API 36.1 分别执行 locked-device、backup exclusion 和 process
    restart 场景，不能由 emulator 单点外推。

这些测试尚未在 Gate 0 运行；它们是实现退出条件。

---

## 15. Crash 与不确定结果矩阵

| 故障点 | 可恢复状态 | 必须动作 |
|---|---|---|
| operation reservation 写前 | 旧高水位 | 可重试 reservation |
| reservation write/force 结果不确定 | 可能新高水位 | full pair reread；无法选择则 block；不得发放猜测区间 |
| Keyring A 写完、未复读 | 旧/新 pair | full pair selector；不信内存 |
| partial keyring bootstrap（含 A1/B UNUSED） | 只有未来 canonical intent/receipt 能证明 exact step 时才可恢复 | 当前 wire BLOCKED；不得补 B、重复生成 ID、枚举猜测或删除 alias |
| Keyring PREPARED 出现 | 旧 ACTIVE 可读 | 新写/删除/rotation block |
| Blob temp 中途 | 不可见 | retire physical ID/DEK/ordinals；owner census 后清理 |
| physical rename 后、parent fsync 前 | INDETERMINATE | close/reopen census；不能直接发 BlobRef |
| locator PREPARED/old ACTIVE | old authority | 仅原 writer连续 ownership且 outcome确定时完成既定 transition；crash/unknown后 old有界只读、locator永久 uncertain，零 peer write/old delete/remap |
| locator first new ACTIVE | exact chain交叉验证后 new mapping至多有界只读、未收敛 | 仅原 writer连续 ownership且 outcome确定时完成；crash/unknown后零 repair、零 old delete、零 remap，显式 whole-logical erasure仍必须可收敛 |
| old unlink 后、parent fsync 前 | INDETERMINATE_DELETE | fsync + reboot census；不得删 alias |
| erasure PREPARING crash | durable pending/fence 可能存在 | 从 control store 恢复；保持 capture blocked |
| coordinator restart | in-memory registry 空 | 重新 handshake/census；不得判 drained |
| alias delete 结果不确定 | unknown | observed KeyStore + cold decrypt negative test；仍 block receipt |

---

## 16. 实现与测试门禁

### 16.1 Gate 0 已接受

- purpose/alias/base32hex registry；
- 64-byte KeyAuthorizationProfile；
- 256-byte operation frontier；
- 65,536-byte Keyring slot、128-byte clear header、144-byte AAD、65,388-byte ciphertext、
  64/96/272 payload records、16 generations/144 keys 与 40,768 payload cap；
- authority/keyring bootstrap 的不可合并安全边界、stable identity、A/B selector 与
  full-pair rollback 限制；bootstrap control wire 本身未接受；
- Blob header/chunk/footer/caps、随机 logical ID 与 512-byte locator；
- plaintext surface、CapturePolicy、lease/delete 顺序与 erasure 状态不变量。

### 16.2 尚未完成

- Kotlin/Android codec；
- Keystore API 29–36.1 requested/observed 设备矩阵；
- fuzz/property/kill/reboot/backup negative tests；
- `WriterSourceAuthorityManifestV1`、`ErasureManifestV1`、`ErasureReceiptV1`、
  `RotationControlV1`、`RotationReceiptV1`、manifest-seal use/frontier/retirement、
  generation-alias destruction 与 authority/keyring bootstrap control/intent/receipt
  schemas；
- root-scoped rotation-control authentication authority的独立 purpose/alias/profile、
  bootstrap/backup/erase、usage frontier与 recovery evidence；purpose registry提升前不得
  用现有 1..13 purpose代替；
- `MemoryUsePolicyV1/MemoryUseGrantV1`、`ProviderMemoryDisclosureGrantV1` 与
  `ExportAuthorizationV1` 的 exact wire/store/replay/revoke；
- `FileIdentitySafetyPortV1` 在 API 30–36.1 的最小高层审计 JNI可行性与实体设备 matrix；
  public `android.system.Os` 不足以提供所需 `*at`/no-replace组合。minSdk29 `.so` 不得有
  unresolved `renameat2`；API29在任何 mutation前返回 `UNSUPPORTED(NO_REPLACE)`，API30+
  只可 typed `dlsym`并在 candidate permit下、CE/noBackup专用 probe目录做 same-filesystem
  semantic probe。probe绑定 exact APK/device fingerprint/filesystem，验证 collision保持两
  inode/bytes、absent destination移动同 inode、双 parent fsync/cleanup与 kill recovery；
  运行时无 accepted fresh probe即在 authoritative root/key/body前 UNSUPPORTED。测试还覆盖
  symlink/hardlink/path swap、final exists、EINTR/short I/O/ENOSPC/EIO/ENOSYS/EXDEV、
  rename/fsync/unlink每个 kill点、directory-fsync失败与 opaque handle stale generation；
  fixed lock-file bootstrap/replace/unlink、`flock` unsupported/contention、process-death
  release与 duplicated-handle semantics；
  不得退回 canonical String path、raw fd integer、overwrite rename或 link/unlink拼装；
- ADR 0016 authenticated `RecordIdentityTombstoneV1` 与
  `RecordIdentityTombstonePhaseGateV1`；
- `LocalErasureControlPhaseGateV1` 与在 measurement-only synthetic runtime 上求值的
  `LocalErasureCapabilityGateV1`；
- `ReleasePolicySemanticsPhaseGateV1`、`BudgetEvidenceWirePhaseGateV1` 与
  `SyntheticMeasurementControlPhaseGateV1`，以及 ADR 0018
  PreCertification candidate/keyring/backup/StageSnapshot phase+capability依赖；
- 真实数据 capture、rotation、export、erasure runtime；
- `ReleaseOwnerContinuityGateV1`、`ReleaseIdentityGateV1`、
  `LocalErasureControlPhaseGateV1`、`StageRevocationFreshnessGateV1`、
  `BudgetProfileGateV1` 与 `BuildAttestationGateV1` PASS。

### 16.3 必须有的测试族

- 所有固定布局的 golden bytes、offset/length arithmetic 和 reserved-zero；
- base32hex canonical tail、alias/path traversal、ID cross-encoding；
- AAD/MAC domain separation、wrong key/purpose/generation/identity；
- Journal segment-DEK GCM usage golden：AAD=162 bytes、charge
  `12+ceil(ciphertext_length/16)`；frame 65,535/65,536/65,537 与 block cap
  `2^24-1/2^24/2^24+1`，checked overflow、Cipher.init 前拒绝、normal seal/new-DEK、
  crash/uncertain-count retire 和 BudgetProfile 只收紧；
- KeyAuthorization API 29/30/31–35/36.1 requested-vs-observed；
- operation frontier 1..1024 reservation、overflow、单槽损坏、完整旧 pair 限制；任意
  old `g1..gN` valid slot replay + invalid peer 必须
  `FRONTIER_SINGLE_SLOT_UNCERTAIN`、零 write/零 init/零 ordinal，原 alias 只可受控 rotation
  或 BLOCKED；完整 adjacent pair 的 65,535→65,536 与 65,536→拒绝边界不得越过 cap；
- Keyring A/B 每个 write/force/reopen byte 的 kill matrix；
- `keyring_payload_region_digest` min/max payload、全零 padding 与 padding bitflip golden；
  same-generation selector/repair intent 必须使用 exact 65,388-byte region digest；
- same-generation mixed manifest-seal alias generation/digest、identity/features/state 必须
  拒绝；只改变 slot label/IV/ciphertext/tag 且 semantic clear fields相同的 canonical pair
  deterministic 选 A；
- Keyring single-valid replay test：任意 old-valid source + current peer invalid 必须
  `READ_ONLY_SINGLE_SLOT_UNCERTAIN`、零 purpose-5 init/零 write；future repair 除
  reserve-before-init、fresh IV/AAD、intent/receipt 与 kill matrix 外，还必须先有独立
  rollback-resistant witness 对 exact source slot digest 的 current-head proof，否则仍
  BLOCKED；
- Blob 0、1、边界、64 MiB、16,384 chunks、oversize-before-allocation；
- chunk transcript 0/1/max-chunk golden；zero 值固定为 `SHA-256(domain||0x00)`，record
  bitflip、commit/CRC bitflip、重排、重复、缺失与 trailing bytes 均使 transcript/footer
  验证失败；
- Blob clear-metadata confidentiality golden：header/footer/locator 不含 raw content type、
  plaintext digest 或 public unkeyed logical commitment；同 plaintext 的两个 independent
  physical attempts 得到不同 purpose-9 commitment；wrong purpose9 generation/key、
  payload/header/locator commitment mismatch 全部拒绝；equal-length 低熵候选没有离线
  verifier oracle，同时 fixture 明示 exact-length/chunk-shape leakage；
- Blob nonce golden：index 0=`SMB1 || 0000000000000000`，index 16,383 的 big-endian
  vector，并穷举允许 index 证明 12-byte nonce 唯一；attempt retry 必须换 BlobDEK/physical
  ID；
- Blob data-DEK usage golden：AAD=83 bytes、charge
  `7+ceil(ciphertext_length/16)`、最大 16,384 invocations 与 4,308,992 authenticated
  blocks；zero/max Blob、chunk mix、边界±1、首个/per-chunk Cipher.init 前 reserve、
  overflow/recovery 重算及 BudgetProfile 只收紧；
- BLOB_WRAP payload golden：fixed 256-byte canonical plaintext、154-byte AAD、27
  authenticated blocks/init、purpose-2 per-alias total 1,769,472 `<2^21`，content type
  zero padding、purpose9 HMAC 与 encrypted BlobRef cross-check；
- locator 表中每个合法/非法组合和 reader acquire/revalidate race；
- locator generation exhaustion golden/kill matrix：base `UINT64_MAX-3` 恰好允许并在
  `MAX-2/MAX-1/MAX` 每一步 crash 后收敛；base `UINT64_MAX-2`、`UINT64_MAX-1`、
  `UINT64_MAX` 均在 fence、ordinal、physical/locator I/O 前拒绝且零 orphan；不得
  wrap/reset/换 logical ID 冒充同一 Blob，显式 erasure 仍可完成；
- physical-generation exhaustion：old=`UINT64_MAX-1` 恰好允许一次 checked remap 到 MAX，
  old=MAX 在 fence/ID/DEK/ordinal/Cipher/I/O 前以
  `PHYSICAL_GENERATION_EXHAUSTED` 零副作用拒绝；crash/retry 不得重置或换 logical ID；
- arbitrary old single-ACTIVE replay + invalid/UNUSED peer 必须
  `LOCATOR_SINGLE_SLOT_UNCERTAIN`，零 write/init/ordinal/fence/physical I/O、零
  generation `+1`，maintenance remap/delete blocked；future repair 仅在故障前 durable
  intent + rollback-resistant current-head witness exact 绑定 source digest 时测试；
- unlink→parent fsync→restart census 与 alias delete 负测；
- CapturePolicy preflight 前零正文、deny 后零 allocation/queue/file；
- erasure PREPARING 每一步 crash、累计 A→B、coordinator/process restart、Provider 迟到回调；
- rotation pre-side-effect exhaustion golden：slot base MAX-2 允许 terminal pair，
  MAX-1/MAX 零 side-effect 拒绝；generation ordinal MAX-1 允许 terminal MAX、MAX 拒绝；
  G=15/K=135 恰允许、G=16/K=144 达 terminal cap、下一单位拒绝；单次预留的 bounded
  purpose-5 attempted-use range中至少两个成功 commit的 slot-init ordinal/attempt均
  durable burn，control/alias/init/slot/receipt每个 kill point可恢复且不重复 use；
- noBackup/directBoot/log/artifact 的真实设备 negative tests。

任何一族缺失，相关 capability 只能保持 BLOCKED；“单元测试通过”不能替代物理设备与重启证据。

---

## 17. 反例

- 用 SHA-256(plaintext) 当 Blob 文件名：泄漏相等性并把物理 rewrite 锁死；
- 把 key epoch/chunking 放进 BlobRef：使逻辑引用随 compaction 改变；
- Keyring 一槽 valid 就删除旧 alias：单槽尚未形成独立可恢复的新 pair；
- 把 KeyInfo 序列化后做 digest：OEM getter 差异会改变 intended contract；
- 以 SharedPreferences 保存 installation ID：清理/恢复后可生成平行身份；
- locator 只比较 mapping digest：实现 bug/碰撞域错误会绕过 exact mapping equality；
- PREPARED generation 因 generation 大就 ACTIVE：绕过 rotation authority；
- `unlink()` 返回成功即宣称擦除：目录项可能未 durable，alias/lease 仍可达；
- Broker 重启后 map 为空即 drained：其他进程/pipe/provider 仍可能持有数据；
- 后一擦除请求只包含 B：A 会在重建时复活；
- `noBackupFilesDir` 中写 plaintext temp：noBackup 只防云备份，不防离线文件读取；
- 完整旧 A/B pair 的 generation 较小就视为“仍然新鲜”：本地 pair 没有外部 freshness。

---

## 18. 决策边界

本 ADR 在 Gate 0 接受 policy 与 M9A primitive wire；它没有声称实现、测试或设备认证已完成。
由于 `ReleaseIdentityGateV1` 仍非 PASS，且 local erasure/source/rotation control schema
尚未接受：

- M9A01 可实现纯 codec/golden/fuzz，stage=`SCHEMA_ONLY`；
- 当前不得创建 normal Memory root/key 或执行持久化 synthetic DARK；normal product必须按
  §8.4/ADR 0018完整 owner/keyring/local-erasure DAG。pre-certification measurement只走
  ADR 0018 separate candidate authority/keyring/backup receipts，建立不可转正的
  candidate substrate，不要求 normal `ReleaseOwnerContinuityGateV1`，也不授权 DARK；
- 之后只有 ADR 0018 全部 control/wire/harness prerequisites 下的一次性
  `SyntheticMeasurementPermitV1` 可运行 exact production candidate applicationId/APK
  内的 candidate-only authority/root measurement paths，并据此求
  `LocalErasureCapabilityGateV1`；separate applicationId只允许 companion harness。常规
  synthetic DARK仍要求 accepted
  `BudgetProfileGateV1` 与 normal product DAG；
- 真实用户数据 capture（即使 DARK）、跨 writer/source、导出、rotation、erasure receipt 与
  `SHADOW+` 全部 BLOCKED；
- 任何代码若找不到明确 phase authority，必须返回 `FEATURE_STAGE_BLOCKED`，不能自行定义
  wire 或降低门禁。

---

## 19. 参考资料

- Android Developers, [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
- Android Developers, [`KeyGenParameterSpec`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)
- Android Developers, [`KeyInfo`](https://developer.android.com/reference/android/security/keystore/KeyInfo)
- Android Developers, [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot)
- Android Developers, [`UserManager.isUserUnlocked`](https://developer.android.com/reference/android/os/UserManager#isUserUnlocked())
- Android Developers, [Back up user data](https://developer.android.com/identity/data/autobackup)
- AOSP bionic, [`libc.map.txt` exported libc ABI](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/libc.map.txt)
- NIST SP 800-38D, [GCM and GMAC](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- NIST SP 800-38D Rev.1, [2025 pre-draft call：random-IV invocation limit](https://csrc.nist.gov/pubs/sp/800/38/d/r1/iprd)
- NIST SP 800-38D Rev.1, [2026 second pre-draft call：usage bounds](https://csrc.nist.gov/pubs/sp/800/38/d/r1/2prd)
- NIST SP 800-38D Rev. 1,
  [Second Pre-Draft Call for Comments](https://csrc.nist.gov/pubs/sp/800/38/d/r1/2prd)
- NIST SP 800-57 Part 1 Rev. 5, [Key Management](https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final)
- NIST SP 800-88 Rev. 2, [Media Sanitization](https://csrc.nist.gov/pubs/sp/800/88/r2/final)
- RFC 4648, [Base-N Encodings / Base32hex](https://www.rfc-editor.org/rfc/rfc4648)
- OWASP MASVS, [Storage and Cryptography](https://mas.owasp.org/MASVS/)
