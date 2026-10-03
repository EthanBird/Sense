#!/usr/bin/env python3
"""Prepare an attributed, grouped sentence corpus. Offline; never promotes a model."""
from __future__ import annotations

import hashlib
import argparse
import importlib.metadata
import json
import platform
import unicodedata
from collections import Counter
from pathlib import Path

from audit_sentence_corpus import audit_source, han, replay_texts, sha256, source_lines

SPLIT_SEED = "sense-cmn-v1"


def digest_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalize_sentence(text, convert):
    normalized = convert(unicodedata.normalize("NFKC", text)).strip()
    if not all(han(ch) or ch.isspace() or unicodedata.category(ch).startswith("P") for ch in normalized):
        return None
    runs = []
    run = []
    for ch in normalized + "。":
        if han(ch):
            run.append(ch)
        elif run:
            runs.append("".join(run))
            run.clear()
    skeleton = "".join(runs)
    if not 2 <= len(skeleton) <= 64:
        return None
    return skeleton, [r for r in runs if len(r) >= 2]


def split_for(group):
    bucket = int(digest_text(SPLIT_SEED + ":" + group)[:16], 16) % 10_000
    return "train" if bucket < 9000 else "dev" if bucket < 9500 else "test"


def near_duplicate_groups(keys):
    """Exact edit-distance <= 1 connected components for skeletons of 6..64 characters.

    Wildcard signatures only join substitutions at the SAME offset; otherwise rotations
    with edit distance two could be falsely called one edit. Deletions use exact lookup.
    This intentionally is not a paraphrase detector or an arbitrary similarity threshold.
    """
    keys = sorted(set(keys))
    parent = list(range(len(keys)))
    sizes = [1] * len(keys)

    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def join(a, b):
        a, b = root(a), root(b)
        if a == b:
            return
        if sizes[a] < sizes[b]:
            a, b = b, a
        parent[b] = a
        sizes[a] += sizes[b]

    index = {key: i for i, key in enumerate(keys) if 6 <= len(key) <= 64}
    signatures = {}
    for key, i in index.items():
        for position in range(len(key)):
            deleted = key[:position] + key[position + 1:]
            if deleted in index:
                join(i, index[deleted])
            signature = (position, deleted)
            previous = signatures.setdefault(signature, i)
            join(i, previous)
    family_ids = {}
    for i, key in enumerate(keys):
        r = root(i)
        candidate = digest_text(key)
        family_ids[r] = min(candidate, family_ids.get(r, candidate))
    return {key: family_ids[root(i)] for i, key in enumerate(keys)}


def prepare_records(rows, convert, protected):
    by_key = {}
    counts = Counter()
    blocked_keys = set(protected)
    for source in sorted(rows, key=lambda r: r["id"]):
        counts["inputRows"] += 1
        value = normalize_sentence(source["text"], convert)
        if value is None or not value[1]:
            counts["rejectedFormatRows"] += 1
            continue
        key, segments = value
        if key in protected or any(len(p) >= 6 and p in key for p in protected):
            blocked_keys.add(key)
            counts["protectedRows"] += 1
            continue
        if key in by_key:
            counts["duplicateSkeletonRows"] += 1
            by_key[key]["sources"].append(source)
        else:
            by_key[key] = {"key": key, "segments": segments, "sources": [source]}
    groups = near_duplicate_groups(set(by_key) | blocked_keys)
    blocked_groups = {groups[p] for p in blocked_keys}
    records = []
    for key, row in sorted(by_key.items()):
        group = groups[key]
        if group in blocked_groups:
            counts["protectedNeighborRows"] += len(row["sources"])
            continue
        records.append({"id": digest_text(key), "group": group, "split": split_for(group), **row})
    counts["uniqueSentences"] = len(records)
    counts["families"] = len({r["group"] for r in records})
    counts["largestFamily"] = max(Counter(r["group"] for r in records).values(), default=0)
    return {"records": records, "audit": dict(sorted(counts.items()))}


def build_partitions(records, protected):
    splits = {"train": [], "dev": [], "test": []}
    seen = set()
    counts = Counter()
    for split in splits:
        for record in sorted((r for r in records if r["split"] == split), key=lambda r: r["id"]):
            retained = []
            for segment in record["segments"]:
                if segment in protected:
                    counts["protectedSegments"] += 1
                elif split != "train" and segment in seen:
                    counts["heldoutDuplicateSegments"] += 1
                else:
                    retained.append(segment)
                seen.add(segment)
            if retained:
                splits[split].append({**record, "segments": retained})
            else:
                counts["emptyAfterSegmentExclusion"] += 1
    return splits, dict(sorted(counts.items()))


def domain(segments):
    text = "".join(segments)
    if any(word in text for word in ("计算机", "电脑", "软件", "编程", "数据库", "服务器", "代码", "算法", "模型", "输入法", "人工智能")):
        return "technical-heuristic"
    if any(word in text for word in ("你好", "谢谢", "再见", "为什么", "怎么", "请问")) or text.startswith(("我", "你")) or text.endswith(("吗", "呢", "吧")):
        return "conversational-heuristic"
    return "general-heuristic"


