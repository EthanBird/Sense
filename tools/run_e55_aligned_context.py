"""One frozen 2x2 boundary/sign experiment; only aligned-signed is selectable."""
import argparse
from collections import Counter
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import time

from aligned_word_context import (FrozenSegmenter, compact_segmenter, AlignedAssociationFeature,
    personal_evidence, select_guarded_inputs, reorder_guarded)
from candidate_student import candidate_evidence, load_features
from context_ranker_cv import evaluate_groups
from evaluate_cross_domain import metrics, nonregression
from fetch_e37_model import digest
from full_word_context import AssociationScorer, fit_scalar
from layered_lexicon import project_base
from project_pinyin_base import BASE_SHA256
from run_e37_rescoring import write_new, distribution
from run_e53_word_context import enrich_evaluation
from train_association_model import Segmenter, dictionary_words

VARIANTS = [('path-positive', False, False), ('aligned-positive', True, False),
            ('path-signed', False, True), ('aligned-signed', True, True)]
SOURCES = ['aligned_word_context.py', 'test_aligned_word_context.py', 'run_e55_aligned_context.py',
    'test_e55_pipeline.py', 'full_word_context.py', 'candidate_student.py', 'context_ranker_cv.py',
    'context_only_ranker.py', 'evaluate_cross_domain.py', 'run_e53_word_context.py',
    'audit_score_features.py', 'train_association_model.py', 'layered_lexicon.py',
    'project_pinyin_base.py', 'masked_lm_rescore.py', 'word_context_ranker.py',
    'train_candidate_ranker.py', 'run_e37_rescoring.py', 'fetch_e37_model.py']


def read(path):
    return json.loads(path.read_text('utf-8'))


def lines(path):
    with path.open(encoding='utf-8') as f:
        return [json.loads(line) for line in f]


def add_provenance(rows, export):
    """Bind guard evidence to the exact audited winner, not just a source label."""
    seen = set(); personal = 0
    with gzip.open(export, 'rt', encoding='utf-8') as f:
        for line in f:
            raw = json.loads(line)
            if raw['type'] != 'row':
                continue
            key = (raw['id'], raw['cut'])
            if key not in rows:
                continue
            if key in seen:
                raise ValueError('Duplicate provenance row')
            seen.add(key)
            row = rows[key]
            ev = row['evidence'] if isinstance(row['evidence'], dict) else dict(zip(row['texts'], row['evidence']))
            winners = {raw['pool'][i]['text']: raw['pool'][i] for i in raw['winnerIndices']}
            for text, item in ev.items():
                candidate = winners[text]
                if (candidate_evidence(candidate) != {k: item[k] for k in ['kind', 'baseline', 'features']}
                        or [e[0] for e in candidate['edges']] != item['words']):
                    raise ValueError('Personal provenance differs from audited winning path')
                item['personalEvidence'] = personal_evidence(candidate)
                personal += item['personalEvidence']
    if seen != rows.keys():
        raise ValueError('Missing actual-path personal provenance')
    return dict(states=len(seen), personalPaths=personal,
                limitation='Exact cancellation among known-word personal contributions is not observable in this host export.')


