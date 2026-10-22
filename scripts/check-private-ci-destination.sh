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
declare -A seen_ids=()
approved_id=''
while IFS= read -r candidate || [[ -n "$candidate" ]]; do
  [[ -z "$candidate" || "$candidate" =~ ^# ]] && continue
  [[ "$candidate" =~ $entry_pattern ]] ||
    fail 'repository policy must contain exact owner/repo numeric-id pairs'
  approved_name="${BASH_REMATCH[1]}"
  numeric_id="${BASH_REMATCH[2]}"
  key="${approved_name,,}"
  [[ ! -v seen["$key"] ]] || fail 'duplicate repository identity in approval policy'
  [[ ! -v seen_ids["$numeric_id"] ]] ||
    fail 'numeric repository ID is approved under multiple names'
  seen["$key"]=1
  seen_ids["$numeric_id"]=1
  if [[ "$key" == "${target,,}" ]]; then approved_id="$numeric_id"; fi
done < "$policy"
[[ -n "$approved_id" ]] || fail 'destination is absent from reviewed repository policy'
# Never admit repository metadata produced by an inherited Bash function or
# alias named 'gh'. Noninteractive Bash imports exported functions from the
# caller's environment, allowing a forged response with no GitHub API call.
# The actual gh executable's placement/integrity remains the publisher's
# independently authenticated runner/bootstrap responsibility.
[[ "$(type -t gh 2>/dev/null || true)" == file ]] ||
  fail 'GitHub CLI must resolve to an executable file, not a shell function or alias'
gh_cli="$(command -v gh)"
[[ "$gh_cli" == /* && -f "$gh_cli" && -x "$gh_cli" ]] ||
  fail 'GitHub CLI must resolve to an absolute executable path'
metadata="$(GH_HOST=github.com GH_PROMPT_DISABLED=1 "$gh_cli" api --hostname github.com --method GET "repos/$target" --jq '[.full_name, .visibility, (.private|tostring), (.id|tostring), (.archived|tostring), (.disabled|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *$'\n'* ]] ||
  fail 'API returned empty or multiline repository metadata'
IFS=$'\t' read -r actual_name visibility private_flag actual_id archived_flag disabled_flag <<< "$metadata"
# Reject Bash IFS whitespace collapsing missing TSV values into valid fields.
tab="$(printf '\t')"
[[ "$metadata" == "$actual_name$tab$visibility$tab$private_flag$tab$actual_id$tab$archived_flag$tab$disabled_flag" ]] ||
  fail 'API metadata must have exactly six nonempty fields'
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
[[ "$archived_flag" == false && "$disabled_flag" == false ]] ||
  fail 'destination is archived, disabled, or its active state is missing'
[[ "$actual_id" =~ ^[1-9][0-9]*$ && "$actual_id" == "$approved_id" ]] ||
  fail 'GitHub numeric repository ID differs from independently reviewed approval'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private id=%s)\n' "$target" "$approved_id"
