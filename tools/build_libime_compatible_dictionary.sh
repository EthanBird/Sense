#!/usr/bin/env bash
set -euo pipefail
set -o noclobber
ulimit -c 0
artifact=${1:?absolute artifact directory required}
[[ "$artifact" = /* ]] || exit 2
data="$artifact/current-data"; prefix="$artifact/root/usr"
[[ ! -e "$data/compatible-sc.dict" && ! -e "$data/compatible-sc.txt" ]] || exit 3
script=$(cd "$(dirname "$0")" && pwd)
g++ -std=c++17 -O2 -Wall -Wextra -Werror "$script/libime_dictionary_compat.cpp" \
  -isystem "$prefix/include" -isystem "$prefix/include/LibIME" -isystem "$prefix/include/Fcitx5/Utils" \
  -L"$prefix/lib/x86_64-linux-gnu" -Wl,-rpath,"$prefix/lib/x86_64-linux-gnu" \
  -Wl,-rpath-link,"$prefix/lib/x86_64-linux-gnu" -lIMEPinyin -lIMECore -lFcitx5Utils -o "$artifact/libime-dictionary-compat"
export LD_LIBRARY_PATH="$artifact/data-tools/root/usr/lib/x86_64-linux-gnu:$prefix/lib/x86_64-linux-gnu"
"$artifact/libime-dictionary-compat" "$data/dict_sc.txt" > "$data/compatible-sc.txt" 2> "$data/excluded-dictionary-rows.tsv"
"$artifact/data-tools/root/usr/bin/libime_pinyindict" "$data/compatible-sc.txt" "$data/compatible-sc.dict"
tail -1 "$data/excluded-dictionary-rows.tsv"
sha256sum "$data/compatible-sc.dict" "$data/compatible-sc.txt"
