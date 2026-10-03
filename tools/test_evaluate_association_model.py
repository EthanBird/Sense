import struct
import unittest
from evaluate_association_model import BinaryPredictor
from train_association_model import context_key


def model(rows):
    data=bytearray(struct.pack('>4sHI',b'SNWP',1,len(rows)))
    for ctx,words in rows:
        data.extend(struct.pack('>QB',context_key(ctx),len(words)))
        for word,score in words:
            b=word.encode(); data.append(len(b));data.extend(b);data.extend(struct.pack('>f',score))
    return bytes(data)


class AssociationBinaryTest(unittest.TestCase):
    def test_longest_context_stop_row_and_unicode_boundary(self):
        p=BinaryPredictor(model([('天',[('气',-2.)]),('今天',[('早上',-1.)]),('明天',[]),('𠮷今天',[('晚上',-1.)])]))
        self.assertEqual([('早上',-1.)],p.suggest('到了今天'))
        self.assertEqual([],p.suggest('明天'))
        self.assertEqual([],p.suggest('今天 '))
        self.assertEqual([('晚上',-1.)],p.suggest('𠮷今天'))
        self.assertEqual([('气',-2.)],p.suggest('昨天'))

    def test_rejects_corrupt_or_oversized_untrusted_tables(self):
        valid=model([('天',[('早上',-1.)])])
        for bad in [valid[:-1],valid+b'0',b'',b'x'*8388609,
                    model([('天',[]),('天',[])]),model([('x',[])]),model([('天',[('abc',-1.)])]),
                    model([('天',[('早上',float('nan'))])]),model([('天',[('早上',1.)])]),
                    model([('天',[('早上',-2.),('晚上',-1.)])]),model([('天',[('早上',-1.),('早上',-1.)])])]:
            with self.subTest(length=len(bad)),self.assertRaises(ValueError): BinaryPredictor(bad)


if __name__=='__main__': unittest.main()
