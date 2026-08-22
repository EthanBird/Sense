# Sense Mic Client

Sense Mic Client 是 Sense Android 输入法“电脑麦克风”服务的 Rust 桌面接收端。Android
负责采集、Opus 编码和加密传输；桌面端负责设备发现、认证、丢包恢复、抖动缓冲、解码，
再把 PCM 写入系统虚拟音频端点。

## 已实现能力

- Windows 10/11 x64 原生 GUI：自动扫描、IP 直连、配对码、延迟选择、连接/停止、
  实时运行记录和驱动状态；
- Windows Setup：安装 GUI 与命令行核心，创建开始菜单入口，并提供可选桌面快捷方式和
  可选开机启动；
- Windows 10/11 x64：仓库内置 WaveRT 虚拟音频驱动源码、可复现 WDK 构建脚本、
  `pnputil` 安装/卸载与状态诊断；
- Linux x64/ARM64：通过 PipeWire 的 PulseAudio 兼容接口自动创建 `sense_mic` null sink，
  应用从 `sense_mic.monitor` 录音；
- 局域网 UDP 发现（49173）、TCP 控制与心跳（49174）、UDP 音频；
- 48 kHz 单声道、20 ms Opus、32/64/96 kbps CBR；
- 每 4 帧一组 XOR FEC、80–240 ms 自适应 jitter buffer、Opus PLC；
- P-256 ECDH、配对码派生、双方 transcript proof、HKDF、AES-256-GCM、128 包重放窗；
- 音频线程使用无锁 SPSC ring；欠载输出静音，过载立即清空陈旧队列而不是累积延迟；
- 心跳中断和音频超时后自动重连，退避上限 32 秒；Ctrl+C 可立即结束。

## Android 端

1. 打开 Sense 设置；
2. 进入“电脑麦克风”；
3. 授予录音权限并开启服务；
4. 记下页面上的六位配对码。

服务以 `connectedDevice|microphone` 前台服务运行。开启但没有已认证电脑时只保留发现和
控制 socket；认证完成后才创建 `AudioRecord`。常驻通知提供静音与停止按钮。关闭设置页、
切换输入法或锁屏不会把已建立的会话绑定到 Activity/IME 生命周期。

## 构建 Rust 客户端

要求 Rust 1.88+（`opus-rs` 与 Linux PulseAudio 依赖使用了 Rust 1.87/1.88 稳定的语言与标准库能力）。

```powershell
cargo test --manifest-path sense-mic-client/Cargo.toml
cargo build --release --manifest-path sense-mic-client/Cargo.toml
```

Windows GUI 产物为 `target/release/sense-mic-gui.exe`，命令行核心为
`target/release/sense-mic.exe`。两者应保持在同一目录。

## Windows GUI 与 Setup

GUI 启动后会自动检查虚拟麦克风并扫描手机。也可以直接填写手机 IP；配对码只通过
`SENSE_MIC_CODE` 传入受控的接收子进程，不出现在命令行、运行记录或磁盘配置中。关闭
窗口会同时结束该子进程。

使用 Inno Setup 6 构建正式安装器：

```powershell
powershell -ExecutionPolicy Bypass -File sense-mic-client/package-setup.ps1 `
  -Version 0.4.13
```

默认生成 `dist/setup/SenseMicSetup-v0.4.13-windows-x64.exe`。这是面向普通设备的
GUI 客户端 Setup，内含 GUI、CLI、许可与构建信息。若已有 Partner Center 返回的 WHQL
驱动目录，可传入 `-DriverStage X:\partner-center\SenseMicVAD`；脚本先执行签名、EKU、
catalog 成员关系与 kernel-policy 门禁，再把驱动纳入安装器并在安装阶段部署。测试证书
驱动不会进入该路径。

Linux 需要 PipeWire Pulse 或 PulseAudio 开发库；Debian/Ubuntu 可安装
`pkg-config libasound2-dev libpulse-dev pulseaudio-utils`，然后运行相同的 Cargo 命令。
`pulseaudio-utils` 提供客户端创建虚拟源所需的 `pactl`。

## Windows 驱动

驱动源码位于 [`driver/windows/SenseMicVAD`](driver/windows/SenseMicVAD)。它提供两个端点：

- `Sense Mic Playback`：Rust 客户端写入的 render endpoint；
- `Sense Mic`：会议、直播、游戏等软件选择的 capture endpoint。

构建脚本固定 WDK NuGet `10.0.26100.6584`，校验每个下载包的 SHA-256，并把官方 WDK
VSIX 集成到仓库私有 MSBuild overlay，不修改机器上的 Visual Studio。脚本最后强制执行
`InfVerif /v /w`，并生成带每个文件哈希的 `build-manifest.json`。

```powershell
# 开发包：WDK 自动生成测试证书
powershell -ExecutionPolicy Bypass -File `
  sense-mic-client/driver/windows/build-driver.ps1 -SignMode Test

# Partner Center staging 目录：不带公开发行签名，并包含 PDB
powershell -ExecutionPolicy Bypass -File `
  sense-mic-client/driver/windows/build-driver.ps1 `
  -SignMode Submission -OutputDirectory X:\submission\SenseMicVAD
```

