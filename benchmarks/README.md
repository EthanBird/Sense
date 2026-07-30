# Sense Benchmarks

Sense provides host and device baselines:

- `./gradlew :core-input:m0HostBenchmark` writes the deterministic host reducer report to `benchmarks/results/m0-host.json`.
- `./gradlew :core-input:m1PinyinBenchmark` loads the production lexicon, verifies representative Chinese candidates and writes lookup timing to `benchmarks/results/m1-pinyin.json`.
- `./gradlew :core-input:m2AdaptiveBenchmark` verifies `w → 我`, exact/prefix isolation, candidate syllable boundaries, short and long-sentence one-edit correction, one-shot initials learning, and 10k-user-entry lookup performance in `benchmarks/results/m2-adaptive.json`.
- `./gradlew :core-input:m3SentenceBenchmark` loads the production lexicon, `pinyin_bigrams.bin`, and the 120-case `benchmarks/replay/m3-sentences.tsv`; it reports Top-1/3/10, MRR, coverage, mode/frequency buckets, and p50/p95/p99 latency at candidate limits 10/64/255 in `benchmarks/results/m3-sentence.json`.
- `./gradlew :core-input:m4CoreBenchmark` gates production `w → 我` and `ygz → 一个字`, replays the progressive `pipei → 匹pei → 匹配` state transitions, and writes correctness, candidate coverage and latency results to `benchmarks/results/m4-core.json`.
- `./gradlew :benchmark:connectedBenchmarkAndroidTest` measures cold and warm settings startup on an attached Android 10+ device.

Device metrics must name the device, API level, refresh rate and build commit. Host numbers are useful for regression detection inside one environment, not for comparing different machines.

## 拼音质量回放

`benchmarks/replay/m3-sentences.tsv` 是版本化的确定性回归集，不用于估算所有真实用户的总体输入质量。当前 120 条用例覆盖全拼、长句、姓名、口语、技术词、单处拼写错误、简拼和混拼八种模式，每种模式至少 8 条，同时包含头部、中频和长尾词频桶。

TSV 支持旧版的两列格式，也支持以下六列格式；空的可选字段仍需保留制表符位置：

| 列 | 必填 | 说明 |
|---|---|---|
| `query` | 是 | 小写 ASCII 拼音、简拼或混拼 |
| `expected` | 是 | 首选期望文本 |
| `mode` | 否 | `full`、`sentence`、`name`、`colloquial`、`technical`、`typo`、`initials` 或 `hybrid`；省略时为 `full` |
| `context` | 否 | 已提交的左侧文本；回放使用最后一个 Unicode code point 调用 `decodeAfter` |
| `aliases` | 否 | 用 `|` 分隔的等价可接受文本；指标采用最先命中的期望或别名 |
| `frequencyBucket` | 否 | `head`、`mid`、`tail` 或 `unknown` |

文件允许使用注释，也允许首行使用未注释的列名。用例身份由 `query + mode + context` 组成，因此同一拼音可以在不同上下文中表达不同预期。

质量报告同时运行无上下文模型的 baseline 和生产 contextual 解码器，并在 10、64、255 三个候选上限下记录：

- **Top-1/3/10**：期望文本或任一等价别名出现在对应排名内的比例；
- **Coverage**：期望文本或别名出现在本次有界候选集合中的比例；
- **MRR**：所有用例的 reciprocal rank 均值，未召回项按 0 计；
- **分桶结果**：在生产上限 255 下按 mode 和 frequency bucket 分别汇总；
- **位次变化**：contextual 相对 baseline 的改善、退化和不变数量。

当前生产质量阻断阈值如下：

| 指标 | 下限 |
|---|---:|
| Coverage@255 | 60% |
| Top-10@255 | 50% |
| Top-1@255 | 25% |
| MRR@255 | 0.30 |

contextual 相对 baseline 最多允许损失 1 条 Coverage@255 和 2 条 Top-10@255。该小容差用于容纳上下文模型对少数无上下文固定预期的合理移动，同时仍会阻断大范围召回退化。

Host 延迟对非 `typo` 用例分别报告候选上限 10/64/255 的 p50、p95、p99；纠错用例单独在生产上限 255 下报告。生产门禁为：

| 路径 | p95 | p99 |
|---|---:|---:|
| 非纠错质量回放，limit 255 | 35 ms | 60 ms |
| 纠错质量回放，limit 255 | 40 ms | 70 ms |

此外，contextual p95 不得超过同批 baseline p95 的 1.35 倍再加 0.5 ms。质量回放保留合成歧义词典，验证上下文分数确实能够改变全局句子路径，而非只验证模型文件成功加载。

## v0.3.1-m4 阻断门禁

`benchmarks/replay/m4-core.tsv` 在原 M4 用例之外新增三条生产资产回放：

| 用例 | 阻断条件 |
|---|---|
| `coverage hua 滑 64` | `hua` 请求 64 项时必须能访问“滑”，不能再被服务层 32 项上限截断 |
| `composition shanghua 上滑` | 255 项有界句级搜索必须保留“上滑”，精确词条不能终止合法自动分词 |
| `learn d 的 de` | “的”必须携带可验证的规范全拼 `de`；显式选择一次后，下一次 `d` 的首候选必须立即变为“的” |

候选数量使用三层独立上限：单次整词解码最多 255 项，渐进前缀最多 255 项，服务展示快照最多 510 项。展示顺序为前 12 个整词、可分词前缀、其余整词；UI 必须使用全局索引让最后一项也可从展开面板选择。数量扩大不能移除 beam、前缀扫描或纠错搜索的既有上限。

新的 Host 性能门禁为：

| 路径 | p95 上限 | 说明 |
|---|---:|---|
| `ygz` 精确简拼 | 250 µs | 每个样本 20,000 次查询 |
| `pipei`，limit 16 | 500 µs | 每个样本 5,000 次渐进解析 |
| `pipei`，limit 255 | 5 ms | 每个样本 100 次生产上限渐进解析 |
| `shanghua`，limit 255 | 5 ms | 每个样本 100 次精确词与分词组合搜索 |

每项采集 7 个样本并使用 nearest-rank p95。上述预算只约束 JVM Host 算法回退，不包含 Android MotionEvent、Canvas 测量、InputConnection、GC、OEM 事件合并或显示帧时间。它们不能替代真机候选更新 p50/p95 和 60/90/120 Hz 掉帧门禁。

所有回放、单元测试、Lint、APK 编译与完整性检查都由本地 Gradle/JDK/Android SDK 工具链执行；报告中的实际数值只由本次本地运行生成，文档不预填性能结果。
