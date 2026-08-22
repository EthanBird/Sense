# SenseMicVAD

SenseMicVAD 是 Sense Mic Client 的 Windows x64 WaveRT 虚拟音频驱动。

## 数据路径

```text
Rust CPAL output
  -> Sense Mic Playback (render pin, 48 kHz stereo PCM16)
  -> nonpaged latest-wins ring buffer
  -> Sense Mic (capture pin, 48 kHz stereo PCM16)
  -> Windows application
```

Rust 客户端把 48 kHz 单声道样本复制到驱动的双声道 render endpoint。驱动在 DMA
position 更新时把 render 字节写入受 spin lock 保护的 nonpaged ring；capture endpoint
按自己的 DMA 时钟读取。ring 欠载时补零，过载时丢弃最旧数据，任一端点从 STOP 进入
RUN 时清空。ring 上限为 500 ms，避免把历史声音送入恢复后的录音会话。

## 来源和许可

工程以 [MikeTheTech/Virtual-Audio-Driver](https://github.com/VirtualDrivers/Virtual-Audio-Driver)
提交 `bb34fba15faf569a6ae9bdea360bc1cf4821354e` 的工程布局为起点，并保留其 MIT License；
其中 Windows audio miniport 源码派生自 Microsoft SysVAD / Simple Audio Sample，继续
保留 Microsoft Public License。详见 `LICENSE-MIT.txt` 与 `THIRD_PARTY_NOTICES.md`。

Sense 增加的核心部分包括：固定的 Sense 端点与硬件 ID、48 kHz 格式表、
`SenseMicRing.*`、render-to-capture 路由、可复现 WDK NuGet 接入、INF 最低系统声明和
打包脚本。

## 构建

从仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File `
  sense-mic-client/driver/windows/build-driver.ps1 -SignMode Test
```

要求 Visual Studio 2022 C++ Build Tools。脚本自行下载并校验官方 WDK/SDK NuGet 与
WDK VSIX，产物写入 `driver/windows/x64`。生成目录和证书均被 Git 忽略。

每次构建包含：

1. x64 Release 全量重建，C++ 使用 `/W4 /WX`；
2. `Inf2Cat` signability；
3. `InfVerif /v /w`；
4. 开发测试签名，或生成不带公开发行签名的 Partner Center staging 目录；
5. INF/SYS/CAT 大小和 SHA-256 manifest。

`-SignMode Test` 的 manifest 标记为 `development-test-only`；`-SignMode Submission` 额外
收集 PDB 并标记为 `unsigned-partner-center-staging`。两者都不是公开发行驱动。
普通 Windows 10/11 发行包只接收 Microsoft Partner Center 返回且通过
`Assert-WindowsDriverPackage.ps1 -ExpectedClass MicrosoftSigned` 的目录；普通用户发行优先
使用 HLK/WHQL，attestation 只按 Microsoft 当前支持边界用于对应测试场景。
