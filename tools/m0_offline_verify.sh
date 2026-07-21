#!/usr/bin/env bash
set -euo pipefail

echo "M0 verification entrypoint is deprecated; running the current verification gate." >&2
exec "$(dirname "$0")/offline_verify.sh" "$@"
