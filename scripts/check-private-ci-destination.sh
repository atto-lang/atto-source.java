#!/usr/bin/env bash
# Fail closed immediately before running a command that transfers private source.
set -euo pipefail
fail() {
  printf 'PRIVATE_CI_DESTINATION_REJECTED: %s\n' "$1" >&2
  exit 1
}
[[ $# -eq 1 ]] || fail 'usage: check-private-ci-destination.sh owner/repo'
target="$1"
[[ "$target" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
  fail 'destination must be an owner/repo slug'
[[ -n "${ORESLANG_APPROVED_PRIVATE_CI_REPOS:-}" ]] ||
  fail 'an explicit comma-separated destination allowlist is required'
allowed=false
IFS=',' read -r -a approved <<< "$ORESLANG_APPROVED_PRIVATE_CI_REPOS"
for candidate in "${approved[@]}"; do
  if [[ "${candidate,,}" == "${target,,}" ]]; then
    allowed=true
    break
  fi
done
[[ "$allowed" == true ]] || fail 'destination is not explicitly approved'
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
# Force the public github.com API, not a GH_HOST enterprise override. Require
# matching identity AND private visibility: an internal repo also reports private=true.
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.full_name, .visibility, (.private|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
IFS=$'\t' read -r actual_name visibility private_flag <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] ||
  fail 'destination is not private'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
