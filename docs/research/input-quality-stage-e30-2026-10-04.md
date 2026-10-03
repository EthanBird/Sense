# E30：冷装载确认覆盖，以及提示前回车/退格的响应

## 结论

补齐了“装载尚未发布、用户已开始确认”的确定性外部编辑器路径，并实际发现一个交互缺陷：
刚按 Space 后、慢候选提示出现之前，立即按 Enter 或 Backspace 会被放入等待队列，
留在组合态直到装载结束。Enter 的字面文本虽仍可见，但当时并未确认；退格也没有即时修改。

本轮修复这两个操作。已排队的后续词仍保持 FIFO，不将后一个词的 Enter 错施加到前一个词。
这不是语言模型或候选评分改动。已发布 rc.2 的 APK/标签保持原样，本修复先在开发分支交付。

## 先修测试边界，而非降低断言

E27 的自然冷启动用例中，3 项在按 Space 前就观察到 `ready=true generation=2`，
失败的是覆盖条件，而非文本正确性。原断言保留；不靠重复运行挑一次较慢的装载。

新增宿主 `tools/run_cold_loader.py` 与 `tools/android-fixture/ColdLoaderDebugger.java`：

1. 仅支持名称为 `sense-input-quality` 的专用 AVD，操作 Sense **debug** 包。
2. force-stop 并等待广播空闲；使用 Android 的临时等待调试器配置，在 IME 进程启动时附加。
3. 经 JDK JDI 的 class-prepare 安装一次性 breakpoint，定位实际编译方法
   `SenseInputMethodService.loadProductionDecoderAsync$lambda$0`，精确签名与非同步方法身份均检查。
4. 停在该方法 codeIndex=0：资产、模型与 SQLite 操作之前；调用栈中可见 `BackgroundRuntimePublisher:19`。
5. breakpoint 使用 `SUSPEND_EVENT_THREAD`，实际命中的线程名为 `sense-decoder-loader`。
   同时检查 owned Java monitors 为 0、main 未被挂起。不是随意暂停正在读库的线程，也不暂停整个应用来制造“键盘卡住”。
6. 独立编辑器实际发触摸键，校验 `ready=false generation=1 characterModel=null` 和 composing 状态，
   测试主动发恢复标记后，释放断点，继续验证生产 runtime 发布后的最终文本。
7. 截止时间、debugger Dispose、adb forward 清理、IME 与硬键盘设置恢复均由宿主负责。

工具位于宿主目录，未加入应用依赖；APK 中不存在调试桥或延迟开关。
这属于**装载延迟故障注入**，不是普通启动时间、手机延迟或候选吞吐评测。

协议依据：[JDI EventRequest 挂起策略](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.jdi/com/sun/jdi/request/EventRequest.html)、
[JDI Method 定位](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.jdi/com/sun/jdi/Method.html)、
[JDWP class-prepare / breakpoint 事件](https://docs.oracle.com/en/java/javase/17/docs/specs/jdwp/jdwp-protocol.html)。

## 复现和根因

按用户可见路径区分：测试错过加载窗口、发布时冲掉确认、旧输入跨编辑器三个方向。
确定性断点先排除了覆盖窗口的不确定性；完整 baseline 结果如下：

| 真实触摸场景 | 基线 | 修复后 |
|---|---|---|
| Space 后继续输入下一词并确认，跨过 5 秒提示期限再恢复 | 通过 | 通过 |
| Space 后立即 Enter，装载仍停止时确认原文 | 失败：仍 composing | 通过 |
| Space 后立即退格，修正当前拼音再确认 | 失败：尾字母仍在 | 通过 |
| 待确认期间切换编辑器，在新编辑器继续输入 | 通过 | 通过 |

基线和修复使用同一个测试 APK、同一个宿主 helper。基线是 rc.2 同生产输入源码的 Debug 包，
不是声称运行了线上正式签名 APK。最初单项探针失败也保留，不算进最终 4 项通过数。

根因在 `handlePendingPinyinControl`：原代码只有 `needsAttention` 或队列满时才处理恢复控制。
冷装载的提示期限是 5 秒；期限前发出的控制键已经排进 FIFO，之后提示变更也不会自动重放。
主机最小回归在应用修改前同样复现：13 项中 Enter / Delete 两项失败；其余通过。

## 修复边界

- 对应当前 revision，且没有任何后续排队输入时，Enter / Delete 即时作用于当前可见拼音；
  “等待较慢”提示只影响展示，不再决定这两种编辑动作是否可用。
- 已有下一词或触发字符的队列，在提示前保持原 FIFO；新增主机测试验证
  `woxihuanbeijing + Space + nihao + Enter` 最终为 `我喜欢北京nihao`，而不是把首词也提交成拼音。
- 原慢提示后、队列满时的恢复交互保持；旧 revision 结果继续被拒绝。
- 没有增加 UI 线程 I/O、模型参数、词库条目或学习规则。

## 验证和证据

- 最终核心 / 服务 / UI：391 + 317 + 235 = **943 项通过**；未变化的 Gradle 任务可能 up-to-date。
- Python：**439 执行、438 通过、1 项 Windows 符号链接权限跳过**。
- 新冷装载 4 项、原慢候选线程 4 项、普通输入 10 项、学习 6 项、静态上下文 8 项，
  合计 **32 项专用模拟器场景**，最终结果由 `e30-acceptance-gate.json` 和原始仪器日志约束。
- 暂停期间截图已查看：组合原文保留、等待提示与暂存键数可见、键盘布局保持；最终文本由真实 EditText/composing 断言证明。
- 最终 Debug APK SHA-256：`36df42836be3177a26f40ba662f3dfc13e3cb8182e8c5a971cc5727b11173d1a`。
- 原始 baseline、修复、失败探针、host XML、截图、命令日志、源码快照与工具均封存到
  `benchmarks/results/e30-evidence-manifest.json` 指向的压缩档案。

执行命令（工作区根目录；先配置本机 Android/JDK 环境）：

```powershell
./gradlew.bat --offline --no-parallel --max-workers=2 :app:assembleDebug :input-quality-device:assembleDebug :input-quality-device:assembleDebugAndroidTest
python tools/run_cold_loader.py .artifacts/input-quality/cold-loader-new-run
```

输出目录应全新，每个场景由独立冷 IME 进程执行。自动生成的观察时长只证明等待发生，不作为性能成绩。
整体输入质量目标仍未完成：自然同音句和姓名、跨音节纠错、个人词大规模 Android 成本及更广系统范围继续推进。
