"""Fixed background-count / scalar-calibration experiment; no production mutation."""
import argparse
import datetime
import gzip
import json
from pathlib import Path
import subprocess

from candidate_student import load_features, select_inputs, reorder_student
from context_ranker_cv import evaluate_groups
from fetch_e37_model import digest
from full_word_context import AssociationFeature, AssociationScorer, fit_scalar
from run_e37_rescoring import write_new
from run_e53_word_context import enrich_evaluation, evaluate, attach_words
from word_context_background import prepare
from word_context_ranker import WordContextScorer, features, valid_word_key


def read(path):
    return json.loads(path.read_text('utf-8'))


def coverage(rows, feature, old_scorer):
    result = dict(states=len(rows), eligible=0, oldAny=0, oldDiscriminating=0,
                  newAny=0, newDiscriminating=0)
    details = []
    for row in rows.values():
        slots, evidence, reason = select_inputs(row['candidates'], row['evidence'])
        if reason:
            continue
        result['eligible'] += 1
        before, after = [], []
        for i, item in zip(slots, evidence):
            text = row['candidates'][i]
            before.append({k: v for k, v in features(row['context'], text, item).items()
                           if valid_word_key(k) and k in old_scorer.weights})
            after.append(feature.vector(row['context'], text, item))
        old_any, new_any = any(before), any(after)
        old_dis = any(x != before[0] for x in before[1:])
        new_dis = any(x != after[0] for x in after[1:])
        result['oldAny'] += old_any; result['newAny'] += new_any
        result['oldDiscriminating'] += old_dis; result['newDiscriminating'] += new_dis
        details.append(dict(id=row['id'], cut=row['cut'], oldAny=old_any, newAny=new_any,
                            oldDiscriminating=old_dis, newDiscriminating=new_dis))
    return result, details


