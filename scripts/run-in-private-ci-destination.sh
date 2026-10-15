#!/usr/bin/env bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail
if [[ $# -ne 5 || "${2:-}" != --source-dir || "${4:-}" != --branch ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --branch ci/oreslang-NAME\n' >&2
  exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/publish-private-ci-source.py" "$@"