def evaluate(rows, scorer):
    details, after, times = [], [], []; reasons = Counter()
    for row in rows.values():
        original = row['candidates']; start = time.perf_counter_ns()
        proposed, meta = reorder_guarded(row['context'], original, row['evidence'], scorer)
        elapsed = time.perf_counter_ns() - start
        repeated, again = reorder_guarded(row['context'], original, row['evidence'], scorer)
        if proposed != repeated or meta != again or sorted(proposed) != sorted(original):
            raise ValueError('Unstable order or changed candidate membership')
        reasons[meta['reason']] += 1
        if meta['reason'] == 'scored': times.append(elapsed)
        rank = proposed.index(row['expected']) + 1 if row['expected'] in proposed else 0
        after.append({**row, 'candidates': proposed, 'rank': rank})
        details.append({k: row[k] for k in ['id', 'cut', 'mode', 'stratum', 'query', 'context', 'expected']} |
            dict(beforeRank=row['rank'], afterRank=rank, beforeCandidates=original,
                 afterCandidates=proposed, metadata=meta, hostNanos=elapsed))
    before = list(rows.values()); b, a = metrics(before), metrics(after)
    return details, dict(before=b, after=a,
        nonregression=nonregression(b, a) and b['recall255'] == a['recall255'],
        modes={mode: dict(before=metrics([r for r in before if r['mode'] == mode]),
                         after=metrics([r for r in after if r['mode'] == mode]))
               for mode in sorted({r['mode'] for r in before})},
        reasons=dict(reasons), timing=distribution(times),
        firstGains=[d for d in details if d['beforeRank'] != 1 and d['afterRank'] == 1],
        firstLosses=[d for d in details if d['beforeRank'] == 1 and d['afterRank'] != 1],
        rankLosses=[d for d in details if d['beforeRank'] and (not d['afterRank'] or d['afterRank'] > d['beforeRank'])])


def coverage(rows, feature):
    eligible = any_signal = discriminating = 0
    for row in rows.values():
        slots, evidence, reason = select_guarded_inputs(row['candidates'], row['evidence'])
        if reason: continue
        eligible += 1
        xs = [feature(row['context'], row['candidates'][i], e) for i, e in zip(slots, evidence)]
        any_signal += any(x != 0. for x in xs)
        discriminating += any(x != xs[0] for x in xs[1:])
    return dict(states=len(rows), eligible=eligible, nonzero=any_signal, discriminating=discriminating)


