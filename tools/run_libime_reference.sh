#!/usr/bin/env bash
set -euo pipefail
set -o noclobber
artifact=${1:?absolute reference directory required}
input=${2:?absolute input TSV required}
output=${3:?absolute new output JSONL required}
[[ "$artifact" = /* && "$input" = /* && "$output" = /* ]] || exit 2
[[ ! -e "$output" && ! -e "$output.stderr" ]] || exit 3
export LD_LIBRARY_PATH="$artifact/root/usr/lib/x86_64-linux-gnu"
"$artifact/libime-reference" "$artifact/root/usr/lib/x86_64-linux-gnu/libime/zh_CN.lm" \
  "$artifact/root/usr/share/libime/sc.dict" "$input" > "$output" 2> "$output.stderr"
