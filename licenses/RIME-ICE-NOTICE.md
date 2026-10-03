Rime Ice supplemental full-pinyin vocabulary (Sense E7)
=====================================================
Upstream: https://github.com/iDvel/rime-ice
Revision: 3aea6d3694fb3d94ec663641f021f788822897ad
Authors: iDvel and Rime Ice contributors; upstream source headers retained.
License: GNU General Public License version 3.0 only.
Complete license: RIME-ICE-GPL-3.0.txt

Changes by Sense contributors: filter 2..8 Han with one known syllable per
character, deduplicate against the pinned Frost base, add 306,963 missing
text/pinyin pairs as independent SPLX/4 source tier 2. All additions use a
uniform fallback prior of 1, not the upstream weights as usage frequencies.
Base word scores, order and private abbreviation/prefix indexes are retained.
The base alone defines the unigram scoring reference and personal-word LM
backoff membership. This is a bounded scoring feature, not a normalized
probability over the expanded vocabulary.

Preferred-form sources, hashes and offline build recipe:
ime-service/src/main/lexicon/vendor/rime-ice/
ime-service/src/main/lexicon/layered-sources.json
tools/rebuild_layered_pinyin.py
The new vocabulary is not used to retrain the bundled association model.
Its original SPLX/3 dependency can be recovered byte-for-byte by removing
tier-2 entries; tools/project_pinyin_base.py verifies the training SHA-256.
