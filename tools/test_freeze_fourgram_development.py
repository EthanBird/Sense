import unittest
from freeze_fourgram_development import choose


def d(before=10,after=10,passed=True):
    return dict(before=dict(top1=before),after=dict(top1=after,characterErrors=3,top5=15),nonregressionPassed=passed)


class FourgramSelectionTest(unittest.TestCase):
    def test_requires_improvement_without_any_domain_gate_failure(self):
        self.assertIsNone(choose({'daily-fourgram':dict(a=d(),b=d())}))
        self.assertIsNone(choose({'balanced-fourgram':dict(a=d(after=20),b=d(passed=False))}))
        self.assertEqual('daily-fourgram',choose({'daily-fourgram':dict(a=d(after=11),b=d())}))
    def test_equal_scores_prefer_daily_source_only(self):
        domains=dict(a=d(after=11),b=d())
        self.assertEqual('daily-fourgram',choose({'balanced-fourgram':domains,'daily-fourgram':domains}))


if __name__=='__main__':unittest.main()
