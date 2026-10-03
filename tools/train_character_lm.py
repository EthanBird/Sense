#!/usr/bin/env python3
"""Train a smoothed character 1/2/3-gram baseline from train.jsonl only.

Absolute-discount interpolation with an additive unigram floor; NOT Kneser-Ney.
Pruning transfers removed direct probability mass to backoff, preserving normalization.
"""
from __future__ import annotations

import math
import struct
import argparse
import json
from pathlib import Path
from collections import Counter, defaultdict
from dataclasses import dataclass

from audit_sentence_corpus import han, sha256

BOS = 0x110000
EOS = 0x110001
UNK = 0x110002
HEADER = struct.Struct(">4sH5I")
MAX_BYTES = 16 * 1024 * 1024


def pack(tokens):
    value = 0
    for token in tokens:
        value = (value << 21) | token
    return value


def unpack(value, order):
    return tuple((value >> (21 * i)) & 0x1FFFFF for i in reversed(range(order)))


@dataclass
class CharacterModel:
    unigrams: dict
    bigrams: dict
    bigram_backoff: dict
    trigrams: dict
    trigram_backoff: dict

    def token(self, cp):
        return cp if cp == BOS or cp in self.unigrams else UNK

    def log_probability(self, previous2, previous1, next_cp, order=3):
        a, b, w = self.token(previous2), self.token(previous1), self.token(next_cp)
        if w == BOS:
            raise ValueError("BOS is context only")
        uni = self.unigrams[w]
        if order == 1:
            return uni
        bi = self.bigrams.get((b, w))
        if bi is None:
            bi = self.bigram_backoff.get(b, 0.0) + uni
        if order == 2:
            return bi
        if order != 3:
            raise ValueError("Only orders 1, 2, 3 are supported")
        tri = self.trigrams.get((a, b, w))
        return tri if tri is not None else self.trigram_backoff.get((a, b), 0.0) + bi


def discounted_table(counts, discount, min_count, lower_probability):
    totals = Counter()
    retained = defaultdict(list)
    for ngram, count in counts.items():
        context = ngram[:-1]
        totals[context] += count
        if count >= min_count:
            retained[context].append((ngram, count))
    probs, backoffs = {}, {}
    for context in sorted(retained):
        entries = retained[context]
        direct_mass = sum(count - discount for _, count in entries) / totals[context]
        backoff = 1.0 - direct_mass
        if not 0.0 < backoff <= 1.0:
            raise ValueError("Discount produced invalid backoff mass")
        backoffs[context] = math.log(backoff)
        for ngram, count in entries:
            probability = (count - discount) / totals[context] + backoff * lower_probability(ngram)
            probs[ngram] = math.log(probability)
    return probs, backoffs


def train_model(segments, discount=0.75, min_char_count=2, min_bigram_count=1, min_trigram_count=2, alpha=0.1):
    if not 0.0 < discount < 1.0 or not math.isfinite(alpha) or alpha <= 0:
        raise ValueError("Invalid smoothing parameters")
    if min(min_char_count, min_bigram_count, min_trigram_count) < 1:
        raise ValueError("Count cutoffs must be positive")
    segments = list(segments)
    if not segments or any(not text for text in segments):
        raise ValueError("Training needs nonempty segments")
    raw_chars = Counter(ord(ch) for text in segments for ch in text)
    vocabulary = {cp for cp, count in raw_chars.items() if count >= min_char_count} | {EOS, UNK}
    uni, bi, tri = Counter(), Counter(), Counter()
    for text in segments:
        a = b = BOS
        for cp in [ord(ch) if ord(ch) in vocabulary else UNK for ch in text] + [EOS]:
            uni[cp] += 1
            bi[(b, cp)] += 1
            tri[(a, b, cp)] += 1
            a, b = b, cp
    denominator = sum(uni.values()) + alpha * len(vocabulary)
    unigrams = {cp: math.log((uni[cp] + alpha) / denominator) for cp in sorted(vocabulary)}
    bigrams, bigram_bow = discounted_table(bi, discount, min_bigram_count, lambda ng: math.exp(unigrams[ng[-1]]))
    model = CharacterModel(unigrams, bigrams, {k[0]: v for k, v in bigram_bow.items()}, {}, {})
    trigrams, trigram_bow = discounted_table(tri, discount, min_trigram_count,
        lambda ng: math.exp(model.log_probability(BOS, ng[-2], ng[-1], order=2)))
    model.trigrams, model.trigram_backoff = trigrams, trigram_bow
    return model


