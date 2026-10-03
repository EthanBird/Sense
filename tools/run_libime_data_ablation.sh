#!/usr/bin/env bash
set -euo pipefail
set -o noclobber
ulimit -c 0
artifact=${1:?absolute artifact directory required}
input=${2:?absolute input path required}
output=${3:?absolute new output path required}
mode=${4:?model-only, dictionary-only or both required}
[[ "$artifact" = /* && "$input" = /* && "$output" = /* ]] || exit 2
[[ ! -e "$output" && ! -e "$output.stderr" ]] || exit 3
model="$artifact/root/usr/lib/x86_64-linux-gnu/libime/zh_CN.lm"
dictionary="$artifact/root/usr/share/libime/sc.dict"
case "$mode" in
  model-only) model="$artifact/current-data/zh_CN.lm" ;;
  dictionary-only) dictionary="$artifact/current-data/compatible-sc.dict" ;;
  both) model="$artifact/current-data/zh_CN.lm"; dictionary="$artifact/current-data/compatible-sc.dict" ;;
  *) exit 4 ;;
esac
export LD_LIBRARY_PATH="$artifact/root/usr/lib/x86_64-linux-gnu"
"$artifact/libime-reference" "$model" "$dictionary" "$input" > "$output" 2> "$output.stderr"
