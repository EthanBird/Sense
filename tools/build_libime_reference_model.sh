#!/usr/bin/env bash
# Clean model-only rebuild recipe; dictionary compatibility is an independent step.
set -euo pipefail
ulimit -c 0
artifact=${1:?absolute reference directory required}
output=${2:?absolute new LM output required}
[[ "$artifact" = /* && "$output" = /* ]] || exit 2
[[ ! -e "$output" ]] || exit 3
data="$artifact/current-data"
python3 - "$data" <<'PY'
import pathlib,hashlib,json,sys
r=pathlib.Path(sys.argv[1]);m=json.loads((r/'source-manifest.json').read_text())
p=r/'lm_sc.arpa';assert hashlib.sha256(p.read_bytes()).hexdigest()==m['files']['lm_sc.arpa']['sha256']
PY
export LD_LIBRARY_PATH="$artifact/data-tools/root/usr/lib/x86_64-linux-gnu:$artifact/root/usr/lib/x86_64-linux-gnu"
scratch=$(mktemp -d /dev/shm/sense-libime-rebuild.XXXXXX)
trap 'rmdir "$scratch" 2>/dev/null || true' EXIT
"$artifact/data-tools/root/usr/bin/libime_slm_build_binary" -s -a 22 -q 4 -T "$scratch/" -S 256M \
  trie "$data/lm_sc.arpa" "$output"
sha256sum "$output"
