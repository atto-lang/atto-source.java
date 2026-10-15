#!/usr/bin/env bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail
if [[ $# -ne 9 || "${2:-}" != --source-dir || "${4:-}" != --branch || "${6:-}" != --expected-source-tree || "${8:-}" != --expected-source-commit ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --branch ci/oreslang-NAME --expected-source-tree HEX40 --expected-source-commit HEX40\n' >&2
  exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/publish-private-ci-source.py" "$@"
