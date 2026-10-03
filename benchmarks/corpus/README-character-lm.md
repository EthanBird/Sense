# Rebuild the experimental character LM

Requires Python 3.12 and the pinned OpenCC preparation dependency. Training itself uses only stdlib.
The training recipe does not change APK assets. The B3 packaging step below is separate and explicit.

The unchanged source archive is checked in at `benchmarks/corpus/sources/`, with its separate
CC BY attribution notice. The upstream weekly URL is not used as an unfrozen training input.

## Windows / PowerShell

```powershell
# Observed Python 3.12 on this workstation; use an equivalent Python 3.12 elsewhere.
$Py = 'C:\Users\syc\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:PYTHONIOENCODING = 'utf-8'
& $Py -m pip install --no-deps --only-binary=:all: --require-hashes `
  --target .artifacts/input-quality/lm-python -r tools/requirements-character-lm.txt
$env:PYTHONPATH = (Resolve-Path .artifacts/input-quality/lm-python).Path

& $Py tools/prepare_sentence_corpus.py `
  benchmarks/corpus/sentence-sources-v1.json benchmarks/corpus/sources `
  .artifacts/input-quality/lm-corpus-v1 --replays benchmarks/replay `
  --report benchmarks/results/sentence-corpus-prepared-v1.json

& $Py tools/train_character_lm.py train .artifacts/input-quality/lm-corpus-v1 `
  .artifacts/input-quality/character-v1.scng benchmarks/results/character-lm-v1-dev.json

# Freeze model and development decisions before this explicit test operation.
& $Py tools/train_character_lm.py evaluate-test .artifacts/input-quality/lm-corpus-v1 `
  .artifacts/input-quality/character-v1.scng benchmarks/results/character-lm-v1-dev.json `
  benchmarks/results/character-lm-v1-test.json

& $Py tools/export_lm_reference.py .artifacts/input-quality/character-v1.scng `
  .artifacts/input-quality/character-v1-reference.tsv
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
.\gradlew.bat --offline --console=plain :core-input:m10CharacterLmBenchmark

# Exploratory top-three reranking: not a production candidate API or frozen P2C score.
& $Py tools/probe_lm_nbest.py .artifacts/input-quality/character-v1.scng `
  benchmarks/results/m8-daily-input.json benchmarks/results/character-lm-v1-nbest-probe.json
```

Every step validates upstream hashes. Prepared `train/dev/test.jsonl`, `attribution.jsonl`, model
and reference probes stay under `.artifacts/`; metadata and reports are checked in.
Preserve `corpus.json` and `attribution.jsonl` with the model. The trainer rejects missing or changed
attribution, and frozen evaluation rejects a changed model, corpus manifest or test partition.

`m10CharacterLmBenchmark` is intentionally not a default release gate yet: it needs experimental
training artifacts. Tiny, authored interoperability fixtures are in normal core unit tests.

## Format and scoring

SCNG/1 uses big-endian fields. Header: `SCNG`, uint16 version 1, then five uint32 counts.
Tables in order: unigram `(uint32 token,float32 logP)`; bigram `(uint64 key,float32 logP)`;
bigram backoff `(uint32 context,float32 logWeight)`; trigram `(uint64 key,float32 logP)`;
trigram backoff `(uint64 context,float32 logWeight)`. Keys are strictly ascending.

Tokens are Unicode scalars, plus BOS=`0x110000`, EOS=`0x110001`, UNK=`0x110002`.
Pack n-grams by concatenating 21-bit tokens. BOS is context only. The model starts each Han segment
with two BOS tokens and scores one EOS; punctuation splits segments rather than forming fake Han pairs.

Observed entries store **full interpolated probabilities**, not just discounted counts.
For absent entries, add the context's log backoff to the lower-order log probability.
Unknown characters map to UNK; do not confuse this token-level likelihood with calibrated
character-level probabilities across every unseen Unicode glyph.

Default training: character count cutoff 2; bigram cutoff 1; trigram cutoff 2; discount 0.75;
unigram additive floor 0.1. Removed direct mass is transferred into the backoff weight.
This is absolute-discount interpolation, **not Kneser-Ney**.

## B3 Android asset packaging

After the fixed development selection and first-use P2C test (see `p2c-v1/README.md`):

```powershell
& $Py tools/package_character_lm.py .artifacts/input-quality/lm-corpus-v1 `
  .artifacts/input-quality/character-v1.scng benchmarks/results/character-lm-v1-dev.json `
  benchmarks/results/m12-p2c-development-freeze.json benchmarks/results/m12-p2c-conclusion.json `
  ime-service/src/main/assets
```

This copies the pinned binary and generates a deterministic UTF-8 per-training-sentence author
index, a JSON provenance notice, and the readable `PINYIN-LM-NOTICE.txt` used by Android About.
The index is plain `.tsv`; the APK ZIP compresses it. Do not rename it `.gz`: aapt expands such
assets and strips the suffix, making the source filename/hash differ from the packaged asset.
Run `tools/audit_packaged_lm.py <apk> <report.json>` against the built APK to verify provenance.
77,733 original source sentences from 932 contributors are attributed. Raw training text and P2C
labels stay outside the APK. The model/data remain CC BY 2.0 FR, separate from code licensing.

In the development checkout the IME's background loader now binds this model at weight **0.5**
for 26-key full pinyin. T9 retains its legacy decoder over the same learning store. Model size/hash
or format failure falls back to the dictionary decoder. System IME/external-editor acceptance
is still required before release; packaging and host-side tests alone do not fulfill that gate.
