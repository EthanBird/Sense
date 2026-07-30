# Rime Frost dictionary notice

- Upstream: https://github.com/gaboolic/rime-frost
- Pinned commit: `69cbcf8937ae03c03792fa285dca7f79f80715bc`
- License: GNU General Public License v3.0 only
- Local license: `licenses/rime-frost-GPL-3.0.txt`

Sense vendors the following preferred-form dictionary sources and transforms
them deterministically through `ime-service/src/main/lexicon/sources.json`:

- `cn_dicts/8105.dict.yaml`
- `cn_dicts/base.dict.yaml`
- `cn_dicts/ext.dict.yaml`
- `cn_dicts/others.dict.yaml`

Sense keeps the upstream pinyin and frequency columns, rejects rows outside the
configured Han/length boundary, calibrates source weights with manifest-pinned
integer ratios, merges duplicate text/pinyin pairs, and builds exact, prefix,
initials, and bounded hybrid indexes. No endorsement by the Rime Frost or Rime
Ice contributors is implied.
