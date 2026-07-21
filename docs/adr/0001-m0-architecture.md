# ADR-0001：M0 工程与热路径边界

- 状态：Accepted
- 日期：2026-07-21
- 阶段：M0

## 背景

Sense 必须先成为稳定的 Android 中文输入法，再叠加 Skill、Provider 和记忆。M0 需要尽早冻结 InputMethodService、UI、解码器及测试之间的依赖方向，同时避免在尚无真实性能数据时创建十多个空模块。

## 决策

1. `app` 只提供设置、启用与输入法切换入口。
2. `ime-service` 运行在 `:ime` 进程，持有 InputConnection 与会话状态。
3. `ime-ui` 使用单个自定义 Canvas View，避免每个按键一个 View。
4. `core-input` 是纯 Kotlin 模块，定义状态、Reducer、Decoder 和 EditorTransaction。
5. M0 使用 FakeDecoder 冻结接口；librime 在 M2 通过 `native-input` 接入。
6. `benchmark` 编译 Macrobenchmark，设备测试以非 debuggable benchmark build type 运行。
7. M0 APK 不声明网络权限；Provider 和 Brain 不进入本阶段。

## 结果

- IME 的普通输入闭环可以独立构建、测试和发布。
- Brain、事件存储和 native-input 可以在后续阶段以依赖倒置方式接入。
- FakeDecoder 只用于 M0/M1 骨架，不能演变成生产中文引擎。
- M0 的 host reducer 报告不替代真机启动、帧时间和 InputConnection 测试。

