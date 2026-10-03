import hashlib
import json
import tempfile
import unittest
from zipfile import ZipFile
from audit_association_asset import audit_association
import test_audit_packaged_lm as fixtures
from test_evaluate_association_model import model


class AssociationAssetAuditTest(unittest.TestCase):
    def package(self, root, corrupt=False):
        apk=fixtures.PackagedLmAuditTest().package(root)
        binary=model([('天',[('早上',-1.)])]); dictionary=b'dictionary-fixture'
        sha=lambda b:hashlib.sha256(b).hexdigest()
        with ZipFile(apk,'a') as z:
            character=json.loads(z.read('assets/pinyin_character_lm_notice.json'))
            notice={'model':{'file':'association_words.snwp','bytes':len(binary),'sha256':sha(binary)},
                    'attribution':character['attribution'],'dictionaryDependency':{'file':'dictionary','sha256':sha(dictionary)}}
            z.writestr('assets/association_words_notice.json',json.dumps(notice))
            z.writestr('assets/association_words.snwp',binary+(b'!' if corrupt else b''))
            z.writestr('assets/dictionary',dictionary)
            z.writestr('assets/ASSOCIATION-MODEL-NOTICE.txt','authors.tsv\nRIME-FROST-NOTICE.txt\n')
        return apk

    def test_bundle_resolves_model_shared_attribution_and_dictionary(self):
        with tempfile.TemporaryDirectory() as root:
            result=audit_association(self.package(root))
            self.assertTrue(result['passed']); self.assertEqual(1,result['contexts'])

    def test_mismatched_packaged_model_fails(self):
        with tempfile.TemporaryDirectory() as root,self.assertRaises(ValueError):
            audit_association(self.package(root,corrupt=True))


if __name__=='__main__': unittest.main()
