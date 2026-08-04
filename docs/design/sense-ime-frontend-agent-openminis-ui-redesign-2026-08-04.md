# Sense IME Front Agent：对标 OpenMinis 的输入法前端重构设计

> 状态：In progress（P1 已完成，P2 后台所有权已进入首个纵向切片），作为 Agent 前端开发的唯一 UI/交互基线<br>
> 日期：2026-08-04<br>
> Sense 基线：v0.4.7，`c71de864b5d332a8a04cc1638b3e279f0c10c76f`<br>
> OpenMinis 研究基线：`0.22-preview`，`9cf3a855fecd27bb5735b84cacbd56852a3ab8dd`<br>
> 视觉输入：用户提供的 Photo 1、Photo 2<br>
> 关联文档：`docs/design/sense-agent-runtime-v2-openminis-core-design-2026-08-03.md`

> **实施快照（2026-08-04）：** 已新增延迟加载的 `agent-ui` Compose 模块、IME 统一根容器、Reading/Composing/History 基础界面、流式 Markdown、工具胶囊、工具详情与工具进度驱动的实时工具坞；Sense 键盘已可将中英文、候选、语音、撤销/重做路由到 Agent 草稿。随后完成 IME 宿主稳定性修复：Agent 与键盘始终属于同一个 `onCreateInputView()` 根 View，Reading 是根 View 内页面，Composing 是“上方 Agent + 下方 Sense 键盘”，输入框可独立收起；普通键盘态会显示带停止按钮的 Agent 运行浮条。Brain Service 现在保留有界 run replay，支持重建后的 IME 通过 Binder 重新订阅；跨进程原子 durable-run 记录负责恢复用户消息与终局回答。完整分页投影与多会话索引继续按 P2 推进。

## 0. 一页结论

这次产品决策正式纠正为：

1. **Agent 是输入法前端的一等主界面。** 用户从 Sense 工具箱进入 Agent 后，界面仍位于当前输入法窗口内，全程保持与当前 App、当前编辑器和当前输入上下文的连续关系。
2. **设置页只负责配置。** 模型、Provider、工具权限、外观、存储与后台选项留在设置页；“打开 Agent 工作台”从设置首页移除。
3. **取消 Agent / Terminal / Browser 三个顶层页签。** 对话是主线，终端和浏览器以消息内工具步骤、左下角实时工具坞和工具详情层出现。
4. **完整对话能力进入 IME，而非精简卡片。** 包含历史会话、流式 Markdown、工具调用、追问、附件、终端、浏览器、停止任务、继续任务和结果插入。
5. **输入法键盘本身成为 Agent 的输入设备。** 点击 Agent 输入框后，Sense 现有键盘在底部展开，按键输出进入 Agent 草稿；退出 Agent 后，同一套按键重新输出到宿主编辑器。
6. **左下角实时工具坞是核心组件。** 终端输出或浏览器画面持续可见，工具执行过程与对话正文并行，点击即可展开详情。
7. **运行时归 `:brain` 进程，前端归 `:ime` 进程。** 会话、Shell、WebView 与后台任务集中在 Brain Service；IME 通过 Binder 接收结构化增量、轻量预览帧与工具状态。
8. **默认结果留在对话中。** “插入”“替换选区”“复制”是显式动作，Agent 自由回答无需套入严格编辑协议，也避免因格式偏差重复消耗 token。

本设计取代旧方案中“设置页入口 + 独立 AgentHubActivity + IME 内仅展示精简 Agent Strip/Result Card”的产品放置方式。旧文档对运行时、工具模型、后台任务与协议解耦的研究仍继续沿用。

---

## 1. 为什么上一版方向偏了

### 1.1 输入法 Agent 的价值来自“就在输入现场”

Sense 的独特上下文是：用户正在某个 App 的某个输入框里，键盘已经拥有当前选区、光标附近文本、输入语言、候选词和语音入口。将 Agent 放进设置页或另一个完整 Activity，会切断以下连续关系：

- 用户看着原 App 内容发起任务；
- Agent 随时读取用户主动附加的选区或编辑器上下文；
- 用户边看回答边补充要求；
- 用户挑选某段结果，再显式插入原输入框；
- 工具任务后台继续，用户返回普通键盘仍能看到运行状态。

因此，Sense 的 Agent 不应复制一个普通聊天 App 的产品入口，而应把完整聊天体验嵌进 IME 生命周期。

### 1.2 顶层三页签破坏 Agent 的叙事主线

当前 `AgentHubActivity` 把 Agent、Terminal、Browser 平铺成三个等权页签。用户真正关心的是“任务正在推进到哪一步”，而不是先决定打开哪种工具。OpenMinis 的优秀之处正是：

- 对话负责叙事；
- 工具调用按时间出现在叙事中；
- 工具坞持续显示最新运行现场；
- 详情层承载高密度交互；
- 会话历史负责长期连续性。

Sense 新版按相同原则组织信息：**Conversation first, tools in context**。

### 1.3 当前渲染质量与目标差距

现有 `AgentHubActivity.kt` 使用命令式 Android View 拼装：

- 消息主要是普通 `TextView`；
- 助手正文缺少 Markdown 结构；
- 流式更新重建消息区域；
- 工具没有统一状态胶囊；
- 终端仅有一次性命令输入和文本输出；
- Browser 是独立页签中的 WebView；
- Composer、间距、颜色、阴影和排版缺少完整设计系统。

这套结构适合验证能力链路，不适合作为发布版 Agent 前端。新版将保留已验证的工具执行代码，同时整体重做信息架构与渲染层。

---

## 2. OpenMinis 视觉与代码复盘

本节不是凭截图猜测，而是将截图与固定 commit 的 Android 源码逐项对齐。

### 2.1 截图 Photo 1：对话页的组成

从上到下可分为六层：

1. **居中会话头部**
   - 第一行是会话标题；
   - 第二行是绿色在线点、模型组和下拉指示；
   - 第三行是 Provider / Model；
   - 左侧返回，右侧更多菜单。
2. **用户消息**
   - 右对齐；
   - 浅灰圆角气泡；
   - 最大宽度约为内容区 80%。
3. **助手消息**
   - `✨ Minis` 身份头；
   - 正文直接使用页面背景，没有大面积气泡；
   - 标题、列表、粗体、行内代码、代码块形成清晰层级。
4. **工具调用胶囊**
   - 小型图标、动作标题、耗时；
   - 流式状态用动态点或旋转状态表示；
   - 胶囊穿插在助手正文中。
5. **左下角实时工具坞**
   - 终端/浏览器缩略图从状态条左侧向上凸出；
   - 状态条显示成功/运行图标、工具标题、页码和切换箭头；
   - 即使正文已经滚动，运行现场仍固定在 Composer 上方。
