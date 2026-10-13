#!/usr/bin/env bash
# Supported entry point for shell commands that transfer private Oreslang
# source to a GitHub CI repository. Verify immediately before command exec.
set -euo pipefail
if [[ $# -lt 3 || "${2:-}" != -- ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo -- command [args...]\n' >&2
  exit 1
fi
target="$1"
shift 2
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
"$script_dir/check-private-ci-destination.sh" "$target" >&2
export ORESLANG_VERIFIED_CI_DESTINATION="$target"
exec "$@"
