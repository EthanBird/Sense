import unittest
from prepare_aishell_corpus import strip_entities, normalize, partition_records


class AishellCorpusTest(unittest.TestCase):
    def row(self, i, text, split):
        return dict(id=str(i), text=text, partition=split, line=i, url='https://example.test/source')

    def test_markup_validation_and_nested_original_spans(self):
        text, spans = strip_entities('<[(北京)]大学>分析')
        self.assertEqual(text, '北京大学分析')
        self.assertEqual([(s['start'], s['end']) for s in spans], [(0,2),(0,2),(0,4)])
        for bad in ['<北京)', '(北京', '北京>', '[]']:
            with self.assertRaises(ValueError): strip_entities(bad)

    def test_normalization_does_not_silently_drop_english_or_markup_content(self):
        self.assertEqual(normalize('(北京)大学', lambda x:x)[0], '北京大学')
        self.assertEqual(normalize('ａｋ公司', lambda x:x)[2], 'non-Han')
        self.assertEqual(normalize('京', lambda x:x)[2], 'length')

    def test_text_family_holdout_priority_and_attribution_are_preserved(self):
        rows = [self.row(1,'甲乙丙丁戊己','train'), self.row(2,'甲乙丙丁戊已','test'),
                self.row(3,'甲乙丙丁戊己','dev'), self.row(4,'另一个完全不同的句子','train')]
        parts, attrs, audit = partition_records(rows, set(), lambda x:x)
        self.assertEqual(len(parts['test']),2);self.assertEqual(len(parts['train']),1)
        self.assertEqual(parts['dev'],[]);self.assertEqual(len(attrs),4)
        self.assertEqual(audit['reassigned-original-rows'],2)
        self.assertEqual({a['originalPartition'] for a in attrs if a['split']=='test'}, {'train','dev','test'})

    def test_protected_near_family_is_not_used_for_training_or_evaluation(self):
        parts, attrs, audit = partition_records([self.row(1,'甲乙丙丁戊己','train')], {'甲乙丙丁戊已'}, lambda x:x)
        self.assertFalse(any(parts.values()));self.assertEqual(attrs,[])
        self.assertEqual(audit['excluded-protected-family-records'],1)


if __name__ == '__main__': unittest.main()