6. **浮动 Composer**
   - 白色圆角卡片和柔和悬浮阴影；
   - 上半部是单行/多行输入；
   - 下半部是附件、Slash、语音、发送；
   - 右侧另有上下滚动快捷键。

### 2.2 截图 Photo 2：会话历史页的组成

- 背景使用很浅的冷灰色；
- 顶栏为设置图标、`Minis` 标题、定时任务、终端；
- 会话按“昨天”“本周”等时间段分组；
- 每行含 44dp 圆形会话图标、标题、一行摘要和弱化时间；
- 左下 56dp 新建会话按钮，右下 56dp 搜索按钮；
- 大量留白让会话列表显得轻盈，而不是设置列表式的密集卡片。

### 2.3 源码测量表

| 组件 | OpenMinis 源码事实 | Sense 复刻基线 |
|---|---|---|
| Chat 顶栏 | 展开高度 68dp；标题 16sp Semibold；模型组 12sp；Provider/Model 11sp | 数值直接作为首版基线 |
| 消息列表 | `LazyColumn(reverseLayout = true)`；横向 16dp；条目间距 2dp | 使用稳定块列表，视觉保持相同密度 |
| 用户气泡 | 最大宽度 80%；圆角 18dp；内边距 14×8dp；正文 16.5sp | 同值 |
| 助手头 | 18dp 渐变 Sparkle；间距 6dp；名字 16sp Semibold | 文案使用 `✨ Sense` |
| 工具胶囊 | 高 36dp；标题 13sp Medium；耗时 11sp Monospace | 同值，增加长按菜单 |
| Composer | 圆角 20dp；双层柔和阴影；正文 16.5sp；发送按钮 38dp | 同值并适配 IME 内键盘展开态 |
| 工具缩略图 | 100×65dp；圆角 8dp；10dp 阴影；0.5dp 描边 | 同值 |
| 工具状态条 | 高 38dp；圆角 10dp；8dp 阴影 | 同值 |
| 缩略图凸出 | `65 - 38 = 27dp` | 同值 |
| 状态条左留白 | 缩略图存在时 118dp | 同值 |
| Browser 刷帧 | 运行期间默认约 3000ms 轮询一次画面 | Sense 采用自适应 0.5–2fps |
| 会话行图标 | 44dp 圆形 | 同值 |
| 历史页 FAB | 56dp 圆形；6–8dp 阴影；页面边距 16/20dp | 同值 |

### 2.4 值得直接继承的工程方法

#### A. 流式 Markdown 按块冻结

OpenMinis 的 `StreamingMarkdownText.kt` 将正文切成稳定块：已完成的段落保持不动，只解析末尾仍在增长的块；解析工作移出主线程，并根据文本长度使用 200–500ms 等不同刷新节奏，最终内容到达时立即提交完整结果。

这正适合 IME：输入法对掉帧非常敏感，逐 token 重建整段 Markdown 会与按键、候选栏和宿主 App 争抢主线程。

#### B. 消息展平为列表项

`ChatFlatItems.kt` 将一条消息内部的文字块、工具块等展平为独立 LazyColumn 项。这样，工具状态更新只影响对应条目，历史正文无需跟随重组。

#### C. 会话状态与 Activity 分离

`ChatViewModelStore.kt` 和 `ChatViewModel.kt` 让会话、窗口尾部消息、流式侧通道在界面重建期间继续存在。Sense 需要进一步把这项原则推进到进程级：会话归 `:brain`，IME 是可随时重新挂接的投影。

#### D. 工具预览优先展示“现场”

`ChatComposerWidgets.kt` 的终端预览显示命令和末尾输出，Browser 预览优先显示实时位图，随后才回退到保存截图。用户看到的不是一句“Browser 正在运行”，而是任务现场。

#### E. 全局状态浮层与对话内工具坞是两个组件

OpenMinis 的 `ToolOverlayController.kt` 还实现了系统级左下角悬浮状态条，用于离开聊天页后继续观察任务；Photo 1 里的则是对话内工具坞。Sense 也按两层实现：首发先完成 IME 内工具坞，后续再提供可选的系统悬浮状态条。

### 2.5 固定版本源码索引

- [ChatScreen.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt)
- [ChatUserMessageUI.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatUserMessageUI.kt)
- [ChatAssistantMessageUI.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt)
- [ChatComposerWidgets.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatComposerWidgets.kt)
- [StreamingMarkdownText.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt)
- [ChatFlatItems.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt)
- [ChatToolDetailUI.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt)
- [SessionListScreen.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt)
- [ChatColors.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/ui/theme/ChatColors.kt)
- [ToolOverlayController.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/service/ToolOverlayController.kt)
- [PersistentShell.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt)
- [BrowserTabPool.kt](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt)

---

## 3. 新产品信息架构

### 3.1 唯一主入口：输入法工具箱中的 Agent

入口流程：

```text
普通键盘
  └─ 工具箱 → Agent
       └─ 当前 IME 窗口内切换到 Agent Reading
            ├─ 新对话
            ├─ 恢复最近对话
            ├─ 打开会话历史
            ├─ 查看工具现场
            └─ 点击 Composer → Agent Composing + Sense 键盘
```

设置页保留以下配置入口：

- 默认模型与模型组；
- Provider 与凭据；
- 工具开关、确认策略与后台行为；
- 会话存储、清理与导出；
- Agent 主题、字号和工具坞偏好。

设置首页的 Agent 工作台启动卡片删除。设置页可以展示“Agent 配置”摘要，但它只是配置导航。

### 3.2 顶层状态

```kotlin
sealed interface ImeFrontMode {
    data object Keyboard : ImeFrontMode
    data class AgentReading(val sessionId: String) : ImeFrontMode
    data class AgentComposing(val sessionId: String) : ImeFrontMode
    data object AgentHistory : ImeFrontMode
    data class TerminalFocus(val runId: String) : ImeFrontMode
    data class BrowserFocus(val runId: String) : ImeFrontMode
}
```

状态语义：

| 状态 | 顶部内容 | 底部内容 | 文本按键目标 |
|---|---|---|---|
| `Keyboard` | 宿主 App | Sense 普通键盘 | 宿主编辑器 |
| `AgentReading` | Agent 完整对话 | 悬浮 Composer | 尚未接收文本按键 |
| `AgentComposing` | 压缩后的 Agent 对话 | Sense 键盘 + Agent 草稿栏 | Agent 草稿 |
| `AgentHistory` | 会话列表 | 新建/搜索 FAB | 历史搜索框（激活后） |
| `TerminalFocus` | 终端详情 | 命令栏或 Sense 键盘 | Terminal stdin/command |
| `BrowserFocus` | Browser 详情 | 操作栏 | Browser 地址/表单目标 |

### 3.3 输入目标路由：输入法场景的关键抽象

IME 自己的 Composer 没有“再唤起一套系统软键盘”这条输入路径。Sense 已经是当前软键盘，因此需要把现有按键输出从“固定写入宿主编辑器”升级为可切换目标：

