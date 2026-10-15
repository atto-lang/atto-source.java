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
[[ -d "$policy_dir" && ! -L "$policy_dir" ]] ||
  fail 'reviewed repository policy directory is missing or symlinked'
policy="$policy_dir/private-ci-approved-repositories.txt"
[[ -f "$policy" && -r "$policy" && ! -L "$policy" ]] ||
  fail 'reviewed policy file is missing, unreadable or symlinked'
allowed=false
while IFS= read -r candidate || [[ -n "$candidate" ]]; do
  [[ -z "$candidate" || "$candidate" =~ ^# ]] && continue
  [[ "$candidate" =~ $slug_pattern ]] || fail 'repository policy contains an invalid entry'
  if [[ "${candidate,,}" == "${target,,}" ]]; then allowed=true; fi
done < "$policy"
[[ "$allowed" == true ]] || fail 'destination is absent from reviewed repository policy'
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.full_name, (.id|tostring), .visibility, (.private|tostring), (.archived|tostring), (.disabled|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *$'\n'* ]] ||
  fail 'API returned empty or multiline repository metadata'
IFS=$'\t' read -r actual_name repository_id visibility private_flag archived disabled <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$repository_id" =~ ^[1-9][0-9]*$ ]] || fail 'destination numeric repository identity unavailable'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
[[ "$archived" == false && "$disabled" == false ]] || fail 'destination is archived, disabled or status unavailable'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
