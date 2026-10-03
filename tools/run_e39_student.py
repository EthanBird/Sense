"""A single pinned teacher/train/evaluate student trial, with no production changes."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import random
import time

from candidate_student import (StudentScorer, group_loss, initialize_student, load_features,
    make_model, reorder_student, select_inputs, tensors, token_rows)
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from masked_lm_rescore import character_vocabulary
from qualified_masked_lm import QualifiedCharacterPLL
from run_e37_rescoring import distribution, validate_baseline, write_new
from run_e38_rescoring import selected_spec

read = lambda p: json.loads(p.read_text('utf-8'))


def pinned(root, out):
    path = root / 'benchmarks/corpus/e39-student-policy.json'
    policy = read(path)
    lock = read(out / 'pre-export-lock.json')
    if digest(path) != lock['policySha256'] or digest(out / 'train.tsv') != lock['trainSha256']:
        raise ValueError('Frozen policy or train input changed')
    return policy


def source_pins(root):
    names = ['candidate_student.py', 'run_e39_student.py', 'qualified_masked_lm.py',
             'masked_lm_rescore.py', 'train_candidate_ranker.py', 'audit_score_features.py',
             'evaluate_cross_domain.py', 'run_e37_rescoring.py', 'run_e38_rescoring.py',
             'fetch_e37_model.py']
    return {f'tools/{name}': digest(root / 'tools' / name) for name in names}


def teacher(root, out, model_root, reuse=None):
    policy = pinned(root, out)
    e38 = read(root / 'benchmarks/corpus/e38-qualified-mlm-policy.json')
    selection = read(root / '.artifacts/input-quality/e38/qualification-v2/model-selection.json')
    spec, qualified = selected_spec(e38, selection)
    rows, audit = load_features(root, out / 'train.tsv', out / 'train-features.jsonl.gz')
    cached = {}; reuse_pin = None
    if reuse is not None:
        old = read(reuse / 'pre-teacher-lock.json')
        if old['teacher'] != spec or old['featureSha256'] != digest(out / 'train-features.jsonl.gz'):
            raise ValueError('Teacher prefix model/workload changed')
        for name in ['qualified_masked_lm.py', 'masked_lm_rescore.py', 'fetch_e37_model.py']:
            key = 'tools/' + name
            if old['sourceFiles'][key] != digest(root / key):
                raise ValueError('Teacher mathematics changed')
        for line in (reuse / 'training-groups.jsonl').read_text('utf-8').splitlines():
            row = json.loads(line); key = (row['id'], row['cut'])
            if key in cached:
                raise ValueError('Duplicate teacher prefix row')
            cached[key] = row
        reuse_pin = dict(path=str(reuse), sourceSha256=digest(reuse / 'training-groups.jsonl'),
                         rows=len(cached), scope='PLL scores only, never old floating-point baseline/features')
    write_new(out / 'pre-teacher-lock.json', dict(policySha256=digest(root / 'benchmarks/corpus/e39-student-policy.json'),
        sourceFiles=source_pins(root), featureSha256=digest(out / 'train-features.jsonl.gz'), audit=audit,
        teacher=spec, student=policy['student'], noDevOutputsUsedForTraining=True, reusedPrefix=reuse_pin))
    scorer = QualifiedCharacterPLL(Path(qualified['folder']), spec)
    qualification = scorer.qualify(e38['qualification']['fixtures'])
    if not qualification['passed'] or qualification['fixtures'] != qualified['fixtures']:
        raise ValueError('Teacher qualification changed')
    _, vocab = character_vocabulary(model_root / 'minirbt-h256-446e1b4/vocab.txt')
    skips = []; groups = 0; timings = []; reused = 0; prefix_rechecks = 0
    print(f'Teacher qualified; {len(rows)} train states only', flush=True)
    with (out / 'training-groups.jsonl').open('x', encoding='utf-8', newline='\n') as f:
        for index, row in enumerate(rows.values()):
            slots, evidence, reason = select_inputs(row['candidates'], row['evidence'])
            texts = [row['candidates'][i] for i in slots]
            if not reason and row['expected'] not in texts:
                reason = 'reference-outside-eligible-pool'
            if not reason and token_rows(row['context'], texts, vocab) is None:
                reason = 'student-unknown-character'
            if not reason:
                begin = time.perf_counter_ns()
                old = cached.get((row['id'], row['cut']))
                if old is not None:
                    if old['context'] != row['context'] or old['texts'] != texts or old['gold'] != texts.index(row['expected']):
                        raise ValueError('Teacher prefix inference inputs changed')
                    scores = old['teacherScores']; reused += 1
                    if prefix_rechecks < 8:
                        if scorer(row['context'], texts) != scores:
                            raise ValueError('Teacher prefix deterministic replay differs')
                        prefix_rechecks += 1
                else:
                    scores = scorer(row['context'], texts)
                timings.append(time.perf_counter_ns() - begin)
                if scores is None:
                    reason = 'teacher-unknown-character'
                elif len(scores) != len(texts) or not all(math.isfinite(x) for x in scores):
                    raise ValueError('Invalid teacher scores')
            if reason:
                skips.append(dict(id=row['id'], cut=row['cut'], reason=reason))
            else:
                item = dict(id=row['id'], cut=row['cut'], context=row['context'], texts=texts,
                            evidence=evidence, gold=texts.index(row['expected']), teacherScores=scores)
                f.write(json.dumps(item, ensure_ascii=False, allow_nan=False) + '\n')
                f.flush(); groups += 1
            if (index + 1) % 32 == 0:
                print(f'teacher: {index+1}/{len(rows)} states, {groups} supervised groups', flush=True)
    write_new(out / 'teacher-complete.json', dict(groups=groups, states=len(rows), skips=skips,
        skipReasons=dict(Counter(s['reason'] for s in skips)), qualification=qualification,
        calls=scorer.calls, masks=scorer.masks, latency=distribution(timings),
        groupsSha256=digest(out / 'training-groups.jsonl'), sourceFiles=source_pins(root),
        featureSha256=digest(out / 'train-features.jsonl.gz'), weakLabels=True,
        reusedPrefixRows=reused, exactPrefixRechecks=prefix_rechecks, reuse=reuse_pin))
    print(f'Teacher complete: {groups} groups; {len(skips)} excluded states', flush=True)


def configure_torch():
    import torch
    torch.set_num_threads(2)
    if torch.get_num_interop_threads() != 1:
        torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(39)
    return torch


def training(root, out, model_root):
    torch = configure_torch()
    from safetensors.torch import save_file
    policy = pinned(root, out); cfg = policy['training']
    complete = read(out / 'teacher-complete.json')
    if complete['groupsSha256'] != digest(out / 'training-groups.jsonl'):
        raise ValueError('Teacher training groups changed')
    if complete['sourceFiles'] != source_pins(root):
        raise ValueError('Scoring/training code changed after teacher scores')
    groups = [json.loads(l) for l in (out / 'training-groups.jsonl').read_text('utf-8').splitlines()]
    if len(groups) != complete['groups'] or not groups:
        raise ValueError('Incomplete teacher dataset')
    start = time.perf_counter_ns()
    model, config, vocab = initialize_student(model_root / 'minirbt-h256-446e1b4', policy['student'])
    encoded = [token_rows(g['context'], g['texts'], vocab) for g in groups]
    if any(x is None for x in encoded):
        raise ValueError('Student tokenizer drift')
    initial_encoder = {k: v.detach().clone() for k, v in model.encoder.state_dict().items()}
    write_new(out / 'pre-train-lock.json', dict(policySha256=digest(root / 'benchmarks/corpus/e39-student-policy.json'),
        teacherCompleteSha256=digest(out / 'teacher-complete.json'), groupsSha256=complete['groupsSha256'],
        sourceFiles=source_pins(root), config=cfg, finalEpochOnly=True,
        runtime=dict(torch=torch.__version__, device='cpu', intraop=2, interop=1, deterministic=True)))
    zero = StudentScorer(model, vocab)
    for group in groups:
        if zero(group['context'], group['texts'], group['evidence']) != [x['baseline'] for x in group['evidence']]:
            raise ValueError('Zero residual changed baseline')
    optimizer = torch.optim.AdamW([
        dict(params=model.encoder.parameters(), lr=cfg['encoderLearningRate']),
        dict(params=model.head.parameters(), lr=cfg['headLearningRate'])], weight_decay=cfg['weightDecay'])
    history = []
    for epoch in range(cfg['epochs']):
        model.train(); order = list(range(len(groups)))
        random.Random(cfg['seed'] + epoch).shuffle(order)
        losses = []; steps = 0
        for offset in range(0, len(order), cfg['groupsPerStep']):
            batch = order[offset:offset + cfg['groupsPerStep']]
            tokens = [t for i in batch for t in encoded[i]]
            evidence = [v for i in batch for v in groups[i]['evidence']]
            inputs = tensors(tokens, evidence, vocab)
            optimizer.zero_grad(set_to_none=True)
            scores = model(*inputs)
            parts = []; position = 0
            for i in batch:
                g = groups[i]; count = len(g['texts'])
                parts.append(group_loss(scores[position:position + count], torch.tensor(g['teacherScores']),
                                         g['gold'], cfg['teacherTemperature']))
                position += count
            loss = torch.stack(parts).mean()
            if not torch.isfinite(loss):
                raise ValueError('Nonfinite training loss')
            loss.backward()
            norm = torch.nn.utils.clip_grad_norm_(model.parameters(), cfg['gradientClip'], error_if_nonfinite=True)
            optimizer.step(); losses += [p.item() for p in parts]; steps += 1
            if steps % 32 == 0:
                print(f'epoch {epoch+1}/{cfg["epochs"]}: {offset+len(batch)}/{len(order)} groups', flush=True)
        entry = dict(epoch=epoch+1, groups=len(order), steps=steps, meanTrainLoss=sum(losses)/len(losses))
        history.append(entry); print(json.dumps(entry), flush=True)
    changed = sum(not torch.equal(v, initial_encoder[k]) for k, v in model.encoder.state_dict().items())
    if changed == 0 or torch.count_nonzero(model.head.weight).item() == 0:
        raise ValueError('Training failed to update encoder/head')
    model.eval()
    checkpoint = out / 'student.safetensors'
    if checkpoint.exists():
        raise ValueError('Preserve existing checkpoint')
    save_file(model.state_dict(), str(checkpoint))
    write_new(out / 'student-config.json', config.to_dict())
    write_new(out / 'training-complete.json', dict(history=history, finalEpoch=cfg['epochs'],
        zeroResidualGroupsEquivalent=len(groups), changedEncoderTensors=changed,
        parameters=sum(p.numel() for p in model.parameters()),
        parameterBytes=sum(p.numel()*p.element_size() for p in model.parameters()),
        checkpointBytes=checkpoint.stat().st_size, checkpointSha256=digest(checkpoint),
        configSha256=digest(out / 'student-config.json'), vocabSha256=digest(model_root / 'minirbt-h256-446e1b4/vocab.txt'),
        elapsedNanos=time.perf_counter_ns()-start, sourceFiles=source_pins(root),
        trainingGroupsSha256=complete['groupsSha256'], noDevCheckpointSelection=True))
    print('Final-epoch checkpoint saved; no dev scores used for selection', flush=True)


def evaluate(root, out, model_root):
    torch = configure_torch()
    from safetensors.torch import load_file
    from transformers import BertConfig
    pinned(root, out)
    complete = read(out / 'training-complete.json')
    for name, key in [('student.safetensors', 'checkpointSha256'), ('student-config.json', 'configSha256')]:
        if digest(out / name) != complete[key]:
            raise ValueError('Trained checkpoint identity changed')
    if complete['sourceFiles'] != source_pins(root):
        raise ValueError('Implementation changed after training')
    _, vocab = character_vocabulary(model_root / 'minirbt-h256-446e1b4/vocab.txt')
    if digest(model_root / 'minirbt-h256-446e1b4/vocab.txt') != complete['vocabSha256']:
        raise ValueError('Trained vocabulary changed')
    config = BertConfig.from_json_file(str(out / 'student-config.json'))
    config._attn_implementation = 'eager'
    model = make_model(config); model.load_state_dict(load_file(str(out / 'student.safetensors')), strict=True)
    scorer = StudentScorer(model, vocab)
    validation = {}
    for domain, source in [('aishell', 'p2c-aishell-v7'), ('tatoeba', 'p2c-supervised-v6')]:
        tsv = root / f'benchmarks/corpus/{source}/dev.tsv'
        _, baseline = validate_baseline(root, tsv, root / f'.artifacts/input-quality/e36/{domain}-baseline.jsonl')
        features, audit = load_features(root, tsv, out / f'{domain}-features.jsonl.gz')
        if set(features) != set(baseline):
            raise ValueError('Baseline/feature states differ')
        for key, row in baseline.items():
            if row['resultSha256'] != features[key]['resultSha256']:
                raise ValueError('Observed progressive result differs from production baseline')
        validation[domain] = (baseline, features, audit)
    write_new(out / 'pre-dev-lock.json', dict(sourceFiles=source_pins(root), checkpointSha256=complete['checkpointSha256'],
        inputs={d: dict(featureSha256=digest(out / f'{d}-features.jsonl.gz'), audit=validation[d][2],
                       baselineSha256=digest(root / f'.artifacts/input-quality/e36/{d}-baseline.jsonl')) for d in validation}))
    # Warm up on a train group, never on a held-out label.
    first = json.loads((out / 'training-groups.jsonl').read_text('utf-8').splitlines()[0])
    a = scorer(first['context'], first['texts'], first['evidence'])
    b = scorer(first['context'], first['texts'], first['evidence'])
    if a != b:
        raise ValueError('Student inference is not repeatable')
    reports = {}
    for domain, (baseline, features, audit) in validation.items():
        results = []; traces = []; timings = []; changes = []; zero_equivalent = 0
        with (out / f'{domain}-student.jsonl').open('x', encoding='utf-8', newline='\n') as f:
            for key, row in baseline.items():
                evidence = features[key]['evidence']
                zero, _ = reorder_student(row['context'], row['candidates'], evidence,
                                            lambda _c, _t, e: [x['baseline'] for x in e])
                if zero != row['candidates']:
                    raise ValueError('Zero residual changed held-out baseline order')
                zero_equivalent += 1
                start = time.perf_counter_ns()
                candidates, trace = reorder_student(row['context'], row['candidates'], evidence, scorer)
                elapsed = time.perf_counter_ns() - start
                if len(candidates) != len(row['candidates']) or set(candidates) != set(row['candidates']):
                    raise ValueError('Student lost candidates')
                rank = candidates.index(row['expected']) + 1 if row['expected'] in candidates else 0
                result = dict(id=row['id'], cut=row['cut'], context=row['context'], query=row['query'],
                    expected=row['expected'], beforeRank=row['rank'], afterRank=rank,
                    beforeCandidates=row['candidates'], afterCandidates=candidates, trace=trace, hostNanos=elapsed)
                f.write(json.dumps(result, ensure_ascii=False, allow_nan=False)+'\n')
                if rank != row['rank']:
                    changes.append({k: v for k,v in result.items() if k not in ['beforeCandidates','afterCandidates','trace']}
                                   | dict(beforeTop5=row['candidates'][:5], afterTop5=candidates[:5]))
                results.append(dict(row, candidates=candidates, rank=rank)); traces.append(trace); timings.append(elapsed)
        before = metrics(list(baseline.values())); after = metrics(results)
        reports[domain] = dict(before=before, after=after, qualityGate=nonregression(before, after),
            latencyAll=distribution(timings), latencyScored=distribution([n for n,t in zip(timings,traces) if t['reason']=='scored']),
            skipReasons=dict(Counter(t['reason'] for t in traces)), zeroResidualEquivalent=zero_equivalent,
            gains=[r for r in changes if r['afterRank']==1], losses=[r for r in changes if r['beforeRank']==1],
            changes=changes, allCandidateMembershipUnchanged=True)
        write_new(out / f'{domain}-comparison.json', reports[domain])
        print(json.dumps(dict(domain=domain, before=before, after=after, latency=reports[domain]['latencyScored']), ensure_ascii=False), flush=True)
    quality = all(r['qualityGate'] for r in reports.values()) and sum(r['after']['top1']-r['before']['top1'] for r in reports.values()) > 0
    cost = all(r['latencyScored'].get('p95Ms', float('inf')) <= 100 for r in reports.values())
    write_new(out / 'development-decision.json', dict(stage='E39', qualityGate=quality, hostCostGate=cost,
        decision='eligible-for-separate-regression' if quality and cost else 'reject-fixed-student',
        domains=reports, checkpointSha256=complete['checkpointSha256'], repeatedScoresEqual=True,
        hostTimingOnly=True, androidMeasured=False, productionChanged=False, knownDevelopmentNotBlind=True,
        teacherInferenceAtRuntime=False, noParameterSweep=True, oneForwardPerCandidate=True))
    print(f'E39 gate quality={quality}, host cost={cost}', flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('phase', choices=['teacher', 'train', 'evaluate'])
    p.add_argument('root', type=Path); p.add_argument('out', type=Path); p.add_argument('models', type=Path)
    p.add_argument('--reuse-teacher-prefix', type=Path)
    a = p.parse_args()
    if a.phase == 'teacher':
        teacher(a.root.resolve(), a.out.resolve(), a.models.resolve(), a.reuse_teacher_prefix)
    else:
        if a.reuse_teacher_prefix:
            p.error('Prefix reuse belongs only to teacher preparation')
        {'train': training, 'evaluate': evaluate}[a.phase](a.root.resolve(), a.out.resolve(), a.models.resolve())
