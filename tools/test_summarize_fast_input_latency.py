import copy
import json
from pathlib import Path
import tempfile
import unittest

from summarize_fast_input_latency import summarize_fast, validate_record


def record(sequence='nihao ', expected='你好', latency=100):
    starts=[1000+i*32 for i in range(len(sequence))]
    actions=[dict(keyIndex=i,action=a,beginNs=(t+a*8)*1000000,endNs=(t+a*8)*1000000+500000,accepted=True)
             for i,t in enumerate(starts) for a in [0,1]]
    changes=[dict(uptimeMs=t+10,text=sequence[:i+1]) for i,t in enumerate(starts[:-1])]
    changes.append(dict(uptimeMs=starts[-1]+latency,text=expected))
    return dict(sequence=sequence, expected=expected, actual=expected, passed=True, composingFinished=True,
                prefixesIntact=True, firstExpectedTextMs=changes[-1]['uptimeMs'], editorChanges=changes,
                source=dict(status='ok',sourceUid=2000,targetUid=10200,keyStartMs=starts,actions=actions))


class FastInputSummaryTest(unittest.TestCase):
    def test_valid_record_has_all_real_events_and_prefixes(self):
        cadence,latency=validate_record(record(),'nihao ','你好',True)
        self.assertEqual([32]*5,cadence);self.assertEqual(100,latency)

    def test_missing_prefix_final_composition_or_actions_rejected(self):
        mutations=[lambda r:r['editorChanges'].pop(2), lambda r:r.update(composingFinished=False),
                   lambda r:r.update(actual='你'), lambda r:r['source']['actions'].pop(),
                   lambda r:r['source']['actions'][0].update(accepted=False),
                   lambda r:r.update(firstExpectedTextMs=999),
                   lambda r:r['source']['keyStartMs'].__setitem__(0,True),
                   lambda r:r['editorChanges'][1].update(uptimeMs=900)]
        for mutate in mutations:
            row=record();mutate(row)
            with self.subTest(row=row), self.assertRaises(ValueError):
                validate_record(row,'nihao ','你好',True)

    def fixture(self, directory):
        queries=[['nihao'+'a'*i, '你好'+'啊'*i] for i in range(8)]
        lock=dict(order=['baseline','optimized','optimized','baseline'],queries=queries,
                  apks={'baseline':{'sha256':'a'},'optimized':{'sha256':'b'}},helperSha256='c',
                  followupInputs=[queries[i][0]+' nihao ' for i in [3,7]])
        measurements=dict(protocol='shell-touch-v1',scope='synthetic test only',apkSha256={'baseline':'a','optimized':'b'},
                          helperSha256='c',runs=[])
        for block,mode in enumerate(lock['order'],1):
            folder=directory/str(block);(folder/'device').mkdir(parents=True)
            (folder/'instrumentation.log').write_text('OK (1 test)',encoding='utf-8')
            (folder/'system.trace').write_text('# entries-in-buffer/entries-written: 2/2\n'
                'worker-1 (123) [000] ..... 1.000: tracing_mark_write: B|123|Sense.Pinyin.decode\n'
                'worker-1 (123) [000] ..... 1.040: tracing_mark_write: E|123\n',encoding='utf-8')
            rows=[];timings=[]
            for round,items in enumerate([queries,list(reversed(queries))]):
                for query,expected in items:
                    latency=90 if mode=='optimized' else 100
                    row=record(query+' ',expected,latency);row.update(round=round,query=query);rows.append(row)
                    timings.append(dict(round=round,query=query,expectedBaselineOutput=expected,actual=expected,
                                        passed=True,spaceToEditorMs=latency))
            continued=[record(queries[i][0]+' nihao ',queries[i][1]+'你好') for i in [3,7]]
            for name,data in [('fast-input.jsonl',rows),('continued-input.jsonl',continued)]:
                (folder/'device'/name).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in data),encoding='utf-8')
            measurements['runs'].append(dict(block=block,mode=mode,artifactDirectory=str(block),passed=True,errors=[],
                                             installedApkSha256=lock['apks'][mode]['sha256'],samples=timings))
        return measurements,lock

    def test_complete_fixed_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);data,lock=self.fixture(root)
            result=summarize_fast(data,lock,root)
            self.assertTrue(result['promotionPassed']);self.assertFalse(result['stableReleaseReady'])
            self.assertEqual(32,result['comparison']['confirmationMs']['optimized']['count'])

    def test_one_failed_block_suppresses_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);data,lock=self.fixture(root);data['runs'][1]['passed']=False
            result=summarize_fast(data,lock,root)
            self.assertFalse(result['promotionPassed']);self.assertIsNone(result['comparison'])
            self.assertEqual(2,result['failures'][0]['block'])

    def test_missing_followup_or_timing_disagreement_not_promoted(self):
        for missing in [False,True]:
            with self.subTest(missing=missing),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);data,lock=self.fixture(root)
                if missing:
                    (root/'3/device/continued-input.jsonl').write_text('',encoding='utf-8')
                else:
                    data['runs'][2]['samples'][0]['spaceToEditorMs']+=1
                result=summarize_fast(data,lock,root)
                self.assertFalse(result['promotionPassed']);self.assertIsNone(result['comparison'])

    def test_different_apk_protocol_or_order_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);original,lock=self.fixture(root)
            for mutation in ['apk','protocol','order']:
                data=copy.deepcopy(original)
                if mutation=='apk':data['apkSha256']['optimized']='other'
                elif mutation=='protocol':data['protocol']='old'
                else:data['runs'].pop()
                with self.subTest(mutation=mutation),self.assertRaises(ValueError):summarize_fast(data,lock,root)


if __name__=='__main__':unittest.main()
