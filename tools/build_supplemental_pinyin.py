"""Build an experimental exact-key fallback layer without changing existing word/index scores."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

from build_pinyin_lexicon import LexiconCandidate, MAX_CANDIDATES, read_binary, write_binary
from lexicon_sources import is_han_text, normalized_syllables


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def supplement(entries, sources, allowed, weight):
    if not 1 <= weight <= 128:
        raise ValueError("Explicit bounded fallback prior required")
    # Upstream extension weights are often all 100, not comparable usage counts.
    # Calibrate a separate uniform prior; keep all existing source scores untouched.
    additions = defaultdict(dict)
    counts = Counter()
    for source_id, lines in sources:
        for line in lines:
            if not line or line.startswith("#") or "\t" not in line:
                continue
            parts = line.split("\t")
            text, units = parts[0].strip(), normalized_syllables(parts[1])
            if not is_han_text(text) or not 2 <= len(text) <= 8:
                counts["outsideHanLengthPolicy"] += 1
                continue
            if len(units) != len(text) or any(u not in allowed for u in units):
                counts["invalidSyllables"] += 1
                continue
            code = "".join(units)
            if any(c.text == text for c in entries.get(code, [])):
                counts["alreadyPresent"] += 1
                continue
            if text in additions[code]:
                counts["duplicateSupplement"] += 1
                continue
            additions[code][text] = LexiconCandidate(text, weight, "".join(u[0] for u in units), 1,
                                                    source_id, False, False, False)
    result = dict(entries)
    for code, values in additions.items():
        old = entries.get(code, [])
        added = sorted(values.values(), key=lambda c: (len(c.text), c.text, c.initials))
        available = max(0, MAX_CANDIDATES - len(old))
        kept = added[:available]
        if kept:
            result[code] = [*old, *kept]
        counts["retainedSupplement"] += len(kept)
        counts["capacityRejected"] += len(added) - len(kept)
    return result, dict(counts)


def build(base, syllables, policy_path, output, weight):
    if output.exists():
        raise ValueError("Retain prior experiment; use a new directory")
    policy = json.loads(policy_path.read_text("utf-8"))
    if policy["baseSha256"] != sha(base) or policy["syllablesSha256"] != sha(syllables):
        raise ValueError("Changed baseline assets")
    if policy["license"] != "GPL-3.0-only":
        raise ValueError("Unexpected source license boundary")
    root = policy_path.parent.resolve()
    def pinned(path, expected):
        path = (root / path).resolve()
        if not path.is_relative_to(root) or sha(path) != expected:
            raise ValueError("Source outside pinned policy or hash changed")
        return path
    pinned(policy["licenseFile"], policy["licenseSha256"])
    sources = [(row["path"], pinned(row["path"], row["sha256"]).read_text("utf-8").splitlines())
               for row in policy["sources"]]
    entries = read_binary(base)
    result, counts = supplement(entries, sources, set(syllables.read_text("utf-8").splitlines()), weight)
    # Verify every original candidate and all private namespaces, not only a few anchors.
    assert all(result[code][:len(values)] == values for code, values in entries.items())
    assert all(result[code] == values for code, values in entries.items() if code[0] in "{~}")
    output.mkdir(parents=True)
    binary = output / "pinyin_lexicon.bin"
    write_binary(result, binary)
    manifest = {"schemaVersion": 1, "scope": "Prototype exact-key coverage layer, not a production replacement or quality claim",
                "policy": policy, "policySha256": sha(policy_path), "generatorSha256": sha(Path(__file__)),
                "fallbackWeight": weight, "sourceTier": 1, "indexesAdded": False,
                "existingCandidatesAndIndexesPreserved": True, "counts": counts,
                "asset": {"file": binary.name, "bytes": binary.stat().st_size, "sha256": sha(binary)}}
    (output / "build.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "syllables", "policy", "output"):
        p.add_argument(name, type=Path)
    p.add_argument("--weight", type=int, required=True)
    args = p.parse_args()
    result = build(args.base, args.syllables, args.policy, args.output, args.weight)
    print(json.dumps({"counts": result["counts"], "asset": result["asset"]}))
