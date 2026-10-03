"""Offline, hash-pinned E7 build from checked-in preferred-form dictionary sources."""
import argparse
import hashlib
import json
from pathlib import Path
from build_pinyin_lexicon import read_manifest_dictionary, write_binary
from build_supplemental_pinyin import build as supplement
from build_layered_pinyin import build as layer

ROOT = Path(__file__).resolve().parents[1]


def rebuild(output):
    if output.exists():
        raise ValueError('Use a new output directory; previous evidence is retained')
    manifest = ROOT / 'ime-service/src/main/lexicon/layered-sources.json'
    policy = json.loads(manifest.read_text('utf-8'))
    entries, _, _ = read_manifest_dictionary(manifest.with_name('sources.json'), None)
    output.mkdir(parents=True)
    base = output / 'base.bin'
    write_binary(entries, base)
    if hashlib.sha256(base.read_bytes()).hexdigest() != policy['baseSha256']:
        raise ValueError('Base dictionary build differs')
    del entries
    supplement(base, ROOT / 'ime-service/src/main/assets/pinyin_syllables.txt', manifest, output / 'prototype', 1)
    result = layer(base, output / 'prototype', output / 'layered')
    expected = json.loads(manifest.with_name('pinyin_lexicon.stats.json').read_text('utf-8'))['asset']
    if result['asset']['sha256'] != expected['sha256'] or result['asset']['bytes'] != expected['bytes']:
        raise ValueError('Rebuilt layered dictionary differs from the package pin')
    print(json.dumps(result['asset']))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    rebuild(p.parse_args().output)
