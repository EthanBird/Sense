"""Post-test E18 paired source-cluster intervals; not another selection pass."""
import json
from evaluate_cross_domain import load
from train_candidate_ranker import distance
from summarize_ranker_uncertainty import summarize
from evaluate_fourgram_test import ART, ROOT, DATA, write
from audit_sentence_corpus import sha256


def main():
    decision = json.loads((ART/'test-decision.json').read_text('utf-8'))
    reports = {}
    for domain, dataset in DATA.items():
        path = ROOT/f'benchmarks/corpus/{dataset}/test.tsv'
        text = path.read_text('utf-8'); paired = {}; pins = {str(path): sha256(path)}
        for key, mode in [('before', 'baseline'), ('after', decision['selectedMode'])]:
            file = ART/f'test-{domain}-{mode}.jsonl'
            _, rows = load(file.read_text('utf-8').splitlines(), text)
            paired[key] = dict(rows=[{**r, 'characterErrors': distance(
                r['candidates'][0] if r['candidates'] else '', r['expected'])} for r in rows.values()])
            pins[str(file)] = sha256(file)
        report = summarize(paired, seed=1818)
        report['limitations'][1] = 'Prepared source-text reconstruction and single-reference labels; not natural mobile input or independent human review.'
        report['pins'] = pins
        reports[domain] = report
    write(ART/'test-uncertainty.json', reports)
    print(json.dumps(reports))


if __name__ == '__main__':
    main()
