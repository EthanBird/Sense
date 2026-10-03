"""Check real host adapter repeatability, answer isolation and dictionary format handling."""
import argparse
import json
from pathlib import Path
import subprocess


def query_rows(path):
    values = [json.loads(line) for line in path.read_text('utf-8').splitlines()]
    assert values[-1]['type'] == 'summary'
    rows = [r for r in values if r['type'] == 'query']
    assert values[-1]['rows'] == len(rows)
    return [{k: v for k, v in r.items() if k != 'typingMicros'} for r in rows]


def main(artifact, external_linux, output):
    a = query_rows(artifact / 'probe-control-a.jsonl'); b = query_rows(artifact / 'probe-control-b.jsonl')
    assert len(a) == len(b) == 2 and a == b
    assert a[0]['candidates'] == a[1]['candidates']
    before = query_rows(artifact / 'test-both.jsonl'); repeated = query_rows(artifact / 'test-both-repeat.jsonl')
    assert len(before) == len(repeated) == 124
    repeat_changes = []
    for old, new in zip(before, repeated):
        assert (old['id'], old['query']) == (new['id'], new['query'])
        if old != new:
            repeat_changes.append(dict(id=old['id'], query=old['query'],
                beforeSentence=old['sentence'], afterSentence=new['sentence'],
                beforeCandidates=old['candidates'], afterCandidates=new['candidates']))
    absolute = (artifact / 'dictionary-control.txt').resolve().as_posix()
    fixture_linux = '/mnt/' + absolute[0].lower() + absolute[2:]
    command = ['wsl', '-d', 'Ubuntu', '--', 'env',
        'LD_LIBRARY_PATH=' + external_linux + '/root/usr/lib/x86_64-linux-gnu',
        external_linux + '/libime-dictionary-compat', fixture_linux]
    result = subprocess.run(command, capture_output=True, check=True)
    stdout = result.stdout.decode('utf-8'); stderr = result.stderr.decode('utf-8')
    assert stdout.splitlines() == ["你好\tni'hao\t-0.1", "奥姆 ao'mu"]
    assert 'TOTAL\t4\tACCEPTED\t2\tEXCLUDED\t2' in stderr
    report = dict(schemaVersion=1, passed=not repeat_changes, answerMutationUnchanged=2,
        freshContextRepeatUnchanged=1, completeCandidateRepeatRows=124,
        completeCandidateRepeatEqualRows=124-len(repeat_changes), repeatChanges=repeat_changes,
        dictionaryControlAccepted=2, dictionaryControlExcluded=2,
        dictionaryStdout=stdout, dictionaryStderr=stderr,
        scope='Actual WSL host binaries; synthetic controls and known replay only; no Android evidence')
    with output.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print(f'Reference controls: answer isolation and dictionary formats passed; '
          f'complete repeated lists equal={124-len(repeat_changes)}/124; overallPassed={not repeat_changes}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__); parser.add_argument('artifact', type=Path)
    parser.add_argument('external_linux'); parser.add_argument('output', type=Path)
    args = parser.parse_args(); main(args.artifact, args.external_linux, args.output)
