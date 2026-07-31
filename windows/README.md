# Sense Windows（工程预览）

Sense Windows 是 Android 版之外的一条原生桌面实现，不是键盘窗口模拟器。系统输入部分使用
Windows Text Services Framework（TSF）进程内 DLL；Provider、Skills、工具和后续记忆能力位于
独立 `Sense.AgentHost` 进程，通过有界 named-pipe 协议连接。

## 已交付

- C++20 `ITfTextInputProcessorEx` / `ITfKeyEventSink` TSF 文本服务；
- TSF composition 的创建、更新、提交和取消事务；
- 直接读取现有 `pinyin_lexicon.bin` 的 SPLX v3 原生解码器；
- 词库 memory mapping、精确拼音、句子 beam composition、简拼、混拼索引和前缀候选；
- `ITfCandidateListUIElementBehavior`，支持 Windows UILess 候选接管；
- 不抢焦点的 Arctic 候选条，支持 DPI、屏幕边界、鼠标选词和 1–5 数字键；
- x64/x86 TSF 构建与注册脚本；
- WPF 设置中心，保存 JSON 配置并生成原生只读 INI 投影；
- `sense.agent.bridge.v1` NDJSON / named-pipe ABI 和 JSON Schema；
- Agent Host 握手、状态、运行受理与流式事件的进程外骨架；
- 原生解码测试、Agent ABI 测试、TSF COM 装载测试和 Agent Host 自检。

## 目录

```text
windows/
├─ native/
│  ├─ core/                 # SPLX 解码、输入 Session、Agent 请求编码
│  ├─ tsf/                  # COM/TSF、composition、候选 UI、注册
│  └─ tests/                # decoder + DLL COM smoke tests
├─ host/
│  ├─ Sense.Settings/       # Windows 设置中心
│  └─ Sense.AgentHost/      # 进程外 Agent bridge
├─ protocol/
│  └─ agent-bridge.schema.json
└─ tools/
   ├─ build.ps1
   ├─ install.ps1
   └─ uninstall.ps1
```

## 构建

运行环境：

- Windows 10 19041+ 或 Windows 11；
- `.NET 8 Desktop Runtime` x64（仅设置中心与 Agent Host 使用；TSF 输入核心为原生 DLL）。

构建环境另需：

- Visual Studio 2022 C++ Desktop toolchain；
- Windows 10/11 SDK；
- .NET 8 SDK。

构建 Release x64 + x86、执行测试并输出 zip：

```powershell
powershell -ExecutionPolicy Bypass -File windows/tools/build.ps1
```

仅构建 x64 Debug：

```powershell
powershell -ExecutionPolicy Bypass -File windows/tools/build.ps1 `
  -Configuration Debug -Architecture x64 -NoArchive
```

产物：

```text
windows/out/bundle/
windows/out/Sense-Windows-Release.zip
```

## 安装

从 bundle 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File windows/out/bundle/install.ps1
```

脚本会提升权限、复制文件到 `C:\Program Files\Sense`、分别注册 x64/x86 TSF DLL，
并创建“Sense 设置”开始菜单入口。随后按 `Win + Space`，在中文（简体）下选择
“Sense 先思输入法”。

开发阶段只构建 x64 时，同一安装脚本会注册已有架构。

卸载：

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\Sense\uninstall.ps1"
```

追加 `-PurgeUserData` 会同时删除 `%LOCALAPPDATA%\Sense`。

## 输入操作

| 操作 | 行为 |
|---|---|
| `A–Z` | 输入并实时解码拼音 |
| `Space` | 提交当前候选 |
| `1–5` | 提交当前页对应候选 |
| `↑ / ←`、`↓ / → / Tab` | 移动候选 |
| `PageUp / PageDown` | 翻候选页 |
| `Enter` | 提交原始拼音 |
| `Backspace` | 删除一个组合字符 |
| `Esc` | 取消组合 |
| `Ctrl + Space` | 切换 Sense 中英文模式 |

## Agent 预留边界

TSF DLL 不包含 HTTP client、Provider key 或模型 SDK。Agent 激活路径遵循：

```text
TSF explicit trigger
  → bounded EditorSnapshot
  → \\.\pipe\sense.agent.v1
  → Sense.AgentHost
  → stream events
  → hash/generation checked editor patch
```

当前 Schema 已冻结这些基础字段：

- `protocol = sense.agent.bridge.v1`
- `request_id`、`generation`、`capability`
- `before_cursor`、`selected_text`、`after_cursor`
- `skill_id`、`skill_revision`、`instruction`
- `document_hash`

单次 snapshot 总长度上限为 65,536 个 UTF-16 code units，单条 instruction 上限为
4,096。Settings 只保存 endpoint 与 model；Credential Store 能力留在 bridge capability
中，后续接入 Windows Credential Manager。

## 工程边界

当前工程预览聚焦“能作为 Windows 输入法安装、输入、组词和出候选”的主干。以下能力排在后续
里程碑：

1. display attribute provider 与 mode language-bar item；
2. 用户词频持久化、上下文 bigram 和 Android 版完整纠错图等价；
3. Windows Search 的 `ITfFnSearchCandidateProvider`；
4. Agent editor snapshot 捕获、patch CAS 应用及真实 Provider transport；
5. ARM64 安装矩阵、签名、MSIX/企业部署包；
6. UI Automation、Narrator 与真实应用兼容矩阵。

## 设计依据

- [Microsoft：自定义 IME 要求](https://learn.microsoft.com/zh-cn/windows/apps/develop/input/input-method-editor-requirements)
- [Microsoft：文本服务注册](https://learn.microsoft.com/zh-cn/windows/win32/tsf/text-service-registration)
- [Microsoft：TSF composition](https://learn.microsoft.com/en-us/windows/win32/tsf/compositions)
- [Microsoft：UILess Mode](https://learn.microsoft.com/en-us/windows/win32/tsf/uiless-mode-overview)
- [Microsoft：ITfUIElementMgr](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nn-msctf-itfuielementmgr)
- [Rime Weasel：Windows 输入法工程参考](https://github.com/rime/weasel)

更完整的取舍记录见
[`docs/adr/0024-windows-tsf-native-port.md`](../docs/adr/0024-windows-tsf-native-port.md)。
