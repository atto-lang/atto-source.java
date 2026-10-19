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
id_pattern='^[1-9][0-9]*$'
[[ "$target" =~ $slug_pattern ]] || fail 'destination must be an owner/repo slug'
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
policy_dir="$script_dir/../.github"
[[ -d "$policy_dir" && ! -L "$policy_dir" ]] ||
  fail 'reviewed repository policy directory is missing or symlinked'
policy="$policy_dir/private-ci-approved-repositories.txt"
[[ -f "$policy" && -r "$policy" && ! -L "$policy" ]] ||
  fail 'reviewed policy file is missing, unreadable or symlinked'

allowed=false
pinned_id=''
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" =~ ^# ]] && continue
  IFS=' ' read -r candidate candidate_id extra <<< "$line"
  [[ -n "$candidate" && "$candidate" =~ $slug_pattern
      && -n "$candidate_id" && "$candidate_id" =~ $id_pattern
      && -z "${extra:-}" ]] ||
    fail 'repository policy contains an invalid entry'
  if [[ "${candidate,,}" == "${target,,}" ]]; then
    [[ "$allowed" == false ]] || fail 'repository policy contains duplicate destination entries'
    allowed=true
    pinned_id="$candidate_id"
  fi
done < "$policy"
[[ "$allowed" == true ]] || fail 'destination is absent from reviewed repository policy'

command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.full_name, (.id|tostring), .visibility, (.private|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *$'\n'* ]] ||
  fail 'API returned empty or multiline repository metadata'
IFS=$'\t' read -r actual_name actual_id visibility private_flag <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$actual_id" == "$pinned_id" ]] || fail 'API repository ID differs from reviewed policy'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s id=%s (private)\n' "$target" "$pinned_id"
