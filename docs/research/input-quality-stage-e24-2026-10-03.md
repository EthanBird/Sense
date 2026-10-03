# E24：把快打事件源与输入法延迟分开

日期：2026-10-03。v0.4.16-rc.1 已按原正式证书发布，标签指向 `d4031cd`。
本阶段只修改独立输入测试宿主与测量工具，生产代码、词库、模型、Debug / Release APK 均保持。

## 本次解决的问题

E22 已证实 `UiAutomation` 的异步调用不等于按目标节奏发送：此前设置 32 ms，实际常超过 100 ms。
所以旧结果保留为系统输入与确认回归，而不是 32 ms 快打覆盖。
本阶段固定同一个 E23 Debug APK、三个已知短/中输入，按 A→B→B→A 更换**事件源**，不更换输入法版本：

- `nihao → 你好`
- `woxihuanbeijing → 我喜欢北京`
- `qingjianchayixiayoujian → 请检查一下邮件`

每段逐键输入后立即发 Space；外部普通 EditText 的 TextWatcher 记录每一个不同的原始拼音前缀及最终文本。
最终还检查 composing 已结束。注入返回 true 不被当作事件已收到的证据。

## 两个失败保留

1. 直接在 AndroidTest 应用中反射带目标 UID 的 `InputManager.injectInputEvent` 重载，设备返回 `NoSuchMethodException`。
   没有产生计时样本，没有更改系统隐藏 API 策略。
2. 将有界事件源放入独立 adb-shell `app_process` 后，首次调用用了环境变量赋值前缀。
   `UiAutomation.executeShellCommand` 直接执行参数而非交给交互 shell，因此宿主没有取得 helper 输出；仅前三段 A 产生数据。
   改用 `/system/bin/env CLASSPATH=... app_process ...` 显式启动。该次不完整数据保留，不并入完整对照。

## 独立的 shell 事件源

`tools/android-fixture/TouchBurst.java` 编译为单独的 DEX，经 adb 送入专用模拟器的 `/data/local/tmp`。
它既不进入 Sense APK，也不进入编辑器 APK；执行前后校验 SHA-256。
只接受 adb shell UID、合法应用目标 UID、有限坐标及最多 96 个触摸点。所有事件指定 Sense 的目标 UID，
通过 `InputManagerGlobal` 的 NONE 注入模式排队；没有直接调用 Sense 按键处理器或解码器。
事件实际发送起点与每次注入调用的开始/结束分别记录；启动 shell 进程的准备时间不混入逐键间隔。

API 语义依据 [AOSP InputManager](https://android.googlesource.com/platform/frameworks/base/+/4799aea23d3fb7f9d18926b67b889385ffdf8689/core/java/android/hardware/input/InputManager.java)：
NONE 模式是异步注入，因此仍须独立核对接收结果；本测试保留逐前缀与最终上屏双重检查。
这是 API 37 专用测试工具，不代表其他 Android 版本也支持相同内部接口。

## 完整一次校准的实测

12 段全部通过，每种事件源各 6 段、86 个字母、92 次按键（含 Space）、184 个 DOWN / UP 事件。
全部原始前缀均按序到达，最终文本和 composing 状态均正确。

| 指标 | UiAutomation 三参数调用 | 独立 shell InputManager |
|---|---:|---:|
| 实际按键起点间隔中位数 | 49 ms | 33 ms |
| 实际按键起点间隔 P95 | 58 ms | 35 ms |
| 实际按键起点间隔范围 | 32–60 ms | 32–44 ms |
| 单次注入调用中位数 | 18.152 ms | 0.710 ms |
| 单次注入调用 P95 | 27.145 ms | 3.692 ms |
| 起点到对应外部拼音前缀的中位数 | 33 ms | 20 ms |

这些结果说明本测试源已经能提供接近目标的真实发送节奏，并检查到所有前缀和最终提交。
外部 TextWatcher 时间不是屏幕绘制时间，也不是 Sense 内部触摸回调时刻；没有据此声明键盘帧延迟。

汇总文件同时保留六次 Space 到文本的观测（两种事件源中位数分别 141 / 119.5 ms）。
**这不是不同产品版本的性能比较**：样本仅三个已知短/中输入，顺序与预热状态可能影响结果，
也没有覆盖之前的长句。它不推翻 E21 的失败性能门槛、不替代真机结果、不声称产品已经提速。

本次重新启动既有专用 AVD，使用 `-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -port 5580`。
本次 UiAutomation 为 49 ms 而 E22 较慢，环境/预热状态不同；不把跨阶段差异归因成任何源码修复。

## 回归与交付边界

- 原按键方法只提取出坐标函数，默认注入语义保持。随后十项既有外部输入场景全部通过。
- Python 工具共 422 项执行，421 通过、一项既有 Windows 符号链接权限测试跳过。
  新增 11 项汇总回归检查缺段、重复事件、丢前缀、错误结果、无效时钟、未完成 composing 等。
- Debug APK 仍为 `7ce9874adf9f9b0a84408aa67c90727b8d98ab5f4e7f1433e1d4eded05ee16ed`。
- 已发布 Release APK 仍为 `48b24fded1430f7aa85dedffb93122607013d65e4015ba03945ef3998b413e23`。
- 未进行产品新构建或覆盖发布；模拟器默认输入法恢复 Gboard。

下一步使用已验证的事件源，预先固定长句、确认/续打与取消历史的可比执行条件，再测最新查询成本。
保留既有质量与学习预算，不以减少候选搜索换取好看的时间。