def run(root, out):
    start = time.perf_counter(); e53, e54 = out.parent / 'e53', out.parent / 'e54'
    policy = root / 'benchmarks/corpus/e55-aligned-word-context-policy.json'
    p = read(policy)
    if p['experiments'] != [v[0] for v in VARIANTS] or p['soleCandidate'] != 'aligned-signed' or p['controlsSelectable']:
        raise ValueError('Experiment policy changed')
    manifests = {stage: read(root / f'benchmarks/results/{stage}-evidence-manifest.json') for stage in ['e53', 'e54']}
    pins = {}
    for stage, names in [('e53', ['word-training-groups.jsonl', 'aishell-features.jsonl.gz',
                                 'tatoeba-features.jsonl.gz', 'current-diagnostics.jsonl.gz']),
                         ('e54', ['association-table.json', 'background-report.json', 'background-selection.json',
                                  'independent-count-audit-final.json'])]:
        for name in names:
            path = out.parent / stage / name
            if digest(path) != manifests[stage]['files']['raw/' + name]['sha256']:
                raise ValueError('Changed prior evidence: ' + str(path))
            pins[str(path)] = digest(path)
    for stage, name in [('e53', 'train-features.jsonl.gz'), ('e54', 'background-segments.jsonl')]:
        path = out.parent / stage / name
        entry = manifests[stage]['localLargeEvidence'][str(path)]
        if digest(path) != entry['sha256'] or path.stat().st_size != entry['bytes']:
            raise ValueError('Changed local input: ' + str(path))
        pins[str(path)] = entry['sha256']
    background = read(e54 / 'background-report.json')
    for name, sha in background['protected'].items():
        if digest(root / name) != sha: raise ValueError('Changed heldout isolation target')
    calibration = out.parent / 'e44/train/selected.json'
    if digest(calibration) != manifests['e53']['files']['train-provenance/selected.json']['sha256']:
        raise ValueError('Changed calibration source provenance')
    pins[str(calibration)] = digest(calibration)
    lexicon = root / 'ime-service/src/main/assets/pinyin_lexicon.bin'
    if digest(lexicon) != background['lexiconSha256']: raise ValueError('Changed dictionary')
    pins[str(lexicon)] = digest(lexicon)
    sources = {'tools/' + name: digest(root / 'tools' / name) for name in SOURCES}
    write_new(out / 'pre-run-lock.json', dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        sourceFiles=sources, policySha256=digest(policy), inputs=pins,
        dependencies={stage: digest(root / f'benchmarks/results/{stage}-evidence-manifest.json') for stage in manifests}))

    segments = lines(e54 / 'background-segments.jsonl')
    if len(segments) != background['backgroundSegments']: raise ValueError('Background segment count changed')
    base = project_base(lexicon.read_bytes())
    if hashlib.sha256(base).hexdigest() != BASE_SHA256: raise ValueError('Changed F1 base projection')
    original = Segmenter(dictionary_words(base))
    model, report = compact_segmenter(original, segments)
    with (out / 'tokenizer.json').open('x', encoding='utf-8', newline='\n') as f:
        json.dump(model, f, ensure_ascii=False, separators=(',', ':')); f.write('\n')
    tokenizer = FrozenSegmenter(read(out / 'tokenizer.json'))
    if any(tokenizer.units(r['text']) != r['words'] for r in segments):
        raise ValueError('Serialized tokenizer changed training paths')
    report.update(serializedPathsVerified=len(segments), tokenizerSha256=digest(out / 'tokenizer.json'),
                  tokenizerBytes=(out / 'tokenizer.json').stat().st_size,
                  combinedBytes=(out / 'tokenizer.json').stat().st_size + (e54 / 'association-table.json').stat().st_size)
    write_new(out / 'tokenizer-audit.json', report)
    print('Tokenizer', json.dumps(report), flush=True)
    del original, segments, base, model
    if report['combinedBytes'] > 16 * 1024 * 1024:
        write_new(out / 'decision.json', dict(eligibleForFurtherValidation=False, reason='size-gate',
            fitRun=False, productionChanged=False, goalComplete=False))
        return
    table = read(e54 / 'association-table.json')
    groups = lines(e53 / 'word-training-groups.jsonl')
    by_key = {(g['id'], g['cut']): g for g in groups}
    if len(by_key) != len(groups): raise ValueError('Duplicate calibration group')
    # Frozen E53 training export was fully audited there; recheck its runtime identity here.
    with gzip.open(e53 / 'train-features.jsonl.gz', 'rt', encoding='utf-8') as f:
        header = json.loads(next(f))
    current_sources = {p.relative_to(root).as_posix(): digest(p) for p in (root / 'core-input/src/main/kotlin').rglob('*.kt')}
    if header['sources'] != current_sources: raise ValueError('Calibration export is not current production')
    for name, sha in header['assets'].items():
        if digest(root / 'ime-service/src/main/assets' / name) != sha: raise ValueError('Calibration assets changed')
    provenance = add_provenance(by_key, e53 / 'train-features.jsonl.gz')
    selected = {r['id']: r for r in read(calibration) if not r['exclusion']}
    bg_selection = read(e54 / 'background-selection.json')
    calibration_families = {r['group'] for r in selected.values()}
    if calibration_families != set(bg_selection['calibrationFamilies']): raise ValueError('Family exclusion changed')
    bg_records = set(bg_selection['recordIds'])
    for g in groups:
        if selected[g['id']]['recordId'] in bg_records: raise ValueError('Calibration leakage')
        ev = dict(zip(g['texts'], g['evidence']))
        # originalTop8 also contains protected non-selected lengths; only original selected slots matter.
        slots, _, reason = select_guarded_inputs(g['originalTop8'], ev)
        if reason or slots != g['slots']: raise ValueError('Calibration eligibility changed: ' + str(reason))
    with (out / 'guarded-training-groups.jsonl').open('x', encoding='utf-8', newline='\n') as f:
        for g in groups: f.write(json.dumps(g, ensure_ascii=False, separators=(',', ':')) + '\n')
    write_new(out / 'calibration-provenance.json', provenance | dict(groups=len(groups),
        distinctFamilies=len({selected[g['id']]['group'] for g in groups}),
        allExcludedCalibrationFamilies=len(calibration_families), backgroundDisjoint=True))
    print('Provenance', json.dumps(provenance), flush=True)

    fits = {}; scorers = {}; features = {}
    for name, aligned, signed in VARIANTS:
        feature = AlignedAssociationFeature(table, tokenizer, aligned=aligned, signed=signed)
        print('Fitting', name, flush=True)
        weight, fitted = fit_scalar(groups, feature)
        scorer = AssociationScorer(feature, weight); zero = AssociationScorer(feature, 0.)
        for g in groups:
            if zero(g['context'], g['texts'], g['evidence']) != [e['baseline'] for e in g['evidence']]:
                raise ValueError('Zero residual changed baseline')
        predictions, training = evaluate_groups(groups, scorer)
        write_new(out / (name + '-fit.json'), fitted | dict(weight=weight, training=training, zeroResidualExact=True))
        write_new(out / (name + '-train-predictions.json'), predictions)
        fits[name] = dict(weight=weight, converged=fitted['converged'], fitSha256=digest(out / (name + '-fit.json')))
        scorers[name] = scorer; features[name] = feature
        print(name, json.dumps(fits[name] | dict(training=training)), flush=True)
    freeze = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), fits=fits,
        soleCandidate=p['soleCandidate'], controlsSelectable=False, policySha256=digest(policy),
        tokenizerSha256=digest(out / 'tokenizer.json'), tableSha256=digest(e54 / 'association-table.json'),
        guardedGroupsSha256=digest(out / 'guarded-training-groups.jsonl'))
    write_new(out / 'pre-dev-freeze.json', freeze)
    if not all(f['converged'] for f in fits.values()):
        write_new(out / 'decision.json', dict(eligibleForFurtherValidation=False, reason='fit-convergence',
            fitRun=True, productionChanged=False, goalComplete=False))
        return
    reports = {name: {} for name in scorers}
    for domain, folder in [('aishell', 'p2c-aishell-v7'), ('tatoeba', 'p2c-supervised-v6')]:
        export = e53 / (domain + '-features.jsonl.gz')
        rows, audit = load_features(root, root / 'benchmarks/corpus' / folder / 'dev.tsv', export)
        enrich_evaluation(rows, export)
        guard_audit = add_provenance(rows, export)
        write_new(out / (domain + '-audit.json'), dict(export=audit, personalProvenance=guard_audit))
        for name, scorer in scorers.items():
            details, result = evaluate(rows, scorer)
            result['coverage'] = coverage(rows, features[name])
            write_new(out / (name + '-' + domain + '-details.json'), details)
            write_new(out / (name + '-' + domain + '-comparison.json'), result)
            reports[name][domain] = result
            print(name, domain, json.dumps({k: result[k] for k in ['before', 'after', 'nonregression', 'timing', 'coverage']}), flush=True)
    candidate = reports[p['soleCandidate']]
    quality = (all(r['nonregression'] for r in candidate.values())
               and sum(r['after']['top1'] - r['before']['top1'] for r in candidate.values()) > 0)
    cost = all(r['timing'].get('p95Ms', float('inf')) <= 100 for r in candidate.values())
    for path, sha in sources.items():
        if digest(root / path) != sha: raise ValueError('Frozen source changed')
    for path, sha in pins.items():
        if digest(Path(path)) != sha: raise ValueError('Frozen input changed')
    if digest(policy) != freeze['policySha256']: raise ValueError('Frozen policy changed')
    decision = dict(eligibleForFurtherValidation=quality and cost, qualityGate=quality, hostCostGate=cost,
        sizeGate=True, trainingPathsExact=True, fitRun=True, soleCandidate=p['soleCandidate'], controlsSelectable=False,
        productionChanged=False, androidTested=False, goalComplete=False, totalSeconds=time.perf_counter()-start)
    write_new(out / 'decision.json', decision)
    print('Decision', json.dumps(decision), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('root', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); run(args.root.resolve(), args.output.resolve())
