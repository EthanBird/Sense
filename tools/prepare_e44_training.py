"""Prepare expanded, source-isolated weak supervision without reading candidates."""
import argparse
from collections import Counter
import importlib.metadata
import json
from pathlib import Path

from fetch_e37_model import digest
from prepare_p2c import read_lines, select_rows, overlaps
from prepare_weak_ranker_train import valid_readings
from run_e37_rescoring import write_new


def exclusion(text, heldout):
    return overlaps(text, heldout) or ("contains-heldout" if any(t in text for t in heldout) else None)


def prepare(root, out, previous):
    from pypinyin import lazy_pinyin, Style
    policy_path = root / "benchmarks/corpus/e44-context-only-policy.json"
    policy = json.loads(policy_path.read_text("utf-8"))
    spec = policy["train"]
    if importlib.metadata.version("pypinyin") != spec["proposal"]["version"]:
        raise ValueError("Changed weak annotation package")
    if out.exists():
        raise ValueError("Preserve previous preparation")
    corpus = root / ".artifacts/input-quality/lm-corpus-v1"
    manifest = json.loads((corpus / "corpus.json").read_text("utf-8"))
    for split in ["train", "dev", "test"]:
        if digest(corpus / (split + ".jsonl")) != manifest["outputs"][split]["sha256"]:
            raise ValueError("Corpus partition changed")
    if digest(corpus / "attribution.jsonl") != manifest["attribution"]["sha256"]:
        raise ValueError("Attribution changed")
    partitions = {s: read_lines(corpus / (s + ".jsonl")) for s in ["train", "dev", "test"]}
    families = {s: {r["group"] for r in rows} for s, rows in partitions.items()}
    if (families["train"] & (families["dev"] | families["test"]) or families["dev"] & families["test"]):
        raise ValueError("Corpus family overlap")
    heldout_files = sorted(set((root / "benchmarks/corpus").rglob("dev.tsv")) |
                           set((root / "benchmarks/corpus").rglob("test.tsv")))
    heldout_files.append(root / ".artifacts/input-quality/e28/diagnostic.tsv")
    heldout = sorted({line.split("\t")[2] for p in heldout_files
                      for line in p.read_text("utf-8").splitlines() if line and not line.startswith("#")})
    prior_lock = json.loads((previous / "pre-export-lock.json").read_text("utf-8"))
    if digest(previous / "train.tsv") != prior_lock["trainSha256"]:
        raise ValueError("Previous training labels changed")
    base = json.loads((previous / "selected.json").read_text("utf-8"))
    prior_manifest = json.loads((root / "benchmarks/results/e39-evidence-manifest.json").read_text("utf-8"))
    if digest(previous / "selected.json") != prior_manifest["files"]["external/e39/final/selected.json"]["sha256"]:
        raise ValueError("Previous source selection changed")
    additional = select_rows(partitions["train"], "train", spec["additionalProposals"],
        {"seed": spec["seed"], "strata": spec["strata"], "excludedFamilies": [r["group"] for r in base]})
    allowed = set((root / "ime-service/src/main/assets/pinyin_syllables.txt").read_text("utf-8").splitlines())
    training_members = {(r["group"], s) for r in partitions["train"] for s in r["segments"]}
    selected = []
    for origin, proposals in [("small", base), ("additional", additional)]:
        for original in proposals:
            r = dict(original, cohort=origin)
            if (r["group"], r["text"]) not in training_members or r["partition"] != "train":
                raise ValueError("Selected non-train source")
            if origin == "additional":
                r["syllables"] = lazy_pinyin(r["text"], style=Style.NORMAL, v_to_u=False, tone_sandhi=False)
            r["exclusion"] = exclusion(r["text"], heldout) or valid_readings(r["text"], r["syllables"], allowed)
            r["labelQuality"] = "automatic weak supervision; not reviewed gold"
            selected.append(r)
    if len({r["group"] for r in selected}) != len(selected):
        raise ValueError("Source families repeated across cohorts")
    retained = [r for r in selected if not r["exclusion"]]
    out.mkdir(parents=True)
    write_new(out / "selected.json", selected)
    for mode in ["small", "expanded"]:
        rows = [r for r in retained if mode == "expanded" or r["cohort"] == "small"]
        with (out / (mode + ".tsv")).open("x", encoding="utf-8", newline="\n") as f:
            f.write("# E44 train-only automatic weak supervision, not reviewed gold\n")
            for r in sorted(rows, key=lambda r: r["id"]):
                f.write("\t".join([r["id"], "".join(r["syllables"]), r["text"], r["stratum"], " ".join(r["syllables"])]) + "\n")
    source_ids = {r["recordId"] for r in retained}
    seen = set()
    with (out / "attribution.jsonl").open("x", encoding="utf-8", newline="\n") as f:
        for r in read_lines(corpus / "attribution.jsonl"):
            if r["recordId"] in source_ids:
                if r["split"] != "train" or r["license"] != "CC-BY-2.0-FR":
                    raise ValueError("Unexpected attribution")
                seen.add(r["recordId"]); f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    if seen != source_ids:
        raise ValueError("Missing attribution")
    write_new(out / "pre-export-lock.json", dict(policySha256=digest(policy_path),
        sourceFiles={"tools/prepare_e44_training.py": digest(Path(__file__)),
                     "tools/prepare_p2c.py": digest(root / "tools/prepare_p2c.py")},
        corpusManifestSha256=digest(corpus / "corpus.json"),
        partitions={s: digest(corpus / (s + ".jsonl")) for s in partitions},
        heldout={p.relative_to(root).as_posix(): digest(p) for p in heldout_files},
        priorTrain=digest(previous / "train.tsv"), priorSelection=digest(previous / "selected.json"),
        proposed=len(selected), retained=len(retained), smallRetained=sum(r["cohort"] == "small" for r in retained),
        exclusions=dict(Counter(r["exclusion"] for r in selected if r["exclusion"])),
        outputs={p.name: digest(p) for p in out.iterdir()},
        familiesDisjoint=True, weakSupervision=True, candidateOutputsRead=False))
    print(json.dumps({"proposed":len(selected), "retained":len(retained),
                      "smallRetained":sum(r["cohort"] == "small" for r in retained),
                      "exclusions":dict(Counter(r["exclusion"] for r in selected if r["exclusion"]))}), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(__doc__)
    for name in ["root", "output", "previous"]:
        p.add_argument(name, type=Path)
    a = p.parse_args(); prepare(a.root.resolve(), a.output.resolve(), a.previous.resolve())
