import importlib.util
import gzip
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from candidate_student import (FEATURE_COUNT, candidate_evidence, group_loss, make_model,
    initialize_student, load_features, reorder_student, select_inputs, tensors, token_rows)
from audit_score_features import f32
from fetch_e37_model import digest


def item(score=10., kind='BASE_COMPOSED'):
    return dict(kind=kind, baseline=score, features=[0.]*FEATURE_COUNT)


class StudentContractTests(unittest.TestCase):
    def setUp(self):
        self.texts = ['今天', '惊天', '京天', '很长的候选']
        self.evidence = {t: item(10.-i) for i,t in enumerate(self.texts)}
        self.vocab = {t:i for i,t in enumerate(['[CLS]','[SEP]','[PAD]','[UNK]','今','天','惊','京','这','是'])}

    def test_zero_residual_preserves_all_slots(self):
        got, trace = reorder_student('', self.texts, self.evidence, lambda _c,_t,e: [x['baseline'] for x in e])
        self.assertEqual(got, self.texts); self.assertEqual(trace['reason'], 'scored')

    def test_ties_are_stable(self):
        self.assertEqual(reorder_student('',self.texts,self.evidence,lambda *_:[0.,0.,0.])[0],self.texts)

    def test_permutation_keeps_ineligible_slots_and_membership(self):
        got,_=reorder_student('',self.texts,self.evidence,lambda *_:[0.,2.,1.])
        self.assertEqual(got,['惊天','京天','今天','很长的候选'])
        self.assertCountEqual(got,self.texts)

    def test_personal_and_corrected_sources_fall_back_whole_row(self):
        for kind in ['USER_FULL','USER_INITIALS','CORRECTED','BASE_INITIALS','BASE_PREFIX']:
            with self.subTest(kind=kind):
                self.evidence['京天']['kind']=kind
                got,trace=reorder_student('',self.texts,self.evidence,lambda *_:self.fail('model called'))
                self.assertEqual(got,self.texts);self.assertEqual(trace['reason'],'protected-source')

    def test_missing_and_nonfinite_features_do_not_call_model(self):
        for defect in ['missing','length','nan','baseline']:
            with self.subTest(defect=defect):
                data={t:item() for t in self.texts}
                if defect=='missing':del data['今天']
                if defect=='length':data['今天']['features']=[]
                if defect=='nan':data['今天']['features'][0]=math.nan
                if defect=='baseline':data['今天']['baseline']=math.inf
                got,trace=reorder_student('',self.texts,data,lambda *_:self.fail('model called'))
                self.assertEqual(got,self.texts);self.assertNotEqual(trace['reason'],'scored')

    def test_nonfinite_wrong_length_and_unknown_results_retain_order(self):
        for scores in [None,[1.,2.],[1.,math.nan,0.],[1.,math.inf,0.]]:
            with self.subTest(scores=scores):
                self.assertEqual(reorder_student('',self.texts,self.evidence,lambda *_:scores)[0],self.texts)

    def test_duplicate_candidates_rejected(self):
        with self.assertRaises(ValueError):select_inputs(['今天','今天'],self.evidence)

    def test_short_or_english_inputs_do_not_call_model(self):
        for texts in [['今','京'],['hello','你好'],[]]:
            self.assertEqual(reorder_student('',texts,{},lambda *_:self.fail('model called'))[0],texts)

    def test_inference_callback_receives_no_label(self):
        seen=[]
        def score(context,texts,evidence):
            seen.append((context,texts,evidence));return [0.]*len(texts)
        reorder_student('这是',self.texts,self.evidence,score)
        self.assertEqual(seen[0][0],'这是')
        self.assertEqual(seen[0][1],self.texts[:3])
        self.assertEqual(set(seen[0][2][0]),{'kind','baseline','features'})

    def test_tokenization_is_one_visible_input_per_candidate(self):
        encoded=token_rows('这是',self.texts[:3],self.vocab)
        self.assertEqual(len(encoded),3);self.assertEqual(len(encoded[0]),6)
        self.assertEqual(encoded[0],[0,8,9,4,5,1])

    def test_unknown_anywhere_falls_back_whole_group(self):
        self.assertIsNone(token_rows('', ['今天','明天'],self.vocab))
        self.assertIsNone(token_rows('明', ['今天','惊天'],self.vocab))

    def test_malformed_text_and_missing_special_rejected(self):
        for context,texts in [('今天是',['今天']),('', ['hello']),('', ['今天','今天天']),('', [])]:
            with self.assertRaises(ValueError):token_rows(context,texts,self.vocab)
        vocab=dict(self.vocab);del vocab['[SEP]']
        with self.assertRaises(ValueError):token_rows('', ['今天'],vocab)

    def test_kotlin_float_text_reconstructs_actual_float32_total(self):
        c=dict(kind='BASE_COMPOSED',total=20.897861,prior=12.,features=[0.]*16)
        self.assertNotEqual(c['total'],f32(c['total']))
        self.assertEqual(candidate_evidence(c)['baseline'],20.89786148071289)
        self.assertEqual(candidate_evidence(c)['features'][-1],12.)

    def test_feature_keys_align_with_production_replay(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);path=root/'export.gz'
            row=dict(type='row',id='sample',cut=0,mode='empty',stratum='short',query='jintian',context='',
                expected='今天',rank=1,resultSha256='result',winnerIndices=[0],
                pool=[dict(text='今天',kind='BASE_EXACT',total=20.897861,features=[0.]*16,prior=0.)])
            with gzip.open(path,'wt',encoding='utf-8') as f:
                f.write('{}\n'+json.dumps(row)+'\n')
            with patch('candidate_student.load_export',return_value=({'sources':{},'assets':{}},[row],{})):
                rows,_=load_features(root,root/'input.tsv',path)
            self.assertEqual(set(rows),{('sample',0)})
            self.assertEqual(rows[('sample',0)]['evidence']['今天']['baseline'],f32(20.897861))


