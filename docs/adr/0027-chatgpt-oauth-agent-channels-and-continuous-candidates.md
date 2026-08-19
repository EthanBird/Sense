# ADR 0027：ChatGPT OAuth、Agent 信道平台与连续候选交互

- 状态：Accepted（v0.4.12）
- 日期：2026-08-19
- 范围：Android App / AI Runtime / IME UI
- 关联：ADR 0019、0020、0026

## 1. 背景

Sense 已把 Agent 放进输入法前端，并由独立 Brain 前台服务承接长任务；但 v0.4.11 仍有三条断裂：

1. Codex 订阅登录采用设备码流程。移动端在浏览器与设置页之间复制代码，状态反馈也不连续。
2. Agent 只能在设备上的输入法前端或 Agent Activity 中管理，缺少 Telegram、飞书等常驻用户信道。
3. 展开候选把连续候选集合切成离散页面，滑动手势只能跳整页，视觉位置与手指运动不连续。

本决策参考以下实现和协议：

- jcode `37272c9150c5759575acf16c892bb3458439dc7a` 的
  `crates/jcode-base/src/auth/oauth.rs`：Authorization Code + PKCE、localhost callback
  和浏览器登录生命周期；
- OpenAI Codex 当前 `codex-rs/login/src/server.rs`：localhost 1455、PKCE S256、
  authorize/token 参数形态；
- Hermes Agent `c8e558c72cedcfe2f614366de869df5c2ab10279` 的
  `gateway/platforms/base.py`、Telegram/Feishu platform plugins：窄平台接口、
  session source、停止命令、流式消息编辑和边缘能力插件化；
- Telegram Bot API `getUpdates` / `sendMessage` / `editMessageText`；
- 飞书开放平台 `im.message.receive_v1` 与官方 Java SDK WebSocket long connection。

## 2. 决策摘要

### 2.1 Codex 使用浏览器 Authorization Code + PKCE

登录状态机固定为：

```text
IDLE
  -> bind 127.0.0.1:1455
  -> generate verifier/challenge/state
  -> open browser
  -> accept callback
  -> validate state
  -> exchange code
  -> persist refreshable bundle in Keystore Vault
  -> close listener
```

约束：

- listener 必须先成功绑定，再向浏览器暴露 authorize URL；
- verifier 为 43–128 个 URL-safe 字符，challenge 使用 SHA-256 + base64url no-padding；
- success、error 与显式 cancel 都验证同一个随机 state；
- 错 state、畸形 HTTP 请求和单连接 read timeout 只结束该连接，不结束五分钟总会话；
- URL、code、verifier、state、access token、refresh token 不写日志、设置文件或异常文本；
- 登录页离开、设置页销毁、用户再次点击按钮均关闭 listener；
- refresh 采用同进程 mutex + 跨进程文件锁。锁内重新读取 token：若另一个进程已经完成
  轮换则复用新 bundle，否则只有一个刷新者调用 token endpoint 并原子写回。

这避免 `:ime`、`:brain` 与主进程同时使用旧 refresh token 造成轮换覆盖。

## 3. Agent 信道平台

### 3.1 两个正交维度

Hermes 的 platform 思路在 Sense 中拆成两个正交接口：

```text
User Channel                          Agent Control Target
Telegram ─┐                       ┌─ local Sense Hub (v0.4.12)
Feishu  ──┼─ durable inbox/router ┼─ remote Responses target (next)
future… ──┘                       └─ Hermes/AGY/MCP target (next)
```

- **Channel** 负责连接、标准化入站、发送、编辑和连接状态。
- **Target** 负责开始 run、观察结构化流、停止、状态和外部会话 ID。
- Router 负责配对、幂等、FIFO、run identity、session/target 选择和交付确认。

平台 SDK、Bot API JSON 和具体认证字段不得进入 Agent 核心。

### 3.2 v0.4.12 实际能力边界

v0.4.12 的可执行 target 只有 `sense`，即本机 `SenseAgentHubRuntime`。Telegram 和
飞书可从远端启动、观察、停止本机 Agent，因此也能触发本机已有的 terminal/browser/
memory/action tools。

本版尚未把 Hermes、Codex CLI、AGY 或任意 HTTP Agent 注册为可执行 target；
`AgentControlTargetRegistry` 是后续扩展 seam，不代表这些远端 Agent 已连接。两个信道
当前共享 Sense Hub 的当前会话；它们不是两个隔离的模型上下文。设置文案与命令输出必须
保持这个边界。

### 3.3 ChannelAdapter

