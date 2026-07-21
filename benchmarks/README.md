# Sense Benchmarks

Sense provides host and device baselines:

- `./gradlew :core-input:m0HostBenchmark` writes the deterministic host reducer report to `benchmarks/results/m0-host.json`.
- `./gradlew :core-input:m1PinyinBenchmark` loads the production lexicon, verifies representative Chinese candidates and writes lookup timing to `benchmarks/results/m1-pinyin.json`.
- `./gradlew :benchmark:connectedBenchmarkAndroidTest` measures cold and warm settings startup on an attached Android 10+ device.

Device metrics must name the device, API level, refresh rate and build commit. Host numbers are useful for regression detection inside one environment, not for comparing different machines.
