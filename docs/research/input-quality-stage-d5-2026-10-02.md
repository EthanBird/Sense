# D5：保持完整候选不变，降低词格搜索的实际开销

## 结果

这轮改进的是搜索执行成本，没有降低 beam、候选上限、纠错路径预算，也没有改词库、LM 权重或排序公式。

- **1,790 次完整结果比较全部一致**：包括全部整句候选、分段候选、顺序、分数及来源，不只比较首选。
- 专用 API 37 x86_64 模拟器，8 组输入、每版 32 次，按 A→B→B→A 顺序比较。空格到外部编辑框完成提交的中位数 **165.5 → 139.5 ms**。
- 两个长句的中位数分别 **252.5 → 208 ms**、**286.5 → 227 ms**。“你好”则 **73 → 78.5 ms**，没有把它隐藏或宣称每类输入都提速。
- 正常输入、冷启动、个人词学习与进程重启回归通过；本阶段仍是开发 APK，没有发布 release。

上述数字描述一个固定诊断工作负载，不代表真机总体输入延迟，也不证明统计显著性。

## 1. 从真实热点开始

先用 Windows JFR 重放既有 125 条 P2C 查询，再在真实系统输入法进程中执行 ART 方法追踪。

JFR 的解码相关样本中，码表字符串比较、纠错词格预估、词格展开和反复计算 Unicode 字数占据明显位置。Android 侧更突出的是词格排序：浮点装箱、排序比较器、路径字段访问反复出现。两个环境给出互补线索，没有把 JVM 采样比例直接视为 Android 占比。