```kotlin
sealed interface TextTarget {
    data object HostEditor : TextTarget
    data class AgentDraft(val sessionId: String) : TextTarget
    data class TerminalInput(val runId: String) : TextTarget
    data object HistorySearch : TextTarget
}

interface TextTargetRouter {
    val activeTarget: StateFlow<TextTarget>
    fun commitText(text: CharSequence)
    fun deleteBackward()
    fun moveCursor(delta: Int)
    fun replaceSelection(text: CharSequence)
    fun submit()
}
```

拼音、英文、符号、语音、撤销/重做手势和候选选择都调用同一个 Router。`HostEditor` 使用 `InputConnection`；其他目标操作 Brain 投影中的草稿或工具输入缓冲区。

这项抽象让 Agent 真正成为输入法前端，而不是塞进输入法外壳的一张 Web/Activity 页面。

---

## 4. 页面与交互设计

### 4.1 Agent Reading：阅读优先态

```text
┌──────────────────────────────────────┐
│ ‹               会话标题          ⋮ │
│              ● 默认模型组            │
│           provider · model           │
├──────────────────────────────────────┤
│                                      │
│                      用户消息气泡     │
│                                      │
│ ✨ Sense                              │
│ 我先查看当前项目结构。                 │
│ [▣ 查看项目结构                 1.9s] │
│ 然后启动构建并观察输出。               │
│                                      │
│ ## 构建结果                           │
│ - app:assembleRelease                 │
│ - 状态：执行中                        │
│                                      │
│                              [↑]     │
│ [terminal preview] [◌ 正在构建  2/4 ‹›]│
│ ┌──────────────────────────────────┐ │
│ │ 给 Sense 发消息…                 │ │
│ │  ＋   ／                🎙   ↑  │ │
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

行为：

- 点击顶部返回：回到普通键盘，任务继续；
- 点击标题：打开会话信息与重命名；
- 点击模型组：仅影响下一次请求；
- 点击 Composer：进入 `AgentComposing`；
- 点击工具胶囊：展开对应工具详情；
- 点击左下缩略图：展开当前工具详情；
- 点击状态条左右箭头或横向滑动：切换本轮工具；
- 长按助手正文：选择、复制、插入或引用；
- 滚离底部后显示向下按钮；新内容到达时保持用户阅读位置，并显示未读计数。

### 4.2 Agent Composing：键盘输入态

```text
┌──────────────────────────────────────┐
│ ‹               会话标题          ⋮ │
├──────────────────────────────────────┤
│ ✨ Sense                              │
│ ……最近几条消息与工具状态……            │
│ [terminal] [● 构建中  2/4 ‹›]         │
├──────────────────────────────────────┤
│ 给 Sense 发消息：请继续修复测试…    ↑ │  ← AgentDraftBuffer
├──────────────────────────────────────┤
│ 候选词 / Agent 上下文动作             │
│ Q  W  E  R  T  Y  U  I  O  P         │
│  A  S  D  F  G  H  J  K  L           │
│   Z  X  C  V  B  N  M                 │
│ 123  🎙      空格       ⏎             │
└──────────────────────────────────────┘
```

关键规则：

- 现有 `SenseKeyboardView` 继续承担按键、候选、手势和语音；
- Agent 草稿栏只呈现 `AgentDraftBuffer`，不创建嵌套系统输入法；
- 发送后回到 Reading，也允许用户保持键盘展开连续追问；
- 返回键优先收起 Sense 键盘回 Reading，再次返回才回普通键盘；
- 键盘上方的候选区可在“拼音候选”和“Agent 快捷动作”之间切换。

### 4.3 Agent History：输入法内会话历史

```text
┌──────────────────────────────────────┐
│ ⚙  Sense Agent                 ◷  ◉ │
│                                      │
│ 今天                                 │
│  (绿)  修复 release 构建      2 分钟  │
│        已定位签名配置，并正在…         │
│                                      │
│ 昨天                                 │
│  (灰)  New Chat              14 小时  │
│        hi                            │
│                                      │
│                                      │
│                                      │
│  (＋新建)                    (⌕搜索)  │
└──────────────────────────────────────┘
```

与 OpenMinis 对齐的细节：

- 分组：置顶、今天、昨天、本周、本月、更早；
- 顶部 `◷` 进入计划任务，`◉` 打开活跃运行抽屉；它们都回到具体会话，不形成独立 Terminal 首页；
- 活跃会话图标带旋转进度环；
- 行摘要从最后一个可读消息块提取，而不是直接截取工具 JSON；
- 左滑显示置顶、重命名、删除；
- 运行中的会话删除时先呈现任务状态和停止选项；
- 搜索覆盖标题、用户消息、助手 Markdown 纯文本和工具标题。

### 4.4 Terminal Tool Detail

```text
┌──────────────────────────────────────┐
│ ‹ Terminal · 构建项目      ● Running │
├──────────────────────────────────────┤
│ $ ./gradlew :app:assembleRelease      │
│ > Task :app:compileReleaseKotlin      │
│ > Task :app:packageRelease            │
│ █                                    │
│                                      │
├──────────────────────────────────────┤
│ [停止] [复制尾部] [保存日志] [收起]    │
│ 输入命令…                         ↵   │
└──────────────────────────────────────┘
```

- 使用固定宽度字体；
- 实时尾随默认开启，用户上滑后暂停自动滚动；
- 回到底部按钮恢复尾随；
- ANSI 色彩转换为受控富文本；
- 每次命令、退出码、耗时、工作目录形成可折叠段；
- 超长输出进入日志 Artifact，界面只保留可配置环形窗口。

### 4.5 Browser Tool Detail

```text
┌──────────────────────────────────────┐
│ ‹ Browser                    ● Live  │
│ ‹  ›  ⟳   https://example/       ⋮  │
├──────────────────────────────────────┤
│                                      │
│         最新浏览器画面                │
│      [可点击热点与编号覆盖层]          │
│                                      │
├──────────────────────────────────────┤
│ 页面标题 · 加载状态 · 最近动作         │
│ [接管浏览器] [刷新画面] [查看 DOM]     │
└──────────────────────────────────────┘
```

`:brain` 中的 WebView 是唯一浏览器会话实体。IME 中展示画面投影、URL、标题、可交互元素和操作状态。用户需要直接触摸完整网页时，点击“接管浏览器”打开专用 `BrowserTakeoverActivity`，它挂载同一 `:brain` 进程中的 WebView；返回后 IME 恢复同一会话投影。这个 Activity 是工具接管层，不承担 Agent 首页与对话页职责。

---

## 5. 视觉系统

### 5.1 颜色 Token

首版以 OpenMinis 的低噪声系统色为基线，再替换为 Sense 品牌强调色。

| Token | Light | Dark | 用途 |
|---|---:|---:|---|
| `agentBackground` | `#FFFFFF` | `#000000` | 对话背景 |
| `secondaryBackground` | `#F2F2F7` | `#26262A` | 历史页、弱层级背景 |
| `composerBackground` | `#FFFFFF` | `#2C2C30` | Composer |
| `textPrimary` | `#000000` | `#FFFFFF` | 正文 |
| `textSecondary` | `#6D6D72` | `#A9A9AE` | 元信息 |
| `userBubble` | `rgba(120,120,128,0.12)` | `#2F3A5C` | 用户气泡 |
| `toolCapsule` | `#F2F2F7` | `#28282C` | 工具胶囊 |
| `toolSurface` | `#FFFFFF` | `#3A3A3F` | 工具状态条 |
| `senseAccent` | `#34C86F` | `#48DD82` | 状态、运行、成功 |
| `link` | `#007AFF` | `#0A84FF` | 链接 |
| `inlineCodeText` | `#FF9500` | `#FF9F0A` | 行内代码 |
| `codeBackground` | `#000000` | `#262626` | 代码块 |
| `codeText` | `#34C759` | `#8CF38C` | 代码块文本 |
| `danger` | `#FF3B30` | `#FF453A` | 失败、停止 |

