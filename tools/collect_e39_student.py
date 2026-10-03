"""Independently audit the fixed student trial and archive its reproducible evidence."""
import argparse
from collections import Counter
import gzip
import hashlib
import json
import math
from pathlib import Path
import subprocess

from candidate_student import load_features, reorder_student, select_inputs, token_rows
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from masked_lm_rescore import character_vocabulary
from prepare_p2c import overlaps
from run_e37_rescoring import distribution, validate_baseline, write_new
from run_e39_student import source_pins

read = lambda p: json.loads(p.read_text('utf-8-sig'))
sha = lambda b: hashlib.sha256(b).hexdigest()


def collect(root, base, models):
    out = base / 'final'; policy_file = root / 'benchmarks/corpus/e39-student-policy.json'
    policy = read(policy_file); lock = read(out / 'pre-export-lock.json')
    assert lock['policySha256'] == digest(policy_file)
    assert lock['trainSha256'] == digest(out / 'train.tsv')
    assert lock['attributionSha256'] == digest(out / 'attribution.jsonl')
    for name, h in lock['sourceFiles'].items():
        assert digest(root / name) == h, name
    corpus = root / '.artifacts/input-quality/lm-corpus-v1'
    manifest = read(corpus / 'corpus.json')
    records = {}
    for split, entry in manifest['outputs'].items():
        assert digest(corpus / entry['fileName']) == entry['sha256'] == lock['corpus'][split]
        records[split] = [json.loads(l) for l in (corpus / entry['fileName']).read_text('utf-8').splitlines()]
    groups = {s: {r['group'] for r in rs} for s, rs in records.items()}
    assert not (groups['train'] & groups['dev'] or groups['train'] & groups['test'] or groups['dev'] & groups['test'])
    heldout = []
    for name, h in lock['heldout'].items():
        assert digest(root / name) == h
        heldout += [l.split('\t')[2] for l in (root / name).read_text('utf-8').splitlines() if l and not l.startswith('#')]
    selected = read(out / 'selected.json')
    assert len(selected) == lock['kept'] == 512 and not lock['excluded']
    for row in selected:
        assert row['partition'] == 'train' and row['group'] in groups['train']
        assert not overlaps(row['text'], heldout) and not any(t in row['text'] for t in heldout)
    attributed = [json.loads(l) for l in (out / 'attribution.jsonl').read_text('utf-8').splitlines()]
    assert {r['group'] for r in attributed} == {r['group'] for r in selected}
    assert all(r['license'] == 'CC-BY-2.0-FR' and r['split'] == 'train' for r in attributed)

    teacher = read(out / 'teacher-complete.json'); train = read(out / 'training-complete.json')
    for name in ['pre-teacher-lock.json', 'teacher-complete.json', 'pre-train-lock.json',
                 'training-complete.json', 'pre-dev-lock.json']:
        assert read(out / name)['sourceFiles'] == source_pins(root), name
    assert teacher['qualification']['passed'] and teacher['exactPrefixRechecks'] == 8
    assert teacher['groupsSha256'] == train['trainingGroupsSha256'] == digest(out / 'training-groups.jsonl')
    assert teacher['featureSha256'] == digest(out / 'train-features.jsonl.gz')
    assert train['checkpointSha256'] == digest(out / 'student.safetensors')
    assert train['configSha256'] == digest(out / 'student-config.json')
    assert train['finalEpoch'] == 4 and len(train['history']) == 4 and train['changedEncoderTensors'] > 0
    assert all(math.isfinite(h['meanTrainLoss']) for h in train['history'])
    _, vocab = character_vocabulary(models / 'minirbt-h256-446e1b4/vocab.txt')
    training, _ = load_features(root, out / 'train.tsv', out / 'train-features.jsonl.gz')
    used = {}; prefix = {(r['id'],r['cut']):r for r in
        [json.loads(l) for l in (base / 'training-groups.jsonl').read_text('utf-8').splitlines()]}
    for line in (out / 'training-groups.jsonl').read_text('utf-8').splitlines():
        r = json.loads(line); key = (r['id'], r['cut']); assert key not in used
        b = training[key]; slots, evidence, reason = select_inputs(b['candidates'], b['evidence'])
        texts = [b['candidates'][i] for i in slots]
        assert reason is None and r['texts'] == texts and r['evidence'] == evidence and r['context'] == b['context']
        assert texts[r['gold']] == b['expected'] and token_rows(r['context'], texts, vocab) is not None
        assert len(r['teacherScores']) == len(texts) and all(math.isfinite(v) for v in r['teacherScores'])
        if key in prefix:
            assert r['teacherScores'] == prefix[key]['teacherScores']
        used[key] = r
    skipped = {(r['id'], r['cut']) for r in teacher['skips']}
    assert len(used) == teacher['groups'] == train['zeroResidualGroupsEquivalent']
    assert not set(used) & skipped and set(used) | skipped == set(training)
    assert sum(key in prefix for key in used) == teacher['reusedPrefixRows']

    decision = read(out / 'development-decision.json'); reports = {}
    for domain, src in [('aishell', 'p2c-aishell-v7'), ('tatoeba', 'p2c-supervised-v6')]:
        tsv = root / f'benchmarks/corpus/{src}/dev.tsv'
        _, old = validate_baseline(root, tsv, root / f'.artifacts/input-quality/e36/{domain}-baseline.jsonl')
        features, _ = load_features(root, tsv, out / f'{domain}-features.jsonl.gz')
        result = {}; all_times = []; scored_times = []; reasons = Counter()
        for line in (out / f'{domain}-student.jsonl').read_text('utf-8').splitlines():
            row = json.loads(line); key = (row['id'], row['cut']); assert key not in result
            b = old[key]; trace = row['trace']; assert b['resultSha256'] == features[key]['resultSha256']
            assert all(row[k] == b[k] for k in ['query','context','expected'])
            assert row['beforeCandidates'] == b['candidates'] and row['beforeRank'] == b['rank']
            if trace['reason'] == 'scored':
                scores = trace['scores']; scored_times.append(row['hostNanos'])
            elif trace['reason'] == 'unknown-character':
                scores = None
                assert token_rows(b['context'], [b['candidates'][i] for i in trace['slots']], vocab) is None
            else:
                scores = None
                assert select_inputs(b['candidates'], features[key]['evidence'])[2] == trace['reason']
            actual, expected_trace = reorder_student(b['context'], b['candidates'], features[key]['evidence'],
                                                     lambda c, t, e: scores)
            assert actual == row['afterCandidates'] and trace == expected_trace
            assert set(actual) == set(b['candidates']) and len(actual) == len(b['candidates'])
            rank = actual.index(b['expected'])+1 if b['expected'] in actual else 0
            assert rank == row['afterRank']
            result[key] = dict(b,candidates=actual,rank=rank)
            assert type(row['hostNanos']) is int and row['hostNanos'] >= 0
            all_times.append(row['hostNanos']); reasons[trace['reason']] += 1
        assert result.keys() == old.keys()
        report = read(out / f'{domain}-comparison.json'); assert report == decision['domains'][domain]
        assert report['before'] == metrics(list(old.values())) and report['after'] == metrics(list(result.values()))
        assert report['qualityGate'] == nonregression(report['before'],report['after'])
        assert report['latencyAll'] == distribution(all_times) and report['latencyScored'] == distribution(scored_times)
        assert report['skipReasons'] == dict(reasons) and report['zeroResidualEquivalent'] == len(old)
        for name, predicate in [('gains',lambda b,a: b!=1 and a==1),('losses',lambda b,a:b==1 and a!=1)]:
            assert [(r['id'],r['cut']) for r in report[name]] == [k for k in old if predicate(old[k]['rank'],result[k]['rank'])]
        reports[domain] = report
    quality = all(r['qualityGate'] for r in reports.values()) and sum(r['after']['top1']-r['before']['top1'] for r in reports.values()) > 0
    cost = all(r['latencyScored']['p95Ms'] <= 100 for r in reports.values())
    assert (quality,cost) == (decision['qualityGate'],decision['hostCostGate'])
    assert not subprocess.check_output(['git','status','--porcelain','--','core-input/src/main','ime-service/src/main','ime-ui/src/main','app'],cwd=root,text=True).strip()
    release = read(root / 'benchmarks/results/release-v0.4.16-rc.4.json')
    assert digest(root / 'app/build/outputs/apk/release/app-release.apk') == release['apkSha256']
    tests = read(out / 'verified-tests.json'); assert tests == dict(tests=50,failures=0,errors=0,skipped=0)
    assert 'OK (skipped=7)' in (out / 'minimal-tests.log').read_text('utf-8-sig')

    paths = {'external/e39/'+p.relative_to(base).as_posix(): p for p in base.rglob('*')
             if p.is_file() and p.suffix not in ['.safetensors','.pyc'] and '__pycache__' not in p.parts}
    names = set(source_pins(root)) | {'tools/prepare_e39_training.py','tools/test_candidate_student.py',
        'tools/collect_e39_student.py','benchmarks/corpus/e39-student-policy.json',
        'docs/research/input-quality-stage-e39-2026-10-04.md'}
    for name in names: paths[name] = root / name
    for name in ['manifest.json','selected.jsonl','train.tsv','attribution.jsonl']:
        key = 'benchmarks/corpus/ranker-train-v1/'+name; paths[key] = root / key
    paths['.artifacts/input-quality/lm-corpus-v1/corpus.json'] = corpus / 'corpus.json'
    archive_dir = root / 'benchmarks/results/e39-evidence'; archive_dir.mkdir()
    entries = {}
    for name, p in sorted(paths.items()):
        data = p.read_bytes(); packed = gzip.compress(data,mtime=0)
        archive = archive_dir / (sha(name.encode())[:20]+'.gz'); archive.write_bytes(packed)
        assert gzip.decompress(archive.read_bytes()) == data
        entries[name] = dict(archive=archive.relative_to(root).as_posix(),sha256=sha(data),archiveSha256=sha(packed),bytes=len(data))
    manifest_path = root / 'benchmarks/results/e39-evidence-manifest.json'
    write_new(manifest_path,dict(schemaVersion=1,files=entries,
        dependencies={'e38':digest(root / 'benchmarks/results/e38-evidence-manifest.json')},
        externalStudent=dict(path=str(out / 'student.safetensors'),sha256=train['checkpointSha256'],bytes=train['checkpointBytes']),
        note='Weights retained on E, not Git/APK. Original pretrained model pins are in E38. Public train attribution retained.'))
    write_new(root / 'benchmarks/results/e39-acceptance-gate.json',dict(schemaVersion=1,stage='E39',
        decision=decision['decision'],qualityGate=quality,hostCostGate=cost,domains=reports,
        training=train,teacherGroups=teacher['groups'],teacherSkips=teacher['skipReasons'],
        tests=tests,minimalTests=dict(tests=50,passed=43,skipped=7),sourceFamiliesDisjoint=True,
        all256CandidateSetsPreserved=True,productionChanged=False,androidMeasured=False,
        releaseUnchanged='v0.4.16-rc.4',releaseApkSha256=release['apkSha256'],goalComplete=False,
        evidenceManifestSha256=digest(manifest_path),archivedFiles=len(entries)))
    print(json.dumps(dict(stage='E39',qualityGate=quality,hostCostGate=cost,archivedFiles=len(entries),tests=tests)))


if __name__ == '__main__':
    p=argparse.ArgumentParser(__doc__)
    for name in ['root','base','models']:p.add_argument(name,type=Path)
    a=p.parse_args();collect(a.root.resolve(),a.base.resolve(),a.models.resolve())