Android 追踪请求 `--sampling 1000 --clock-type dual --profiler-output-version 3`，诊断解析器按 [AOSP ART 记录格式](https://android.googlesource.com/platform/art/+/fd2ccc4d4ca05f6b9a97b7a70a3aa0493206ad13/runtime/trace.cc)读取双时钟记录，检查溢出、记录长度和调用栈平衡。测试另以合成记录核验独占/包含 CPU 区间及损坏输入处理。

**这份 ART 追踪明显扰动了运行**：一次输入耗时越过现有确认等待上限，实际提交原始拼音，系统测试为红。保留失败记录，不用这个被扰动的数值衡量速度。它暴露的极端超时交互仍是待解决项。

正式 A/B 测量关闭 ART 方法追踪，只保留已有的低开销固定名称 `atrace` 区段；64 次输入全部提交与基线相同的完整文本。

## 2. 实现

### 相同 Top-K，不排序所有候选

每个 LM 上下文原先先排序整条词边的候选，再取前若干项。现在仍计算同样的全部候选分数，用固定大小的整数索引堆选择**完全相同**的 Top-K。

- 比较键仍是分数降序、文本字典序。
- 完全相同的键以输入索引打破平局，保留旧稳定排序的先后关系。
- 同时覆盖正负零、NaN、无穷值、完全相同键、顺序和逆序的参考排序测试；实际词库分数仍为有限值。
- 堆不持有新的编辑器状态，不引入跨查询的可变缓存。

`StableTopKTest` 用 200 组固定随机列表、7 种 k 和额外顺序案例逐项对照完整稳定排序，而非只检查“看起来已经排序”。

### 移走排序和展开中的重复工作

- 路径的 `score + languageScore + searchContextScore` 在创建时计算一次。
- 路径与语言候选排序使用原生 Float/Int 比较，减少 ART 上每次比较的装箱；加法顺序不变。
- 首尾 Unicode 码点、单字判定属于词边，在构造边时计算一次，不随到达该边的每条路径重复扫描字符串。
- 二分查词每一步只调用一次 `compareCode`，不在两个分支里重复比较。
- 纠错的词频调度预估跳过所有状态都不可达的位置。原本这些位置也不产生路径，现在不再为它们遍历和查询所有子串。

这些改动不改变接受的拼写、选择的路径预算、上下文计分或最后导出的候选域。

## 3. 完整输出等价，不用速度掩盖召回退步

新增 M14：正式 LM 0.5、255 候选、空个人词表，不学习。每轮执行：

- M8 日常 40 条的每个按键，共 770 次，包括未完成音节。
- 已知 M12 的 125 条完整拼音。
- 再重复一轮，共 `(770 + 125) × 2 = 1,790` 次。

对整个 `ProgressivePinyinDecoding` 数据结构的稳定文本序列化取 SHA-256。它包含完整候选和分段候选、Float 的可往返表示、canonical 拼写、match kind 和修订信息。比较时仅排除时间，不排除候选元数据。

结果：**1,790 / 1,790 相同，零差异**。原始观察已压缩保留于：

- `benchmarks/results/baselines/d5-m14-before.json.gz`
- `benchmarks/results/baselines/d5-m14-after.json.gz`

汇总见 `benchmarks/results/d5-progressive-equivalence.json`。其中的 Windows JVM 耗时仅用于诊断，不当作安卓触摸或帧延迟。

这个语料现在是已知回归集，不是新的独立盲测。相同输出也保留原有错误，例如 `jintianyouyidianlei` 首选仍为“今天有一点类”，目标“累”排第二。性能工作没有修好这个语义错误。

## 4. Android 系统输入对照

独立外部编辑器通过真实 26 键触摸逐字输入、按空格，再检查编辑框文本与 composing span。未直接调用 decoder、service 或候选监听器。

每个 APK 测试块清空专用 AVD 的 `.debug` 配置，编辑框设置 `IME_FLAG_NO_PERSONALIZED_LEARNING`，先等待正式词库和模型加载完成。每块先正序输入 8 条，再逆序重复，避免长句总位于同一热身阶段。四块顺序为旧版、新版、新版、旧版。

| 输入 | 旧版中位数 ms | 新版中位数 ms |
|---|---:|---:|
| 你好 | 73 | 78.5 |
| 我喜欢北京 | 200 | 183 |
| 请发个位置给我 | 140.5 | 111 |
| 请检查一下邮件 | 185.5 | 158.5 |
| 这个应用很好用 | 142.5 | 132 |
| 今天有一点类（已知错误基线） | 147.5 | 137.5 |
| 汤姆和玛丽每天晚上都看电视 | 252.5 | 208 |
| 汤姆没有注意到玛丽换了新发型 | 286.5 | 227 |

每行每版只有 4 个样本。汇总 32 个样本的观测 P95 为 **316 → 251 ms**，最大值 **398 → 291 ms**；这些是本工作负载的经验分位数，不是总体尾延迟保证。

空格到上屏包含注入、系统调度和轮询成本；不含初次资产加载。实际按键起始间隔记录在 cadence 文件，传入 `12 ms` 的间隔并不等于包含注入开销后的真实按键间隔。既有 trace 的完整解码区段还包含打字期间已完成的短前缀，不与“最终确认耗时”混为一谈。

原始所有样本和每个输入的变化见 `benchmarks/results/d5-system-latency.json`；日志、设备截图及 trace 保留在 `.artifacts/input-quality/d5-latency-abba/`。

APK SHA-256：

- A / D4：`801cca9f0ce9e2ed9c88e02bde25a1edc12050ab12ffa2adcce75699e09666bc`
- B / D5：`2e9e15d8c174fc28c2647d57124c11e06534aa050b37518b405e884fab11b5a6`

## 5. 回归与复现

主机：core **269**、UI **229**、service **271**、App About 定向 **3**，合计 **772**；Python **32**。其中生产资产工厂的 125 条已知 P2C 对照、D4 的重复接受和负反馈边界继续通过。App 数字不代表所有 Agent 和远程信道测试。

优化 APK 再执行：正式模型就绪系统回归 **10/10**、冷启动 **4/4**、干净配置的学习/隐私/进程重启组合 **2/2**。这 16 次独立验收不与 A/B 的 64 次句子确认混作一种测试数量。语言模型、署名与 APK 内容审计也通过，见 `d5-apk-asset-audit.json`、`d5-host-validation.json` 和 `d5-system-ime.json`。

```powershell
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
$env:ANDROID_HOME = 'F:\Android\Sdk'
./gradlew.bat :core-input:m14ProgressiveEquivalenceBenchmark '-PprogressiveEquivalenceReport=.artifacts/input-quality/fresh-m14.json'
python tools/compare_progressive_results.py benchmarks/results/baselines/d5-m14-before.json.gz .artifacts/input-quality/fresh-m14.json .artifacts/input-quality/fresh-equivalence.json
python tools/measure_input_latency.py BASELINE.apk app/build/outputs/apk/debug/app-debug.apk .artifacts/input-quality/fresh-abba
python tools/summarize_input_latency.py .artifacts/input-quality/fresh-abba/measurements.json .artifacts/input-quality/fresh-latency.json
```

A/B 前先构建并安装 `input-quality-device` 的应用及 AndroidTest APK；runner 自己切换被测 Sense APK，并只在验证名称的专用 AVD 上清空 debug 配置。证据目录使用新名称。普通用户配置不参与测量。

## 接下来

1. 候选质量：继续处理“累/类”等常见上下文错误、切分与纠错，增加独立评价数据，不在已知评测答案上硬加权。
2. 仍有 200～300 ms 的长句确认样本；继续优化，不以本轮改善宣称已达到主流输入法水平。
3. 超时原文提交仍会在强扰动/极慢解码下出现，需要独立完善确认等待与取消交互。
4. 大字体、横屏、更多输入控件和系统版本，以及真实逐键帧延迟，继续验收。

目标继续进行。
