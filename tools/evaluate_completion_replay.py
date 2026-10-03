"""E19 known-regression comparison: two trial sources, three for the final implementation."""
import json
import argparse
from pathlib import Path
from audit_sentence_corpus import sha256
from evaluate_cross_domain import load, compare

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e19'
CHANGED={'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/'+s for s in
         ['PinyinDecoder.kt','PinyinLanguageScorer.kt']}


def main(final=False,prefix=None):
    prefix=prefix if prefix is not None else ('final-' if final else '')
    changed=CHANGED|({'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinSyllableSegmenter.kt'} if final else set())
    lock=json.loads((ART/'before-lock.json').read_text('utf-8'))['files']
    reports={}
    for partition in ['dev','test']:
        for domain in ['aishell','tatoeba']:
            dataset='p2c-aishell-v7' if domain=='aishell' else ('p2c-supervised-v6' if partition=='dev' else 'p2c-daily-mixture-v8')
            source=ROOT/f'benchmarks/corpus/{dataset}/{partition}.tsv'
            before=ROOT/f'.artifacts/input-quality/e18/{partition}-{domain}-baseline.jsonl'
            after=ART/f'{prefix}{partition}-{domain}.jsonl'
            text=source.read_text('utf-8')
            bh,b=load(before.read_text('utf-8').splitlines(),text); nh,n=load(after.read_text('utf-8').splitlines(),text)
            assert bh['modelSha256']==nh['modelSha256']==lock['ime-service/src/main/assets/pinyin_character_lm.scng']
            assert not bh['fourgram']['enabled'] and not nh['fourgram']['enabled']
            assert all(bh['fourgram'][k]==nh['fourgram'][k] for k in ['baselineSha256','editorSnapshot'])
            assert bh['inputSha256']==nh['inputSha256']==sha256(source)
            assert bh['sources'].keys()==nh['sources'].keys()
            assert {p for p in bh['sources'] if bh['sources'][p]!=nh['sources'][p]}==changed
            for p,h in bh['sources'].items():assert lock[p]==h
            for p,h in nh['sources'].items():assert sha256(ROOT/p)==h
            # Explicit permitted treatment, not pretending to have identical implementations.
            report=compare({**bh,'sources':nh['sources']},b,nh,n)
            report['sourceChanges']={p:dict(before=bh['sources'][p],after=nh['sources'][p]) for p in sorted(changed)}
            report['scope']='Known E18 regression; source-aware completion fix, not a new held-out accuracy estimate'
            report['unusedExtensionHashes']=[bh['fourgram']['extensionSha256'],nh['fourgram']['extensionSha256']]
            report['fullProgressiveEqual']=sum(b[k]['resultSha256']==n[k]['resultSha256'] for k in b)
            report['pins']={str(p):sha256(p) for p in [before,after,source]}
            reports[f'{partition}-{domain}']=report
    def interaction(path):
        rows=[json.loads(x) for x in path.read_text('utf-8').splitlines()]
        assert rows[-1]['passed'] and rows[-1]['states']==1468
        return {(r['query'],r['context'],r['limit']):r for r in rows if r['type']=='row'}
    old=interaction(ROOT/'.artifacts/input-quality/e18/interaction.jsonl');new=interaction(ART/f'{prefix}interaction.jsonl')
    assert old.keys()==new.keys()
    changes={}
    for model,field in [('default-threegram','beforeTop5'),('optional-fourgram','afterTop5')]:
        changes[model]=[dict(query=k[0],context=k[1],limit=k[2],before=old[k][field],after=new[k][field])
                        for k in old if old[k][field][:1]!=new[k][field][:1]]
    out=dict(knownRegressions=reports,interactionFirstChanges=changes,permittedRuntimeSourceChanges=sorted(changed),
             defaultModelUnchanged=True,testLabelsAlreadyKnown=True)
    with (ART/f'{prefix}replay-comparison.json').open('x',encoding='utf-8') as f:
        json.dump(out,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps(dict(replay={k:{q:r[q] for q in ['before','after','nonregressionPassed','fullProgressiveEqual']} for k,r in reports.items()},
                         interactionFirstChanges={k:len(v) for k,v in changes.items()}),ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(__doc__);parser.add_argument('--final',action='store_true')
    parser.add_argument('--prefix',choices=['accepted-'])
    args=parser.parse_args();main(args.final,args.prefix)
