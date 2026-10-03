# Frozen source-reconstruction P2C evaluation v1

This is a new, deterministic subset of the already frozen Tatoeba corpus partitions.
It was selected independently of decoder results and the earlier M8 authored development cases.
It is **not human-annotated gold**, not an open-domain IME certification, and not live user text.

## Files and governance

- `../p2c-v1-selection-policy.json`: sampling, review, selection, and promotion rules fixed before candidate evaluation.
- `selection.json`, `proposals.jsonl`: original sample and automated phonetic proposals, with no replacement after exclusions.
- `annotations.tsv`: explicit agent review of all 192 proposals, completed before candidate output was inspected.
- `attribution.jsonl`: original source IDs, contributor names, text, source URLs, license, and normalization metadata.
- `dev.tsv`, `test.tsv`, `frozen.json`: exact reviewed inputs and byte fingerprints.

64 development + 128 test source Han segments were sampled, half 6–9 characters and half 10–16.
One segment per prepared near-duplicate family; sort key is SHA-256 of the fixed seed, partition,
record ID, and text. Four samples overlapped earlier partitions by substring or one edit and were
excluded without replacement: **63 dev / 125 test** remain. This guard checks Han segments as well
as the earlier whole-sentence family isolation. It does not remove ordinary shared words/n-grams.

Six phonetic proposals were corrected before evaluation: three adverbial 地 readings (`di → de`),
aspectual 著 (`zhu → zhe`), loan-meaning 藉 (`ji → jie`), and the house classifier 幢 (`chuang → zhuang`).
The source characters and awkward/archaic fragments were preserved, including 著/藉, rather than
silently modernizing targets. No homophone aliases were added after seeing output. This exact-text
reconstruction metric can penalize natural equivalent character choices and is not semantic correctness.

`pypinyin==0.55.0` was used only to propose labels; its official wheel hash is pinned in
`tools/requirements-p2c.txt`. The annotation review was by the coding agent, not an independent human.
See [pypinyin documentation](https://github.com/mozillazg/python-pinyin) for phrase readings,
polyphones, `v` for ü, and the need to correct proposals.

## License

Source/derived sentence data retain **CC BY 2.0 France**. Authors and direct source links are in
`attribution.jsonl`. Changes: NFKC/OpenCC simplified-character normalization, Han-span selection,
deduplication/partitioning, hash sampling, phonetic proposals, and explicit pronunciation corrections.
The original snapshot remains in `../sources/`; data are not relicensed as the surrounding code.
See [Tatoeba download/license description](https://tatoeba.org/en/downloads) and
[CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/).

## Reproduce

On a new output directory, after rebuilding the B1 corpus/model:

```powershell
$Py = 'C:\Users\syc\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $Py -m pip install --no-deps --only-binary=:all: --require-hashes `
  --target .artifacts/input-quality/p2c-python -r tools/requirements-p2c.txt
$env:PYTHONPATH = (Resolve-Path .artifacts/input-quality/p2c-python).Path
& $Py tools/prepare_p2c.py prepare .artifacts/input-quality/lm-corpus-v1 `
  benchmarks/corpus/p2c-v1-selection-policy.json .artifacts/input-quality/p2c-rebuild
# Copy the reviewed annotations.tsv to p2c-rebuild, then freeze that directory.
& $Py tools/prepare_p2c.py freeze .artifacts/input-quality/p2c-rebuild `
  ime-service/src/main/assets/pinyin_syllables.txt .artifacts/input-quality/character-v1.scng
```

The first-use sequence was `evaluate_p2c.py dev`, `freeze-dev`, then `test`.
Its report/pins/start marker are kept under `benchmarks/results/m12-p2c-*` and intentionally resist
overwrite. The selected mode was **lm0.5**: lm1.0's larger top-1 gain lost candidate coverage and
failed the predeclared development gate. Only legacy and lm0.5 were evaluated on test.

For subsequent known-test regression replay, do not overwrite those historical first-use artifacts:

```powershell
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
.\gradlew.bat --offline --no-parallel --console=plain :core-input:m12P2cBenchmark `
  -Pp2cPartition=test -Pp2cModes=legacy,lm0.5 `
  -Pp2cReport=.artifacts/input-quality/m12-known-test-replay.json
```

After first evaluation this set is a regression set. New tuning requires a new independent cohort;
replaying it does not become fresh held-out evidence.
