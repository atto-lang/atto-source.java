#!/usr/bin/env bash
# Fail closed before moving private Oreslang source to any GitHub CI repository.
set -euo pipefail

fail() {
  printf 'PRIVATE_CI_DESTINATION_REJECTED: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  fail 'usage: check-private-ci-destination.sh owner/repo'
fi

target="$1"
if [[ ! "$target" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
  fail 'destination must be an owner/repo slug'
fi

# Exact full repository names; neither an org match nor a substring is enough.
[[ -n "${ORESLANG_APPROVED_PRIVATE_CI_REPOS:-}" ]] ||
  fail 'ORESLANG_APPROVED_PRIVATE_CI_REPOS must contain explicit repo names'

allowed=false
IFS=',' read -r -a approved <<< "$ORESLANG_APPROVED_PRIVATE_CI_REPOS"
for candidate in "${approved[@]}"; do
  if [[ "$candidate" == "$target" ]]; then
    allowed=true
    break
  fi
done
[[ "$allowed" == true ]] || fail 'destination is not explicitly approved'

command -v gh >/dev/null 2>&1 || fail 'GitHub CLI (gh) is required'
# No cached visibility assumption; auth failures and public/internal fail closed.
visibility="$(gh api "repos/${target}" --jq '.visibility' 2>/dev/null)" ||
  fail 'cannot verify destination visibility via GitHub API'
[[ "$visibility" == private ]] || fail 'destination visibility is not private'

printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
