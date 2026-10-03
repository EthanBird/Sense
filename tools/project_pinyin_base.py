"""Recover the pinned F1 training dictionary, not the extended runtime vocabulary."""
import argparse
import hashlib
from pathlib import Path
from layered_lexicon import project_base

BASE_SHA256 = '71258c3d1b4cade8693a13564ead0217a7e92068bbe554ecc806ae0f3a08e800'


def project(source, output):
    if output.exists():
        raise ValueError('Use a new output path')
    result = project_base(source.read_bytes())
    if hashlib.sha256(result).hexdigest() != BASE_SHA256:
        raise ValueError('Projection does not match the pinned training dictionary')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(result)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    project(a.source, a.output)
