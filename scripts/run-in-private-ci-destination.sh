#!/usr/bin/env bash
# Destination-bound private CI v3 dispatcher; never execute a caller-supplied command.
set -euo pipefail
if [[ $# -ne 9    || "${2:-}" != --source-dir    || "${4:-}" != --source-commit    || "${6:-}" != --source-pr    || "${8:-}" != --test-plan ]]; then
  printf '%s\n'     'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --source-commit FULL_40_HEX_SHA --source-pr POSITIVE_INTEGER --test-plan ci/test-plans/NAME.json' >&2
  exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/publish-private-ci-source-v3.py" "$@"
