#!/usr/bin/env bash
set -euo pipefail
artifact=${1:?reference artifact directory required}
[[ "$artifact" = /* ]] || exit 2
data="$artifact/current-data"
[[ ! -e "$data/zh_CN.lm" && ! -e "$data/sc.dict" ]] || exit 3
python3 - "$data" <<'PY'
import pathlib,hashlib,json,sys
root=pathlib.Path(sys.argv[1]);m=json.loads((root/'source-manifest.json').read_text())
for name,pin in m['files'].items():
    p=root/name
    assert p.stat().st_size==pin['bytes'] and hashlib.sha256(p.read_bytes()).hexdigest()==pin['sha256']
PY
export LD_LIBRARY_PATH="$artifact/data-tools/root/usr/lib/x86_64-linux-gnu:$artifact/root/usr/lib/x86_64-linux-gnu"
# KenLM unlinks scratch files before resizing; DrvFS rejects that sequence. Use bounded RAM scratch.
scratch=$(mktemp -d /dev/shm/sense-libime-e14.XXXXXX)
trap 'rmdir "$scratch" 2>/dev/null || true' EXIT
"$artifact/data-tools/root/usr/bin/libime_slm_build_binary" -s -a 22 -q 4 -T "$scratch/" -S 256M \
  trie "$data/lm_sc.arpa" "$data/zh_CN.lm"
"$artifact/data-tools/root/usr/bin/libime_pinyindict" "$data/dict_sc.txt" "$data/sc.dict"
sha256sum "$data/zh_CN.lm" "$data/sc.dict"
