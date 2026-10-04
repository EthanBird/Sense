import copy
import unittest

from candidate_student import candidate_evidence
from run_e45_context_calibration import attach_slots


class OriginalSlotJoin(unittest.TestCase):
    def fixture(self):
        texts=['你好','更多文字','你号']
        pool=[dict(text=t,total=3-i,features=[0.]*16,prior=0.,kind='BASE_COMPOSED') for i,t in enumerate(texts)]
        row=dict(id='a',cut=0,context='',expected='你号',resultSha256='hash',pool=pool,winnerIndices=[0,1,2])
        group=dict(id='a',cut=0,context='',resultSha256='hash',texts=['你好','你号'],gold=1,
                   evidence=[candidate_evidence(pool[i]) for i in [0,2]])
        return group,row

    def test_keeps_unequal_length_slot(self):
        group,row=self.fixture();joined=attach_slots(group,row)
        self.assertEqual(joined['slots'],[0,2]);self.assertEqual(joined['originalTop8'],['你好','更多文字','你号'])

    def test_stale_inputs_labels_and_fingerprints_rejected(self):
        group,row=self.fixture()
        for field,value in [('context','今天'),('resultSha256','stale'),('gold',0),('texts',['你号','你好'])]:
            changed=copy.deepcopy(group);changed[field]=value
            with self.assertRaises(ValueError):attach_slots(changed,row)

    def test_source_protection_is_not_bypassed(self):
        group,row=self.fixture();row['pool'][0]['kind']='USER_EXACT'
        with self.assertRaises(ValueError):attach_slots(group,row)

    def test_original_scores_are_checked(self):
        group,row=self.fixture();row['pool'][2]['total']=100
        group['evidence'][1]=candidate_evidence(row['pool'][2])
        with self.assertRaises(ValueError):attach_slots(group,row)


if __name__=='__main__':unittest.main()
