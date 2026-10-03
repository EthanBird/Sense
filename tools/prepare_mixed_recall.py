"""Deterministic dictionary-pronunciation retrieval audit, not natural typing accuracy."""
import hashlib
import json
from pathlib import Path
import sys

from lexicon_sources import is_han_text, normalized_syllables


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    output = Path(sys.argv[1])
    output.mkdir(exist_ok=False, parents=True)
    lex = root / 'ime-service/src/main/lexicon'
    manifest = json.loads((lex / 'sources.json').read_text())
    allowed = set((root / 'ime-service/src/main/assets/pinyin_syllables.txt').read_text().splitlines())
    buckets, provenance, seen = {}, {}, set()
    for source in manifest['sources']:
        if source['id'] not in ('rime-frost-base', 'rime-frost-ext', 'rime-frost-others'):
            continue
        path = lex / source['path']
        assert sha(path.read_bytes()) == source['sha256']
        provenance[source['path']] = source['sha256']
        for line in path.read_text('utf-8').splitlines():
            if line.startswith('#') or '\t' not in line:
                continue
            f = line.split('\t')
            if len(f) < 3 or not f[2].isdigit():
                continue
            text, syllables, weight = f[0], normalized_syllables(f[1]), int(f[2])
            if not is_han_text(text) or not 3 <= len(text) <= 5 or len(syllables) != len(text):
                continue
            if any(s not in allowed or len(s) < 2 for s in syllables):
                continue
            key = text, tuple(syllables)
            if key in seen:
                continue
            seen.add(key)
            group = f"{len(text)}han-{'low' if weight < 100 else 'common'}"
            identity = sha(('sense-mixed-recall-v1\0' + text + '\0' + ' '.join(syllables)).encode())
            buckets.setdefault(group, []).append((identity, text, syllables, weight, source['id']))
    selected = [(group, row) for group, rows in sorted(buckets.items()) for row in sorted(rows)[:32]]
    rows = []
    for group, (identity, text, syllables, weight, source_id) in selected:
        canonical = ''.join(syllables)
        masks = {'full': set(range(len(syllables))), 'first-full': {0},
                 'middle-full': {len(syllables) // 2}, 'last-full': {len(syllables) - 1},
                 'alternating': set(range(0, len(syllables), 2))}
        for mode, full in masks.items():
            query = ''.join(s if i in full else s[0] for i, s in enumerate(syllables))
            rows.append([identity + '-' + mode, group, mode, query, text, canonical, ' '.join(syllables), str(weight), source_id])
    data = '# id\tstratum\tmode\tquery\texpected\tcanonical\tsyllables\trawSourceWeight\tsource\n'
    data += ''.join('\t'.join(row) + '\n' for row in rows)
    (output / 'audit.tsv').write_text(data, encoding='utf-8', newline='\n')
    notice = {'schemaVersion': 1, 'scope': 'Known dictionary reconstruction; synthetic abbreviation recall, not human intent, fresh accuracy, or LM evaluation',
              'license': 'GPL-3.0-only', 'upstream': manifest['upstream'], 'sources': provenance,
              'selection': 'SHA256 ascending, 32 pronunciations per Han-length/raw-weight stratum; no decoder output used',
              'eligible': {g: len(v) for g, v in buckets.items()}, 'families': len(selected), 'rows': len(rows),
              'auditSha256': sha(data.encode())}
    (output / 'notice.json').write_text(json.dumps(notice, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(f'Pronunciations={len(selected)}, rows={len(rows)}')


if __name__ == '__main__':
    main()
