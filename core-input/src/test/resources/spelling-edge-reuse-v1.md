# Spelling-edge reuse oracle

The TSV contains 1,481 deterministic cases: the existing 945-row `benchmarks/corpus/typo-v1/dev.tsv`
plus prefix, forced-joint, ambiguous-unit, long-unit, budget and maximum-input controls.
It is regression data derived from previously used inputs, not a new blind accuracy set.
The original corpus provenance and licenses remain in its corpus documentation.

Expected SHA-256 values cover the complete ordered `List<PinyinSpellingPath>.toString()` produced by
the **uncached E31 cross-joint candidate**, before E34 cache changes:

- Core JAR SHA-256: `93c0d4246f2217a997ba5bf8f99903c6a0987d2221e8adb472f10a88d2c7e06d`.
- Graph source SHA-256: `fc0f6ecf433dea28eefe63c5f1222cc38d49d3c36a421e441ba0814f3c73c328`.
- Main inventory: `ime-service/src/main/assets/pinyin_syllables.txt`; two explicit synthetic inventories
  cover ambiguous segmentation and the supported 24-character spelling-unit limit.
- Oracle generator: `tools/android-fixture/SpellingGraphSnapshot.java`, with an isolated frozen JAR
  and Kotlin stdlib. Query-generation policy and source/fixture hashes are archived with E34 evidence.

CI needs only the TSV, current graph and existing inventory asset. The regression replays in forward
and reverse order to exercise incremental typing, deletion and cache eviction. Do not regenerate
expectations from a cache candidate simply to make the equality check pass.
