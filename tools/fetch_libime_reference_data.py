"""Fetch exact upstream data for a host-only model/data ablation; no APK packaging."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import zstandard

UPSTREAM = '171edcf137001e8eb8274f53ec010058cda70b09'
DATA = [
    ('lm_sc.arpa-20260629.tar.zst', '06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f', {'lm_sc.arpa'}),
    ('dict-20260907.tar.zst', 'fb75a179065e690dfc4559ce1807cbaf4fbe4f0111a5005615be9f435e6b9d76', {'dict_sc.txt', 'dict_extb.txt'}),
]


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''): h.update(data)
    return h.hexdigest()


def fetch(root):
    root.mkdir(parents=True, exist_ok=False)
    sources = []; extracted = {}
    cmake_url = f'https://raw.githubusercontent.com/fcitx/libime/{UPSTREAM}/data/CMakeLists.txt'
    cmake = urllib.request.urlopen(cmake_url, timeout=30).read()
    (root / 'upstream-data-CMakeLists.txt').write_bytes(cmake)
    for name, expected, allowed in DATA:
        assert name.encode() in cmake and expected.encode() in cmake
        url = f'https://download.fcitx-im.org/data/{name}'; path = root / name
        with urllib.request.urlopen(url, timeout=60) as remote, path.open('xb') as local:
            for chunk in iter(lambda: remote.read(1024 * 1024), b''): local.write(chunk)
        if digest(path) != expected: raise ValueError('Upstream archive checksum mismatch')
        members = []
        with path.open('rb') as source, zstandard.ZstdDecompressor().stream_reader(source) as stream:
            with tarfile.open(fileobj=stream, mode='r|') as tar:
                for member in tar:
                    members.append(dict(name=member.name, size=member.size, regular=member.isfile()))
                    if member.name not in allowed: continue
                    if not member.isfile() or member.size > 2 * 1024**3: raise ValueError('Unexpected data member')
                    destination = root / member.name
                    # Exact basename allowlist only; no archive path/symlink extraction.
                    with tar.extractfile(member) as src, destination.open('xb') as out:
                        for data in iter(lambda: src.read(1024 * 1024), b''): out.write(data)
                    extracted[member.name] = dict(bytes=destination.stat().st_size, sha256=digest(destination))
                    print(member.name, destination.stat().st_size, flush=True)
        if not allowed.issubset(extracted): raise ValueError('Missing expected upstream data')
        sources.append(dict(url=url, bytes=path.stat().st_size, sha256=expected, members=members))
    manifest = dict(schemaVersion=1, scope='Host-only updated-data ablation on old engine; not current complete engine or APK',
        upstreamCommit=UPSTREAM, upstreamBuildFile=cmake_url, buildFileSha256=digest(root / 'upstream-data-CMakeLists.txt'),
        decoderVersion='1.0.11-1build1', zstandardVersion=zstandard.__version__, sources=sources, files=extracted,
        dataRightsStatus='Upstream distribution hashes pinned; do not infer current model training provenance from old Debian copyright. Packaging review remains separate.')
    with (root / 'source-manifest.json').open('x', encoding='utf-8') as output:
        json.dump(manifest, output, ensure_ascii=False, indent=2); output.write('\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('output', type=Path)
    fetch(parser.parse_args().output)
