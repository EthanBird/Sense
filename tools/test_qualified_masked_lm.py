import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fetch_e37_model import fetch_pinned_model
from qualified_masked_lm import REQUIRED_HEAD_KEYS, require_head_keys, qualification_passed
from run_e38_rescoring import selected_spec


class QualifiedModelTest(unittest.TestCase):
    def test_complete_mlm_head(self):
        self.assertEqual(set(require_head_keys(REQUIRED_HEAD_KEYS)), REQUIRED_HEAD_KEYS)

    def test_encoder_only_or_missing_bias_rejected(self):
        for keys in [[], ['bert.embeddings.word_embeddings.weight'], REQUIRED_HEAD_KEYS - {'cls.predictions.bias'}]:
            with self.subTest(keys=keys), self.assertRaises(ValueError): require_head_keys(keys)

    def test_official_legacy_layernorm_names_are_not_missing_weights(self):
        keys = [k.replace('.LayerNorm.weight', '.LayerNorm.gamma').replace('.LayerNorm.bias', '.LayerNorm.beta')
                for k in REQUIRED_HEAD_KEYS]
        normalized = require_head_keys(keys)
        self.assertEqual(normalized['cls.predictions.transform.LayerNorm.weight'], 'cls.predictions.transform.LayerNorm.gamma')
        self.assertEqual(normalized['cls.predictions.transform.LayerNorm.bias'], 'cls.predictions.transform.LayerNorm.beta')

    def test_duplicate_modern_and_legacy_names_rejected(self):
        with self.assertRaises(ValueError): require_head_keys(list(REQUIRED_HEAD_KEYS) + ['cls.predictions.transform.LayerNorm.gamma'])

    def test_smoke_gate_six_of_eight_not_shape_only(self):
        self.assertTrue(qualification_passed([dict(hitTop5=i<6) for i in range(8)]))
        self.assertFalse(qualification_passed([dict(hitTop5=i<5) for i in range(8)]))

    def test_partial_smoke_results_rejected(self):
        with self.assertRaises(ValueError): qualification_passed([dict(hitTop5=True)] * 7)

    def test_first_qualified_model_selected_without_dev_scores(self):
        policy=dict(qualificationOrder=[dict(repo='small',revision='s'),dict(repo='teacher',revision='t')])
        selection=dict(results=[dict(model='small',revision='s',passed=False),dict(model='teacher',revision='t',passed=True)],selectedModel='teacher')
        self.assertEqual(selected_spec(policy,selection)[0]['repo'],'teacher')

    def test_model_selection_rejects_skipped_qualified_model_or_reordered_pins(self):
        policy=dict(qualificationOrder=[dict(repo='small',revision='s'),dict(repo='teacher',revision='t')])
        for results in [[dict(model='small',revision='s',passed=True),dict(model='teacher',revision='t',passed=True)],
                        [dict(model='teacher',revision='t',passed=True)],
                        [dict(model='small',revision='s',passed=False)]]:
            with self.subTest(results=results),self.assertRaises(ValueError):
                selected_spec(policy,dict(results=results,selectedModel='teacher'))

    def fixture(self, root):
        data = {'README.md': b'license: apache-2.0\n', 'pytorch_model.bin': b'fixture-only-not-loaded'}
        siblings = []
        for name, contents in data.items():
            (root / name).write_bytes(contents)
            info = dict(rfilename=name, size=len(contents),
                        blobId=hashlib.sha1(f'blob {len(contents)}\0'.encode() + contents).hexdigest())
            if name.endswith('.bin'): info['lfs'] = dict(sha256=hashlib.sha256(contents).hexdigest())
            siblings.append(info)
        return dict(sha='pinned', cardData=dict(license='apache-2.0'), siblings=siblings), data

    def fetch_fixture(self, root, metadata, data):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(metadata).encode())) as network:
            result = fetch_pinned_model(root, 'fixture/model', 'pinned', list(data), 'pytorch_model.bin',
                                        hashlib.sha256(data['pytorch_model.bin']).hexdigest())
        self.assertEqual(network.call_count, 1)  # Cached files are still verified, not re-downloaded.
        return result

    def test_cached_pinned_download_still_verifies_every_byte(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); metadata,data=self.fixture(root)
            result=self.fetch_fixture(root,metadata,data)
            self.assertEqual(result['repo'],'fixture/model')
            self.assertEqual(result['revision'],'pinned')

    def test_download_rejects_revision_license_and_lfs_mismatch(self):
        for field in ['revision','license','lfs']:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as d:
                root=Path(d);metadata,data=self.fixture(root)
                if field=='revision':metadata['sha']='wrong'
                if field=='license':metadata['cardData']['license']='missing'
                if field=='lfs':metadata['siblings'][1]['lfs']['sha256']='wrong'
                with self.assertRaises(ValueError):self.fetch_fixture(root,metadata,data)

    def test_download_rejects_corrupt_same_size_cached_file(self):
        for name in ['README.md','pytorch_model.bin']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                root=Path(d);metadata,data=self.fixture(root)
                (root/name).write_bytes(b'x'*len(data[name]))
                with self.assertRaises(ValueError):self.fetch_fixture(root,metadata,data)


if __name__ == '__main__': unittest.main()
