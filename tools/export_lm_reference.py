#!/usr/bin/env python3
"""Export deterministic mixed seen/backoff/OOV probes for Kotlin interop testing."""
import argparse
import random
from pathlib import Path

from audit_sentence_corpus import sha256
from train_character_lm import read_model, BOS, EOS, UNK


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    model = read_model(args.model.read_bytes())
    rng = random.Random(20261002)
    chars = sorted(model.unigrams)
    probes = [(BOS, BOS, EOS), (UNK, UNK, UNK), (0x3134F, 0x3134E, 0x3134D)]
    probes += [(BOS, BOS, ch) for ch in rng.sample(chars, min(64, len(chars)))]
    probes += rng.sample(sorted(model.trigrams), min(128, len(model.trigrams)))
    probes += [(UNK, b, c) for b, c in rng.sample(sorted(model.bigrams), min(128, len(model.bigrams)))]
    probes += [(a, b, rng.choice(chars)) for a, b in rng.sample(sorted(model.trigram_backoff), min(128, len(model.trigram_backoff)))]
    probes += [tuple(rng.choice(chars) for _ in range(3)) for _ in range(128)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(f"# model-sha256={sha256(args.model)}\n# previous2\tprevious1\tnext\tlogProbability\n")
        for a, b, c in probes:
            stream.write(f"{a}\t{b}\t{c}\t{model.log_probability(a, b, c):.10f}\n")
    print(f"Exported {len(probes)} reference transitions")


if __name__ == "__main__":
    main()
