import copy
import json
import math
from pathlib import Path
import tempfile
import unittest

from aligned_word_context import reorder_guarded
from static_word_lm_ranker import HEADER, StaticWordScorer, centered_values, fit, identity, load_scores, requests, write_requests


def records():
    result = {}
    for text, score in [('甲乙', -3.), ('甲丙', -1.)]:
        result['',text] = dict(type='row',id=identity('',text),context='',text=text,log10Score=score,unknowns=0,
                              words=list(text),states=3,transitions=2,nanos=100,directExact=True)
    return result


def group():
    texts = ['甲乙','甲丙']
    return dict(id='synthetic',cut=0,context='',texts=texts,gold=1,originalTop8=texts,slots=[0,1],
        evidence=[dict(kind='BASE_COMPOSED',features=[0.]*17,baseline=.1-i*.1,personalEvidence=False) for i in range(2)])


class StaticWordLmTests(unittest.TestCase):
    def test_bounded_request_identity_and_reference_independence(self):
        g=group(); before=requests([g]); g['gold']=0; g['id']='unrelated'
        self.assertEqual(before,requests([g])); self.assertEqual(len(before),2)
        for ctx,text in [('三个字','甲乙'),('','甲'),('','latin')]:
            with self.assertRaises(ValueError):identity(ctx,text)

    def test_centering_and_common_offset_invariance(self):
        rs=records(); texts=group()['texts']; expected=centered_values('',texts,rs)
        self.assertAlmostEqual(math.fsum(expected),0.)
        changed=copy.deepcopy(rs)
        for row in changed.values():row['log10Score']+=50.
        self.assertEqual(expected,centered_values('',texts,changed))

    def test_zero_weight_identity_bounded_residual_and_stable_order(self):
        g=group(); rs=records()
        self.assertEqual(StaticWordScorer(rs,0.)('',g['texts'],g['evidence']),[.1,0.])
        score=StaticWordScorer(rs,4.)('',g['texts'],g['evidence'])
        self.assertTrue(all(abs(s-e['baseline'])<8. for s,e in zip(score,g['evidence'])))
        for bad in [-1.,5.,float('inf')]:
            with self.assertRaises(ValueError):StaticWordScorer(rs,bad)

    def test_one_unknown_candidate_preserves_whole_group(self):
        g=group();rs=records();rs['','甲丙']['unknowns']=1
        result,meta=reorder_guarded('',g['texts'],dict(zip(g['texts'],g['evidence'])),StaticWordScorer(rs,1.))
        self.assertEqual(result,g['texts']);self.assertEqual(meta['reason'],'unknown-character')

    def test_personal_guard_precedes_native_score_use(self):
        g=group();g['evidence'][0]['personalEvidence']=True
        result,meta=reorder_guarded('',g['texts'],dict(zip(g['texts'],g['evidence'])),StaticWordScorer({},1.))
        self.assertEqual(result,g['texts']);self.assertEqual(meta['reason'],'personalized-path')

    def test_scalar_fit_improves_controlled_signal(self):
        g=group();weight,eligible,report=fit([g],records())
        self.assertTrue(report['converged']);self.assertEqual(eligible,[g]);self.assertGreater(weight,0.)
        scores=StaticWordScorer(records(),weight)('',g['texts'],g['evidence']);self.assertGreater(scores[1],scores[0])

    def test_score_stream_exact_coverage_and_mutation_rejected(self):
        rs=records();req=requests([group()]);rows=[HEADER,*rs.values(),dict(type='summary',rows=2)]
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'scores.jsonl';input_path=Path(directory)/'requests.tsv'
            write_requests(input_path,req)
            self.assertEqual(len(input_path.read_text('utf-8').splitlines()),2)
            def write(data):path.write_text('\n'.join(json.dumps(r) for r in data),encoding='utf-8')
            write(rows);self.assertEqual(load_scores(path,req),rs)
            for field,value in [('log10Score',float('nan')),('words',['甲']),('unknowns',-1),('directExact',False),('id','wrong'),('nanos',-1)]:
                bad=copy.deepcopy(rows);bad[1][field]=value;write(bad)
                with self.assertRaises(ValueError):load_scores(path,req)
            write([HEADER,rows[1],dict(type='summary',rows=1)])
            with self.assertRaises(ValueError):load_scores(path,req)


if __name__=='__main__':unittest.main()
