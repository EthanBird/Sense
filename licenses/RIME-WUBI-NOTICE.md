# Rime Wubi attribution

Sense includes a generated Wubi86 lexicon derived from the preferred editable source
`wubi86.dict.yaml` in the Rime Wubi project.

- Project: Rime Wubi
- Upstream: https://github.com/rime/rime-wubi
- Revision: `152a0d3f3efe40cae216d1e3b338242446848d07`
- Source file SHA-256: `f833d86b72341fe82e069a425b6625f29ef85f1bc0f34f6fb7975fe514888b5a`
- License: GNU Lesser General Public License v3.0
- Local changes: entries whose code starts with `z` are omitted so `z` remains available for
  Pinyin reverse lookup; exact and bounded prefix indexes plus a character-to-code reverse index
  are deterministically compiled into the Sense `SWBX/1` format.

The editable source, AUTHORS file, original license, manifest, build script and generated-asset
statistics are distributed with the Sense source tree.
