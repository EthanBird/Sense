"""Pin a locally extracted, older libime reference before opening its diagnostic outputs."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def entry(path):
    data = path.read_bytes()
    return dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def freeze(external, output):
    if output.exists(): raise ValueError('Keep previous freeze')
    if any((output.parent / f'{name}-reference.jsonl').exists() for name in ['dev', 'test']):
        raise ValueError('Reference outputs already present')
    paths = ['tools/libime_reference_probe.cpp', 'tools/setup_libime_reference.sh',
        'tools/build_libime_reference.sh', 'tools/run_libime_reference.sh', 'tools/evaluate_reference_engine.py',
        'tools/test_evaluate_reference_engine.py', 'tools/train_candidate_ranker.py',
        'tools/freeze_libime_reference.py', 'benchmarks/corpus/e14-reference-policy.json',
        'benchmarks/corpus/p2c-supervised-v6/dev.tsv', 'benchmarks/corpus/p2c-supervised-v6/test.tsv',
        '.artifacts/input-quality/e13/fit/development.json', '.artifacts/input-quality/e13/independent-test.json']
    files = [external / p for p in ['libime-reference', 'package-metadata.txt', 'package-sha256.txt',
        'linked-libraries.txt', 'host-environment.txt', 'root/usr/share/libime/sc.dict',
        'root/usr/lib/x86_64-linux-gnu/libime/zh_CN.lm', 'root/usr/share/doc/libime-data/copyright']]
    files += list((external / 'packages').glob('*.deb'))
    # Real ELF objects only, not the WSL reparse-point linker aliases.
    for path in (external / 'root/usr/lib/x86_64-linux-gnu').glob('*.so.*'):
        if path.is_symlink(): continue
        try:
            with path.open('rb') as stream:
                if stream.read(4) == b'\x7fELF': files.append(path)
        except OSError:
            continue
    result = dict(schemaVersion=1, frozenAt=datetime.now(timezone.utc).isoformat(),
        knownDataDiagnostic=True, outputFilesAbsentAtFreeze=True,
        localPins={p: entry(ROOT / p) for p in paths},
        externalRoot=str(external), externalPins={p.relative_to(external).as_posix(): entry(p) for p in sorted(files)})
    with output.open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2); f.write('\n')
    print(f'Reference frozen: local={len(paths)}, external={len(files)}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('external', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); freeze(args.external, args.output)
