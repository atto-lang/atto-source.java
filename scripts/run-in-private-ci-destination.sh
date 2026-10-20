#!/usr/bin/env bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail

usage() {
  printf '%s\n'     'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --branch ci/oreslang-NAME --source-pr NUMBER --source-commit SHA --source-tree SHA [--maintainer-approval-comment ID --security-approval-comment ID]' >&2
  exit 1
}

[[ $# -eq 11 || $# -eq 15 ]] || usage
[[ "${2:-}" == --source-dir
    && "${4:-}" == --branch
    && "${6:-}" == --source-pr
    && "${8:-}" == --source-commit
    && "${10:-}" == --source-tree ]] || usage
if [[ $# -eq 15 ]]; then
  [[ "${12:-}" == --maintainer-approval-comment
      && "${14:-}" == --security-approval-comment ]] || usage
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 "$script_dir/publish-private-ci-source.py" "$@"
