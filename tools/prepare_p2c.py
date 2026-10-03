#!/usr/bin/env python3
"""Select independent P2C cases, propose labels, then freeze explicitly reviewed annotations.

No decoder output or lexical frequency is read by this tool. Selected source sentences keep
their original attribution. A proposal is not a reviewed gold label.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.metadata
import json
from collections import Counter
from pathlib import Path

from audit_sentence_corpus import sha256


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_lines(path, rows):
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows), encoding="utf-8")


def select_rows(records, split, count, policy):
    strata = policy["strata"]
    excluded_groups = set(policy.get("excludedFamilies", []))
    if count % len(strata):
        raise ValueError("Unequal strata")
    result, groups, texts = [], set(), set()
    for stratum in strata:
        eligible = []
        for row in records:
            if row["group"] in excluded_groups:
                continue
            for text in row["segments"]:
                if stratum["minCharacters"] <= len(text) <= stratum["maxCharacters"]:
                    identity = f'{policy["seed"]}\t{split}\t{row["id"]}\t{text}'
                    key = hashlib.sha256(identity.encode("utf-8")).hexdigest()
                    eligible.append({"id": key, "partition": split, "recordId": row["id"], "group": row["group"],
                                     "stratum": stratum["name"], "text": text})
        added = 0
        for row in sorted(eligible, key=lambda r: r["id"]):
            if row["group"] in groups or row["text"] in texts:
                continue
            result.append(row); groups.add(row["group"]); texts.add(row["text"])
            added += 1
            if added == count // len(strata):
                break
        if added != count // len(strata):
            raise ValueError(f"Insufficient families in {split}/{stratum['name']}")
    return result


def one_edit(a, b):
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) > len(b):
        a, b = b, a
    i = j = errors = 0
    while i < len(a) and j < len(b):
        if a[i] == b[j]:
            i += 1; j += 1
        else:
            errors += 1
            if errors > 1:
                return False
            if len(a) == len(b):
                i += 1
            j += 1
    return errors + (len(a) - i) + (len(b) - j) <= 1


def overlaps(text, earlier):
    for other in earlier:
        if text in other:
            return "substring"
    return "near" if any(one_edit(text, other) for other in earlier) else None


def validate_annotation(row, annotation, allowed):
    status, syllables, note = annotation
    if status not in ("accept", "exclude") or not note.strip():
        raise ValueError("Every row requires an explicit review status and note")
    if status == "exclude":
        return
    if row.get("leakage"):
        raise ValueError("Leaky row marked accepted")
    units = syllables.split()
    if len(units) != len(row["text"]) or any(unit not in allowed for unit in units):
        raise ValueError("Invalid syllable annotation")
    if len("".join(units)) > 96:
        raise ValueError("Annotation exceeds IME composing budget")


def prepare(args):
    from pypinyin import lazy_pinyin, Style
    policy = read_json(args.policy)
    if importlib.metadata.version("pypinyin") != policy["proposal"]["version"]:
        raise ValueError("Wrong pypinyin proposal version")
    corpus = read_json(args.corpus / "corpus.json")
    for split in ("train", "dev", "test"):
        if sha256(args.corpus / f"{split}.jsonl") != corpus["outputs"][split]["sha256"]:
            raise ValueError(f"Changed corpus partition {split}")
    if sha256(args.corpus / "attribution.jsonl") != corpus["attribution"]["sha256"]:
        raise ValueError("Changed corpus attribution")
    data = {split: read_lines(args.corpus / f"{split}.jsonl") for split in ("train", "dev", "test")}
    earlier = {text for row in data["train"] for text in row["segments"]}
    rows = []
    for split, count in policy["partitions"].items():
        selected = select_rows(data[split], split, count, policy)
        earlier_sorted = sorted(earlier | set(policy.get("excludedTexts", [])))
        earlier_groups = {row["group"] for part in ("train", "dev") if part != split for row in data[part]} if split == "test" else {row["group"] for row in data["train"]}
        for row in selected:
            row["leakage"] = "family" if row["group"] in earlier_groups else overlaps(row["text"], earlier_sorted)
            row["proposedSyllables"] = lazy_pinyin(row["text"], style=Style.NORMAL, v_to_u=False, tone_sandhi=False)
        rows.extend(selected)
        earlier.update(text for row in data[split] for text in row["segments"])
    args.output.mkdir(parents=True, exist_ok=True)
    if any((args.output / name).exists() for name in ("proposals.jsonl", "annotations.tsv", "selection.json")):
        raise ValueError("Existing selection/annotations must not be silently replaced")
    selected_ids = {row["recordId"] for row in rows}
    attributions = [row for row in read_lines(args.corpus / "attribution.jsonl") if row["recordId"] in selected_ids]
    if selected_ids != {row["recordId"] for row in attributions}:
        raise ValueError("Missing selected attribution")
    write_lines(args.output / "proposals.jsonl", rows)
    write_lines(args.output / "attribution.jsonl", attributions)
    with (args.output / "annotations.tsv").open("w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out, delimiter="\t", lineterminator="\n")
        writer.writerow(["id", "status", "syllables", "note"])
        for row in rows:
            writer.writerow([row["id"], "exclude" if row["leakage"] else "pending", " ".join(row["proposedSyllables"]),
                             "automatic corpus overlap: " + row["leakage"] if row["leakage"] else ""])
    selection = {"schemaVersion": 1, "policy": policy, "policySha256": sha256(args.policy),
                 "corpusManifestSha256": sha256(args.corpus / "corpus.json"),
                 "corpusPartitions": corpus["outputs"], "scriptSha256": sha256(Path(__file__)),
                 "proposalsSha256": sha256(args.output / "proposals.jsonl"), "attributionSha256": sha256(args.output / "attribution.jsonl"),
                 "selected": dict(Counter(row["partition"] for row in rows)),
                 "automaticExclusions": dict(Counter(row["partition"] + ":" + row["leakage"] for row in rows if row["leakage"])),
                 "candidateOutputsUsed": False, "annotationStatus": "pending agent review; not human gold"}
    write_json(args.output / "selection.json", selection)
    print(json.dumps({key: selection[key] for key in ("selected", "automaticExclusions")}, ensure_ascii=False))


def freeze(args):
    selection = read_json(args.output / "selection.json")
    for name, key in (("proposals.jsonl", "proposalsSha256"), ("attribution.jsonl", "attributionSha256")):
        if sha256(args.output / name) != selection[key]:
            raise ValueError(f"Changed {name}")
    with (args.output / "annotations.tsv").open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        annotations = list(reader)
    rows = read_lines(args.output / "proposals.jsonl")
    by_id = {row["id"]: row for row in annotations}
    if len(by_id) != len(annotations) or set(by_id) != {r["id"] for r in rows}:
        raise ValueError("Annotations must cover every selected row exactly once")
    allowed = set(args.syllables.read_text(encoding="utf-8").splitlines())
    accepted = {"dev": [], "test": []}
    for row in rows:
        a = by_id[row["id"]]
        validate_annotation(row, [a["status"], a["syllables"], a["note"]], allowed)
        if a["status"] == "accept":
            units = a["syllables"].split()
            accepted[row["partition"]].append([row["id"], "".join(units), row["text"], row["stratum"], " ".join(units)])
    if (args.output / "frozen.json").exists():
        raise ValueError("Frozen dataset already exists; create a new version rather than overwrite")
    outputs = {}
    for split, values in accepted.items():
        file = args.output / f"{split}.tsv"
        file.write_text("# id\tquery\texpected\tstratum\treviewedSyllables\n" + "".join("\t".join(v) + "\n" for v in values), encoding="utf-8")
        outputs[split] = {"sha256": sha256(file), "rows": len(values), "strata": dict(Counter(v[3] for v in values))}
    write_json(args.output / "frozen.json", {"schemaVersion": 1, "selectionSha256": sha256(args.output / "selection.json"),
        "annotationsSha256": sha256(args.output / "annotations.tsv"), "attributionSha256": selection["attributionSha256"],
        "scriptSha256": sha256(Path(__file__)), "syllablesSha256": sha256(args.syllables), "outputs": outputs,
        "modelFrozenBeforeCandidateEvaluation": sha256(args.model), "reviewer": "Codex agent, pre-candidate linguistic review; not independent human annotation",
        "testUse": "test only after development mode/hash freeze; no post-result aliases"})
    print(json.dumps(outputs))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("prepare")
    command.add_argument("corpus", type=Path); command.add_argument("policy", type=Path); command.add_argument("output", type=Path)
    command = sub.add_parser("freeze")
    command.add_argument("output", type=Path); command.add_argument("syllables", type=Path); command.add_argument("model", type=Path)
    args = parser.parse_args()
    (prepare if args.command == "prepare" else freeze)(args)


if __name__ == "__main__":
    main()
