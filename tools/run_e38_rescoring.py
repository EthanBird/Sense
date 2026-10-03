"""Evaluate the first E38 task-qualified model, with the unchanged E37 scorer."""
import argparse
import json
from pathlib import Path
import platform
import subprocess
import sys
import time

from fetch_e37_model import digest
from qualified_masked_lm import QualifiedCharacterPLL
from run_e37_rescoring import evaluate_pools, validate_baseline, write_new


def selected_spec(policy, selection):
    results = selection['results']
    if not results or len(results) > len(policy['qualificationOrder']):
        raise ValueError('Missing/invalid ordered qualification results')
    for index, result in enumerate(results):
        spec = policy['qualificationOrder'][index]
        if result['model'] != spec['repo'] or result['revision'] != spec['revision']:
            raise ValueError('Model qualification order/pin changed')
        if result['passed']:
            if index != len(results) - 1 or selection['selectedModel'] != spec['repo']:
                raise ValueError('Selection ignored the first qualified model')
            return spec, result
    raise ValueError('No task-qualified model; skip Sense evaluation')


def run(root, qualification_folder, output):
    policy_file = root / 'benchmarks/corpus/e38-qualified-mlm-policy.json'
    policy = json.loads(policy_file.read_text('utf-8'))
    prequal_file = qualification_folder / 'pre-qualification-lock.json'
    prequal = json.loads(prequal_file.read_text('utf-8'))
    if digest(policy_file) != prequal['policySha256']:
        raise ValueError('Policy changed after qualification')
    for name, expected in prequal['sources'].items():
        if digest(root/name) != expected:
            raise ValueError('Qualification code changed: ' + name)
    selection_file = qualification_folder / 'model-selection.json'
    selection = json.loads(selection_file.read_text('utf-8'))
    spec, qualified = selected_spec(policy, selection)
    folder = Path(qualified['folder'])
    if digest(folder/'download-manifest.json') != qualified['manifestSha256']:
        raise ValueError('Qualified model files changed')
    head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    if subprocess.run(['git','merge-base','--is-ancestor',policy['sourceBaseCommit'],head],cwd=root).returncode:
        raise ValueError('Unexpected source history')
    sources = {d: root / policy['development'][d] for d in ['aishell','tatoeba']}
    sources['diagnostic'] = root / '.artifacts/input-quality/e28/diagnostic.tsv'
    baselines = {d: root / f'.artifacts/input-quality/e36/{d}-baseline.jsonl' for d in sources}
    validated = {d: validate_baseline(root,sources[d],baselines[d]) for d in sources}
    output.mkdir(parents=True,exist_ok=True)
    scripts = ['tools/run_e38_rescoring.py','tools/run_e37_rescoring.py','tools/qualified_masked_lm.py',
               'tools/masked_lm_rescore.py','tools/fetch_e37_model.py','tools/evaluate_cross_domain.py',
               'tools/train_candidate_ranker.py','tools/test_qualified_masked_lm.py']
    lock = dict(sourceBaseCommit=policy['sourceBaseCommit'],checkoutCommit=head,selectedModel=spec,
        policySha256=digest(policy_file),qualificationLockSha256=digest(prequal_file),
        selectionSha256=digest(selection_file),sourceFiles={s:digest(root/s) for s in scripts},
        inputs={d:dict(source=sources[d].relative_to(root).as_posix(),sourceSha256=digest(sources[d]),
                       baseline=baselines[d].relative_to(root).as_posix(),baselineSha256=digest(baselines[d])) for d in sources},
        modelManifest=json.loads((folder/'download-manifest.json').read_text('utf-8')),
        host=dict(python=sys.version,executable=sys.executable,platform=platform.platform(),processor=platform.processor()),
        sourceAndAssetsMatchCurrentProduction=True,networkInference=False,productionChanged=False)
    write_new(output/'pre-replay-lock.json',lock)
    start = time.perf_counter_ns();scorer=QualifiedCharacterPLL(folder,spec)
    startup = time.perf_counter_ns()-start
    recheck = scorer.qualify(policy['qualification']['fixtures'])
    if not recheck['passed'] or recheck['fixtures'] != qualified['fixtures']:
        raise ValueError('Qualification results drifted before accuracy replay')
    write_new(output/'model-runtime.json',dict(**scorer.metadata,constructorIncludingHashesNanos=startup,
        qualification=recheck,verification=recheck['numerics']))
    print('Qualified model and repeated fixture results verified; replay begins',flush=True)
    evaluate_pools(scorer,validated,policy_file,lock,output,'E38')


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__)
    p.add_argument('root',type=Path);p.add_argument('qualification',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();run(a.root.resolve(),a.qualification.resolve(),a.output.resolve())