这两个构建类别的 `build-manifest.json` 都明确写入 `releaseEligible: false`：`Test` 仅用于
启用了测试签名的开发机；`Submission` 仅生成用于 CAB 签名与 Microsoft Partner Center
提交的 staging 目录。面向普通 Windows 10/11 设备分发的驱动目录必须来自 Partner Center，
并通过 Microsoft catalog
签名、Windows kernel policy、catalog 成员关系和 EKU 检查。当前 Microsoft 文档把
[attestation 定位于测试场景](https://learn.microsoft.com/windows-hardware/drivers/dashboard/code-signing-attestation)；
面向普通用户强制使用 HLK/WHQL 签名；组合打包脚本会拦截 attestation 目录。Partner Center 返回文件的 Microsoft EKU 判定遵循
[Validate the Microsoft Signature](https://learn.microsoft.com/windows-hardware/drivers/dashboard/code-signing-validate)。
staging 目录仍需按 Hardware Dev Center 流程制作 CAB，并使用已登记的代码签名证书签署
CAB 后提交；仓库不会把未完成这一步的目录称为正式驱动。

把 Rust EXE 与 Microsoft 返回的正式驱动目录打成同一个发行 ZIP：

```powershell
powershell -ExecutionPolicy Bypass -File sense-mic-client/package-windows.ps1 `
  -PackageFlavor MicrosoftSigned `
  -MicrosoftSigningPolicy WHQL `
  -DriverPackage X:\partner-center\SenseMicVAD
```

脚本生成 `dist/SenseMicClient-windows-x64.zip` 和对应 SHA-256 文件；EXE 旁保持
`driver/windows/x64/SenseMicVAD.inf` 布局，因此 `sense-mic driver install` 可直接定位。
验证过程会生成 `driver-validation.json`；缺少 Microsoft 签名、EKU 不匹配、catalog 未覆盖
INF/SYS 或 kernel-policy 校验失败都会终止打包。
EKU 分类会先识别 `1.3.6.1.4.1.311.10.3.5.1` 为 attestation，再识别
`1.3.6.1.4.1.311.10.3.5` 为 HLK/WHQL；attestation 目录不需要同时携带后一个 EKU。

没有 Partner Center 返回包时，只生成名称明确的 client-only 发行资产：

```powershell
powershell -ExecutionPolicy Bypass -File sense-mic-client/package-windows.ps1 `
  -PackageFlavor ClientOnly
```

产物名为 `SenseMicClient-windows-x64-client-only.zip`，根目录的
`PACKAGE-MANIFEST.json` 同时声明 `driverIncluded: false`。开发测试驱动需要显式选择
`-PackageFlavor DevelopmentTest`，产物名固定包含 `development-test`，不会进入公开 Release。

以管理员终端安装已签名包：

```powershell
./sense-mic.exe driver install --package ./driver/windows/x64
./sense-mic.exe driver status
```

卸载：

```powershell
./sense-mic.exe driver uninstall
```

安装命令的边界是调用系统 `pnputil`；管理员权限、catalog 信任与内核加载策略由 Windows
执行。公开发行资产的自动查找目录只在 Microsoft-signed 组合包中出现；名称带
`development-test` 的开发包也保留相同布局，但仅用于测试签名环境。client-only 包需要先
单独取得 Microsoft-signed 驱动目录，再通过 `--package` 指定其 INF 或所在目录。

## 连接

```powershell
# 查看手机
./sense-mic.exe discover

# 单台手机可直接连接；配对码从隐藏输入读取
./sense-mic.exe serve

# 路由器禁用广播时显式指定手机 IP
./sense-mic.exe serve --host 192.168.1.23

# 多台手机时固定设备 id；可调整基础抖动缓冲
./sense-mic.exe serve --device-id 123456 --latency-ms 120
```

也可通过一次性的 `SENSE_MIC_CODE` 环境变量提供配对码。客户端默认只匹配
`Sense Mic Playback`；`--output default` 可用于不经过虚拟驱动的声卡回放诊断。

Windows 应用的麦克风列表中选择 **Sense Mic**。Linux 首次连接会自动加载虚拟源，
也可提前执行：

```bash
./sense-mic driver install
./sense-mic serve
```

## 诊断

```powershell
./sense-mic.exe doctor
./sense-mic.exe devices
./sense-mic.exe driver status
```

`doctor` 同时输出虚拟端点、CPAL 输出设备和局域网发现结果；虚拟端点尚未就绪或未发现
手机时以非零状态退出，便于安装器和 CI 把它作为 readiness gate。运行中每 5 秒输出收到、
恢复、丢失、jitter、缓存深度与丢弃样本计数。默认链路预算约为：20 ms 采集帧 +
100 ms 网络缓冲 + 40 ms 音频线程预滚，常见局域网端到端约 140–220 ms；可用
`--latency-ms 80` 换取更低延迟。

## 协议边界

- v1 传输面向可信局域网，所有音频包仍经过 AES-GCM 认证加密；
- 一次只允许一台桌面客户端占用手机麦克风；
- 码率或配对码变更会轮换 P-256 临时密钥并结束旧会话，客户端随后按退避策略重连；
- 控制帧与音频帧都有长度上限；来自其他 IP、错误 session、重复计数器或认证失败的
  数据包在进入解码器前丢弃。

完整选型与协议决策见
[`docs/adr/0026-sense-mic-phone-to-pc-virtual-microphone.md`](../docs/adr/0026-sense-mic-phone-to-pc-virtual-microphone.md)。
