import struct
import unittest
from train_association_model import Segmenter, WordPredictor, context_key, dictionary_words, fit


class AssociationTrainingTest(unittest.TestCase):
    def test_segmentation_and_midword_suffix_never_drop_characters(self):
        s=Segmenter({'智能体':100,'输入法':80,'智能':20,'体':1,'好':2})
        self.assertEqual(['智能体','好'],s.units('智能体好'))
        events=list(s.events('智能体好'))
        self.assertEqual(['能体','体','好',''],[e[2] for e in events])
        self.assertEqual([False,False,True,True],[e[4] for e in events])
        self.assertEqual('𠮷智能体',''.join(s.units('𠮷智能体')))

    def test_model_uses_context_and_can_suppress_a_known_stop(self):
        s=Segmenter({'你好':100,'你们':100,'学习':100})
        rows=[{'segments':['你们学习','你好']}] * 5
        model=WordPredictor(*fit(rows,s))
        self.assertEqual('学习',model.suggest('你们',0,1)[0][0])
        self.assertEqual([],model.suggest('你好',0,1))
        self.assertEqual([],model.suggest('未知',0,1))

    def test_context_encoding_is_injective_and_fits_positive_long(self):
        values=[context_key(t) for t in ['好','你好','你好好','𠮷你好']]
        self.assertEqual(len(values),len(set(values)))
        self.assertTrue(all(0<=v<2**63 for v in values))

    def test_dictionary_rejects_truncation_and_drops_index_namespaces(self):
        def record(code,word):
            b=word.encode(); return bytes([len(code)])+code.encode()+bytes([1,len(b)])+b+struct.pack('>I',10)+b'\x01h\x00'
        binary=b'SPLX'+struct.pack('>HI',3,2)+record('hao','好')+record('{h','号')
        self.assertEqual({'好':10},dictionary_words(binary))
        with self.assertRaises(ValueError): dictionary_words(binary[:-1])


if __name__=='__main__': unittest.main()
