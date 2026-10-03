"""Audit actual APK bytes, including shared attribution and dictionary dependency pins."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile
from audit_packaged_lm import audit, digest
from evaluate_association_model import BinaryPredictor
from layered_lexicon import project_base


def verify_dictionary_dependency(data, dependency):
    """A vocabulary extension must prove the original training dictionary byte-for-byte."""
    actual = digest(data)
    if "projection" in dependency:
        if dependency["projection"] != {"format": "SPLX/3", "operation": "remove-tier-2"}:
            raise ValueError("Unknown dictionary compatibility projection")
        runtime = dependency.get("runtimeAsset", {})
        if runtime.get("format") != "SPLX/4" or runtime.get("sha256") != actual or runtime.get("bytes") != len(data) or data[4:6] != b'\x00\x04':
            raise ValueError("Runtime dictionary differs from its compatibility pin")
        base = project_base(data)
        training = digest(base)
    else:
        training = actual
    if training != dependency["sha256"]:
        raise ValueError("Dictionary training dependency differs")
    return {"runtimeSha256": actual, "trainingBaseSha256": training, "baseProjectionVerified": "projection" in dependency}


def audit_association(apk):
    character = audit(apk)
    with ZipFile(apk) as z:
        notice=json.loads(z.read('assets/association_words_notice.json'))
        info=notice['model']; model=z.read('assets/'+info['file'])
        if digest(model)!=info['sha256'] or len(model)!=info['bytes']: raise ValueError('Association asset differs')
        parsed=BinaryPredictor(model)
        attribution=z.read('assets/'+notice['attribution']['file'])
        if digest(attribution)!=notice['attribution']['sha256'] or notice['attribution']!=character['attribution']:
            raise ValueError('Association attribution differs')
        dependency=notice['dictionaryDependency']
        dictionary_verification = verify_dictionary_dependency(z.read('assets/'+dependency['file']), dependency)
        readable=z.read('assets/ASSOCIATION-MODEL-NOTICE.txt').decode()
        if notice['attribution']['file'] not in readable or 'RIME-FROST-NOTICE.txt' not in readable:
            raise ValueError('Missing readable attribution links')
        return {'schemaVersion':1,'scope':'APK contents only, Android execution reported separately',
                'apkSha256':character['apkSha256'],'apkBytes':character['apkBytes'],'model':info,
                'modelCompressedBytes':z.getinfo('assets/'+info['file']).compress_size,'contexts':len(parsed.rows),
                'sharedAttribution':notice['attribution'],'dictionary':dependency,'dictionaryVerification':dictionary_verification,
                'characterModelAudit':character,'passed':True}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('report',type=Path);a=p.parse_args()
    result=audit_association(a.apk);a.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result))
