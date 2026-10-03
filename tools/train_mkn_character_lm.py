"""Train-only continuation-count character trigram, in the existing SCNG/1 format.

Modified Kneser-Ney style: estimated D1/D2/D3+, raw BOS-context counts,
continuation lower orders. The explicit additive unigram floor and pruning
are Sense choices; this is not a reproduction of KenLM's scalable estimator.
"""
from collections import Counter, defaultdict
import argparse
import json
import math
from pathlib import Path

from audit_sentence_corpus import han, sha256
from train_character_lm import BOS, EOS, UNK, CharacterModel, read_model, read_partition, write_model


def estimate_discounts(counts):
    histogram = Counter(counts.values())
    n1, n2 = histogram[1], histogram[2]
    y = n1 / (n1 + 2 * n2) if n1 and n2 else None
    values, fallbacks = [], []
    for k in (1, 2, 3):
        value = (k - (k + 1) * y * histogram[k + 1] / histogram[k]
                 if y is not None and histogram[k] and histogram[k + 1] else None)
        if value is None or not 0 < value < k or not math.isfinite(value):
            value = 0.75
            fallbacks.append(k)
        values.append(value)
    return values, dict(countOfCounts={str(k): histogram[k] for k in (1, 2, 3, 4)},
                        discounts=values, fallbackBuckets=fallbacks)


def adjusted_counts(segments, vocabulary):
    raw_bigrams, trigrams = Counter(), Counter()
    for text in segments:
        a = b = BOS
        for token in [ord(ch) if ord(ch) in vocabulary else UNK for ch in text] + [EOS]:
            raw_bigrams[b, token] += 1
            trigrams[a, b, token] += 1
            a, b = b, token
    # Iteration is over unique keys, not occurrence counts: distinct predecessors.
    bigrams = Counter()
    for a, b, w in trigrams:
        bigrams[b, w] += 1
    for (b, w), count in raw_bigrams.items():
        if b == BOS:
            bigrams[b, w] = count
    unigrams = Counter(w for b, w in raw_bigrams)
    return unigrams, bigrams, trigrams


def discounted_table(counts, discounts, minimum, lower_probability):
    totals, retained = Counter(), defaultdict(list)
    for ngram, count in counts.items():
        context = ngram[:-1]
        totals[context] += count
        if count >= minimum:
            retained[context].append((ngram, count))
    probabilities, backoffs = {}, {}
    for context in sorted(retained):
        entries = sorted(retained[context])
        direct = [(key, (count - discounts[min(count, 3) - 1]) / totals[context])
                  for key, count in entries]
        backoff = 1.0 - math.fsum(value for _, value in direct)
        if not 0 < backoff <= 1:
            raise ValueError('Invalid backoff mass')
        backoffs[context] = math.log(backoff)
        for key, value in direct:
            probabilities[key] = math.log(value + backoff * lower_probability(key))
    return probabilities, backoffs


def train_model(segments, min_char_count=2, min_bigram_count=1, min_trigram_count=2, alpha=0.1):
    if min(min_char_count, min_bigram_count, min_trigram_count) < 1 or not math.isfinite(alpha) or alpha <= 0:
        raise ValueError('Invalid count cutoff or floor')
    segments = list(segments)
    if not segments or any(not text or not all(han(ch) for ch in text) for text in segments):
        raise ValueError('Expected nonempty Han segments')
    characters = Counter(ord(ch) for text in segments for ch in text)
    vocabulary = {cp for cp, count in characters.items() if count >= min_char_count} | {EOS, UNK}
    uni, bi, tri = adjusted_counts(segments, vocabulary)
    denominator = sum(uni.values()) + alpha * len(vocabulary)
    unigrams = {cp: math.log((uni[cp] + alpha) / denominator) for cp in sorted(vocabulary)}
    d2, report2 = estimate_discounts(bi)
    d3, report3 = estimate_discounts(tri)
    bigrams, bows = discounted_table(bi, d2, min_bigram_count, lambda ng: math.exp(unigrams[ng[-1]]))
    model = CharacterModel(unigrams, bigrams, {context[0]: value for context, value in bows.items()}, {}, {})
    trigrams, bows = discounted_table(tri, d3, min_trigram_count,
                                      lambda ng: math.exp(model.log_probability(BOS, ng[-2], ng[-1], order=2)))
    model.trigrams, model.trigram_backoff = trigrams, bows
    return model, dict(bigram=report2, trigram=report3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('corpus', type=Path)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('model', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    if args.model.exists() or args.report.exists():
        raise ValueError('Retain previous model/evidence')
    corpus_path = args.corpus / 'corpus.json'
    manifest = json.loads(corpus_path.read_text('utf-8'))
    attribution = manifest['attribution']
    if (manifest.get('schemaVersion') != 1 or attribution['fileName'] != 'attribution.jsonl'
            or sha256(args.corpus / 'attribution.jsonl') != attribution['sha256']):
        raise ValueError('Missing/changed corpus attribution')
    train = read_partition(args.corpus, manifest, 'train')  # Never read dev/test here.
    model, estimates = train_model(s for row in train for s in row['segments'])
    baseline = read_model(args.baseline.read_bytes())
    if model.unigrams.keys() != baseline.unigrams.keys():
        raise ValueError('Vocabulary changed')
    write_model(model, args.model)
    loaded = read_model(args.model.read_bytes())
    report = dict(schemaVersion=1, scriptSha256=sha256(Path(__file__)),
                  modelSha256=sha256(args.model), modelBytes=args.model.stat().st_size,
                  baselineSha256=sha256(args.baseline), corpusManifestSha256=sha256(corpus_path),
                  train=manifest['outputs']['train'], attribution=attribution, source=manifest['source'],
                  algorithm=__doc__.strip(), estimates=estimates, vocabularyUnchanged=True,
                  counts=dict(vocabulary=len(loaded.unigrams), bigrams=len(loaded.bigrams),
                              trigrams=len(loaded.trigrams)), developmentEvaluated=False,
                  testEvaluated=False, productionChanged=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(dict(modelSha256=report['modelSha256'], bytes=report['modelBytes'], estimates=estimates)))


if __name__ == '__main__':
    main()
