#!/usr/bin/env bash
# Gate private-source transfers using a reviewed, checked-in repository policy.
set -euo pipefail
fail() {
  printf 'PRIVATE_CI_DESTINATION_REJECTED: %s\n' "$1" >&2
  exit 1
}
[[ $# -eq 1 ]] || fail 'usage: check-private-ci-destination.sh owner/repo'
target="$1"
slug_pattern='^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$'
[[ "$target" =~ $slug_pattern ]] || fail 'destination must be an owner/repo slug'
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
policy_dir="$script_dir/../.github"
# The final policy file might not be a symlink while its parent is. Deny
# directory-level redirects as well as direct policy-file symlinks.
[[ -d "$policy_dir" && ! -L "$policy_dir" ]] ||
  fail 'reviewed policy directory is missing or symlinked'
policy="$policy_dir/private-ci-approved-repositories.txt"
[[ -f "$policy" && -r "$policy" && ! -L "$policy" ]] ||
  fail 'reviewed repository policy file is missing, unreadable or symlinked'
allowed=false
while IFS= read -r candidate || [[ -n "$candidate" ]]; do
  [[ -z "$candidate" || "$candidate" =~ ^# ]] && continue
  [[ "$candidate" =~ $slug_pattern ]] || fail 'repository policy contains an invalid entry'
  if [[ "${candidate,,}" == "${target,,}" ]]; then allowed=true; fi
done < "$policy"
[[ "$allowed" == true ]] || fail 'destination is absent from reviewed repository policy'
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.full_name, .visibility, (.private|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
# Reject unexpected multiline output before field parsing. Do not accept
# a legitimate-looking first record followed by additional unexamined data.
[[ -n "$metadata" && "$metadata" != *
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
\n'* ]] ||
  fail 'API returned malformed or multiline repository metadata'
IFS=
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
\t' read -r actual_name visibility private_flag <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
