# Sense Benchmarks

M0 provides two complementary baselines:

- `./gradlew :core-input:m0HostBenchmark` writes the deterministic host reducer report to `benchmarks/results/m0-host.json`.
- `./gradlew :benchmark:connectedBenchmarkAndroidTest` measures cold and warm settings startup on an attached Android 10+ device.

Device metrics must name the device, API level, refresh rate and build commit. Host numbers are useful for regression detection inside one environment, not for comparing different machines.

