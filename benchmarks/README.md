# Sense Benchmarks

Sense provides host and device baselines:

- `./gradlew :core-input:m0HostBenchmark` writes the deterministic host reducer report to `benchmarks/results/m0-host.json`.
- `./gradlew :core-input:m1PinyinBenchmark` loads the production lexicon, verifies representative Chinese candidates and writes lookup timing to `benchmarks/results/m1-pinyin.json`.
- `./gradlew :core-input:m2AdaptiveBenchmark` verifies `w → 我`, exact/prefix isolation, candidate syllable boundaries, short and long-sentence one-edit correction, one-shot initials learning, and 10k-user-entry lookup performance in `benchmarks/results/m2-adaptive.json`.
- `./gradlew :core-input:m3SentenceBenchmark` loads the production lexicon, `pinyin_bigrams.bin`, and `benchmarks/replay/m3-sentences.tsv`; it checks sentence-boundary reranking and the no-model fallback, then writes the host regression baseline to `benchmarks/results/m3-sentence.json`.
- `./gradlew :core-input:m4CoreBenchmark` gates production `w → 我` and `ygz → 一个字`, replays the progressive `pipei → 匹pei → 匹配` state transitions, and writes correctness, candidate coverage and latency results to `benchmarks/results/m4-core.json`.
- `./gradlew :benchmark:connectedBenchmarkAndroidTest` measures cold and warm settings startup on an attached Android 10+ device.

Device metrics must name the device, API level, refresh rate and build commit. Host numbers are useful for regression detection inside one environment, not for comparing different machines.

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

本地工作区没有可用的 Gradle 8.13/Android 构建工具链，因此新 Kotlin 回放与性能数字不在文档中预填。`v0.3.1-m4` 仅在 GitHub Actions 依次完成单元测试、M0–M4 Host benchmark、Lint、APK 编译与完整性检查后才允许创建 prerelease；最终运行产物中的 `m4-core.json` 是该次构建的权威 Host 报告。
