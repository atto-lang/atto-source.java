#!/usr/bin/env bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail
if [[ $# -ne 7 || "${2:-}" != --source-dir || "${4:-}" != --source-commit || "${6:-}" != --branch ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --source-commit SOURCE_COMMIT_SHA --branch ci/oreslang-SOURCE_TREE_SHA\n' >&2
  exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/publish-private-ci-source.py" "$@"