def run(root, out):
    previous = out.parent / 'e53'; old_train = out.parent / 'e44/train'
    policy = root / 'benchmarks/corpus/e54-full-word-context-policy.json'
    old_manifest = read(root / 'benchmarks/results/e53-evidence-manifest.json')
    needed = ['word-training-groups.jsonl', 'joint-model.json', 'aishell-features.jsonl.gz',
              'tatoeba-features.jsonl.gz', 'current-diagnostics.jsonl.gz']
    for name in needed:
        if digest(previous / name) != old_manifest['files']['raw/' + name]['sha256']:
            raise ValueError('Changed prior actual-path evidence: ' + name)
    for name in ['selected.json', 'pre-export-lock.json']:
        if digest(old_train / name) != old_manifest['files']['train-provenance/' + name]['sha256']:
            raise ValueError('Changed calibration provenance')
    corpus = root / '.artifacts/input-quality/lm-corpus-v1'
    source_names = ['full_word_context.py', 'test_full_word_context.py', 'word_context_background.py',
        'test_word_context_background.py', 'run_e54_full_word_context.py', 'word_context_ranker.py',
        'run_e53_word_context.py', 'candidate_student.py', 'context_only_ranker.py',
        'train_association_model.py', 'prepare_p2c.py', 'layered_lexicon.py', 'project_pinyin_base.py']
    sources = {'tools/' + name: digest(root / 'tools' / name) for name in source_names}
    lock = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        policySha256=digest(policy), sourceFiles=sources,
        priorManifestSha256=digest(root / 'benchmarks/results/e53-evidence-manifest.json'),
        priorInputs={name: digest(previous / name) for name in needed},
        corpusManifestSha256=digest(corpus / 'corpus.json'))
    write_new(out / 'pre-count-lock.json', lock)
    table, background = prepare(root, out, read(old_train / 'selected.json'))
    table_file = out / 'association-table.json'
    with table_file.open('x', encoding='utf-8', newline='\n') as f:
        json.dump(table, f, ensure_ascii=False, separators=(',', ':')); f.write('\n')
    write_new(out / 'background-report.json', background)
    feature = AssociationFeature(read(table_file))
    write_new(out / 'pre-coverage-freeze.json', dict(tableSha256=digest(table_file),
        tableBytes=table_file.stat().st_size, preCountLockSha256=digest(out / 'pre-count-lock.json'),
        backgroundReportSha256=digest(out / 'background-report.json'), labelsUsedForCounts=False))
    print('Background', json.dumps({k: background[k] for k in ['backgroundRecords', 'backgroundSegments',
        'backgroundCharacters', 'calibrationFamilies', 'retainedPairs', 'excludedFamilies']}), flush=True)
    print('Table bytes', table_file.stat().st_size, flush=True)
    for name, sha in sources.items():
        if digest(root / name) != sha:
            raise ValueError('Frozen source changed: ' + name)
    rows_by_domain = {}; audits = {}; coverage_reports = {}
    old_scorer = WordContextScorer(read(previous / 'joint-model.json'))
    for domain, folder in [('aishell', 'p2c-aishell-v7'), ('tatoeba', 'p2c-supervised-v6')]:
        export = previous / (domain + '-features.jsonl.gz')
        rows, audit = load_features(root, root / 'benchmarks/corpus' / folder / 'dev.tsv', export)
        enrich_evaluation(rows, export)
        rows_by_domain[domain] = rows; audits[domain] = audit
        report, details = coverage(rows, feature, old_scorer)
        coverage_reports[domain] = report
        write_new(out / (domain + '-coverage.json'), report)
        write_new(out / (domain + '-coverage-details.json'), details)
        print('Coverage', domain, json.dumps(report), flush=True)
    coverage_pass = all(r['newDiscriminating'] > r['oldDiscriminating'] for r in coverage_reports.values())
    size_pass = table_file.stat().st_size <= 16 * 1024 * 1024
    write_new(out / 'pre-fit-gate.json', dict(coverageGate=coverage_pass, sizeGate=size_pass,
        tableSha256=digest(table_file), labelsOrRanksUsedInCoverage=False, coverage=coverage_reports))
    if not (coverage_pass and size_pass):
        write_new(out / 'decision.json', dict(eligibleForFurtherValidation=False,
            reason='coverage-or-size-gate', coverageGate=coverage_pass, sizeGate=size_pass,
            fitRun=False, productionChanged=False, goalComplete=False))
        return
    groups = [json.loads(line) for line in (previous / 'word-training-groups.jsonl').read_text('utf-8').splitlines()]
    selected = {r['id']: r for r in read(old_train / 'selected.json') if not r['exclusion']}
    counts_selection = read(out / 'background-selection.json')
    background_ids = set(counts_selection['recordIds'])
    for g in groups:
        if selected[g['id']]['recordId'] in background_ids:
            raise ValueError('Calibration record found in background')
    weight, fitted = fit_scalar(groups, feature)
    write_new(out / 'scalar-fit.json', fitted | dict(weight=weight))
    scorer = AssociationScorer(feature, weight)
    zero = AssociationScorer(feature, 0.)
    for g in groups:
        if zero(g['context'], g['texts'], g['evidence']) != [e['baseline'] for e in g['evidence']]:
            raise ValueError('Zero weight differs from baseline')
    predictions, train = evaluate_groups(groups, scorer)
    write_new(out / 'calibration-predictions.json', predictions)
    write_new(out / 'pre-rank-freeze.json', dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        weight=weight, tableSha256=digest(table_file), fitSha256=digest(out / 'scalar-fit.json'),
        trainingMetrics=train, zeroWeightExact=True))
    print('Scalar fit', json.dumps(fitted | dict(weight=weight, trainingMetrics=train)), flush=True)
    if not fitted['converged']:
        write_new(out / 'decision.json', dict(eligibleForFurtherValidation=False, reason='fit-not-converged',
            fitRun=True, productionChanged=False, goalComplete=False))
        return
    reports = {}
    for domain, rows in rows_by_domain.items():
        details, report = evaluate(rows, scorer)
        write_new(out / (domain + '-comparison.json'), report)
        write_new(out / (domain + '-details.json'), details)
        write_new(out / (domain + '-audit.json'), audits[domain])
        reports[domain] = report
        print('Rank', domain, json.dumps({k: report[k] for k in ['before', 'after', 'nonregression', 'timing']}), flush=True)
    quality = (all(r['nonregression'] for r in reports.values())
               and sum(r['after']['top1'] - r['before']['top1'] for r in reports.values()) > 0)
    cost = all(r['timing'].get('p95Ms', float('inf')) <= 100 for r in reports.values())
    write_new(out / 'decision.json', dict(eligibleForFurtherValidation=quality and cost,
        coverageGate=coverage_pass, sizeGate=size_pass, qualityGate=quality, hostCostGate=cost,
        weight=weight, tableSha256=digest(table_file), fitRun=True, productionChanged=False,
        androidTested=False, goalComplete=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('root', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); run(args.root.resolve(), args.output.resolve())
