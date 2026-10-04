#!/usr/bin/env bash
set -euo pipefail
artifact=${1:?absolute existing reference directory required}
output=${2:?absolute evidence directory required}
[[ "$artifact" = /* && "$output" = /* && -d "$output" ]] || exit 2
[[ ! -e "$output/real-model-verify" ]] || exit 3
script=$(cd "$(dirname "$0")" && pwd)
prefix="$artifact/root/usr"
g++ -std=c++17 -O2 -Wall -Wextra -Werror "$script/static_word_lm_graph_verify.cpp" \
  -isystem "$prefix/include" -isystem "$prefix/include/LibIME" -isystem "$prefix/include/Fcitx5/Utils" \
  -L"$prefix/lib/x86_64-linux-gnu" -Wl,-rpath,"$prefix/lib/x86_64-linux-gnu" \
  -Wl,-rpath-link,"$prefix/lib/x86_64-linux-gnu" -lIMECore -lFcitx5Utils -o "$output/real-model-verify"
export LD_LIBRARY_PATH="$prefix/lib/x86_64-linux-gnu"
"$output/real-model-verify" "$artifact/current-data/zh_CN.lm" "$output/train-requests.tsv"
sha256sum "$output/real-model-verify"
