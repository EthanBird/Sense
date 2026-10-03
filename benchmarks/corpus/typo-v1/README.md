# 26-key typo replay v1

**Scope:** deterministic synthetic single-key perturbations of the existing,
already reviewed M12 P2C source sentences. This is not fresh natural user-typing
data. Source sentences and their clean reconstruction results are already known;
the new mutation outputs have a separate development/test use boundary.

- Development: 63 source families, 945 rows.
- Test: 125 source families, 1,874 rows (one source has no eligible phonetic confusion).
- Every source has a clean spelling and a fully apostrophe-delimited control.
- Delete, duplicate, geometric QWERTY neighbor and adjacent transposition: one
  deterministic SHA-256-selected mutation in each character-offset third.
- One eligible phonetic confusion per source; its selection ignores decoder output.
- Geometry is independently specified here, not imported from production code.
- Rows with identical source/typed strings retain their distinct operation labels;
  941/1,865 distinct source/input pairs respectively. Variant rows are correlated.
- All eligible sources are retained. No filtering by target candidate coverage,
  legal resulting spelling, measured difficulty, or algorithm success.
- A substituted spelling may itself be valid Chinese input. The requested text
  is the **synthetic intention**, not a claim that a real user should be corrected.

The generator checks the original source hashes; `frozen.json` pins the recipe,
generator and output data. The source pinyin was reviewed by the Codex agent,
not by an independent human annotation team.

## Attribution and license

Text originates from the fixed Tatoeba ordinary-Mandarin snapshot used in
[`../p2c-v1`](../p2c-v1/). It is licensed under
[CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/).
The preserved sentence contributors, source URLs, original IDs and modification
metadata are in [`../p2c-v1/attribution.jsonl`](../p2c-v1/attribution.jsonl).
`sourceId` joins the original P2C case `id` in
[`../p2c-v1/proposals.jsonl`](../p2c-v1/proposals.jsonl); its `recordId` joins the
attribution ledger. The original source text was normalized and segmented;
this dataset additionally derives pinyin and injects artificial typing errors.
No statement of author endorsement is implied.

These are evaluation-only assets. They are not copied into Android assets or
used to add production phrases, weights, or special-case corrections.

Reproduce into a **new** directory:

```powershell
python tools/prepare_typo_replay.py benchmarks/corpus/p2c-v1 OUTPUT
./gradlew.bat :core-input:m15TypoRecallBenchmark '-PtypoReplay=OUTPUT/dev.tsv' '-PtypoReport=NEW_REPORT.json'
```
