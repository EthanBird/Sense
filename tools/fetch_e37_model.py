"""Fetch only the pinned official RBT3 inputs, outside the source checkout.

This downloads data, never imports model code or executes an upstream script.
The exact LFS SHA is checked before loading the weights with weights_only=True.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request

REPO = "hfl/rbt3"
REVISION = "0aa0527ff4170f29e1dfd3eb6ef60dc67e1bf75c"
WEIGHT_SHA = "3e04f7477f55dffce2a2fbc4d0ba35068415162a9e92e3d5cc74a49781ba4eb0"
FILES = ("README.md", "config.json", "vocab.txt", "special_tokens_map.json",
         "tokenizer_config.json", "pytorch_model.bin")


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def fetch(destination):
    destination.mkdir(parents=True, exist_ok=True)
    api = f"https://huggingface.co/api/models/{REPO}/revision/{REVISION}?blobs=true"
    with urllib.request.urlopen(api, timeout=60) as r:
        metadata = json.load(r)
    if metadata["sha"] != REVISION or metadata["cardData"].get("license") != "apache-2.0":
        raise ValueError("Unexpected pinned model metadata")
    siblings = {s["rfilename"]: s for s in metadata["siblings"]}
    if siblings["pytorch_model.bin"]["lfs"]["sha256"] != WEIGHT_SHA:
        raise ValueError("Unexpected model LFS object")
    manifest = dict(repo=REPO, revision=REVISION, license="apache-2.0", files={})
    for name in FILES:
        path = destination / name
        info = siblings[name]
        url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}"
        if not path.exists():
            partial = path.with_name(path.name + ".partial")
            start = time.monotonic(); last_report = start
            with urllib.request.urlopen(url, timeout=90) as src, partial.open("wb") as out:
                total = 0
                while chunk := src.read(1024 * 1024):
                    out.write(chunk); total += len(chunk)
                    if time.monotonic() - last_report > 10:
                        print(f"{name}: {total}/{info['size']} bytes", flush=True)
                        last_report = time.monotonic()
            if partial.stat().st_size != info["size"]:
                raise ValueError(f"Size mismatch: {name}")
            if name == "pytorch_model.bin" and digest(partial) != WEIGHT_SHA:
                raise ValueError("Downloaded weights hash mismatch")
            partial.replace(path)
        if path.stat().st_size != info["size"]:
            raise ValueError(f"Existing file size mismatch: {name}")
        actual = digest(path)
        if name == "pytorch_model.bin" and actual != WEIGHT_SHA:
            raise ValueError("Existing weights hash mismatch")
        # Ordinary HF files use the Git blob SHA, not an LFS sha256.
        if "lfs" not in info:
            data = path.read_bytes()
            git_sha = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            if git_sha != info["blobId"]:
                raise ValueError(f"Git blob mismatch: {name}")
        manifest["files"][name] = dict(url=url, bytes=path.stat().st_size, sha256=actual)
        print(f"Verified {name}: {path.stat().st_size} bytes", flush=True)
    (destination / "upstream-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (destination / "download-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    p = argparse.ArgumentParser(__doc__); p.add_argument("destination", type=Path)
    args = p.parse_args(); fetch(args.destination)