### 5.2 排版

- 正文：16.5sp，行高 1.45；
- 助手名：16sp Semibold；
- H1/H2/H3：24/20/17sp Semibold；
- 列表项间距：4dp；段落间距：10dp；
- 工具标题：13sp Medium；工具耗时：11sp Monospace；
- 终端：11–12sp Monospace；缩略图终端命令 7sp、输出 5.5–6sp；
- 会话标题：16sp Semibold；摘要 14sp；时间 13sp；
- 支持系统 Font Scale，正文最高按 1.3 倍重新排版，工具坞转为更宽/双行布局。

### 5.3 圆角、边框与阴影

- 用户气泡 18dp；
- Composer 20dp；
- 工具胶囊 18dp；
- 工具缩略图 8dp；状态条 10dp；
- Composer 使用 6dp 环境柔光 + 10dp 下落阴影；
- 工具缩略图 10dp 阴影，状态条 8dp 阴影；
- 深色模式以 0.5dp 描边补充层级，减少大面积阴影。

### 5.4 动效

- Keyboard → Agent Reading：180ms，内容淡入 + 从底部 12dp 上移；
- Reading → Composing：220ms，列表高度与键盘位移同步；
- 工具坞进入：缩略图从左下 8dp/0.94 scale 过渡到位；
- 工具运行：状态图标旋转，标题保持稳定；
- 工具完成：图标在 160ms 内转为绿色勾，缩略图保留；
- 流式文字采用内容刷新节奏，不对每个 token 做位移动画；
- 遵循系统“移除动画”设置。

---

## 6. 左下角实时工具坞

这是新前端的高优先级组件，命名为 `ToolLiveDock`。

### 6.1 几何结构

```text
      ┌──────────── 100dp ────────────┐
      │ terminal / browser thumbnail   │ 65dp
      │ live output / live frame       │
┌─────┴────────────────────────────────┴──────────────┐
│ 118dp reserved │ ● 工具标题      2/7    ‹   ›      │ 38dp
└─────────────────────────────────────────────────────┘
        ↑ thumbnail 比 status bar 向上凸出 27dp
```

布局常量：

```kotlin
object ToolDockDimens {
    val ThumbnailWidth = 100.dp
    val ThumbnailHeight = 65.dp
    val ThumbnailRadius = 8.dp
    val StatusHeight = 38.dp
    val StatusRadius = 10.dp
    val ThumbnailOverhang = 27.dp
    val ThumbnailStartInset = 10.dp
    val TextStartWithThumbnail = 118.dp
}
```

### 6.2 内容优先级

终端缩略图：

1. 最近命令一行；
2. 最后 12 行可见输出；
3. 运行时光标；
4. 可选 CPU/MEM 细条；
5. 完成后保留退出码状态。

Browser 缩略图：

1. 最新实时帧；
2. 本次工具保存的截图；
3. 同一会话最近一次 Browser 帧；
4. 骨架屏 + 页面标题。

状态条：

- 15dp 类型/状态图标；
- 13sp 单行标题；
- 运行耗时或结果摘要；
- `当前序号/总数`，11sp Monospace；
- 两个 18dp 切换按钮。

### 6.3 状态与交互

```kotlin
enum class ToolRunState {
    QUEUED,
    RUNNING,
    STREAMING,
    WAITING_USER,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}
```

- 默认跟随最近一个活跃工具；
- 用户手动切换到历史工具后，保持所选页；新工具开始时用小圆点提示；
- 左右滑动与箭头执行相同翻页；
- 点击缩略图或标题打开工具详情；
- 长按打开“复制输出、保存 Artifact、在会话中引用、隐藏预览”；
- 运行中的工具在对应消息胶囊和详情层提供停止按钮；
- 工具完成后保留在本轮分页中，开始下一轮对话时折叠为上一轮记录；
- 工具坞始终位于 Composer 上方，消息列表底部预留 `65 + 14dp`，避免遮挡最后一条消息。

---

## 7. IME 容器架构

### 7.1 保留 Canvas 键盘热路径，Agent UI 延迟加载

`ime-ui` 当前没有 Compose 依赖，`SenseKeyboardView` 是性能敏感的 Canvas 视图。新结构采用混合容器：

```text
SenseImeRootLayout (FrameLayout)
├─ SenseKeyboardSurface              // 现有 Canvas 键盘
│  ├─ SenseKeyboardView
│  └─ ActiveSkillAuroraOverlayView
└─ AgentComposeHost (lazy)           // 首次进入 Agent 时创建
   └─ ImeAgentSurface
      ├─ AgentConversationScreen
      ├─ AgentHistoryScreen
      ├─ ToolLiveDock
      └─ ToolDetailSheet
```

原则：

- 普通输入启动期间不创建 ComposeView、Markdown Parser 或 Agent ViewModel；
- 首次点 Agent 时再加载 `agent-ui`；
- `AgentComposing` 下 Compose 区域缩短，底部复用现有 `SenseKeyboardSurface`；
- 过渡动画只改变两个子层的可见区域与位移；
- 内存压力较高时可释放历史 Markdown 缓存，保留 Brain 侧会话。

### 7.2 为 InputMethodService 提供 ViewTree Owners

Compose 和 ViewModel 需要明确生命周期。新增 `ImeAgentViewTreeOwners`：

```kotlin
class ImeAgentViewTreeOwners(
    private val service: SenseInputMethodService,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    // onCreateInputView -> CREATED
    // onStartInputView / window shown -> STARTED / RESUMED
    // onWindowHidden -> CREATED
    // service destroyed -> DESTROYED
}
```

