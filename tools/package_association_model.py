"""Promote only the frozen and evaluated continuation asset; retain its training provenance."""
import argparse
import json
from pathlib import Path
from audit_sentence_corpus import sha256
from evaluate_association_model import BinaryPredictor
from audit_association_asset import verify_dictionary_dependency, digest


def dictionary_dependency(data, training_sha):
    dependency = {'file': 'pinyin_lexicon.bin', 'sha256': training_sha,
                  'license': 'GPL-3.0', 'notice': 'RIME-FROST-NOTICE.txt'}
    if data[:6] == b'SPLX\x00\x04':
        dependency['projection'] = {'format': 'SPLX/3', 'operation': 'remove-tier-2'}
        dependency['runtimeAsset'] = {'format': 'SPLX/4', 'sha256': digest(data), 'bytes': len(data)}
    # Verify before writing any package asset; never update the training hash to
    # conceal a changed dictionary. SPLX/4 must project to the frozen SPLX/3.
    verify_dictionary_dependency(data, dependency)
    return dependency


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('experiment','freeze','evaluation','assets'): p.add_argument(name,type=Path)
    a=p.parse_args(); freeze=json.loads(a.freeze.read_text('utf-8')); result=json.loads((a.evaluation/'conclusion.json').read_text('utf-8'))
    model=a.experiment/(freeze['selectedModel']+'.snwp')
    if result['freezeSha256']!=sha256(a.freeze) or result['modelSha256']!=sha256(model) or not all(result['acceptance'].values()):
        raise ValueError('Frozen evaluation gate failed')
    if result['testReportSha256']!=sha256(a.evaluation/'test.json'): raise ValueError('Test evidence changed')
    BinaryPredictor(model.read_bytes())
    previous=json.loads((a.assets/'pinyin_character_lm_notice.json').read_text('utf-8'))
    if previous['trainingPartition']!=freeze['inputs']['training']['partition'] or previous['corpusManifestSha256']!=freeze['inputs']['training']['manifestSha256']:
        raise ValueError('Shared attribution does not describe this training partition')
    if sha256(a.assets/previous['attribution']['file'])!=previous['attribution']['sha256']: raise ValueError('Attribution changed')
    dependency = dictionary_dependency((a.assets/'pinyin_lexicon.bin').read_bytes(), freeze['inputs']['assets']['pinyin_lexicon.bin'])
    target=a.assets/'association_words.snwp'; target.write_bytes(model.read_bytes())
    notice={'schemaVersion':1,'title':'Sense offline context-to-word continuation model v1','model':{'file':target.name,'bytes':target.stat().st_size,'sha256':sha256(target)},
            'source':previous['source'],'sourceAttributionLicense':'CC-BY-2.0-FR','sourceLicenseUrl':previous['licenseUrl'],'authors':previous['authors'],
            'attribution':previous['attribution'],'sourceSentenceUrlTemplate':previous['sourceSentenceUrlTemplate'],'trainingPartition':previous['trainingPartition'],
            'dictionaryDependency':dependency,
            'transformation':'Same normalized grouped train partition as character LM; dictionary-unigram Viterbi units; suffix contexts 1..3 Han; interpolated discounted next-unit/remaining-suffix counts; STOP-aware precomputed top8; no neural model or online API',
            'parameters':{'lengthBonus':freeze['lengthBonus'],'stopRatio':freeze['stopRatio'],**freeze['inputs']['fixedPruning']},
            'freezeSha256':sha256(a.freeze),'evaluationSha256':sha256(a.evaluation/'conclusion.json'),
            'rebuild':'tools/train_association_model.py; tools/evaluate_association_model.py; tools/package_association_model.py; benchmarks/corpus/README-association.md',
            'packagerSha256':sha256(Path(__file__)),'scope':'Local debug-stage model; single-reference offline retrieval evidence, not human acceptability or release certification'}
    (a.assets/'association_words_notice.json').write_text(json.dumps(notice,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (a.assets/'ASSOCIATION-MODEL-NOTICE.txt').write_text(
        'Sense offline context-to-word continuation model\n'
        'Source sentences: Tatoeba contributors, frozen Mandarin export 2026-09-26.\n'
        'https://tatoeba.org/en/downloads\n'
        'Source attribution: Creative Commons Attribution 2.0 France\n'
        'https://creativecommons.org/licenses/by/2.0/fr/\n'
        'Per-sentence author mapping: pinyin_character_lm_attribution.tsv (same train partition).\n'
        'All contributor names and source URL template: PINYIN-LM-NOTICE.txt.\n'
        'Segmentation dependency: Rime Frost-derived pinyin_lexicon.bin, GPL-3.0.\n'
        'See RIME-FROST-NOTICE.txt and RIME-FROST-GPL-3.0.txt.\n'
        'Sense changes: normalize/filter/group; dictionary Viterbi segmentation; train-only\n'
        'discounted context/continuation counts; STOP suppression; compact lookup export.\n'
        'This model combines the attributed corpus with GPL-3.0 dictionary-derived segmentation;\n'
        'retain both sets of notices when redistributing it with Sense.\n'
        'No raw training sentences or test labels are bundled; no editor text leaves the device.\n'
        'Hashes, parameters, provenance and rebuild recipe: association_words_notice.json.\n',encoding='utf-8')
    if 'projection' in dependency:
        with (a.assets/'ASSOCIATION-MODEL-NOTICE.txt').open('a', encoding='utf-8') as stream:
            stream.write('Runtime SPLX/4 also contains Rime Ice tier-2 coverage; see RIME-ICE-NOTICE.txt.\n'
                         'The training base is recovered byte-for-byte by removing tier 2; both hashes\n'
                         'and the projection rule are retained in association_words_notice.json.\n')
    print(json.dumps(notice,ensure_ascii=False))


if __name__=='__main__': main()
