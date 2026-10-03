import copy
import gzip
import json
from pathlib import Path
import tempfile
import unittest

from compact_m19_training import extract
import test_train_candidate_ranker as ranker_fixtures


class CompactExportTest(unittest.TestCase):
    def fixture(self, root):
        source, export, payload = ranker_fixtures.CandidateRankerTrainingTest().fixture(root)
        payload[0].update(sources={}, assets={})
        for row in payload[1:-1]:
            row['resultSha256'] = 'fixture-result'
        selected=[dict(id='sample', cohort='small', exclusion=None)]
        return source, export, payload, selected

    def save(self, path, payload):
        with gzip.open(path,'wt',encoding='utf-8') as f:
            for r in payload:f.write(json.dumps(r)+'\n')

    def test_every_row_and_path_checked_even_when_no_group_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source,export,payload,selected=self.fixture(root);self.save(export,payload)
            groups,audit=extract(root,source,export,root/'out.jsonl',selected)
            self.assertEqual(groups,[]);self.assertEqual(audit['states'],2)
            self.assertEqual(audit['actualPaths'],2);self.assertEqual(sum(audit['reasons'].values()),2)

    def test_partial_stream_changed_source_or_score_rejected(self):
        mutations=[lambda p:p.pop(),lambda p:p[0].update(inputSha256='changed'),
                   lambda p:p[1]['pool'][0].update(score=999),lambda p:p[1].update(rank=2),
                   lambda p:p.append(copy.deepcopy(p[1]))]
        for change in mutations:
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);source,export,payload,selected=self.fixture(root);change(payload);self.save(export,payload)
                with self.assertRaises(ValueError):extract(root,source,export,root/'out.jsonl',selected)

    def test_reference_is_not_inserted_to_eligible_candidates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source,export,payload,selected=self.fixture(root)
            # First state's full candidate list contains two wrong homophones.
            a=payload[1]['pool'][0];a['text']='泥号';a['edges'][0][0]='泥';a['edges'][1][0]='号'
            b=copy.deepcopy(a);b['text']='拟好';b['edges'][0][0]='拟';b['edges'][1][0]='好'
            # Independent deterministic lexicographic tie order: 拟 precedes 泥.
            payload[1].update(pool=[b,a],winnerIndices=[0,1],rank=0)
            payload[-1]['paths']=3
            self.save(export,payload)
            groups,audit=extract(root,source,export,root/'out.jsonl',selected)
            self.assertEqual(groups,[]);self.assertEqual(audit['reasons']['target-not-recalled'],1)


if __name__=='__main__':unittest.main()