并在 `AgentComposeHost` 上安装 `ViewTreeLifecycleOwner`、`ViewTreeSavedStateRegistryOwner` 与 `ViewTreeViewModelStoreOwner`。页面状态仍来自 Brain 投影，ViewModel 只保存展示选择、滚动位置和临时动画状态。

### 7.3 动态高度

普通键盘继续使用用户配置高度。Agent 使用 `ImeWindowHeightController`：

| 模式 | 目标高度 |
|---|---|
| Keyboard | 当前键盘高度 |
| Agent Reading | 可用屏幕高度的 72–82%，至少保留约 56dp 宿主内容提示区 |
| Agent Composing | 与 Reading 总高度一致；内部把底部键盘高度分配给 `SenseKeyboardSurface` |
| Agent History | 与 Reading 一致 |
| Tool Focus | 与 Reading 一致 |

实现规则：

- 进入/退出 Agent 时更新一次窗口高度；
- token、工具输出和 Browser 帧只更新内部内容，不触发窗口反复测量；
- 横屏、分屏、折叠屏按 `WindowMetrics` 重新计算；
- OEM 高度策略存在差异时，使用设备已接受的最大稳定高度，并保存兼容档位；
- 首帧先展示纯色骨架，再加载历史消息，避免窗口升高时出现空白闪烁。

---

## 8. 会话运行时与 Binder 协议

### 8.1 当前结构中的进程问题

当前结构：

```text
:ime   SenseInputMethodService
  └─ SenseAiBrainClient ─Binder─> :brain SenseAiBrainService

:brain AgentHubActivity
  └─ process-local SenseAgentHubRuntime
      ├─ AgentTerminalRuntime
      └─ AgentBrowserRuntime / WebView pool
```

`SenseAgentHubRuntime` 是进程内单例。若 IME 前端直接实例化它，`:ime` 会得到另一份会话、终端和 Browser 状态。新版将所有 durable state 集中到 `:brain`：

```text
:ime
  SenseInputMethodService
  ├─ ImeAgentCoordinator
  ├─ ImeAgentClient
  ├─ AgentComposeHost
  └─ TextTargetRouter
           │ Binder commands + batched UI deltas
           ▼
:brain
  SenseAiBrainService
  ├─ AgentSessionRuntime
  ├─ AgentProjectionStore
  ├─ AgentToolEventBroker
  ├─ StreamingTerminalRuntime
  ├─ BrowserRuntime / WebView pool
  └─ ArtifactStore
```

### 8.2 会话命令

```kotlin
sealed interface AgentSessionCommand {
    data class CreateSession(val seed: SessionSeed) : AgentSessionCommand
    data class SendMessage(val sessionId: String, val draft: UserDraft) : AgentSessionCommand
    data class StopRun(val sessionId: String, val runId: String) : AgentSessionCommand
    data class RetryFrom(val sessionId: String, val messageId: String) : AgentSessionCommand
    data class SwitchModel(val sessionId: String, val modelRef: String) : AgentSessionCommand
    data class SubmitToolInput(val runId: String, val input: String) : AgentSessionCommand
    data class PerformEditorAction(val action: EditorActionRequest) : AgentSessionCommand
}
```

对话请求直接表达用户消息、附件和上下文引用。旧实现把整段聊天拼进 `EditorSnapshotV1` 的方式退出主链路；`EditorSnapshotV1` 继续服务快速改写 Skill，完整 Agent 会话使用独立协议。

### 8.3 UI 投影

```kotlin
data class AgentFrontProjection(
    val session: SessionHeader,
    val visibleMessages: List<MessageBlockRef>,
    val streamingTail: StreamingTextProjection?,
    val toolRuns: List<ToolRunProjection>,
    val selectedToolRunId: String?,
    val draft: AgentDraftProjection,
    val runState: AgentRunState,
    val unreadCount: Int,
)

sealed interface MessageBlock {
    data class TextMarkdown(val id: String, val markdown: String, val stable: Boolean) : MessageBlock
    data class ToolUse(val id: String, val runId: String) : MessageBlock
    data class Artifact(val id: String, val ref: ArtifactRef) : MessageBlock
    data class EditorProposal(val id: String, val proposal: EditorProposalData) : MessageBlock
    data class SystemNotice(val id: String, val text: String) : MessageBlock
}
```

### 8.4 增量传输

Binder 侧遵循以下约束：

- 首次 attach 获取分页快照；
- 后续传 `AgentUiDelta`，同一会话按 `revision` 排序；
- token 聚合为约 50–100ms 一批，前端最高约 10Hz 刷新；
- 终端输出传有界 tail chunk 与累计行号；
- Browser 图片写入内部 Artifact 文件，Binder 只传 `ArtifactRef`；
- 大段历史按页读取；
- 断线重连携带 `lastRevision`，Brain 返回 delta 或新快照；
- 客户端 attach/detach 只影响观察关系，任务生命周期由 Brain 决定。

示例：

```kotlin
data class AgentUiDelta(
    val sessionId: String,
    val fromRevision: Long,
    val toRevision: Long,
    val operations: List<AgentUiOperation>,
)

sealed interface AgentUiOperation {
    data class AppendBlocks(val blocks: List<MessageBlock>) : AgentUiOperation
    data class UpdateStreamingTail(val text: String, val final: Boolean) : AgentUiOperation
    data class UpdateToolRun(val run: ToolRunProjection) : AgentUiOperation
    data class UpdateDraft(val draft: AgentDraftProjection) : AgentUiOperation
    data class UpdateRunState(val state: AgentRunState) : AgentUiOperation
}
```

---

## 9. 流式对话与 Markdown 渲染

### 9.1 渲染流水线

```text
Brain token stream
  → 50–100ms batch
  → AgentUiDelta.UpdateStreamingTail
  → block splitter (Dispatchers.Default)
  → stable prefix blocks + mutable tail block
  → markdown parse cache
  → LazyColumn keyed items
```

规则：

1. 已闭合的段落、列表、代码块、表格冻结为稳定块；
2. 只重解析最后一个增长块；
3. 内容长度越大，刷新间隔从 100/200ms 自适应升至 300/500ms；
4. `final=true` 时立即提交完整解析；
5. 超大流式块先显示尾部纯文本窗口，完成后再生成完整 Markdown；
6. 缓存键包含内容 hash、主题、字号和解析器版本；
7. 消息项 key 使用 blockId，工具状态更新只重组对应胶囊；
8. 用户停在历史位置时保持 scroll anchor，新内容通过“↓ 3 条新内容”按钮提示。

### 9.2 支持范围

首发支持：

- 标题、段落、粗体、斜体、删除线；
- 有序/无序列表与任务列表；
- 引用；
- 行内代码与 fenced code block；
- 链接；
- 简单表格；
- 图片/截图 Artifact；
- 可选择文本；
- 代码块复制与横向滚动。