def write_model(model, path):
    tables = [model.unigrams, model.bigrams, model.bigram_backoff, model.trigrams, model.trigram_backoff]
    formats = [(">If", 1), (">Qf", 2), (">If", 1), (">Qf", 3), (">Qf", 2)]
    data = bytearray(HEADER.pack(b"SCNG", 1, *(len(t) for t in tables)))
    for table, (fmt, order) in zip(tables, formats):
        for key, score in sorted(table.items()):
            data.extend(struct.pack(fmt, key if order == 1 else pack(key), score))
    if len(data) > MAX_BYTES:
        raise ValueError("Model exceeds experimental binary budget")
    # Parse before publishing the binary, including ordering, references and finite scores.
    read_model(bytes(data))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def read_model(data):
    if len(data) < HEADER.size or len(data) > MAX_BYTES:
        raise ValueError("Ngram model size outside budget")
    magic, version, *counts = HEADER.unpack_from(data)
    if magic != b"SCNG" or version != 1:
        raise ValueError("Invalid ngram model magic/version")
    formats = [(">If", 1), (">Qf", 2), (">If", 1), (">Qf", 3), (">Qf", 2)]
    expected = HEADER.size + sum(struct.calcsize(fmt) * count for (fmt, _), count in zip(formats, counts))
    if expected != len(data) or counts[0] < 2:
        raise ValueError("Ngram model table length mismatch")
    cursor = HEADER.size
    tables = []
    for count, (fmt, order) in zip(counts, formats):
        table = {}
        last = -1
        for _ in range(count):
            key, score = struct.unpack_from(fmt, data, cursor)
            cursor += struct.calcsize(fmt)
            if key <= last or (order > 1 and key >= 1 << (21 * order)) or not math.isfinite(score) or not -80.0 <= score <= 0.0:
                raise ValueError("Ngram keys/scores invalid")
            last = key
            table[key if order == 1 else unpack(key, order)] = score
        tables.append(table)
    model = CharacterModel(*tables)
    if EOS not in model.unigrams or UNK not in model.unigrams or BOS in model.unigrams:
        raise ValueError("Invalid special vocabulary")
    if any(cp not in (EOS, UNK) and (cp > 0x10FFFF or 0xD800 <= cp <= 0xDFFF) for cp in model.unigrams):
        raise ValueError("Invalid Unicode scalar")
    contexts = set(model.unigrams) | {BOS}
    for (b, w) in model.bigrams:
        if b not in contexts or w not in model.unigrams or b not in model.bigram_backoff:
            raise ValueError("Invalid bigram references")
    for (a, b, w) in model.trigrams:
        if a not in contexts or b not in contexts or w not in model.unigrams or (a, b) not in model.trigram_backoff:
            raise ValueError("Invalid trigram references")
    if any(c not in contexts for c in model.bigram_backoff) or any(a not in contexts or b not in contexts for a, b in model.trigram_backoff):
        raise ValueError("Invalid backoff context")
    return model


def read_partition(directory, manifest, split):
    info = manifest["outputs"][split]
    if info["fileName"] != split + ".jsonl":
        raise ValueError("Unexpected corpus partition filename")
    path = directory / info["fileName"]
    if sha256(path) != info["sha256"]:
        raise ValueError(f"Corpus {split} hash changed after preparation")
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    if len(rows) != info["records"] or len({r["id"] for r in rows}) != len(rows):
        raise ValueError("Corpus partition record count/identity mismatch")
    for row in rows:
        if not row["segments"] or any(not 2 <= len(t) <= 64 or not all(han(ch) for ch in t) for t in row["segments"]):
            raise ValueError("Expected normalized Han-only segments")
    return rows


