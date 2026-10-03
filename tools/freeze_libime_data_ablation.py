"""Pin updated-data ablations separately from the already frozen old reference."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from freeze_libime_reference import ROOT, entry


def freeze(external, output):
    original = json.loads((output.parent / 'reference-lock.json').read_text())
    for p, pin in original['localPins'].items(): assert entry(ROOT / p) == pin
    for p, pin in original['externalPins'].items(): assert entry(external / p) == pin
    assert not list(output.parent.glob('*-model-only.jsonl'))
    assert not list(output.parent.glob('*-dictionary-only.jsonl'))
    assert not list(output.parent.glob('*-both.jsonl'))
    sources = ['tools/fetch_libime_reference_data.py', 'tools/setup_libime_data_tools.sh',
        'tools/build_libime_updated_data.sh', 'tools/libime_dictionary_compat.cpp',
        'tools/build_libime_compatible_dictionary.sh', 'tools/run_libime_data_ablation.sh',
        'tools/freeze_libime_data_ablation.py', 'benchmarks/corpus/e14-data-ablation-policy.json']
    external_files = [external / 'libime-dictionary-compat']
    external_files += [external / 'current-data' / n for n in [
        'source-manifest.json', 'upstream-data-CMakeLists.txt', 'lm_sc.arpa', 'dict_sc.txt',
        'zh_CN.lm', 'compatible-sc.dict', 'compatible-sc.txt', 'excluded-dictionary-rows.tsv']]
    external_files += list((external / 'data-tools/packages').glob('*.deb'))
    external_files += [external / 'data-tools' / n for n in ['package-metadata.txt', 'package-sha256.txt',
        'root/usr/bin/libime_slm_build_binary', 'root/usr/bin/libime_pinyindict']]
    result = dict(schemaVersion=1, frozenAt=datetime.now(timezone.utc).isoformat(),
        knownDataDiagnostic=True, outputFilesAbsentAtFreeze=True,
        originalReferenceLock=entry(output.parent / 'reference-lock.json'),
        localPins={p: entry(ROOT / p) for p in sources},
        externalPins={p.relative_to(external).as_posix(): entry(p) for p in external_files})
    with output.open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2); f.write('\n')
    print(f'Data ablation frozen: {len(external_files)} external pins')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('external', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); freeze(args.external, args.output)
