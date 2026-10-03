import copy
import json
import unittest
from evaluate_cross_domain import CONFIG, workload, load, compare, nonregression


class CrossDomainTest(unittest.TestCase):
    text='one\tnihao\t你好\tshort\tni hao\n'
    def fixture(self):
        h=dict(type='header',schemaVersion=1,mode='whole-and-midpoint',configuration=CONFIG,
               personalization='empty; no learn',inputSha256='input',sources={},assets={},modelSha256='model')
        rows=[dict(type='row',**r,rank=1,candidates=[r['expected']],hostNanos=1,firstKind='EXACT') for r in workload(self.text).values()]
        return [h,*rows,dict(type='summary',rows=2,sentences=1)]
    def load(self,items):return load([json.dumps(i) for i in items],self.text)
    def test_pair_and_actual_rank(self):
        h,b=self.load(self.fixture()); nh,n=copy.deepcopy((h,b)); n[('one',0)].update(rank=2,candidates=['拟好','你好'])
        result=compare(h,b,nh,n)
        self.assertEqual(result['before']['top1'],2);self.assertEqual(result['after']['top1'],1)
        self.assertEqual(result['after']['characterErrors'],1)
        self.assertEqual(len(result['firstLosses']),1)
        self.assertFalse(result['nonregressionPassed'])
    def test_incomplete_duplicate_wrong_context_and_rank_rejected(self):
        for kind in ['summary','duplicate','context','rank','candidate','timing','config']:
            with self.subTest(kind=kind):
                f=self.fixture()
                if kind=='summary':f.pop()
                if kind=='duplicate':f[2]=f[1]
                if kind=='context':f[2]['context']='拟'
                if kind=='rank':f[1]['rank']=0
                if kind=='candidate':f[1]['candidates']*=2
                if kind=='timing':f[1]['hostNanos']=-1
                if kind=='config':f[0]['configuration']=dict(CONFIG,limit=10)
                with self.assertRaises(ValueError):self.load(f)
    def test_workload_rejects_bad_alignment_and_duplicate_id(self):
        for t in [self.text*2,self.text.replace('nihao','ni'),'',self.text.replace('你好','你')]:
            with self.assertRaises(ValueError):workload(t)
    def test_changed_inputs_rejected_and_strict_gate_requires_real_gain(self):
        h,b=self.load(self.fixture());nh=copy.deepcopy(h);nh['assets']={'changed':True}
        with self.assertRaises(ValueError):compare(h,b,nh,b)
        r=compare(h,b,h,b)
        self.assertTrue(r['nonregressionPassed']);self.assertFalse(r['strictImprovementPassed'])
        a=dict(r['after'],top1=3,characterErrors=1)
        self.assertFalse(nonregression(r['before'],a,True))


if __name__=='__main__':unittest.main()