def evaluate(model, rows, order):
    totals = defaultdict(lambda: [0.0, 0, 0, 0, 0])
    for row in rows:
        for segment in row["segments"]:
            loss = 0.0
            a = b = BOS
            for cp in list(map(ord, segment)) + [EOS]:
                loss -= model.log_probability(a, b, cp, order=order)
                a, b = b, cp
            oov = sum(ord(ch) not in model.unigrams for ch in segment)
            for name in ("all", row["domain"]):
                value = totals[name]
                value[0] += loss
                value[1] += len(segment) + 1
                value[2] += len(segment)
                value[3] += oov
                value[4] += 1
    return {name: {"crossEntropyBitsPerToken": loss / tokens / math.log(2), "perplexity": math.exp(loss / tokens),
                   "tokensIncludingEos": tokens, "characters": chars, "oovCharacters": oov,
                   "oovRate": oov / chars, "segments": segments}
            for name, (loss, tokens, chars, oov, segments) in sorted(totals.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    fitting = commands.add_parser("train")
    fitting.add_argument("corpus", type=Path)
    fitting.add_argument("model", type=Path)
    fitting.add_argument("report", type=Path)
    fitting.add_argument("--min-trigram-count", type=int, default=2)
    fitting.add_argument("--discount", type=float, default=0.75)
    testing = commands.add_parser("evaluate-test")
    testing.add_argument("corpus", type=Path)
    testing.add_argument("model", type=Path)
    testing.add_argument("frozen_report", type=Path)
    testing.add_argument("report", type=Path)
    args = parser.parse_args()
    manifest_path = args.corpus / "corpus.json"
    corpus = json.loads(manifest_path.read_text(encoding="utf-8"))
    if corpus.get("schemaVersion") != 1:
        raise ValueError("Unsupported corpus manifest")
    attribution = corpus["attribution"]
    if attribution["fileName"] != "attribution.jsonl" or sha256(args.corpus / "attribution.jsonl") != attribution["sha256"]:
        raise ValueError("Missing or changed corpus attribution sidecar")
    if args.command == "train":
        train = read_partition(args.corpus, corpus, "train")
        dev = read_partition(args.corpus, corpus, "dev")
        if {r["group"] for r in train} & {r["group"] for r in dev}:
            raise ValueError("Train/dev families overlap")
        if {s for r in train for s in r["segments"]} & {s for r in dev for s in r["segments"]}:
            raise ValueError("Train/dev segment leakage")
        config = {"discount": args.discount, "min_char_count": 2, "min_bigram_count": 1,
                  "min_trigram_count": args.min_trigram_count, "alpha": 0.1}
        model = train_model((s for r in train for s in r["segments"]), **config)
        write_model(model, args.model)
        model = read_model(args.model.read_bytes())  # evaluate the actual float32 runtime format
        development = {str(order): evaluate(model, dev, order) for order in (1, 2, 3)}
        report = {"schemaVersion": 1, "modelSha256": sha256(args.model), "modelBytes": args.model.stat().st_size,
                  "algorithm": "absolute-discount interpolated character 1/2/3-gram; additive unigram floor; not Kneser-Ney",
                  "scriptSha256": sha256(Path(__file__)), "config": config,
                  "corpusManifestSha256": sha256(manifest_path), "inputs": corpus["outputs"],
                  "attribution": corpus["attribution"], "source": corpus["source"],
                  "counts": {"vocabularyIncludingEosUnk": len(model.unigrams), "bigrams": len(model.bigrams),
                             "trigrams": len(model.trigrams), "bigramBackoffs": len(model.bigram_backoff), "trigramBackoffs": len(model.trigram_backoff)},
                  "dev": development, "testEvaluated": False, "productionReady": False,
                  "metricsNote": "BOS context resets at punctuation; EOS counted once per Han segment; unseen characters map to UNK; perplexity is not P2C accuracy"}
    else:
        frozen = json.loads(args.frozen_report.read_text(encoding="utf-8"))
        if sha256(args.model) != frozen["modelSha256"] or sha256(manifest_path) != frozen["corpusManifestSha256"]:
            raise ValueError("Frozen model/corpus hash mismatch")
        model = read_model(args.model.read_bytes())
        heldout = read_partition(args.corpus, corpus, "test")
        # Independent verification before opening the frozen measurement, not just trust in the splitter.
        earlier = read_partition(args.corpus, corpus, "train") + read_partition(args.corpus, corpus, "dev")
        if {r["group"] for r in earlier} & {r["group"] for r in heldout} or {s for r in earlier for s in r["segments"]} & {s for r in heldout for s in r["segments"]}:
            raise ValueError("Frozen evaluation leaked across families/segments")
        report = {"schemaVersion": 1, "modelSha256": sha256(args.model), "frozenReportSha256": sha256(args.frozen_report),
                  "corpusManifestSha256": sha256(manifest_path), "test": {str(o): evaluate(model, heldout, o) for o in (1, 2, 3)},
                  "productionReady": False, "note": "Independent sentence-likelihood evaluation; still requires decoder and UI acceptance"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    phase = "dev" if args.command == "train" else "test"
    print(json.dumps({"phase": phase, "modelSha256": report["modelSha256"], "perplexity": {o: v["all"]["perplexity"] for o, v in report[phase].items()}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