消息默认没有助手大气泡，保证长文阅读宽度。工具和 Artifact 作为独立块穿插其中。

---

## 10. Terminal 能力重构

当前 `AgentTerminalRuntime` 每次调用新建 `/system/bin/sh` 进程并收集有界输出。新目标是 `StreamingTerminalRuntime`：

```kotlin
interface StreamingTerminalRuntime {
    fun open(sessionId: String, cwd: String?): TerminalHandle
    fun write(handleId: String, bytes: ByteArray)
    fun resize(handleId: String, columns: Int, rows: Int)
    fun interrupt(handleId: String)
    fun close(handleId: String)
    fun observe(handleId: String): Flow<TerminalEvent>
}
```

里程碑：

1. **P3a：持久化 shell + 流式管道**
   - 同一会话保持工作目录与环境变量；
   - stdout/stderr 分块进入 ring buffer；
   - 进程组级取消；
   - 命令退出码、开始/结束时间结构化记录。
2. **P3b：PTY 与 ANSI**
   - 支持交互命令、终端尺寸、颜色与光标；
   - 输出渲染限帧，不让高频日志压住 IME 主线程。
3. **P3c：增强运行环境**
   - 在 Android shell 能力之上增加可选 PRoot/Alpine 环境；
   - 环境安装与磁盘占用在设置页管理；
   - ToolLiveDock 始终显示当前真实环境标签。

数据预算：

- 内存 tail 默认 2,000 行或 256KiB；
- 完整日志按 Artifact 落盘；
- UI 合并更新最高 10Hz；
- 缩略图最多 12 行；
- 会话恢复先加载 tail，完整日志按需打开。

---

## 11. Browser 控制与进程边界

### 11.1 Browser 单一归属

Android View 实例属于创建它的进程与窗口体系。当前 WebView 池位于 `:brain`，因此新版保持：

- WebView、Cookie、历史栈、JS 状态和标签池全部归 `:brain`；
- IME 收到的是页面投影，而不是第二个 WebView；
- Browser Tool 的点击、输入、滚动、返回、刷新继续由 Brain 执行；
- 需要完整触摸网页时，`BrowserTakeoverActivity` 在 `:brain` 挂载原 WebView。

### 11.2 Browser 投影

```kotlin
data class BrowserProjection(
    val tabId: String,
    val url: String,
    val title: String,
    val loading: Boolean,
    val progress: Int,
    val frame: ArtifactRef?,
    val frameRevision: Long,
    val viewport: BrowserViewport,
    val elements: List<InteractiveElementRef>,
    val lastAction: BrowserActionSummary?,
)
```

画面策略：

- 页面快速变化时 2fps；
- 普通加载时 1fps；
- 稳定等待阶段 0.5fps；
- 前端隐藏后降频，只在关键动作后产出帧；
- 图片压缩为 WebP/JPEG，尺寸按 360–720px 宽自适应；
- 同一 tab 只保留最近少量帧和被消息引用的持久截图。

### 11.3 交互模式

IME Tool Detail 支持两种控制：

1. **语义控制**：展示 DOM 提取的可交互元素编号，点击编号发送 `click(ref)`；
2. **画面热点**：根据 viewport 坐标覆盖热点，点击后映射到 Brain 页面坐标。

涉及复杂拖拽、Canvas、视频或系统文件选择器时使用“接管浏览器”。接管页关闭后，Agent 对话、工具状态和工具坞立即回到同一 runId。

---

## 12. 输入法生命周期与后台连续性

### 12.1 生命周期语义

| 回调/事件 | 新行为 |
|---|---|
| `onCreateInputView` | 创建轻量 `SenseImeRootLayout`；Agent ComposeHost 仍为 lazy |
| `onStartInput` | 建立新的 `EditorLease`；普通键盘默认可见；保留 Brain 会话 |
| `onStartInputView` | attach 当前 Agent 概览；如同一 editor 且之前停在 Agent，可恢复原前端模式 |
| `onWindowHidden` | detach 高频 UI observer；Brain 任务继续；通知/可选悬浮状态接管进度展示 |
| `onFinishInputView` | 结束旧 `EditorLease` 的写入资格；会话、run 和草稿继续保存 |
| 配置变化 | 重新计算高度与布局；按 sessionId/revision 恢复投影 |
| `:ime` 重建 | 重新 Binder attach；从 Brain 快照恢复 |
| `:brain` 重建 | 从 Session/Artifact Store 恢复 durable projection，运行中步骤标记为 interrupted 或恢复执行 |

### 12.2 EditorLease

Agent 对话与编辑器写入分离。用户点击“插入”或“替换选区”时才校验当前宿主编辑器：

```kotlin
data class EditorLease(
    val editorInstanceId: String,
    val packageName: String,
    val fieldId: Int,
    val createdAtElapsedMs: Long,
    val selectionRevision: Long,
)
```

- 切换 App 或输入框后，旧 Lease 失效；
- 对话仍可阅读和继续；
- 写入动作使用当前 Lease，并让用户看到目标 App/字段摘要；
- 用户主动附加选区时生成不可变 `EditorContextRef`，后续对话引用这一份快照；
- Agent 自然语言回答始终有效，写入宿主只是后处理动作。

### 12.3 回到普通键盘后的运行提示

普通键盘工具栏新增极简 `AgentRunChip`：

```text
[ ✨ 正在构建 · 终端 2/4 ]
```

- 只在有活跃 run 或未读结果时出现；
- 点击恢复 Agent Reading；
- 完成后显示“已完成”与未读点；
- 空闲数秒后收为 Sparkle 小图标；
- 系统通知承担 IME 窗口完全隐藏后的后台状态；
- 系统级左下悬浮条作为后续可选项，视觉与 IME ToolLiveDock 保持同源。

---

## 13. 与宿主编辑器的动作模型

每个助手 Markdown 块长按或更多菜单提供：

- **插入光标处**；
- **替换当前选区**；
- **复制**；
- **引用并追问**；
- **继续生成**；
- **重试这一轮**。

代码块额外提供“复制代码”“插入代码”。工具 Artifact 提供“引用到下一条消息”“保存”“分享”。

Composer 左侧 `+` 菜单：

- 附加当前选区；
- 附加光标附近文本；
- 附加当前 App/页面摘要；
- 图片/相机；
- 文件；
- 最近 Artifact。

Slash 菜单：

- `/terminal`：明确要求使用终端；
- `/browser`：明确要求使用浏览器；
- `/selection`：引用当前选区；
- `/summarize`、`/rewrite`、`/translate`：复用现有 Skill；
- 用户自建 Skill。

这一模型让严格 JSON 编辑协议回到它擅长的快速改写场景；完整 Agent 对话使用普通文本 + 结构化 tool call，协议格式偏差只影响对应工具步骤，不吞掉整条回答。

