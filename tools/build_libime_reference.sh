#!/usr/bin/env bash
set -euo pipefail
artifact=${1:?absolute reference directory required}
[[ "$artifact" = /* ]] || exit 2
script=$(cd "$(dirname "$0")" && pwd)
prefix="$artifact/root/usr"
g++ -std=c++17 -O2 -Wall -Wextra -Werror "$script/libime_reference_probe.cpp" \
  -isystem "$prefix/include" -isystem "$prefix/include/LibIME" -isystem "$prefix/include/Fcitx5/Utils" \
  -L"$prefix/lib/x86_64-linux-gnu" -Wl,-rpath,"$prefix/lib/x86_64-linux-gnu" \
  -Wl,-rpath-link,"$prefix/lib/x86_64-linux-gnu" \
  -lIMEPinyin -lIMECore -lFcitx5Utils -o "$artifact/libime-reference"
sha256sum "$artifact/libime-reference"
