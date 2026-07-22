# Sense Benchmarks

Sense provides host and device baselines:

- `./gradlew :core-input:m0HostBenchmark` writes the deterministic host reducer report to `benchmarks/results/m0-host.json`.
- `./gradlew :core-input:m1PinyinBenchmark` loads the production lexicon, verifies representative Chinese candidates and writes lookup timing to `benchmarks/results/m1-pinyin.json`.
- `./gradlew :core-input:m2AdaptiveBenchmark` verifies `w → 我`, exact/prefix isolation, candidate syllable boundaries, short and long-sentence one-edit correction, one-shot initials learning, and 10k-user-entry lookup performance in `benchmarks/results/m2-adaptive.json`.
- `./gradlew :core-input:m3SentenceBenchmark` loads the production lexicon, `pinyin_bigrams.bin`, and `benchmarks/replay/m3-sentences.tsv`; it checks sentence-boundary reranking and the no-model fallback, then writes the host regression baseline to `benchmarks/results/m3-sentence.json`.
- `./gradlew :benchmark:connectedBenchmarkAndroidTest` measures cold and warm settings startup on an attached Android 10+ device.

Device metrics must name the device, API level, refresh rate and build commit. Host numbers are useful for regression detection inside one environment, not for comparing different machines.