---

## 14. 模块与文件规划

### 14.1 新模块

```text
agent-ui/
  src/main/kotlin/.../
    ImeAgentSurface.kt
    AgentConversationScreen.kt
    AgentHistoryScreen.kt
    AgentTopBar.kt
    AgentMessageList.kt
    AgentComposer.kt
    ToolCallPill.kt
    ToolLiveDock.kt
    ToolDetailSheet.kt
    TerminalToolSurface.kt
    BrowserToolSurface.kt
    StreamingMarkdown.kt
    AgentTheme.kt

agent-protocol/                 // 也可落入现有 brain-api
  AgentSessionCommand.kt
  AgentUiProjection.kt
  AgentUiDelta.kt
  MessageBlock.kt
  ToolRunEvent.kt
  ArtifactRef.kt
```

### 14.2 `ime-service` 新增

```text
ImeAgentCoordinator.kt
ImeAgentClient.kt
ImeAgentViewTreeOwners.kt
ImeWindowHeightController.kt
ImeTextTargetRouter.kt
EditorLeaseManager.kt
SenseImeRootLayout.kt
```

修改点：

- `SenseInputMethodService.onCreateInputView()` 返回 `SenseImeRootLayout`；
- `openAgentHub()` 替换为 `imeAgentCoordinator.open()`；
- 工具箱 Agent 卡直接切换前端模式；
- `onStartInput*` / `onWindowHidden` 交给 Coordinator 处理 attach、Lease 和可见性；
- 键盘 commit/delete/cursor/voice 输出统一走 `ImeTextTargetRouter`。

### 14.3 `ai-runtime` / `brain-api` 新增

```text
AgentSessionRuntime.kt
AgentSessionBinder.kt
AgentProjectionStore.kt
AgentToolEventBroker.kt
StreamingTerminalRuntime.kt
BrowserFramePublisher.kt
ArtifactStore.kt
AgentHistoryRepository.kt
```

修改点：

- `SenseAiBrainService` 成为会话与工具的唯一进程级 owner；
- `AgentToolExecutor` 增加事件流，而非只返回最终 `content`；
- `AiEvent.AgentProgress` 保留给快速编辑链路；完整 Agent 使用 `ToolRunEvent`；
- 当前 `SenseAgentHubRuntime` 迁移数据后删除或改成薄兼容层。

### 14.4 `app` 模块

- 从 `SettingsActivity.renderHome()` 移除“打开 Agent 工作台”；
- `AgentHubActivity` 退出主产品流程；迁移完成后删除；
- 新增可选 `BrowserTakeoverActivity`，只处理同进程 WebView 接管；
- 设置页新增 Agent 配置分组和存储/后台选项。

---

## 15. 性能预算

Sense 是输入法，性能验收优先于普通聊天 App。

| 指标 | 目标 |
|---|---:|
| 普通键盘冷启动 P95 | 与 v0.4.7 基线相比增量 ≤ 8ms |
| 首次按键响应 P95 | 与基线相比增量 ≤ 2ms |
| 进入 Agent 首个骨架帧 | ≤ 120ms |
| 最近会话可交互 | ≤ 350ms（本地数据） |
| 流式 UI 刷新 | ≤ 10Hz |
| Terminal UI 刷新 | ≤ 10Hz |
| Browser 预览 | 0.5–2fps 自适应 |
| 单次 Binder delta | 目标 ≤ 64KiB |
| Agent UI 稳态内存增量 | 目标 ≤ 45MiB，不含 WebView 进程内存 |
| 历史首屏 | 只加载可见窗口 + 前后缓冲 |
| 工具日志内存窗口 | 默认 ≤ 256KiB / run |

关键守则：

- 普通键盘路径不加载 Compose、Markdown、Browser 帧解码器；
- Bitmap 使用尺寸上限、采样与复用；
- Binder 传引用，文件传图；
- stable block 采用不可变模型和稳定 key；
- 输出洪峰由 Brain 聚合，IME 只消费限频投影；
- 性能基准纳入 release 构建，而不是仅看 debug。

---

## 16. 可访问性与多尺寸适配

- 所有可点击目标至少 44dp；
- Tool 状态同时提供图标、文字和 TalkBack 描述；
- 运行时间更新不持续打断读屏，只在状态变化时播报；
- Markdown 标题映射语义 heading；列表保留 list 语义；
- 代码块、终端和 Browser 预览提供明确角色名称；
- 字号放大后工具坞允许标题双行，缩略图可隐藏以保留操作区；
- 360/393/412dp 宽度建立 golden；
- 横屏采用左右分栏：对话为主，工具详情可占右侧；
- 分屏高度过小时进入 compact Reading，Composer 保持可用；
- 深色、浅色、动态色分别验证对比度。

---

## 17. 实施顺序

### P0：冻结设计与协议（1 个开发周期）

- 以本文为 UI 决策基线；
- 为 Photo 1/2 建 360dp golden 参考；
- 冻结 `MessageBlock`、`ToolRunProjection`、`AgentUiDelta`；
- 建立 IME 六状态转移测试；
- 标记旧文档被取代的 UI 段落。

**退出条件：** 页面线框、颜色、尺寸、状态机和 Binder DTO 评审通过。

### P1：输入法内 Agent 壳与静态视觉

- 新增 `SenseImeRootLayout` 与 lazy ComposeHost；
- 完成 Reading、Composing、History；
- 复用 Sense 键盘输入 AgentDraft；
- 用 fake repository 驱动长消息、Markdown、七个工具和流式状态；
- 从设置首页移除 Agent 启动入口。

**退出条件：** Agent 从工具箱无 Activity 跳转打开，截图观感达到 Photo 1/2 基线。

### P2：Brain 会话化与 Binder 投影

- 会话 owner 移入 `SenseAiBrainService`；
- 完成 attach/detach、快照、delta、分页与断线重连；
- 完成历史持久化；
- 普通回答脱离 `EditorSnapshotV1` 全对话拼接；
- 完成显式插入/替换/复制动作。

**退出条件：** 隐藏 IME、切换输入框、重建 `:ime` 后可恢复同一会话和流式回答。

### P3：工具胶囊、实时工具坞与 Streaming Terminal

- 完成 ToolCallPill 和 ToolLiveDock；
- `AgentToolExecutor` 发出结构化生命周期事件；
- 完成持久 shell、输出流、ring buffer、停止与 Artifact；
- 完成 Terminal Detail 和缩略图。

**退出条件：** 连续七步工具任务可分页查看；终端输出边运行边出现在左下缩略图和详情层。

### P4：Browser 画面与接管

- 完成 BrowserFramePublisher；
- 完成 IME Browser Detail、热点/语义点击和工具缩略图；
- 完成同一 WebView 的 `BrowserTakeoverActivity`；
- 验证 Cookie、历史栈和 tabId 连续性。

