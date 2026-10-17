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
entry_pattern='^([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*) ([1-9][0-9]*)$'
declare -A seen=()
approved_id=''
while IFS= read -r candidate || [[ -n "$candidate" ]]; do
  [[ -z "$candidate" || "$candidate" =~ ^# ]] && continue
  [[ "$candidate" =~ $entry_pattern ]] ||
    fail 'repository policy must contain exact owner/repo numeric-id pairs'
  approved_name="${BASH_REMATCH[1]}"
  numeric_id="${BASH_REMATCH[2]}"
  key="${approved_name,,}"
  [[ ! -v seen["$key"] ]] || fail 'duplicate repository identity in approval policy'
  seen["$key"]=1
  if [[ "$key" == "${target,,}" ]]; then approved_id="$numeric_id"; fi
done < "$policy"
[[ -n "$approved_id" ]] || fail 'destination is absent from reviewed repository policy'
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.full_name, .visibility, (.private|tostring), (.id|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *$'\n'* ]] ||
  fail 'API returned empty or multiline repository metadata'
IFS=$'\t' read -r actual_name visibility private_flag actual_id <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
[[ "$actual_id" =~ ^[1-9][0-9]*$ && "$actual_id" == "$approved_id" ]] ||
  fail 'GitHub numeric repository ID differs from independently reviewed approval'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private)\n' "$target"