```kotlin
interface AgentChannelAdapter : AutoCloseable {
    val type: AgentChannelType
    fun start(
        inbound: AgentChannelInboundListener,
        stateChanged: (AgentChannelConnectionState) -> Unit,
    )
    fun sendText(source: AgentChannelSource, text: String): CompletableFuture<String>
    fun editText(
        source: AgentChannelSource,
        remoteMessageId: String,
        text: String,
    ): CompletableFuture<Unit>
}
```

统一 source 至少携带：

- channel type / account id；
- chat id / peer id；
- platform message id；
- optional thread id；
- 可重复计算的 `eventKey` 与 `sessionKey`。

Adapter 不直接调用 Agent，也不自行决定 ACL。

### 3.4 Telegram

选择 Bot API 长轮询，而不是在 Android 设备上暴露 webhook：

- `getUpdates(timeout=25, limit=50)`；
- 只订阅 `message` 与 `edited_message`；
- 入站先持久化，结果为 ADMITTED 或 DUPLICATE 时才推进
  `offset = update_id + 1`；
- inbox 满返回 RETRY_LATER，不推进 offset；
- 网络错误使用 1–30 秒指数退避；
- 输出先 `sendMessage` 获得 message id，后续流式片段通过
  `editMessageText` 原位更新；
- close 先 fence，再 cancel 所有 OkHttp call 和执行器。

### 3.5 飞书 / Lark

选择官方 `com.larksuite.oapi:oapi-sdk:2.7.3`：

- `transport("websocket")`，无需公网回调地址；
- 中国站与国际站使用独立 API domain；
- 订阅 normalized `message` 事件，底层对应 `im.message.receive_v1`；
- outbound 使用 SDK send/edit message；
- 显式关闭 SDK 默认 chat batching，保持一个平台 message id 对应一个 normalized event 和
  一个 durable `eventKey`，不把相邻消息合并成一次 Agent prompt；
- SDK callback 先进入容量 32 的公平单通道队列，严格串行写 Journal。Journal 满时队头在
  callback 内等待，后续事件保持 FIFO；只有 ADMITTED/DUPLICATE 后 callback 才返回；
- 初次连接失败由 Sense scheduler 指数重建 channel；连接后的 WebSocket 恢复继续交给 SDK；
- close 只做非阻塞 fence/disconnect，不在 Service 主线程等待 future。

App ID 是普通配置；App Secret 与 Telegram Bot Token 只存在 Keystore Vault 及适配器
运行内存。仓库与 APK 内置 SDK 的 Apache-2.0 全文和 NOTICE。

### 3.6 配对与控制命令

每个启用信道生成六位配对码。未绑定状态只接受精确 `/pair CODE`；成功后同时绑定
peer id 和 chat id。配对码携带单调 generation，绑定操作在文件锁内 compare-and-bind；
重置与旧 callback 并发时，旧 code/generation 不会覆盖新绑定状态。重置会生成新码并立即
reload 后台 coordinator。

内置命令：

| 命令 | 行为 |
|---|---|
| `/status` | 当前 Hub 状态、工具数、排队数 |
| `/stop` | 中断正在运行的本机 Agent；可越过普通 FIFO |
| `/new` | 当前 Hub 空闲时归档并开始新会话 |
| `/help` | 显示命令 |
| `/agents` | 显示实际注册的 target |
| `/agent use sense` | 选择本机 target |

普通 prompt 保持严格 FIFO；只有 `/stop` 可以中断活动 run。

## 4. 崩溃恢复与投递语义

### 4.1 Durable inbox

`AgentChannelJournal` 使用 AtomicFile + 进程内 mutex + OS 文件锁保存：

- Telegram update offset；
- 至多 32 条 pending normalized events；
- 最近 512 个完成 event key；
- active event key；
- exact Sense request id / generation / user timestamp；
- active remote draft message id；
- 最终回复各分块已经确认的 remote message id；
- session 到 target 的选择。

满队列采取背压，旧事件不被 `takeLast` 覆盖。Telegram 保持 durable offset 不前进；
飞书通过单一有界 admission lane 保持 callback 未完成并反复尝试队头的 AtomicFile 写入。
平台重试或进程恢复时，相同 event key 落到 DUPLICATE，不会重复启动 run。

### 4.2 Run identity

远程 prompt 使用两阶段启动：`prepareRun()` 先生成 exact request id、generation 和 user
message timestamp，Journal 在任何 Hub side effect 前写入 `PREPARED`；随后
`sendPrepared()` 只使用这组 identity 启动，成功后把相位推进为 `ACTIVE`。

