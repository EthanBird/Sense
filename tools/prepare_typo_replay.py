"""Freeze paired clean/synthetic QWERTY errors from reviewed P2C source families.

Source sentences are the existing, known M12 corpus, NOT fresh natural-error gold.
No candidate, frequency, decoder or production neighbor table is consulted.
"""
import argparse
import csv
import hashlib
import io
import json
import os
from collections import Counter
from pathlib import Path

SEED = "sense-26qwerty-error-v1"
POSITIONS = {key: (x + shift, y) for y, (row, shift) in enumerate([
    ("qwertyuiop", 0.0), ("asdfghjkl", 0.25), ("zxcvbnm", 0.75)]) for x, key in enumerate(row)}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def neighbors(key):
    x, y = POSITIONS[key]
    return sorted(other for other, (u, v) in POSITIONS.items()
                  if other != key and (x-u)**2 + (y-v)**2 <= 1.25**2)


def variants(identity, query, syllables):
    if query != "".join(syllables) or not query.isascii() or not query.isalpha() or not query.islower():
        raise ValueError("Expected reviewed lowercase pinyin")
    rows = [("clean", "none", -1, query), ("joints", "none", -1, "'".join(syllables))]
    for operation in ("delete", "repeat", "neighbor", "transpose"):
        for band, zone in enumerate(("head", "middle", "tail")):
            choices = []
            for index, letter in enumerate(query):
                if index * 3 // len(query) != band:
                    continue
                if operation == "delete":
                    options = [query[:index] + query[index+1:]]
                elif operation == "repeat":
                    options = [query[:index] + letter + query[index:]]
                elif operation == "neighbor":
                    options = [query[:index] + other + query[index+1:] for other in neighbors(letter)]
                else:
                    options = [query[:index] + query[index+1] + letter + query[index+2:]] if index+1 < len(query) and letter != query[index+1] else []
                for typed in options:
                    if typed and typed != query:
                        order = hashlib.sha256(f"{SEED}\t{identity}\t{operation}\t{zone}\t{index}\t{typed}".encode()).hexdigest()
                        choices.append((order, index, typed))
            if choices:
                _, index, typed = min(choices)
                rows.append((operation, zone, index, typed))
    # One phonetic confusion per sentence, selected independently of output. Exact legal
    # syllables can be ambiguous; this tests reconstruction of a KNOWN synthetic intention.
    fuzzy = []
    start = 0
    for unit in syllables:
        if unit.startswith(("zh", "ch", "sh")):
            fuzzy.append((start+1, query[:start+1] + query[start+2:]))
        if unit.startswith(("z", "c", "s")) and not unit.startswith(("zh", "ch", "sh")):
            fuzzy.append((start+1, query[:start+1] + "h" + query[start+1:]))
        if unit[0] in "nlfh":
            other = {"n":"l", "l":"n", "f":"h", "h":"f"}[unit[0]]
            fuzzy.append((start, query[:start] + other + query[start+1:]))
        if unit.endswith("ng"):
            end = start + len(unit)-1
            fuzzy.append((end, query[:end] + query[end+1:]))
        start += len(unit)
    if fuzzy:
        index, typed = min(fuzzy, key=lambda v: hashlib.sha256(f"{SEED}\t{identity}\tfuzzy\t{v}".encode()).hexdigest())
        rows.append(("fuzzy", "phonetic", index, typed))
    return rows


def prepare(source, output):
    if output.exists():
        raise ValueError("Frozen output must be new; never overwrite earlier recipes")
    frozen = json.loads((source / "frozen.json").read_text("utf-8"))
    for name, digest in [("dev.tsv", frozen["outputs"]["dev"]["sha256"]), ("test.tsv", frozen["outputs"]["test"]["sha256"]),
                         ("attribution.jsonl", frozen["attributionSha256"]), ("annotations.tsv", frozen["annotationsSha256"])]:
        if sha(source / name) != digest:
            raise ValueError(f"Changed source {name}")
    generated = {}
    for partition in ("dev", "test"):
        rows = []
        for fields in csv.reader((line for line in (source / f"{partition}.tsv").read_text("utf-8").splitlines() if line and not line.startswith("#")), delimiter="\t"):
            identity, query, expected, stratum, units = fields
            syllables = units.split()
            for operation, zone, index, typed in variants(identity, query, syllables):
                row_id = hashlib.sha256(f"{identity}\t{operation}\t{zone}\t{index}\t{typed}".encode()).hexdigest()
                rows.append([row_id, identity, typed, query, expected, stratum, units, operation, zone, str(index)])
        generated[partition] = rows
    output.mkdir(parents=True)
    manifest = {"schemaVersion": 1, "seed": SEED, "source": Path(os.path.relpath(source, output)).as_posix(), "sourceFrozenSha256": sha(source / "frozen.json"),
                "sourceAttributionSha256": sha(source / "attribution.jsonl"), "generatorSha256": sha(Path(__file__)),
                "scope": "Known reviewed P2C base sentences; new deterministic single-error perturbations, not natural user typo gold",
                "candidateOutputsUsed": False, "selection": "SHA256-min within each operation and equal-width character-offset third; include all eligible source rows without result-based filtering",
                "operations": ["clean", "joints", "delete", "repeat", "neighbor", "transpose", "fuzzy"],
                "keyboard": {"rows": [["qwertyuiop",0.0],["asdfghjkl",0.25],["zxcvbnm",0.75]], "neighborRadius":1.25},
                "duplicates": "Keep source/operation/zone labels even if typed strings collide; report by source family, not independent IID errors",
                "holdoutPolicy": "Development mutations may be inspected; test mutation outputs only opened after candidate code freeze. Existing clean test results are known.",
                "outputs": {}}
    for partition, rows in generated.items():
        path = output / f"{partition}.tsv"
        with path.open("w", encoding="utf-8", newline="") as stream:
            stream.write("# id\tsourceId\ttyped\tcanonical\texpected\tstratum\tsyllables\toperation\tzone\teditOffset\n")
            csv.writer(stream, delimiter="\t", lineterminator="\n").writerows(rows)
        manifest["outputs"][partition] = {"sha256": sha(path), "rows":len(rows), "families":len({row[1] for row in rows}),
            "operations":dict(Counter(row[7] for row in rows)), "distinctSourceInputs":len({(row[1],row[2]) for row in rows})}
    (output / "frozen.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, args.output)["outputs"], indent=2))
