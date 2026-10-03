import math
from pathlib import Path
import tempfile
import unittest
from train_character_lm import train_model,write_model,read_model,BOS,EOS,UNK
from train_fourgram_extension import fit,encode,decode


class FourgramExtensionTest(unittest.TestCase):
    def fixture(self):
        segments=['甲乙丙丁']*8+['戊乙丙己']*8+['乙丙丁甲','戊乙丙丁','𠀀乙丙丁']*2
        base=train_model(segments,min_char_count=1,min_trigram_count=1)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'base.scng';write_model(base,p);data=p.read_bytes()
        return segments,read_model(data),data
    def test_third_context_disambiguates_same_trigram_history(self):
        segments,base,data=self.fixture();m,_=fit(base,segments)
        self.assertGreater(m.log_probability(*map(ord,'甲乙丙丁')),m.log_probability(*map(ord,'戊乙丙丁')))
        self.assertGreater(m.log_probability(*map(ord,'戊乙丙己')),m.log_probability(*map(ord,'甲乙丙己')))
    def test_pruned_mass_and_binary_roundtrip_remain_normalized(self):
        segments,base,data=self.fixture();m,_=fit(base,segments,min_fourgram_count=3);loaded=decode(encode(m,data),data)
        for context in [tuple(map(ord,'甲乙丙')),tuple(map(ord,'𠀀乙丙')),(BOS,BOS,BOS),(UNK,ord('乙'),ord('丙'))]:
            self.assertAlmostEqual(1,sum(math.exp(loaded.log_probability(*context,w)) for w in base.unigrams),places=6)
            for w in base.unigrams:self.assertAlmostEqual(m.log_probability(*context,w),loaded.log_probability(*context,w),places=5)
    def test_bos_unknown_and_missing_contexts_return_exact_lower_scores(self):
        segments,base,data=self.fixture();m,_=fit(base,segments)
        for a,b,c in [(BOS,BOS,BOS),(BOS,ord('甲'),ord('乙')),(UNK,ord('乙'),ord('丙')),tuple(map(ord,'丁己甲'))]:
            for w in base.unigrams:self.assertEqual(base.log_probability(b,c,w),m.log_probability(a,b,c,w))
    def test_corrupt_base_hash_and_structure_are_rejected(self):
        segments,base,data=self.fixture();m,_=fit(base,segments);encoded=encode(m,data)
        for changed in [encoded[:12],encoded[:-1],encoded+b'x',b'BAD!'+encoded[4:],encoded[:6]+bytes(32)+encoded[38:]]:
            with self.assertRaises(ValueError):decode(changed,data)
        with self.assertRaises(ValueError):decode(encoded,data+b'x')
    def test_zero_retained_contexts_and_invalid_training_parameters(self):
        segments,base,data=self.fixture();m,stats=fit(base,['甲乙'],min_fourgram_count=100)
        self.assertEqual(0,stats['retainedFourgrams']);self.assertEqual({},decode(encode(m,data),data).backoffs)
        for discount,cutoff in [(0,2),(1,2),(float('nan'),2),(.75,0)]:
            with self.assertRaises(ValueError):fit(base,segments,discount,cutoff)


if __name__=='__main__':unittest.main()
