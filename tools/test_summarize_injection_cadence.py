import copy
import unittest
from summarize_injection_cadence import CASES, CHANNELS, summarize


def fixture():
    rows=[]
    for b,channel in enumerate(CHANNELS):
        for q,expected in CASES:
            starts=[1000+i*32 for i in range(len(q)+1)]
            actions=[dict(keyIndex=i,action=a,beginNs=(s+a*8)*1000000,endNs=(s+a*8)*1000000+100000,accepted=True)
                     for i,s in enumerate(starts) for a in [0,1]]
            changes=[dict(uptimeMs=starts[i-1]+4,text=q[:i]) for i in range(1,len(q)+1)]
            changes.append(dict(uptimeMs=starts[-1]+17,text=expected))
            rows.append(dict(block=b,channel=channel,query=q,expected=expected,actual=expected,targetIntervalMs=32,
                             keyStartMs=starts,actions=actions,editorChanges=changes,firstCommitMs=starts[-1]+17,
                             passed=True,prefixesIntact=True,composingFinished=True))
    return rows


class InjectionCadenceTest(unittest.TestCase):
    def test_complete_delivery_and_independent_clocks(self):
        result=summarize(fixture());self.assertEqual(12,result['completeBursts'])
        self.assertFalse(result['productSpeedupClaim'])
        self.assertEqual(32,result['channels']['input-manager']['senderIntervalsMs']['median'])
        self.assertEqual(4,result['channels']['input-manager']['prefixDelaysMs']['median'])
        self.assertEqual(.1,result['channels']['input-manager']['actionCallsMs']['median'])

    def rejected(self, mutate):
        rows=copy.deepcopy(fixture());mutate(rows)
        with self.assertRaises(ValueError):summarize(rows)

    def test_partial_batch_rejected(self):self.rejected(lambda rows:rows.pop())
    def test_duplicate_batch_rejected(self):self.rejected(lambda rows:rows.append(rows[0]))
    def test_incorrect_output_rejected(self):self.rejected(lambda rows:rows[0].update(actual='您好'))
    def test_prefix_loss_rejected_despite_claimed_pass(self):self.rejected(lambda rows:rows[0]['editorChanges'].pop(1))
    def test_duplicate_action_rejected(self):self.rejected(lambda rows:rows[0]['actions'].append(rows[0]['actions'][0]))
    def test_impossible_timestamp_rejected(self):self.rejected(lambda rows:rows[0]['actions'][0].update(endNs=0))
    def test_composing_not_finished_rejected(self):self.rejected(lambda rows:rows[0].update(composingFinished=False))
    def test_unaccepted_event_rejected(self):self.rejected(lambda rows:rows[0]['actions'][0].update(accepted=False))
    def test_editor_before_source_rejected(self):self.rejected(lambda rows:rows[0]['editorChanges'][0].update(uptimeMs=500))
    def test_stale_extra_text_rejected(self):self.rejected(lambda rows:rows[0]['editorChanges'].append(dict(uptimeMs=9999,text='stale')))


if __name__=='__main__':unittest.main()
