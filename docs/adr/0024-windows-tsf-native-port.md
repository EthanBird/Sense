# ADR 0024：Windows 原生 TSF 端与进程外 Agent Lane

- **状态：** Accepted / Engineering preview
- **日期：** 2026-07-31
- **范围：** Windows 10 19041+、Windows 11，x64/x86；ARM64 保留构建入口

## 背景

Sense 的 Android 端已经形成三条关键边界：

1. 按键、解码、候选和上屏完全本地；
2. AI 由用户显式触发；
3. Provider、工具、Skills、Journal 和长期记忆不进入逐键热路径。

Windows 端既要保留这些产品原则，也必须采用系统认可的输入法扩展模型。Microsoft 当前文档
要求自定义 IME 使用 TSF；Windows 应用会阻止基于 IMM32 的实现。TSF DLL 会被加载到当前
应用进程，并继承该进程的 app-container 约束。因此，将网络和 Provider client 直接塞入
IME DLL 会同时放大兼容性、内存、故障域与凭据边界。

## 决策

### 1. 输入面使用原生 C++20 TSF DLL

`SenseTsf.dll` 实现：

- `ITfTextInputProcessorEx`
- `ITfKeyEventSink`
- `ITfThreadMgrEventSink`
- `ITfCompositionSink`
- `ITfCandidateListUIElementBehavior`

TSF edit session 是唯一的文档写入入口。首次输入创建 composition，后续按键只替换 composition
range；候选提交和取消都在单次 `TF_ES_READWRITE` 事务中结束 composition。

### 2. 继续使用 Sense 自有 SPLX v3 资产

不把 Kotlin/JVM 装进每个宿主进程，也不在第一阶段切换到另一套词典配置。原生端直接解析 Android
生产资产 `pinyin_lexicon.bin`：

- magic：`SPLX`
- version：3
- record count：823,782（当前资产）
- UTF-8 候选、big-endian weight、initials、source tier

词库使用 read-only file mapping。每个宿主进程只保留约 3.3 MB 的 offset table；35 MB
record pages 可由 Windows file cache 共享。

工程预览已实现 exact、sentence beam、initials namespace、hybrid namespace、statistical prefix
和 bounded prefix scan。Android 完整 spelling graph、bigram 与 personalization 属于等价性
里程碑，不阻塞 TSF 主干。

### 3. 候选 UI 同时支持桌面自绘和 UILess

候选对象先调用 `ITfUIElementMgr::BeginUIElement`：

- `pbShow = TRUE`：显示不抢焦点的 Arctic Win32 candidate strip；
- `pbShow = FALSE`：宿主读取 `ITfCandidateListUIElementBehavior` 并自行绘制。

每次候选、选择或分页变化都发布对应 `TF_CLUIE_*` flags。候选条按当前 monitor work area
约束位置，并跟随 DPI。

### 4. Agent 是进程外能力

`Sense.AgentHost.exe` 通过当前用户限定的 named pipe 暴露
`sense.agent.bridge.v1`。TSF 侧的协议模块只负责：

- bounded snapshot；
- immutable skill revision；
- NDJSON serialization；
- generation / document hash；
- request 和 event 的关联字段。

桥接进程负责 Provider transport、Credential Manager、工具、Skills、stream 聚合和后续
Journal。任何连接、超时或 Provider 状态都不改变本地输入能力。

### 5. 设置中心和输入 DLL 通过文件投影解耦

WPF 设置中心保存 `%LOCALAPPDATA%\Sense\settings.json`，并原子生成窄化后的
`settings.ini`：

- native DLL 只读取 input/appearance/agent 的热启动投影；
- Agent Host 读取完整 JSON；
- TSF DLL 不解析可扩展 Provider JSON；
- 保存设置不需要向正在输入的任意应用进程注入 IPC。

## 参考实现与资料

调研覆盖：

- Microsoft TSF 注册、edit session、composition、UIElement 和 UILess 文档；
- Microsoft SampleIME 的 COM/TSF 生命周期模式；
- Rime Weasel 的 Windows 多架构输入法、服务分层与部署形态；
- Sense Android 当前 reducer、SPLX decoder、candidate ranker、EditorSnapshot、Skills 和
  Agent session ABI。

借鉴的是平台协议和工程分层，Windows 代码按 Sense 的数据格式与产品边界重新实现。

## 结果

### 正向

- 系统级输入，而非全局键盘 hook 或悬浮窗口模拟；
- Win32、WPF 和支持 TSF 的现代 Windows 应用共享同一 composition 模型；
- 输入 DLL 不依赖 .NET、网络或 Provider；
- Android 与 Windows 可从同一 Frost-derived SPLX 资产构建；
- UILess host 可以接管候选；
- Agent ABI 可独立演进和重启。

### 成本

- TSF COM 生命周期、跨宿主兼容矩阵和双架构注册带来更高工程复杂度；
- C++ decoder 与 Kotlin decoder 需要回放语料门禁，防止排名漂移；
- 自绘候选和系统绘制需要保持 page/selection 语义一致；
- x64/x86 两个 DLL 都需要签名、安装和回归。

## 验证门禁

当前自动门禁：

1. SPLX 完整结构校验；
2. `nihao → 你好`、`zhongguo → 中国`、`nihaoshijie → 你好世界`；
3. 96 字符输入上限；
4. InputSession 状态与候选选择；
5. Agent snapshot / instruction 上限和 NDJSON 协议版本；
6. `LoadLibrary → DllGetClassObject → IClassFactory → ITfTextInputProcessorEx`；
7. Agent Host `--self-test`；
8. WPF 与 .NET host 编译。

正式发行前追加：

- Notepad、Office、Chromium、Electron、WPF、WinUI、Windows Search；
- x64 app + x86 app；
- 多显示器、125–300% DPI、Narrator、touch keyboard；
- 快速切换 profile、应用焦点和 composition 外部终止；
- 代码签名与升级/回滚；
- Android / Windows 统一候选回放与延迟基准。

## 相关链接

- <https://learn.microsoft.com/zh-cn/windows/apps/develop/input/input-method-editor-requirements>
- <https://learn.microsoft.com/zh-cn/windows/win32/tsf/text-service-registration>
- <https://learn.microsoft.com/en-us/windows/win32/tsf/edit-contexts>
- <https://learn.microsoft.com/en-us/windows/win32/tsf/compositions>
- <https://learn.microsoft.com/en-us/windows/win32/tsf/uiless-mode-overview>
- <https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfuielementmgr-beginuielement>
- <https://github.com/rime/weasel>
