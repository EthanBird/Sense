import copy
import unittest
from summarize_typo_replay import compare


class TypoSummaryTest(unittest.TestCase):
    def fixture(self):
        rows=[]
        for op in ("clean","joints","delete"):
            rows.append(dict(id=op,sourceId="source",typed="niho",canonical="nihao",expected="你好",operation=op,zone="none",stratum="short",editOffset=2,
                rank=0,candidateCount=3,characterErrors=1,decodeNs=100,graphAlignedRank=1,graphCanonicalRank=1,top5=[]))
        return dict(inputSha256="same",candidateLimit=255,lmWeight=.5,assets={},graphDiagnosticLimit=48,learning=False,observations=rows)

    def test_paired_summary_preserves_losses_and_source_family_clustering(self):
        before=self.fixture();after=copy.deepcopy(before)
        before['observations'][0]['rank']=1
        after['observations'][2]['rank']=1
        result=compare(before,after)
        self.assertEqual(1,len(result['top1Losses']))
        self.assertEqual(1,len(result['top1Gains']))
        self.assertFalse(result['qualityGate']['cleanCountsPreserved'])
        self.assertEqual(1,result['syntheticFamilyWeightedTop1Delta']['families'])

    def test_missing_reordered_or_changed_inputs_are_rejected(self):
        before=self.fixture()
        with self.assertRaises(ValueError):compare(before,copy.deepcopy(before),expected_rows=4)
        for mutation in (lambda x:x['observations'].pop(),lambda x:x['observations'].reverse(),lambda x:x.update(lmWeight=1)):
            after=copy.deepcopy(before);mutation(after)
            with self.assertRaises(ValueError):compare(before,after)


if __name__=='__main__':unittest.main()
