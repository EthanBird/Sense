import unittest
from freeze_boundary_development import choose


def domain(before=10,after=10,errors=3,passed=True):
    return dict(before=dict(top1=before),after=dict(top1=after,characterErrors=errors,top5=15),nonregressionPassed=passed)


class BoundarySelectionTest(unittest.TestCase):
    def test_requires_per_domain_nonregression_and_combined_strict_gain(self):
        self.assertIsNone(choose({'daily-no-boundary':dict(aishell=domain(),tatoeba=domain())}))
        self.assertIsNone(choose({'mixture-no-boundary':dict(aishell=domain(after=20),tatoeba=domain(passed=False))}))
    def test_daily_improvement_is_eligible_without_new_domain_strict_gain(self):
        self.assertEqual('daily-no-boundary',choose({'daily-no-boundary':dict(aishell=domain(),tatoeba=domain(after=11))}))
    def test_prefers_lower_error_then_single_model(self):
        equal=dict(aishell=domain(after=11),tatoeba=domain())
        self.assertEqual('daily-no-boundary',choose({'mixture-no-boundary':equal,'daily-no-boundary':equal}))
        better=dict(aishell=domain(after=11,errors=2),tatoeba=domain())
        self.assertEqual('mixture-no-boundary',choose({'mixture-no-boundary':better,'daily-no-boundary':equal}))


if __name__=='__main__':unittest.main()
