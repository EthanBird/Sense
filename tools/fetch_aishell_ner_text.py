"""Fetch text-only research data from the authors' pinned AISHELL-NER repository."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request

REPO = 'Alibaba-NLP/AISHELL-NER'
COMMIT = 'fb11b6495281bc9bbc2bea7b8bde9eb3c19a4605'
FILES = ['LICENSE', 'README.md'] + [f'data/aishell_ner_transcript.{part}.txt' for part in ['train', 'dev', 'test']]


def fetch(root):
    root.mkdir(parents=True, exist_ok=False)
    tree_url = f'https://api.github.com/repos/{REPO}/git/trees/{COMMIT}?recursive=1'
    request = urllib.request.Request(tree_url, headers={'User-Agent': 'Sense-input-quality-research'})
    tree_data = urllib.request.urlopen(request, timeout=30).read()
    (root / 'upstream-tree.json').write_bytes(tree_data)
    tree = json.loads(tree_data); assert not tree['truncated']
    blobs = {r['path']: r for r in tree['tree'] if r['type'] == 'blob'}
    pins = {}
    for name in FILES:
        url = f'https://raw.githubusercontent.com/{REPO}/{COMMIT}/{name}'
        data = urllib.request.urlopen(url, timeout=60).read()
        git_sha = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        assert len(data) == blobs[name]['size'] and git_sha == blobs[name]['sha']
        path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as out: out.write(data)
        pins[name] = dict(url=url, bytes=len(data), githubBlobSha1=git_sha, sha256=hashlib.sha256(data).hexdigest())
    manifest = dict(schemaVersion=1, sourceId='aishell-ner-fb11b64', repository=REPO, commit=COMMIT,
        fetchedAt=datetime.now(timezone.utc).isoformat(), sourcePage='https://github.com/Alibaba-NLP/AISHELL-NER',
        parentCorpusPage='https://www.openslr.org/33/', paper='https://arxiv.org/abs/2202.08533',
        license='Apache-2.0', licenseEvidence='Pinned author repository LICENSE; OpenSLR33 license field',
        scope='Read speech transcripts with NER markup, not natural mobile typing. Pinyin labels are not supplied.',
        files=pins)
    with (root / 'source-manifest.json').open('x', encoding='utf-8') as out:
        json.dump(manifest, out, ensure_ascii=False, indent=2); out.write('\n')
    print(json.dumps({k: dict(bytes=v['bytes'], sha256=v['sha256']) for k, v in pins.items()}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('output', type=Path)
    fetch(parser.parse_args().output)
