# Frozen research corpus source

`cmn_sentences_detailed.tsv.bz2` is the unchanged Tatoeba Mandarin detailed export from
2026-09-26, downloaded 2026-10-02. The 1,722,601-byte source archive is retained here because
the upstream weekly URL changes; a hash without the original snapshot is not a rebuild input.

- Source: https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2
- Work: Tatoeba Mandarin sentence collection
- Authors: **Tatoeba contributors**, with each sentence's contributor retained in column four.
- Sentence attribution URL: `https://tatoeba.org/en/sentences/show/<column-one-id>`
- License: **CC BY 2.0 France**, https://creativecommons.org/licenses/by/2.0/fr/
- License evidence and export format: https://tatoeba.org/en/downloads
- SHA-256: `8b689948972ff7676dd3fb28bef092e893959e6c561dbf06c8434e3d668adfea`
- Raw archive modifications: **none**.

This corpus retains its own CC BY license; it is not relicensed as the surrounding project's GPL code.
It is a research/build input, not an APK asset. Derived corpus files retain per-sentence attribution,
original text, transformation details, and source URLs in `attribution.jsonl`.
Any future model distribution must retain that attribution/provenance boundary rather than copying only a binary.

The rejected all-language CC0 source is not a training input and is not vendored here.
