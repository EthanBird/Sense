# E8：26 键全拼的显式分词与可逆组合

## 本轮交付

默认场景继续固定为 **26 键全拼**。本轮修复手动音节边界从键盘到真实编辑框的完整路径，
不更换词库、模型或评分参数，也不以一个词的改善代表总体准确率提升。

- 中文全拼正在组合时，左下角 Shift 原位置显示 **分词**，点击插入一个可编辑的 `'`。
- `xi'an` 保留为 `xi + an`，空格确认得到“西安”，而不是在渐进查询中重新合并为 `xian`。
- 分隔符属于编辑框的 composing span；回车提交原始拼写，退格先删除分隔符或字母。
- 选择前段“西”后显示 `西an`；删除尾部再撤回前段，恢复原始 `xi'`，不丢用户输入的边界。
- 连续按“分词”不重复插入；沿用已有 96 个原始字符的组合预算。
- 空闲、英文、九宫格、五笔保持旧按键布局；输入结束或进入联想栏时恢复 Shift。

这是本地开发阶段。最终 APK 使用 Android Debug 证书；没有正式签名、commit、push 或 release。
整体输入质量目标继续进行。

## 缺陷证据与修复位置

| 层 | 原因 | 修复 |
|---|---|---|
| 组合状态 | `PinyinComposition.type` 只接收字母 | 将显式分隔符保留为可撤回原始字符；拒绝开头、连续分隔 |
| 服务事件 | 全拼的非字母分支直接提交标点 | 正在组合时让 `'` 进入拼音状态；空闲标点路径保持 |
| 渐进查询 | 归一化后把用户边界一起交给基础解码器 | 分开保存原始编辑串、带边界的解码串、纯字母词典索引串 |
| 音节切分 | 完整切分与前缀列表忽略显式边界 | 动态规划禁止单个音节跨越指定边界，不把它误当词条分界 |
| 分段选字 | 纯字母偏移与原始串偏移混用 | 含分隔符时构建有界偏移映射；已消费前段拥有其后的分隔符 |
| 双语候选 | 英文补全可覆盖显式中文分词意图 | 直接、上下文、渐进入口统一处理；无分隔符输入维持原排序 |
| 候选解释 | 比较原始串与纯字母解释串 | 比较归一化值，尾部分隔符只显示一次；推断边界仍不写进编辑框 |
| 键盘 UI | 没有直接手动分词入口 | 借用 Shift 的既有面积与位置，组合状态仅空/非空切换时重建键盘 |

生产修改位于 `core-input`、`ime-service`、`ime-ui`。新测试入口为
`PinyinExplicitBoundaryTest`、`PinyinSeparatorServiceTest`、`ExternalEditorBoundaryTest`。

## 验证与证据范围

### 先复现

核心初始 7 项测试失败 6 项，服务 1 项测试失败 1 项；之后补充的英文入口一致性测试也先失败。
UI 合约最初因为待实现参数尚不存在而编译失败，这条记录不是运行期断言失败。

专用 API 37 x86_64 AVD 上，将同一个新测试 APK 对着 E7 旧 APK 运行：**6 项中失败 5 项**。
实际观察到 `xi'` 变成 `xi`、`xi'an` 回车变成 `xian`、撤回前段时分隔符丢失。
切换编辑框场景在旧包本来就通过，保留为防回归。

### 修复后的检查

- 主机：core 316、UI 235、service 299、About 3，共 **853 项通过**。
- Python：339 项运行，338 通过、1 项因 Windows 符号链接权限跳过。
- 键盘 View：**15 项真实 Android 测试通过**，包括新分词键的等尺寸、按状态切换、候选刷新不重建、其他输入方案隔离。
- 外部编辑框最终包：边界 6、普通输入 10、纠错 8、联想 9、冷加载 4、学习/重启 2。
  每组的完成状态及 APK 哈希由 `benchmarks/results/e8-acceptance-gate.json` 汇总校验。
- 学习回归继续检查“程彻”“智能体”的再次输入、再次接受、进程重启与私密输入隔离；没有通过直接写数据库制造学习结果。

这些是自动化测试与专用模拟器证据，不是实体手机认证或自然用户评测。

### 普通输入没有顺带改变排序

从 E7 封存的 60 个核心源码文件校验 SHA-256 后还原旧引擎，使用独立构建目录运行同一 M14 重放。
唯一的基线测试工具修改是让其源码指纹读取还原目录；查询、候选上限、模型、评分均相同。

**895 个组合状态各跑两遍，共 1,790 个完整结果指纹全部相同。**
比较包含整词与前缀候选的顺序、评分、来源、拼写和 revision，并非仅比较前几名。
这是已知日常/句子回归，不是新增盲测；两遍也不是 1,790 个独立语言样本。
既有 M15 的 `joints` 输入会去掉撇号，所以本轮不借它证明前端分词；使用实际保留 `'` 的新回归。

### 成本与边界

- 全部 APK 内 assets 与 E7 逐字节相同；词库、LM、联想模型及许可证均未改变。
- 普通小写全拼不分配原始偏移表；含显式分隔符时表长受既有 96 字符预算约束。
- UI 不新增一行，不挤占候选宽度；组合期间每个新字母/候选刷新不重建字母键场景。
- 本轮没有重新测 Android ABBA 延迟分布，不把主机重放时间当作手机延迟结论。
- 新入口针对软件 26 键键盘；本轮没有新增物理键盘处理或完善全键盘读屏导航。

## 构建产物

最终开发 APK：`app/build/outputs/apk/debug/app-debug.apk`

- 大小：85,826,196 字节。
- SHA-256：`ec0662b46821a04646a146de93d2fbffc14ec5624b690ac80ff48ba94d09d162`。
- Android Debug 证书，v2 校验通过；不是历史正式证书产物。
- 已使用 Gradle `:app:packageDebug --rerun` 重新打包，避免增量 ZIP 空洞。

## 重放

```powershell
$env:JAVA_HOME = 'F:/Android/Jdk/jdk-17'
$env:ANDROID_HOME = 'F:/Android/Sdk'
./gradlew.bat --offline --no-parallel --console=plain :core-input:test :ime-ui:testDebugUnitTest :ime-service:testDebugUnitTest
./gradlew.bat --offline --no-parallel --console=plain :app:assembleDebug :input-quality-device:assembleDebug :input-quality-device:assembleDebugAndroidTest
./tools/test_external_editor.ps1 -SkipBuild -Boundary -RunName NEW_UNIQUE_RUN
```

最终门槛：`benchmarks/results/e8-acceptance-gate.json`；普通输入等价：`e8-progressive-equivalence.json`；
原始日志、截图、XML、源码快照：`e8-evidence-manifest.json` 对应的 gzip 归档。
旧阶段封存资料保持原样。下一步继续处理 LM 简拼召回缺口和上下文选词质量，而不是把分词按钮视为目标完成。
