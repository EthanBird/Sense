"""Verify the specific upstream LM license annotation and the pinned data chain."""
import argparse
import fnmatch
import hashlib
import json
from pathlib import Path
import tomllib


def verify(reference, evidence):
    name='lm_sc.arpa-20260629.tar.zst'
    upstream=json.loads((evidence/'model-rights-upstream.json').read_text('utf-8'))
    for filename,entry in upstream['files'].items():
        if hashlib.sha256((evidence/filename).read_bytes()).hexdigest()!=entry['sha256']:
            raise ValueError('Changed upstream licensing evidence')
    reuse=tomllib.loads((evidence/'upstream-REUSE.toml').read_text('utf-8'))
    matches=[a for a in reuse['annotations'] if fnmatch.fnmatchcase('data/'+name,a['path'])]
    if len(matches)!=1 or matches[0]['SPDX-License-Identifier']!='LGPL-2.1-or-later':
        raise ValueError('Missing specific LM data license')
    archive=reference/'current-data'/name
    actual=hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual!='06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f':
        raise ValueError('Changed upstream model archive')
    cmake=(reference/'current-data/upstream-data-CMakeLists.txt').read_text('utf-8')
    if name not in cmake or actual not in cmake:raise ValueError('Model not bound by upstream build file')
    arpa=reference/'current-data/lm_sc.arpa'
    if hashlib.sha256(arpa.read_bytes()).hexdigest()!='9cbfd115c139162fbafc54a1b7a2dbd03d16ac7cd1bb127b2bfbbbfc78436dfa':
        raise ValueError('Changed extracted model source')
    result=dict(model=name,archiveSha256=actual,annotation=matches[0],licenseExplicit=True,
        upstreamCommit='171edcf137001e8eb8274f53ec010058cda70b09',sourceCorpusOverlapKnown=False,
        packagingCompleted=False,requirements=['Preserve upstream copyright and LGPL text',
        'Publish corresponding model source and conversion recipe with any distributed derived binary',
        'Audit native reader dependency licenses and corresponding source separately'],
        references=upstream['files'])
    with (evidence/'model-license-verification.json').open('x',encoding='utf-8',newline='\n') as f:
        json.dump(result,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('reference',type=Path);p.add_argument('evidence',type=Path)
    a=p.parse_args();verify(a.reference,a.evidence)
