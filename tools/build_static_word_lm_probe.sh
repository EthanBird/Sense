#!/usr/bin/env bash
# Uses already extracted dependencies, no package installation or system IME activation.
set -euo pipefail
artifact=${1:?absolute existing libime reference directory required}
output=${2:?absolute new output directory required}
[[ "$artifact" = /* && "$output" = /* && -d "$output" ]] || exit 2
[[ ! -e "$output/static-word-lm" && ! -e "$output/word-graph-test" ]] || exit 3
script=$(cd "$(dirname "$0")" && pwd)
prefix="$artifact/root/usr"
g++ -std=c++17 -O2 -Wall -Wextra -Werror "$script/word_lm_graph_test.cpp" -o "$output/word-graph-test"
"$output/word-graph-test"
g++ -std=c++17 -O2 -Wall -Wextra -Werror "$script/static_word_lm_probe.cpp" \
  -isystem "$prefix/include" -isystem "$prefix/include/LibIME" -isystem "$prefix/include/Fcitx5/Utils" \
  -L"$prefix/lib/x86_64-linux-gnu" -Wl,-rpath,"$prefix/lib/x86_64-linux-gnu" \
  -Wl,-rpath-link,"$prefix/lib/x86_64-linux-gnu" -lIMECore -lFcitx5Utils -o "$output/static-word-lm"
sha256sum "$output/static-word-lm" "$output/word-graph-test"
