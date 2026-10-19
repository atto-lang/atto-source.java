#!/usr/bin/env bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail
if [[ $# -ne 7 || "${2:-}" != --source-dir
      || "${4:-}" != --source-commit
      || "${6:-}" != --branch ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --source-commit 40_HEX_SHA --branch ci/oreslang-NAME\n' >&2
  exit 1
fi
[[ "${5:-}" =~ ^[a-f0-9]{40}$ ]] || {
  printf 'PRIVATE_CI_DESTINATION_REJECTED: source commit must be an exact 40-character Git SHA\n' >&2
  exit 1
}
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/publish-private-ci-source.py" "$@"