def write_jsonl(path, rows):
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--source-id", default="tatoeba-cmn-detailed-20260926")
    parser.add_argument("--replays", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    import opencc
    version = importlib.metadata.version("OpenCC")
    if version != "1.4.2":
        raise ValueError(f"Expected pinned OpenCC 1.4.2; found {version}")
    converter = opencc.OpenCC("t2s.json")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported source manifest")
    source = next(s for s in manifest["sources"] if s["id"] == args.source_id)
    if source["format"] != "tatoeba-detailed-bz2":
        raise ValueError("Preparation requires the attributed detailed export")
    audit_source(source, args.raw_dir, set())  # verifies hash, row identity, shape and license
    protected = {"".join(ch for ch in converter.convert(t) if han(ch)) for t in replay_texts(args.replays)}
    rows = []
    with source_lines(args.raw_dir / source["fileName"], source["format"]) as stream:
        for line in stream:
            f = line.rstrip("\r\n").split("\t")
            if f[1] != "cmn":
                continue
            if f[3] in ("", "\\N"):
                raise ValueError("Missing contributor; decide attribution before including this source")
            rows.append({"id": int(f[0]), "text": f[2], "author": f[3], "modified": f[5]})
    prepared = prepare_records(rows, converter.convert, protected)
    splits, segment_audit = build_partitions(prepared["records"], protected)
    if any(not rows for rows in splits.values()):
        raise ValueError("Corpus does not populate all three partitions")
    # A group is an indivisible split unit; no test text is read by model fitting.
    group_splits = {}
    for split, records in splits.items():
        for row in records:
            if group_splits.setdefault(row["group"], split) != split:
                raise AssertionError("Duplicate family crosses partitions")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    outputs = {}
    attribution = []
    for split, records in splits.items():
        output = args.output_dir / (split + ".jsonl")
        write_jsonl(output, ({"id": r["id"], "group": r["group"], "segments": r["segments"], "domain": domain(r["segments"])} for r in records))
        outputs[split] = {"fileName": output.name, "sha256": sha256(output), "records": len(records),
                          "segments": sum(len(r["segments"]) for r in records),
                          "characters": sum(sum(map(len, r["segments"])) for r in records),
                          "domains": dict(sorted(Counter(domain(r["segments"]) for r in records).items()))}
        for record in records:
            for original in record["sources"]:
                attribution.append({"recordId": record["id"], "group": record["group"], "split": split,
                                    "sourceId": source["id"], "sentenceId": original["id"],
                                    "contributor": original["author"], "originalText": original["text"],
                                    "modifiedAtSource": original["modified"],
                                    "url": f"https://tatoeba.org/en/sentences/show/{original['id']}",
                                    "license": source["license"], "licenseUrl": source["licenseUrl"],
                                    "transformation": "NFKC; OpenCC 1.4.2 t2s; Han spans; dedup/group/exclusion"})
    attribution_file = args.output_dir / "attribution.jsonl"
    write_jsonl(attribution_file, sorted(attribution, key=lambda r: (r["recordId"], r["sentenceId"])))
    resources = Path(opencc.__file__).parent / "clib" / "share" / "opencc"
    resource_hashes = {p.relative_to(resources).as_posix(): sha256(p) for p in sorted(resources.rglob("*")) if p.is_file()}
    if not resource_hashes:
        raise ValueError("OpenCC dictionary resources missing from provenance")
    report = {"schemaVersion": 1, "source": source, "sourceManifestSha256": sha256(args.manifest),
              "scriptSha256": sha256(Path(__file__)), "python": platform.python_version(),
              "unicodeVersion": unicodedata.unidata_version,
              "normalization": {"opencc": version, "config": "t2s.json", "resourceHashes": resource_hashes},
              "grouping": {"unit": "sentence Han skeleton", "nearDuplicates": "edit distance <= 1 connected components; 6..64 scalars", "seed": SPLIT_SEED, "buckets": [9000, 500, 500]},
              "exclusion": {"replaySha256": {p.name: sha256(p) for p in sorted(args.replays.glob("*.tsv"))},
                            "protectedSkeletons": len(protected), "rule": "exact and near families; substring if 6+ chars; exact matching Han segments"},
              "audit": prepared["audit"], "segmentAudit": segment_audit, "outputs": outputs,
              "attribution": {"fileName": attribution_file.name, "sha256": sha256(attribution_file), "rows": len(attribution)},
              "testUse": "frozen; fitting must use train, tuning must use dev; test scoring is a separate explicit operation",
              "productionReady": False}
    # Local pipeline manifest accompanies the files. The checked-in report has no raw sentences.
    payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    (args.output_dir / "corpus.json").write_text(payload, encoding="utf-8")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(payload, encoding="utf-8")
    print(json.dumps({"audit": prepared["audit"], "segmentAudit": segment_audit, "outputs": outputs}, ensure_ascii=False))


if __name__ == "__main__":
    main()
