#!/usr/bin/env python3
"""Check the actual APK, including resource packager transformations, not source assets."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def audit(apk):
    with ZipFile(apk) as package:
        notice = json.loads(package.read("assets/pinyin_character_lm_notice.json"))
        model_info, attribution_info = notice["model"], notice["attribution"]
        model = package.read("assets/" + model_info["file"])
        attribution = package.read("assets/" + attribution_info["file"])
        assert digest(model) == model_info["sha256"], "APK model differs from provenance"
        assert len(model) == model_info["bytes"], "APK model size differs"
        assert digest(attribution) == attribution_info["sha256"], "APK attribution differs from provenance"
        lines = attribution.decode("utf-8").splitlines()
        assert lines.pop(0) == "# sentenceId\tcontributor", "Missing attribution header"
        rows = [line.split("\t") for line in lines]
        assert all(len(row) == 2 and row[0].isdigit() and row[1] for row in rows)
        assert len(rows) == attribution_info["sentences"]
        assert len({row[0] for row in rows}) == len(rows), "Duplicate source IDs"
        authors = {row[1] for row in rows}
        assert len(authors) == attribution_info["contributors"]
        readable = package.read("assets/PINYIN-LM-NOTICE.txt").decode("utf-8")
        assert attribution_info["file"] in readable
        assert authors <= set(readable.splitlines())
        assert notice["license"] == "CC-BY-2.0-FR"
        assert notice["decoderMode"] == "lm0.5"
        assert not any(name.startswith("assets/benchmarks/") for name in package.namelist())
        return {"schemaVersion": 1, "scope": "APK zip content audit; not Android execution",
                "apkSha256": digest(apk.read_bytes()), "apkBytes": apk.stat().st_size,
                "model": model_info, "attribution": attribution_info,
                "attributionBytes": len(attribution),
                "attributionCompressedBytes": package.getinfo("assets/" + attribution_info["file"]).compress_size,
                "decoderMode": notice["decoderMode"], "passed": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    result = audit(args.apk)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
