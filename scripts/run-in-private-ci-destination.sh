#!/bin/bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail

usage() {
  printf '%s\n'     'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir CHECKOUT --branch ci/oreslang-NAME --source-selector pr:NUMBER|main --source-commit SHA --source-tree SHA [--maintainer-approval-comment ID --security-approval-comment ID]' >&2
  exit 1
}

[[ $# -eq 11 || $# -eq 15 ]] || usage
[[ "${2:-}" == --source-dir
    && "${4:-}" == --branch
    && "${6:-}" == --source-selector
    && "${8:-}" == --source-commit
    && "${10:-}" == --source-tree ]] || usage
if [[ $# -eq 15 ]]; then
  [[ "${12:-}" == --maintainer-approval-comment
      && "${14:-}" == --security-approval-comment ]] || usage
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
[[ -n "$token" ]] || {
  printf '%s\n' 'PRIVATE_CI_DESTINATION_REJECTED: GH_TOKEN or GITHUB_TOKEN is required for the trusted control plane' >&2
  exit 1
}
[[ -x /usr/bin/python3 && -x /usr/bin/env ]] || {
  printf '%s\n' 'PRIVATE_CI_DESTINATION_REJECTED: trusted Python runtime unavailable' >&2
  exit 1
}
exec /usr/bin/env -i \
  PATH=/usr/local/bin:/usr/bin:/bin \
  HOME=/tmp \
  LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_TOKEN="$token" \
  /usr/bin/python3 -I -E "$script_dir/publish-private-ci-source.py" "$@"