**退出条件：** Agent 控制页面时用户可在对话里观察，接管/返回后仍是同一页面会话。

### P5：生命周期、后台、性能与无障碍

- 前台服务与通知状态；
- 普通键盘 `AgentRunChip`；
- IME/OEM 高度兼容矩阵；
- Markdown 冻结、缓存、长会话和输出洪峰优化；
- TalkBack、Font Scale、深色模式；
- 可选全局左下状态浮层。

**退出条件：** 性能预算、生命周期矩阵和可访问性清单全部通过。

### P6：清理与发布

- 删除旧 `AgentHubActivity` 主流程和兼容代码；
- 清理进程内重复 Runtime；
- 更新用户文档、截图、版本迁移说明；
- release 构建、签名与发布。

---

## 18. 测试矩阵与验收标准

### 18.1 视觉验收

- 360×常见高度、393dp、412dp 三档宽度；
- Light/Dark；Font Scale 1.0/1.15/1.3；
- 用户气泡、助手头、Markdown、工具胶囊、工具坞、Composer 的 golden；
- ToolLiveDock 覆盖 Terminal running/success/fail、Browser loading/live/saved frame；
- History 覆盖空态、长标题、活跃环、所有时间分组。

### 18.2 产品验收

- 工具箱点击 Agent 后仍在 IME 窗口；
- 设置首页只有 Agent 配置导航，没有工作台启动按钮；
- 阅读态、输入态、历史、终端详情、Browser 详情之间返回层级一致；
- 点击 Agent Composer 后，Sense 键盘输入 Agent 草稿；
- 回普通键盘后按键立即恢复写入宿主编辑器；
- 助手自然语言回答可直接显示；
- 插入/替换仅在用户点击动作时写入宿主；
- 工具按时间嵌入消息，没有顶层 Terminal/Browser 页签。

### 18.3 流式与工具验收

- 流式 Markdown 中已完成段落保持稳定；
- 用户上滑阅读时新 token 不拉回底部；
- 七个工具连续运行时，工具坞页码、切换和状态准确；
- Terminal tail、详情和落盘日志内容一致；
- Browser 缩略图、详情帧、DOM 元素与 tabId 对应；
- 停止命令把 tool run 与 assistant run 一起收敛到明确状态；
- 完成后工具预览仍可回看。

### 18.4 生命周期验收

- IME 隐藏/显示；
- 切换 App 和输入框；
- 屏幕旋转、横屏、分屏；
- `:ime` 进程重建；
- `:brain` 进程重建；
- 后台运行十分钟后返回；
- Browser takeover 进入/返回；
- 锁屏/解锁；
- 网络切换；
- 超长终端输出和超长 Markdown。

### 18.5 回归护栏

- Agent 代码未初始化时，普通键盘的启动、内存和按键链路保持基线；
- 英文键位、中文/英文符号映射、Z/Y 长按下滑撤销重做继续通过现有回归；
- 语音普通模式与流式优化模式在 AgentDraft/HostEditor 两种目标下都正确路由；
- Skill 键位绑定与 Agent Slash Skill 共享同一 Skill ID，不复制配置数据。

---

## 19. 明确决策

| ID | 决策 | 理由 |
|---|---|---|
| D-01 | Agent 主界面位于 IME 前端 | 保持输入上下文与操作连续性 |
| D-02 | 设置页只做配置 | 产品入口与配置职责分离 |
| D-03 | 对话是顶层主线 | 工具应服务任务叙事 |
| D-04 | Terminal/Browser 采用胶囊 + 工具坞 + 详情层 | 运行现场持续可见，又不抢占对话 |
| D-05 | Sense 键盘通过 TextTargetRouter 输入 AgentDraft | 充分利用 IME 自身输入能力 |
| D-06 | Canvas 键盘热路径保留，Compose Agent UI 延迟加载 | 兼顾渲染质量与按键性能 |
| D-07 | 会话与工具 owner 全部位于 `:brain` | 避免跨进程状态分叉 |
| D-08 | Browser 使用 frame projection + 同进程 takeover | 保持同一个 WebView 会话 |
| D-09 | 完整 Agent 使用独立会话协议 | 普通回答与编辑协议解耦 |
| D-10 | 结果写入是显式动作 | 用户掌控目标编辑器与写入时机 |
| D-11 | ToolLiveDock 首发即进入核心范围 | 它是对工具执行信任感最关键的反馈 |
| D-12 | 旧 AgentHubActivity 退出主流程 | 消除设置页/独立工作台的错误产品结构 |

### 19.1 本阶段范围边界

- 首发聚焦 Android IME 内完整 Agent；
- 系统级左下悬浮条排在 IME 内工具坞之后；
- Browser 首发采用画面投影与接管页，跨进程 Surface 直出作为后续研究；
- Terminal 首发先完成持久 shell 与流式输出，完整 Linux 环境随后交付；
- 快速改写 Skill 继续使用紧凑结果卡，长任务进入完整 Agent 会话。

---

## 20. 对旧设计文档的取代关系

`sense-agent-runtime-v2-openminis-core-design-2026-08-03.md` 中以下方向由本文取代：

- “IME 只展示有界 Agent Strip / Result Card”作为完整 Agent 的主要前端；
- “完整 Agent Hub 位于独立 Activity”；
- “Agent / Terminal / Browser 顶层三页签”；
- Candidate C 中“IME UI 与 Agent UI 分离”的产品呈现；
- Phase 3 先建设独立 Agent Hub UI 的实施顺序。

以下研究继续保留并融入本文：

- Brain Service 的后台执行职责；
- 工具注册、调用、审批与事件模型；
- 会话持久化、恢复与前台服务；
- Terminal、Browser、Artifact 的运行时能力；
- 编辑器写入与 Agent 自然语言结果解耦；
- 生命周期、资源预算与故障恢复原则。

一句话总结新的系统边界：

> **运行时与 IME 进程保持解耦，完整 Agent 体验与输入法前端保持一体。**

---

## 21. 开发启动清单

实现阶段从以下 tracer bullet 开始，尽快形成一条真正纵向贯通的路径：

1. 工具箱 Agent → 同一 IME 窗口打开静态 Reading；
2. 点击 Composer → SenseKeyboardView 输入本地 AgentDraft；
3. 发送一条消息 → Binder → Brain fake/real session；
4. 流式文字 → delta → Markdown mutable tail；
5. 运行一条 shell 命令 → ToolCallPill → ToolLiveDock 实时 tail；
6. 隐藏 IME → Brain 继续 → 再打开恢复同一 session；
7. 点击助手文字“插入” → 校验当前 EditorLease → 写入宿主。

这七步打通后，再扩展历史列表、Browser 帧、takeover、附件、搜索与完整视觉状态。这样每个阶段都有真实的输入法内 Agent，而不是先造一个新的设置页工作台。