恢复时只附着同时匹配三项的 run；`PREPARED` 且 Hub 没有运行证据时复用同一 identity
继续一次，`ACTIVE` 且 Hub 记录缺失时明确完成而不重放 prompt。assistant 结果只在匹配
USER 到下一条 USER 之间查找，同毫秒冲突取最后追加的 USER 段。没有 identity 的旧 journal
事件返回明确状态，而不是把另一个前端的最后一条 assistant 消息误发到信道。

### 4.3 Outbound completion

- 控制回复与 Agent 最终回复只有在 platform future 成功并返回非空 remote message id 后
  才 `markDone`；
- 失败使用 1–30 秒有界指数退避，pending event 保留；
- 流式预览采用 latest-wins 单泵：同一时刻最多一个 edit in flight，新文本只替换 buffer，
  旧 edit 失败不会覆盖更新的 B/C 片段；
- remote draft id 写入 Journal；进程恢复后继续编辑同一条远端消息；
- 每次 send/edit 成功都更新本次 delivery 的 `lastAppliedText`；最终文本相同就直接复用
  remote id 并完成 Journal，避免 Telegram 的 `message is not modified` 被当成永久失败；
- Telegram 最终回复按不超过 3980 个 UTF-16 unit 的 surrogate-safe 分块投递；Journal v5
  逐块保存 remote id，重启从首个未确认分块继续，不截断长回答，也不重发已落盘的分块；
- 最终 edit/send 全部成功后再完成事件。恢复转 ACTIVE 会保留已持久化的 remote draft id
  与最终分块进度，连续崩溃不会退化成从头重复发送。

平台 send 成功与本地 Journal 确认之间仍存在不可消除的窄窗口（平台 API 没有客户端幂等
key）；这个窗口中控制回复或当前分块可能重复一次，但不会丢失命令或长回答尾部，整体语义
为 at-least-once。

## 5. Android 生命周期

`SenseAgentChannelService`：

- 运行在 `:brain` 进程，与 `AgentHubActivity` 共享同一个 `SenseAgentHubRuntime`，避免信道连接到第三份 process-local Hub；
- Settings 仍在 App 主进程，通过包内显式状态广播接收紧凑的 phase、连接数和队列状态；广播不携带凭据或消息正文；
- IME 前端位于既有 `:ime` 进程，通过私有 `SenseAgentHubBridgeService` 和带 command ack 的
  `RemoteSenseAgentHubClient` 控制 `:brain` 中的唯一 Hub；信道、Agent Activity 与 Brain
  executor 都留在 `:brain`；
- 首个 IPC projection 采用小于 256 KiB 的有界 wire：携带最近 28 条消息、12 个 tools 和
  16 个 conversations，超预算展示字段带“…”标记；随后按 revision/cursor 自动分页回填，
  每页最多 4 条完整消息或 12 个会话摘要。最终投影覆盖 Hub 当前保留上限内的 80 条消息、
  40 个归档与当前会话；这不会把 durable store 的有界保留策略描述成无限历史；
- 所有 Binder transaction 都从专用 writer 线程以 `FLAG_ONEWAY` 发出，IME 主线程只排队和
  消费回调。每条命令携带 `clientCommandId` 并异步 ACK；两阶段 prompt 的 ACK 同时核对
  prepared request id / generation，`:brain` 的有界 ACK ledger 支持重连幂等重放。每个
  client 最多一个 projection in flight，ACK 前更新 latest-wins；
- Binder death 自动重绑，客户端以 connection generation + projection revision 丢弃旧连接、
  旧 revision 的回调，避免重连后状态倒退；
- 使用 `specialUse` foreground service 和常驻低优先级通知；
- `START_STICKY` 恢复被系统回收的服务；
- 通知提供“暂停信道”，把 paused 状态与递增 config revision 原子持久化；该操作与
  `/stop` Agent run 分开，并在设置页提供显式“恢复信道”；
- 状态查询只发送包内广播，不会为了打开设置页而创建前台服务；
- 显式启用且未暂停时，BOOT_COMPLETED receiver 才恢复服务；
- 设置页保存/重置通过 Intent reload coordinator；配对产生的新 config revision 会触发
  设置页刷新，revision 在初次慢读取期间到达时会再执行一次读取；
- 关闭设置页不关闭信道；关闭通知动作会关闭 adapters 并释放连接。

认证失败进入终态 ERROR，不被随后的 STOPPED 覆盖；单信道重连显示 STARTING，部分信道
可用显示 DEGRADED，只有全部启用信道均连接后才显示 RUNNING。poll/connect 或任意
send/edit future 返回致命认证错误时，该 adapter 会立即退出可投递集合并关闭；其 active /
control blocker 写入 failure ledger，其他信道继续推进。

