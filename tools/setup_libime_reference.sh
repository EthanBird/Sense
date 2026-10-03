#!/usr/bin/env bash
# Local extraction only: no apt install, no system IME activation, no user history.
set -euo pipefail
artifact=${1:?absolute reference directory required}
[[ "$artifact" = /* ]] || exit 2
mkdir -p "$artifact/packages" "$artifact/root"
cd "$artifact/packages"
packages=(libimecore0=1.0.11-1build1 libimepinyin0=1.0.11-1build1
  libimecore-dev=1.0.11-1build1 libimepinyin-dev=1.0.11-1build1
  libime-data=1.0.11-1build1 libime-data-language-model=1.0.11-1build1
  libfcitx5utils2=5.0.14-1 libfcitx5utils-dev=5.0.14-1 libdouble-conversion3=3.1.7-4
  libboost1.74-dev=1.74.0-14ubuntu3)
apt-cache show "${packages[@]}" > ../package-metadata.txt
apt-get download "${packages[@]}"
python3 - <<'PY'
import hashlib, pathlib
metadata = pathlib.Path('../package-metadata.txt').read_text()
expected = {}
for paragraph in metadata.split('\n\n'):
    fields = dict(line.split(': ', 1) for line in paragraph.splitlines() if ': ' in line and not line.startswith(' '))
    if 'Filename' in fields:
        expected[pathlib.Path(fields['Filename']).name] = fields['SHA256']
paths = sorted(pathlib.Path('.').glob('*.deb'))
assert len(paths) == len(expected) == 10
for path in paths:
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected[path.name], path
print('All 10 packages match SHA-256 from apt repository metadata')
PY
for package in *.deb; do dpkg-deb -x "$package" ../root; done
sha256sum ./*.deb > ../package-sha256.txt
export LD_LIBRARY_PATH="$artifact/root/usr/lib/x86_64-linux-gnu"
ldd "$artifact/root/usr/lib/x86_64-linux-gnu/libIMEPinyin.so.0" > ../linked-libraries.txt
cat ../linked-libraries.txt
if grep -q 'not found' ../linked-libraries.txt; then exit 3; fi
{ cat /etc/os-release; g++ --version; uname -m; } > ../host-environment.txt