@unittest.skipUnless(importlib.util.find_spec('torch') and importlib.util.find_spec('transformers'), 'optional ML runtime')
class StudentTensorTests(unittest.TestCase):
    def setUp(self):
        import torch
        torch.set_num_threads(2);torch.manual_seed(39)

    def test_zero_head_exact_scores_and_bounded_residual(self):
        import torch
        from transformers import BertConfig
        config=BertConfig(vocab_size=16,hidden_size=16,num_hidden_layers=1,num_attention_heads=2,intermediate_size=24)
        model=make_model(config).eval()
        inputs=tensors([[1,2,3],[1,3]], [item(20.),item(8.)],{'[PAD]':0})
        with torch.inference_mode():
            self.assertTrue(torch.equal(model(*inputs),inputs[-1]))
            model.head.weight.fill_(100.)
            scores=model(*inputs)
            self.assertTrue(torch.all((scores-inputs[-1]).abs()<=8.))

    def test_padding_uses_attention_and_base_features(self):
        ids,mask,features,baseline=tensors([[1,2,3],[1,2]], [item(5.),item(3.)], {'[PAD]':0})
        self.assertEqual(ids.tolist(),[[1,2,3],[1,2,0]])
        self.assertEqual(mask.tolist(),[[1,1,1],[1,1,0]])
        self.assertEqual(tuple(features.shape),(2,FEATURE_COUNT));self.assertEqual(baseline.tolist(),[5.,3.])

    def test_distillation_loss_gradient_and_direction(self):
        import torch
        scores=torch.tensor([0.,0.],requires_grad=True);teacher=torch.tensor([3.,-2.])
        loss=group_loss(scores,teacher,0)
        loss.backward();self.assertLess(scores.grad[0].item(),0);self.assertGreater(scores.grad[1].item(),0)
        self.assertLess(group_loss(torch.tensor([1.,-1.]),teacher,0).item(),loss.item())

    def fixture_checkpoint(self, root, wrong_positions=False):
        import torch
        from transformers import BertConfig
        config=BertConfig(vocab_size=7,hidden_size=8,num_hidden_layers=1,num_attention_heads=2,
                          intermediate_size=12,max_position_embeddings=8)
        model=make_model(config)
        state={'bert.'+k:v for k,v in model.encoder.state_dict().items()}
        state['bert.embeddings.position_ids']=torch.arange(8).expand(1,-1)+(1 if wrong_positions else 0)
        torch.save(state,root/'pytorch_model.bin')
        (root/'config.json').write_text(config.to_json_string(),encoding='utf-8')
        (root/'vocab.txt').write_text('[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\n今\n天\n',encoding='utf-8')
        manifest=dict(repo='test-fixture',revision='fixture',license='apache-2.0',files={
            name:dict(sha256=digest(root/name)) for name in ['config.json','vocab.txt','pytorch_model.bin']})
        (root/'download-manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
        return dict(repo='test-fixture',revision='fixture',weightsSha256=digest(root/'pytorch_model.bin'))

    def test_only_exact_legacy_position_buffer_is_discarded(self):
        for wrong in [False,True]:
            with self.subTest(wrong=wrong),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);spec=self.fixture_checkpoint(root,wrong)
                if wrong:
                    with self.assertRaisesRegex(ValueError,'legacy position'):initialize_student(root,spec)
                else:
                    model,_,vocab=initialize_student(root,spec)
                    self.assertEqual(model.head.weight.count_nonzero().item(),0)
                    self.assertEqual(len(vocab),7)

    def test_checkpoint_or_tokenizer_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);spec=self.fixture_checkpoint(root)
            (root/'vocab.txt').write_text('changed',encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'file changed'):initialize_student(root,spec)


if __name__=='__main__':unittest.main()
