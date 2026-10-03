import unittest
from freeze_mixture_development import choose


def result(top1,errors,top5,passed=True):
    return dict(after=dict(top1=top1,characterErrors=errors,top5=top5),strictImprovementPassed=passed,nonregressionPassed=passed)


class MixtureSelectionTest(unittest.TestCase):
    def test_no_cross_domain_tradeoff_can_bypass_gate(self):
        trials={'0.5':dict(aishell=result(120,1,125),tatoeba=result(82,74,110,False))}
        self.assertIsNone(choose(trials))

    def test_selection_tiebreak_is_predeclared_and_numeric(self):
        equal=dict(aishell=result(70,90,100),tatoeba=result(83,70,113))
        self.assertEqual('0.1',choose({'0.5':equal,'0.25':equal,'0.1':equal}))
        better=dict(aishell=result(71,90,100),tatoeba=result(83,70,113))
        self.assertEqual('0.5',choose({'0.1':equal,'0.5':better}))

    def test_error_and_top5_tiebreaks_before_alpha(self):
        base=dict(aishell=result(70,90,100),tatoeba=result(83,70,113))
        fewer=dict(aishell=result(70,89,100),tatoeba=result(83,70,113))
        higher=dict(aishell=result(70,89,101),tatoeba=result(83,70,113))
        self.assertEqual('0.5',choose({'0.1':base,'0.25':fewer,'0.5':higher}))


if __name__=='__main__':unittest.main()
