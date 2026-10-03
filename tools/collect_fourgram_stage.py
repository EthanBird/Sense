"""Close E18: offline gate passes, default promotion held by interaction/cost evidence."""
import gzip
import json
import math
import statistics
import xml.etree.ElementTree as ET
from pathlib import Path
from collect_boundary_stage import sha, read, write_new
from evaluate_fourgram_test import ROOT, ART, EXT, DATA, inputs
from evaluate_cross_domain import load, compare
from freeze_fourgram_development import choose


def digest(p):
    return sha(p.read_bytes())


def main():
    manifest = ROOT/'benchmarks/results/e18-evidence-manifest.json'
    gate = ROOT/'benchmarks/results/e18-acceptance-gate.json'
    archive = ROOT/'benchmarks/results/e18-evidence'
    assert not any(p.exists() for p in [manifest, gate, archive])
    previous = ROOT/'benchmarks/results/e17-evidence-manifest.json'
    prior = read(ROOT/'benchmarks/results/e17-acceptance-gate.json')
    assert digest(previous) == prior['evidenceManifestSha256']
    for item in read(previous)['files'].values():
        packed = (ROOT/item['archive']).read_bytes()
        assert sha(packed) == item['archiveSha256'] and sha(gzip.decompress(packed)) == item['sha256']
    freeze, pins = inputs()
    assert choose(freeze['trials']) == 'balanced-fourgram'
    start = read(ART/'test-start.json'); decision = read(ART/'test-decision.json')
    runner = str(ROOT/'tools/evaluate_fourgram_test.py')
    assert {k:v for k,v in start['files'].items() if k != runner} == {k:v for k,v in pins.items() if k != runner}
    assert start['files'][runner] == digest(ART/'evaluate_fourgram_test-start.py') == decision['evaluatorCorrection']['before']
    assert pins[runner] == decision['evaluatorCorrection']['after']
    assert decision['selectedMode'] == freeze['selectedMode'] and decision['testGatePassed']
    assert not decision['tuningAfterTest'] and not decision['deploymentEnabled']
    for path, h in decision['pins'].items():
        assert digest(Path(path)) == h
    for domain, dataset in DATA.items():
        text = (ROOT/f'benchmarks/corpus/{dataset}/test.tsv').read_text('utf-8')
        bh, b = load((ART/f'test-{domain}-baseline.jsonl').read_text('utf-8').splitlines(), text)
        nh, n = load((ART/f'test-{domain}-balanced-fourgram.jsonl').read_text('utf-8').splitlines(), text)
        report = compare(bh,b,nh,n)
        assert report == read(ART/f'test-{domain}-comparison.json')
        assert report['nonregressionPassed'] and len(n) == 248
    assert decision['domains']['aishell']['after']['top1'] == 152
    assert decision['domains']['tatoeba']['after']['top1'] == 170
    probability = read(ART/'probability-control.json')
    assert probability['passed'] and probability['distributions'] == 70 and probability['maximumMassError'] < 1e-6
    interaction = [json.loads(line) for line in (ART/'interaction.jsonl').read_text('utf-8').splitlines()]
    end = interaction[-1]; rows = [r for r in interaction if r['type'] == 'row']
    assert end['passed'] and end['states'] == len(rows) == 1468 and end['repeatedCompleteResults'] == 2936
    assert end['personalAssertions'] == 12 and end['shortStates'] == 1188 and end['shortTop1Equal'] == 1186
    losses = [r for r in rows if len(r['query']) <= 3 and r['beforeTop5'][:1] != r['afterTop5'][:1]]
    assert len(losses) == 2 and all(r['query'] == 'wox' and r['limit'] == 255 for r in losses)

    android = read(ART/'android-art.json')
    assert 'OK (1 test)' in (ART/'android-instrumentation.log').read_text('utf-8-sig')
    assert android['repeatedResultsEqual'] and not android['factoryChanged'] and len(android['records']) == 128
    timing = {}
    for model in [0,1]:
        records = [r for r in android['records'] if r['model'] == model]
        ns = sorted(r['nanos'] for r in records)
        assert len(ns) == 64 and min(ns) > 0
        by_query = {}
        for query in {r['query'] for r in records}:
            matched = [r for r in records if r['query'] == query]
            assert len(matched) == 8 and len({r['first'] for r in matched}) == 1
            by_query[query] = dict(medianNanos=statistics.median(r['nanos'] for r in matched), first=matched[0]['first'])
        timing[str(model)] = dict(medianNanos=statistics.median(ns), p95NearestRankNanos=ns[math.ceil(.95*len(ns))-1],
                                 maximumNanos=max(ns), byQuery=by_query)
    assert timing['0']['byQuery']['wox']['first'] == '我想'
    assert timing['1']['byQuery']['wox']['first'] == '我想和你在一起'
    xmls = []; counts = {}
    for module, variant, expected in [('core-input','test',348),('ime-ui','testDebugUnitTest',235),
                                    ('ime-service','testDebugUnitTest',310),('app','testDebugUnitTest',3)]:
        files = list((ROOT/f'{module}/build/test-results/{variant}').glob('TEST-*.xml')); xmls.extend(files)
        counts[module] = {k:sum(int(ET.parse(p).getroot().get(k,0)) for p in files) for k in ['tests','failures','errors','skipped']}
        assert counts[module] == dict(tests=expected, failures=0, errors=0, skipped=0)
        log = (ART/'host-tests.log').read_text('utf-8-sig')
        assert f'> Task :{module}:{variant}\n' in log and 'BUILD SUCCESSFUL' in log
    py = (ART/'python-tests.log').read_text('utf-8-sig')
    assert 'Ran 398 tests' in py and 'OK (skipped=1)' in py
    for file, h in prior['assetPins'].items():
        assert digest(ROOT/'ime-service/src/main/assets'/file) == h
    app = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    assert digest(app) == prior['unchangedExistingApk']['sha256']
    assert 'BUILD SUCCESSFUL' in (ART/'android-build.log').read_text('utf-8-sig')

    files = {p.relative_to(ROOT).as_posix():p for p in ART.rglob('*') if p.is_file()}
    files.update({p.relative_to(ROOT).as_posix():p for p in xmls})
    for p in EXT.glob('*'):
        if p.is_file(): files['external/e18-fourgram/'+p.name] = p
    names = ['benchmarks/corpus/e18-fourgram-policy.json', 'tools/train_fourgram_extension.py',
        'tools/test_train_fourgram_extension.py','tools/build_fourgram_fixture.py','tools/freeze_fourgram_development.py',
        'tools/test_freeze_fourgram_development.py','tools/evaluate_fourgram_test.py','tools/summarize_fourgram_uncertainty.py',
        'tools/VerifyFourgramModelMass.java','tools/VerifyFourgramInteraction.java','tools/collect_fourgram_stage.py',
        'tools/evaluate_cross_domain.py','tools/summarize_ranker_uncertainty.py','tools/collect_boundary_stage.py',
        'docs/research/input-quality-stage-e18-2026-10-03.md','docs/development/input-quality-program-v2.md',
        'ime-service/src/androidTest/kotlin/io/github/ethanbird/senseime/service/FourgramModelDeviceTest.kt']
    names += ['core-input/src/main/kotlin/io/github/ethanbird/senseime/core/'+n for n in
              ['FourgramLanguageModel.kt','PinyinLanguageScorer.kt','PinyinDecoder.kt','M23FourgramBenchmark.kt']]
    names += ['core-input/src/test/kotlin/io/github/ethanbird/senseime/core/'+n for n in
              ['FourgramLanguageModelTest.kt','FourgramPinyinIntegrationTest.kt']]
    files.update({n:ROOT/n for n in names})
    for p in (ROOT/'core-input/src/test/resources/fourgram-lm').glob('*'):
        files[p.relative_to(ROOT).as_posix()] = p
    archive.mkdir(); entries = {}
    for logical, path in sorted(files.items()):
        data = path.read_bytes(); packed = gzip.compress(data,mtime=0)
        out = archive/(sha(logical.encode())[:20]+'.gz'); out.write_bytes(packed)
        assert gzip.decompress(out.read_bytes()) == data
        entries[logical] = dict(archive=out.relative_to(ROOT).as_posix(), sha256=sha(data), bytes=len(data), archiveSha256=sha(packed))
    write_new(manifest,dict(schemaVersion=1,dependency=dict(stage='E17',manifestSha256=digest(previous)),files=entries))
    main_sources = {p.relative_to(ROOT).as_posix():digest(p) for p in (ROOT/'core-input/src/main/kotlin').rglob('*.kt')}
    write_new(gate,dict(schemaVersion=1,stage='E18',decision='hold-default-promotion-short-completion-and-cost',
        developmentGatePassed=True, testGatePassed=True, shortInputPromotionGatePassed=False, deploymentEnabled=False,
        defaultFactoryChanged=False, optionalRuntimeImplemented=True, goalComplete=False, releaseReady=False,
        selectedMode=freeze['selectedMode'], test=decision['domains'], sourcePins=main_sources, assetPins=prior['assetPins'],
        primaryDevStates=768, primaryTestStates=992, testCandidatesNowKnown=True,
        interaction=end, shortInputChanges=losses, probabilityControl=probability,
        hostTests=counts,python=dict(run=398,passed=397,skipped=1),
        androidInstrumentationTests=1,androidMeasuredDecodes=128,androidMicrobenchmark=timing,
        androidUiTestsThisStage=0,appApkRebuiltThisStage=False,unchangedExistingApk=prior['unchangedExistingApk'],
        libraryTestApkSha256=digest(ROOT/'ime-service/build/outputs/apk/androidTest/debug/ime-service-debug-androidTest.apk'),
        evidenceManifestSha256=digest(manifest), archivedFiles=len(entries)))
    print(f'E18 closed: independent gate passed; default held; host=896/Python=398/ART=1; archives={len(entries)}')


if __name__ == '__main__':
    main()
