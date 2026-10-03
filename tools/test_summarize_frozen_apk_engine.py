import copy
import unittest

from summarize_frozen_apk_engine import summarize


class FrozenApkEngineSummaryTest(unittest.TestCase):
    def fixture(self):
        queries=[f'q{i}' for i in range(10)]
        order=['reference','candidate','candidate','reference']
        lock={'protocol':{'order':order,'queries':queries},'assetPins':{'dict':'hash'},
              'referenceApk':{'sha256':'a'},'candidateApk':{'sha256':'b'}}
        measurements={'runs':[]};blocks=[]
        for block,mode in enumerate(order,1):
            measurements['runs'].append(dict(block=block,mode=mode,passed=True))
            header=dict(type='header',mode=mode,apkSha256=lock[mode+'Apk']['sha256'],assets=lock['assetPins'],
                        configuration=dict(lmWeight=.5,oovFeature=-4,limit=255,learning=False),queries=queries,repetitions=6,pid=block)
            rows=[dict(round=r,query=q,passed=True,stable=True,actual='word',expected='word',resultSha256=q,
                       wallNs=2000000 if r==0 else 1000000,threadCpuNs=500000)
                  for r in range(6) for q in (queries if r%2==0 else list(reversed(queries)))]
            blocks.append([header,*rows,dict(type='summary',rows=60,passed=True)])
        return measurements,blocks,lock

    def test_separates_first_pass_and_repeats_without_calling_them_ui_timings(self):
        result=summarize(*self.fixture())
        self.assertEqual(240,result['calls'])
        self.assertEqual(20,result['firstPass']['reference']['wallNs']['count'])
        self.assertEqual(100,result['repeated']['candidate']['wallNs']['count'])
        self.assertEqual(1,result['repeated']['reference']['wallNs']['medianMs'])
        self.assertTrue(all(r['fullCandidateEqual'] for r in result['perQuery']))

    def test_incomplete_or_failed_runs_are_rejected(self):
        a,b,c=self.fixture(); a['runs'][1]['passed']=False
        with self.assertRaises(ValueError):summarize(a,b,c)
        a,b,c=self.fixture(); b[0].pop(1)
        with self.assertRaises(ValueError):summarize(a,b,c)

    def test_wrong_source_configuration_pid_or_order_are_rejected(self):
        for change in ['apk','model','pid','order']:
            a,b,c=self.fixture()
            if change=='apk':b[1][0]['apkSha256']='wrong'
            if change=='model':b[1][0]['configuration']['lmWeight']=0
            if change=='pid':b[1][0]['pid']=b[0][0]['pid']
            if change=='order':b[1][1],b[1][2]=b[1][2],b[1][1]
            with self.assertRaises(ValueError):summarize(a,b,c)

    def test_same_variant_must_keep_entire_candidate_identity_across_blocks(self):
        a,b,c=self.fixture();b[2][1]['resultSha256']='changed'
        with self.assertRaises(ValueError):summarize(a,b,c)


if __name__=='__main__':unittest.main()
