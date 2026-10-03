"""Promote an E5 coverage prototype to a projectable, separately identified SPLX/4 layer."""
import argparse
from dataclasses import replace
import hashlib
import json
from pathlib import Path

from build_pinyin_lexicon import read_binary, write_binary
from layered_lexicon import project_base


def sha(data):
    return hashlib.sha256(data).hexdigest()


def build(base_path, prototype_folder, output):
    if output.exists(): raise ValueError("Retain previous builds; use a new directory")
    manifest = json.loads((prototype_folder / "build.json").read_text("utf-8"))
    base_bytes = base_path.read_bytes()
    prototype = prototype_folder / "pinyin_lexicon.bin"
    if sha(base_bytes) != manifest["policy"]["baseSha256"] or sha(prototype.read_bytes()) != manifest["asset"]["sha256"]:
        raise ValueError("Changed frozen base or coverage prototype")
    base, combined = read_binary(base_path), read_binary(prototype)
    if not base.keys() <= combined.keys(): raise ValueError("A base record was removed")
    added = 0
    for code, values in combined.items():
        old = base.get(code, [])
        if values[:len(old)] != old: raise ValueError("A base candidate was changed or reordered")
        if code[0] in "{~}" and values != old: raise ValueError("Private alias index changed")
        new = values[len(old):]
        if any(v.source_tier != 1 or v.weight != manifest["fallbackWeight"] for v in new):
            raise ValueError("Unexpected supplemental prior")
        combined[code] = [*old, *(replace(v, source_tier=2) for v in new)]
        added += len(new)
    output.mkdir(parents=True)
    asset = output / "pinyin_lexicon.bin"
    write_binary(combined, asset, version=4)
    # Byte identity proves original weights, ordering and every alias, not only word membership.
    if project_base(asset.read_bytes()) != base_bytes: raise ValueError("Base projection differs")
    result = {"schemaVersion": 1, "format": "SPLX/4", "scope": "Candidate layered vocabulary; adoption requires quality, learning and Android gates",
              "sourcePolicy": manifest["policy"], "prototypeManifestSha256": sha((prototype_folder / "build.json").read_bytes()),
              "generatorSha256": sha(Path(__file__).read_bytes()), "projectorSha256": sha(Path(__file__).with_name("layered_lexicon.py").read_bytes()),
              "baseProjection": {"format": "SPLX/3", "operation": "remove-tier-2", "sha256": sha(base_bytes), "bytes": len(base_bytes)},
              "supplementalTier": 2, "supplementalEntries": added, "fallbackWeight": manifest["fallbackWeight"],
              "baseReferenceMassUnchanged": True, "personalNewWordMembershipUsesBaseOnly": True,
              "asset": {"file": asset.name, "bytes": asset.stat().st_size, "sha256": sha(asset.read_bytes())}}
    (output / "build.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "prototype", "output"): p.add_argument(name, type=Path)
    args = p.parse_args()
    result = build(args.base, args.prototype, args.output)
    print(json.dumps({k: result[k] for k in ("supplementalEntries", "fallbackWeight", "baseProjection", "asset")}))
