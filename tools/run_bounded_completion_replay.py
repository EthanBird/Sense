"""Run E23's fixed word probes and prior clean/typo regressions with current compiled core."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / '.artifacts/input-quality/e23'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    lock = json.loads((ART/'before-lock.json').read_text('utf-8'))
    current = {p.relative_to(ROOT).as_posix(): sha(p) for p in (ROOT/'core-input/src/main/kotlin').rglob('*.kt')}
    assert set(current) == set(lock['sourcePins'])
    assert {n for n in current if current[n] != lock['sourcePins'][n]} == {
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinDecoder.kt',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinSyllableSegmenter.kt'}
    assert all(sha(ROOT/'ime-service/src/main/assets'/n) == h for n,h in lock['assetPins'].items())
    java = Path('F:/Android/Jdk/jdk-17/bin/java.exe')
    stdlib = Path('C:/Users/syc/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.0/fdfc65fbc42fda253a26f61dac3c0aca335fae96/kotlin-stdlib-2.2.0.jar')
    cp = str(ROOT/'core-input/build/classes/kotlin/main') + ';' + str(stdlib)
    jobs = [('probe', 'M19ScoreFeatureBenchmark', [ROOT,ART/'prefix-probes.tsv',ART/'candidate-probe.jsonl.gz','sentences'])]
    inputs = {}
    for part in ['dev','test']:
        for domain in ['aishell','tatoeba']:
            dataset = 'p2c-aishell-v7' if domain == 'aishell' else ('p2c-supervised-v6' if part == 'dev' else 'p2c-daily-mixture-v8')
            path = ROOT/f'benchmarks/corpus/{dataset}/{part}.tsv'
            inputs[path.relative_to(ROOT).as_posix()] = sha(path)
            jobs.append((f'{part}-{domain}','M20CrossDomainBenchmark', [ROOT,path,ROOT/'ime-service/src/main/assets/pinyin_character_lm.scng',ART/f'candidate-{part}-{domain}.jsonl']))
    for dataset in ['typo-v1','typo-layered-v4']:
        path = ROOT/f'benchmarks/corpus/{dataset}/test.tsv'
        inputs[path.relative_to(ROOT).as_posix()] = sha(path)
        jobs.append((dataset,'M15TypoRecallBenchmark',[ROOT,path,ART/f'candidate-{dataset}.json','48']))
    record = ART/'candidate-replay-lock.json'
    assert not record.exists()
    record.write_text(json.dumps(dict(sources=current,inputs=inputs,assets=lock['assetPins'],
        compiledClasses={p.relative_to(ROOT/'core-input/build/classes/kotlin/main').as_posix():sha(p) for p in (ROOT/'core-input/build/classes/kotlin/main').rglob('*.class')}),indent=2)+'\n')
    for name, main, args in jobs:
        with (ART/f'candidate-{name}.log').open('w',encoding='utf-8') as log:
            subprocess.run([str(java),'-Xmx1g','-cp',cp,'io.github.ethanbird.senseime.core.'+main,*map(str,args)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
        print('Completed '+name,flush=True)
    assert all(sha(ROOT/n) == h for n,h in current.items())


if __name__ == '__main__':
    main()
