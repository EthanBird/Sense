#!/usr/bin/env bash
# Additional host converters in a separate extraction root; original reference stays pinned.
set -euo pipefail
artifact=${1:?reference artifact directory required}
[[ "$artifact" = /* ]] || exit 2
mkdir -p "$artifact/data-tools/packages" "$artifact/data-tools/root"
cd "$artifact/data-tools/packages"
packages=(libime-bin=1.0.11-1build1 libboost-iostreams1.74.0=1.74.0-14ubuntu3)
apt-cache show "${packages[@]}" > ../package-metadata.txt
apt-get download "${packages[@]}"
python3 - <<'PY'
import pathlib,hashlib
expected={}
for p in pathlib.Path('../package-metadata.txt').read_text().split('\n\n'):
    f=dict(s.split(': ',1) for s in p.splitlines() if ': ' in s and not s.startswith(' '))
    if 'Filename' in f: expected[pathlib.Path(f['Filename']).name]=f['SHA256']
paths=list(pathlib.Path('.').glob('*.deb'))
assert len(paths)==len(expected)==2
for p in paths: assert hashlib.sha256(p.read_bytes()).hexdigest()==expected[p.name]
PY
for package in *.deb; do dpkg-deb -x "$package" ../root; done
sha256sum ./*.deb > ../package-sha256.txt
export LD_LIBRARY_PATH="$artifact/data-tools/root/usr/lib/x86_64-linux-gnu:$artifact/root/usr/lib/x86_64-linux-gnu"
"$artifact/data-tools/root/usr/bin/libime_slm_build_binary" --help 2>&1 || true
