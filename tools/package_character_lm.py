#!/usr/bin/env python3
"""Package the frozen, evaluated LM with per-training-sentence author attribution.

The model and corpus attribution retain their separate CC BY 2.0 FR license.
Training text/test labels are not bundled in the APK. Rebuild inputs remain in benchmarks/.
"""
import argparse
import json
from pathlib import Path
from audit_sentence_corpus import sha256


def attributed_training_rows(rows):
    selected = {}
    for row in rows:
        if row["split"] != "train":
            continue
        key = int(row["sentenceId"])
        author = row["contributor"]
        if not author or "\t" in author or "\n" in author or row["license"] != "CC-BY-2.0-FR":
            raise ValueError("Invalid training attribution")
        if key in selected and selected[key] != author:
            raise ValueError("Conflicting sentence attribution")
        selected[key] = author
    if not selected:
        raise ValueError("No training attribution")
    return sorted(selected.items())


def attribution_bytes(rows):
    # aapt expands .gz assets and strips that suffix. Use a plain TSV and let the
    # APK container compress it so provenance describes the shipped bytes/name.
    text = "# sentenceId\tcontributor\n" + "".join(f"{sid}\t{author}\n" for sid, author in rows)
    return text.encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path); parser.add_argument("model", type=Path)
    parser.add_argument("development_report", type=Path); parser.add_argument("p2c_freeze", type=Path)
    parser.add_argument("p2c_conclusion", type=Path); parser.add_argument("assets", type=Path)
    args = parser.parse_args()
    def read(p): return json.loads(p.read_text(encoding="utf-8"))
    corpus, dev, freeze, conclusion = map(read, (args.corpus / "corpus.json", args.development_report, args.p2c_freeze, args.p2c_conclusion))
    model_hash = sha256(args.model)
    if model_hash != dev["modelSha256"] or model_hash != freeze["pins"]["assets"][args.model.name]:
        raise ValueError("Model does not match evaluation")
    if sha256(args.corpus / "corpus.json") != dev["corpusManifestSha256"] or sha256(args.corpus / "attribution.jsonl") != corpus["attribution"]["sha256"]:
        raise ValueError("Corpus/attribution changed")
    if conclusion["developmentFreezeSha256"] != sha256(args.p2c_freeze) or not conclusion["sourceReconstructionGatePassed"] or conclusion["selectedMode"] != freeze["selectedMode"]:
        raise ValueError("Invalid P2C selection evidence")
    if sha256(args.p2c_conclusion.parent / "m12-p2c-test.json") != conclusion["reportSha256"]:
        raise ValueError("P2C test report changed")
    attribution = [json.loads(line) for line in (args.corpus / "attribution.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = attributed_training_rows(attribution)
    args.assets.mkdir(parents=True, exist_ok=True)
    (args.assets / "pinyin_character_lm.scng").write_bytes(args.model.read_bytes())
    authors_file = args.assets / "pinyin_character_lm_attribution.tsv"
    authors_file.write_bytes(attribution_bytes(rows))
    notice = {"schemaVersion": 1, "title": "Sense character language model v1 derived from Tatoeba Mandarin sentences",
              "authors": "Tatoeba contributors; per-sentence names in pinyin_character_lm_attribution.tsv; model transformation by Sense contributors",
              "license": "CC-BY-2.0-FR", "licenseUrl": "https://creativecommons.org/licenses/by/2.0/fr/",
              "sourceSentenceUrlTemplate": "https://tatoeba.org/en/sentences/show/{sentenceId}",
              "source": corpus["source"], "transformation": "NFKC; OpenCC 1.4.2 t2s; Han spans; family partition/dedup/exclusion; train-only absolute-discount interpolated character 1/2/3-gram",
              "model": {"file": "pinyin_character_lm.scng", "sha256": model_hash, "bytes": args.model.stat().st_size, "trainingConfig": dev["config"]},
              "trainingPartition": corpus["outputs"]["train"], "corpusManifestSha256": dev["corpusManifestSha256"],
              "attribution": {"file": authors_file.name, "sha256": sha256(authors_file), "sentences": len(rows), "contributors": len({author for _, author in rows})},
              "decoderMode": freeze["selectedMode"], "p2cConclusionSha256": sha256(args.p2c_conclusion),
              "rebuild": "tools/prepare_sentence_corpus.py; tools/train_character_lm.py; tools/package_character_lm.py; benchmarks/corpus/README-character-lm.md",
              "packagerSha256": sha256(Path(__file__)), "androidAcceptance": "pending; this asset is not a release approval"}
    (args.assets / "pinyin_character_lm_notice.json").write_text(json.dumps(notice, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    authors = sorted({author for _, author in rows})
    text_notice = (
        "Sense offline character language model / Tatoeba contributors\n"
        "============================================================\n\n"
        "Model and source attribution license: Creative Commons Attribution 2.0 France\n"
        "https://creativecommons.org/licenses/by/2.0/fr/\n\n"
        "Source: Tatoeba Mandarin sentence collection, frozen export 2026-09-26.\n"
        "https://tatoeba.org/en/downloads\n"
        "Changes by Sense contributors: simplified-character normalization, grouping/filtering,\n"
        "train-only absolute-discount interpolated character 1/2/3-gram model.\n"
        "This offline model sends no keystrokes or editor text to a server.\n\n"
        f"Training sources: {len(rows)} attributed sentences, {len(authors)} contributors.\n"
        "Per-sentence author index: pinyin_character_lm_attribution.tsv (APK asset).\n"
        "Sentence source URL: https://tatoeba.org/en/sentences/show/{sentenceId}\n"
        "Model hash, corpus provenance, and rebuild recipe: pinyin_character_lm_notice.json.\n"
        "Raw corpus snapshot: benchmarks/corpus/sources/ in the Sense source repository.\n"
        "These model/data assets retain CC BY 2.0 FR, separate from Sense's code license.\n\n"
        "Tatoeba contributors (per-sentence mapping is in the index above):\n" + "\n".join(authors) + "\n")
    (args.assets / "PINYIN-LM-NOTICE.txt").write_text(text_notice, encoding="utf-8")
    print(json.dumps({"modelBytes": args.model.stat().st_size, "attributionBytes": authors_file.stat().st_size, "trainingSentences": len(rows), "mode": freeze["selectedMode"]}))


if __name__ == "__main__":
    main()
