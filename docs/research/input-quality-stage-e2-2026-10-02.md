# E2：纠错预筛选与完整搜索共享词边

## 范围

E1 让语言模型参与纠错拼写的预筛选，提高了漏键召回，但也增加了计算开销。本阶段只优化执行成本，保持词库、模型、权重、拼音图、搜索预算和个人词策略不变。它不增加准确率，也不把已知合成误触集重新算作盲测。

## 诊断与实现

按顺序检查三个假设：重复解码词库记录、重复过滤／排序词边、语言模型状态缓存不足。第一版只缓存原始词库记录，完整结果等价，但主机改善较小，且部分分组变慢；结果保留在 `benchmarks/results/e2-raw-cache-equivalence.json`。本轮实现并对照前两项，第三项未据此作出结论。

最终实现 `QueryLexicalCache`，在一次纠错搜索内共享基础词边的准备结果：

- 键为 **词库记录 ID + 音节数约束 + 前文字符**。相同字母记录在不同音节边界、前文条件下分别准备。
- 值仅包含基础词典候选经过音节校验、去重、词频／边界排序后的结果；**语言模型状态选择仍在每条路径上执行**，预筛选和完整搜索保留各自的候选预算。
- 带个人词证据的边和跨强制音节边界的边绕过缓存；个人学习、降权和上下文变化不会冻结在缓存里。
- 上限为 **128 个键、4,096 个候选引用**，按最近访问淘汰；空结果也占键预算，超预算的单个结果直接返回而不保留。这里不是堆峰值字节数承诺。
- 缓存只属于当前同步调用栈，不放到 decoder 字段中，不跨查询、线程、编辑框或会话保留。命中仍检查协作取消。
- 九宫格、未启用 LM 的旧路径及正常候选的排名公式保持不变。

这减少了重复 UTF-8 解码、对数词频计算、音节过滤与基础排序；没有缓存带个人权重的候选，也没有跳过错误拼写路径以换取速度。

## 完整结果对照

扩展 M14，加入 E1 已知开发／测试合成变体；生成旧实现结果后再修改生产代码。每遍包含：

| 分组 | 每遍观测数 | 覆盖 |
|---|---:|---|
| M8 日常输入 | 770 | 40 条输入逐键，包括未完成拼音与前文 |
| M12 已知句子 | 125 | 完整拼写 |
| E1 已知开发变体 | 945 | 正确、分隔、漏键、重复、邻键、换位、模糊音 |
| E1 已知测试变体 | 1,874 | 同上，来源与操作标签保留 |

两遍合计 **7,428/7,428** 完整 `ProgressivePinyinDecoding` 指纹相同，包括全部整句／首段候选、顺序、分数、拼写和来源，不仅比较第一项或前五项。比较器额外拒绝语料、资产、LM 权重、候选上限或学习配置不一致的对照。

第二遍主机 JVM 的单次查询中位数（仅诊断，不代替 Android）：

| 分组 | E1 ms | E2 ms |
|---|---:|---:|
| 日常逐键 | 5.445 | 4.970 |
| 已知句子 | 26.618 | 24.640 |
| 已知开发变体 | 16.981 | 15.166 |
| 已知测试变体 | 18.313 | 16.044 |

数据、源码与完整指纹见 `benchmarks/results/e2-progressive-equivalence.json` 及配套压缩原始报告。E1 的两条“毛线球跑／冒险球拍”退步仍在结果中，没有为优化报告删除。

## Android 与回归

Android 对照仍使用独立外部编辑器、真实触摸和系统 `InputConnection`，没有直接调用 decoder 代替上屏。顺序 E1→E2→E2→E1，各块清空专用 AVD 的 debug 配置、禁用学习、等待正式词库与模型就绪；8 个固定输入，每版每个 4 次，合计 **64/64** 输出一致。

| 输入 | E1 中位数 ms | E2 中位数 ms |
|---|---:|---:|
| 你好 | 94.5 | 93.5 |
| 我喜欢北京 | 203 | 197.5 |
| 请发个位置给我 | 147.5 | 124 |
| 请检查一下邮件 | 193.5 | 161 |
| 这个应用很好用 | 135 | 143.5 |
| 今天有一点类（保留既有错误） | 150 | 139.5 |
| 汤姆和玛丽每天晚上都看电视 | 243 | 204.5 |
| 汤姆没有注意到玛丽换了新发型 | 263.5 | 239.5 |

