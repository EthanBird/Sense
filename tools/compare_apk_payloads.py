"""Prove APK repackaging preserves every uncompressed ZIP entry; signatures are checked separately."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def digest(data): return hashlib.sha256(data).hexdigest()


def compare(before, after):
    def entries(path):
        with ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)): raise ValueError('Duplicate ZIP entries')
            return {n: digest(archive.read(n)) for n in sorted(names)}
    a, b = entries(before), entries(after)
    if a != b: raise ValueError('APK payload changed, not just repackaging')
    return {'schemaVersion': 1, 'passed': True, 'scope': 'ZIP-entry byte identity only; signing and Android execution are separate',
            'before': {'sha256': digest(before.read_bytes()), 'bytes': before.stat().st_size},
            'after': {'sha256': digest(after.read_bytes()), 'bytes': after.stat().st_size}, 'entries': b}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for n in ('before', 'after', 'report'): p.add_argument(n, type=Path)
    a = p.parse_args()
    if a.report.exists(): raise ValueError('Retain previous evidence')
    result = compare(a.before, a.after)
    a.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v if k != 'entries' else len(v) for k, v in result.items()}))
