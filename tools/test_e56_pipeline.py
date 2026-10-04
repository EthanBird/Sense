import copy
import unittest
from run_e56_regression import export_prefix
from run_e56_static_word_lm import dev_requests, same_scores


class E56PipelineTests(unittest.TestCase):
    def test_export_prefix_excludes_original_root_and_workload(self):
        main='io.github.ethanbird.senseime.core.M19ScoreFeatureBenchmark'
        command=['java','-Xmx1g','-Dfile.encoding=UTF-8','-cp','jar;stdlib',main,'old-root','old.tsv','old.gz','sampled']
        self.assertEqual(export_prefix(command),command[:6])
        for bad in [command[:5],command+['-cp','other.jar'],command+[main]]:
            with self.assertRaises(ValueError):export_prefix(bad)

    def test_native_request_excludes_protected_rows_and_reference(self):
        row=dict(context='',candidates=['甲乙','甲丙'],expected='甲丙',rank=2,
            evidence={t:dict(kind='BASE_COMPOSED',features=[0.]*17,baseline=1.,personalEvidence=False) for t in ['甲乙','甲丙']})
        rows={('a',0):row};before=dev_requests(rows)
        row['expected']='无关';row['rank']=99
        self.assertEqual(before,dev_requests(rows));self.assertEqual(len(before),2)
        row['evidence']['甲乙']['personalEvidence']=True
        self.assertEqual(dev_requests(rows),{})

    def test_repeat_ignores_only_timing_not_score_or_path(self):
        a={('','甲乙'):dict(log10Score=-2.,words=['甲','乙'],unknowns=0,nanos=1)}
        b=copy.deepcopy(a);b['','甲乙']['nanos']=10
        same_scores(a,b)
        for field,value in [('log10Score',-3.),('words',['甲乙']),('unknowns',1)]:
            bad=copy.deepcopy(b);bad['','甲乙'][field]=value
            with self.assertRaises(ValueError):same_scores(a,bad)


if __name__=='__main__':unittest.main()
