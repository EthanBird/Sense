import copy
import gzip
import json
from pathlib import Path
import tempfile
import unittest

from candidate_student import candidate_evidence
from run_e53_word_context import attach_words, enrich_training


def candidate(text, words, score=2.):
    return dict(text=text, kind="BASE_COMPOSED", total=score, prior=6., features=[0.] * 16,
                edges=[[word, "code", 10, 0] for word in words])


class WordEvidenceTests(unittest.TestCase):
    def test_only_actual_winner_used_not_reference_friendly_path(self):
        losing = candidate("甲乙丙", ["甲", "乙丙"], 1.)
        winner = candidate("甲乙丙", ["甲乙", "丙"])
        row = dict(pool=[losing, winner], winnerIndices=[1], expected="甲乙丙")
        evidence = {"甲乙丙": candidate_evidence(winner)}
        self.assertEqual(attach_words(row, evidence), ["甲乙丙"])
        self.assertEqual(evidence['甲乙丙']['words'], ["甲乙", "丙"])

    def test_mismatched_score_or_path_rejected(self):
        c = candidate("甲乙", ["甲", "乙"])
        for altered in ({**c, "total": 9.}, {**c, "edges": [["甲", "code", 10, 0]]}):
            with self.assertRaises(ValueError):
                attach_words(dict(pool=[altered], winnerIndices=[0]), {"甲乙": candidate_evidence(c)})

    def test_preserves_holes_in_first_eight_slots(self):
        a, b = candidate("甲乙", ["甲", "乙"]), candidate("甲丙", ["甲", "丙"])
        other = candidate("甲乙丁", ["甲乙丁"])
        row = dict(type="row", id="one", cut=0, pool=[a, other, b], winnerIndices=[0, 1, 2])
        group = dict(id="one", cut=0, texts=["甲乙", "甲丙"],
                     evidence=[candidate_evidence(a), candidate_evidence(b)])
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory) / 'test.gz'
            with gzip.open(export, 'wt', encoding='utf-8') as f:
                f.write(json.dumps(row))
            enrich_training([group], export)
        self.assertEqual(group['slots'], [0, 2])
        self.assertEqual(group['originalTop8'], ["甲乙", "甲乙丁", "甲丙"])

    def test_missing_group_rejected(self):
        group = dict(id="one", cut=0, texts=["甲乙"], evidence=[candidate_evidence(candidate("甲乙", ["甲乙"]))])
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory) / 'test.gz'
            with gzip.open(export, 'wt', encoding='utf-8') as f:
                f.write(json.dumps(dict(type='summary')))
            with self.assertRaises(ValueError): enrich_training([group], export)


if __name__ == '__main__':
    unittest.main()