保存 disable/pause 后，旧 coordinator 对每条入站都会先在设置文件锁下重读快照；已暂停或
对应 endpoint 已禁用时不会再启动 Agent。reload 后若全局 FIFO 头或 active event 属于当前
配置中不存在的 adapter，Journal v5 会写入有界 terminal failure ledger，再移出 active/FIFO，
避免一个已禁用信道永久阻塞仍启用信道。平台重试仍由 recent event key 幂等吸收。

服务恢复并不绕过 Android 的系统省电调度；连接状态与错误会显示在设置页，用户可重新加载。

## 6. 连续候选

展开候选改用单一 content-space grid：

```text
screen y + expandedScrollOffset -> content y -> binary search first row
```

决策：

- 去掉 page count、previous/next page control 和离散 page action；
- 拖动、velocity、fling、clamp 复用已有 `PanelScrollController`；
- 布局时建立 primitive-width slots；帧渲染只二分并遍历 viewport 内候选；
- Canvas clip 后 translate content offset；
- 命中映射回 content coordinate，返回稳定全局 source index；
- 候选 revision 改变即取消冻结 pointer、VelocityTracker 与 CANDIDATES OverScroller；
- viewport `RectF` 使用单个 reusable projection，惯性热帧不创建 Rect；
- 折叠候选条继续水平连续滚动，两个状态互不复用 offset。

## 7. 远程控制其他 Agent 的下一阶段

要把本机 Sense 变成“多 Agent 遥控器”，下一阶段不应在 Telegram/飞书 adapter 里硬编码
Provider，而应实现真正的 `AgentControlTarget`：

```kotlin
interface AgentControlTarget {
    val id: String
    val displayName: String
    fun start(session: TargetSessionRef, prompt: String): TargetRunRef
    fun observe(run: TargetRunRef, sink: (TargetEvent) -> Unit): AutoCloseable
    fun stop(run: TargetRunRef): Result<Unit>
    fun status(run: TargetRunRef?): TargetStatus
}
```

`TargetSessionRef` 必须持久化外部 conversation/thread id，而不是轮询转录文本。优先适配：

1. OpenAI Responses/Codex 服务端会话：SSE 或 NDJSON 流、显式 response id；
2. Hermes gateway：复用 platform/plugin session source 和 stop/status 语义；
3. AGY/Codex CLI bridge：持久化 provider conversation id，使用 `stream-json`；
4. MCP Agent endpoint：能力发现、typed tool events、取消 token。

新 target 的 manifest 只声明：

- target id / display name；
- endpoint 与协议版本；
- credential handle（Vault 中的句柄，不是 secret）；
- 支持的事件、停止与会话能力；
- 最大并发与退避策略。

Router 再把 `channel session key -> target id + external conversation id` 保存为一等状态。
这样新增 Slack、Teams、Discord 等用户信道时不复制 Agent 逻辑；新增远端 Agent 时也不修改
Telegram/飞书实现。

## 8. 验证

发布门禁覆盖：

- RFC 7636 challenge、authorize query、callback state、cancel、慢连接与端口释放；
- 两个 store/rotating refresh client 的并发 single-flight；
- 命令解析、ACL、stream gate、FIFO/interrupt、retry 与 run identity；
- Journal v1/v2/v3/v4 -> v5、PREPARED/ACTIVE、满队列背压、remote draft/final chunk id、terminal failure ledger 与连续重启恢复；
- Telegram durable offset 顺序、未连接 future、相同 final edit 幂等响应、Unicode 分块重组与分块崩溃恢复；
- 飞书关闭 SDK batching、并发 callback FIFO admission、connect retry 与非阻塞 close；
- 持久暂停/显式恢复、非启动式状态查询、配置 revision 慢加载竞态和认证错误聚合；
- 候选连续布局、全局索引命中、revision fence、510 项可见裁剪和 Rect 复用；
- `:ai-runtime:testDebugUnitTest`、`:ime-ui:testDebugUnitTest`、相关 Lint、
  App test/assemble，以及正式签名 APK 门禁。

## 9. 参考实现与协议

- [jcode OAuth（固定研究版本）](https://github.com/1jehuang/jcode/blob/37272c9150c5759575acf16c892bb3458439dc7a/crates/jcode-base/src/auth/oauth.rs)
- [OpenAI Codex localhost OAuth server](https://github.com/openai/codex/blob/main/codex-rs/login/src/server.rs)
- [Hermes Agent（固定研究版本）](https://github.com/NousResearch/hermes-agent/tree/c8e558c72cedcfe2f614366de869df5c2ab10279)
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [飞书消息 API 开发介绍](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message-development-tutorial/introduction)
- [飞书 WebSocket / MCP 智能助手示例](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/mcp_integration/develop-mcp-intelligent-assistant-bot)