汇总确认中位数 **163 → 150 ms**，观测 P95 **295 → 278 ms**，最大值 **361 → 279 ms**。保留“这个应用很好用”中位数增加 8.5 ms 的记录，不称所有输入均提速。

这是同一 API 37、x86_64 模拟器和 debug 包的固定工作负载，样本包含注入、轮询与调度，不含首次资产加载。普通应用 trace 开启，未使用 ART 方法采样。它不代表实体手机总体分布或统计显著性；也不跨轮次把 E1 报告里的另一组 P95 直接相减。本轮测量期间未并行运行 Gradle/JVM 基准。

在 ABBA 前固定阶段规则和生产源码 hash：完整结果全等；64 次确认正确；新中位数下降、观测 P95 ≤旧版 1.1 倍、最大值 <1 秒。结果见 `e2-development-freeze.json`、`e2-system-latency.json`。

回归：core **279**、UI **229**、Service **282**、App About 定向 **3**，共 **793**；Python **40**，均通过。新增缓存契约覆盖复用、音节／前文隔离、两种容量限制、空条目、超大条目、加载异常及命中后的取消；真实生产资产测试还覆盖纠错词边在学习、降权和换上下文后与新 decoder 一致。

最终 APK 的普通系统输入 **10**、冷启动 **4**、新纠错 **3**、学习／隐私／进程重启 **2**，共 **19 次执行通过**。实际选择学习后的只读 SQLite 检查确认“程彻”使用 4 次、“智能体”使用 3 次，强偏好保留；隐私测试词“程澈”没有持久化。这不是直接往数据库填词的测试。

最终 E2 APK 的慢候选故障注入 **4/4** 通过：连续中文确认、显式回车保留原文、退格修正、切换编辑框后隔离旧请求。通过宿主 JDWP 仅暂停候选线程约 2.4～3.2 秒，没有生产延迟开关。结果见 `e2-slow-system.json`，不混入性能样本。普通系统与故障回归合计 **23 次测试执行**，不是 23 个独立场景，也不包含 ABBA 的 64 次确认。

阶段验收汇总在 `e2-acceptance-gate.json`；全部通过仅表示本次等价优化验收完成，不表示整个输入质量专项完成。

APK SHA-256：

- E1：`d42994ace9e62d290b91390d90a3e01c7e5e203ddfc07d098b1077dfcf980a56`
- E2：`f2696b5e961943956f1dacb6f9db3d787c01b1508bce0f94c0e1c18e957630e9`

## 产物与复现

APK 内模型、署名与许可审计通过；模型和词库未变化，评测文件未打包。压缩原始报告位于 `benchmarks/results/baselines/e2-{before,raw-cache,after}.json.gz`，SHA 和数量在 `e2-evidence-manifest.json`。系统原始触摸证据、截图、XML、日志与 trace 位于 `.artifacts/input-quality/` 的 `e2-*` 及 `external-editor-e2-*` 目录。

```powershell
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
$env:ANDROID_HOME = 'F:\Android\Sdk'
./gradlew.bat --offline --no-parallel --console=plain :core-input:m14ProgressiveEquivalenceBenchmark '-PprogressiveIncludeKnownTypos=true' '-PprogressiveEquivalenceReport=.artifacts/input-quality/fresh-e2.json'
python tools/compare_progressive_results.py benchmarks/results/baselines/e2-before.json.gz .artifacts/input-quality/fresh-e2.json .artifacts/input-quality/fresh-e2-comparison.json
./tools/test_external_editor.ps1 -RunName fresh-e2-correction -Correction
python tools/measure_input_latency.py .artifacts/input-quality/e2/e1-baseline.apk app/build/outputs/apk/debug/app-debug.apk .artifacts/input-quality/fresh-e2-latency
```

## 后续

拼音图召回、模糊音、自然误触数据、上下文歧义以及更广输入 UI 验收仍未完成。专项目标保持进行中；本阶段未进行 commit、push 或 release。
