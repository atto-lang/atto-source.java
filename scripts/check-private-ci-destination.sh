#!/usr/bin/env bash
# Diagnostic-only destination visibility and identity check; never export authorization.
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
declare -A seen=() seen_ids=()
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
  [[ ! -v seen_ids["$numeric_id"] ]] ||
    fail 'duplicate numeric repository ID in approval policy'
  seen_ids["$numeric_id"]=1
  if [[ "$key" == "${target,,}" ]]; then approved_id="$numeric_id"; fi
done < "$policy"
[[ -n "$approved_id" ]] || fail 'destination is absent from reviewed repository policy'

target_key="${target,,}"
quarantined_slugs=(
  "zed-pkg-test/java-app"
  "scintilla-run-test/gleam-runner-conformance"
  "ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra"
  "agent-pontifex-test/agent-pontifex-e2e-tests"
  "led-dynamo/leddy-e2e"
  "claimgraph-test/claimgraph-e2e"
)
for quarantined_slug in "${quarantined_slugs[@]}"; do
  [[ "$target_key" != "$quarantined_slug" ]] ||
    fail 'destination is quarantined by private-source incident policy'
done

quarantined_ids=(
  1323575966
  1323598260
  1412142815
  1370782550
  1324139596
  1350751074
)
for quarantined_id in "${quarantined_ids[@]}"; do
  [[ "$approved_id" != "$quarantined_id" ]] ||
    fail 'destination repository ID is quarantined by private-source incident policy'
done

command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.full_name, .visibility, (.private|tostring), (.id|tostring), (.archived|tostring), (.disabled|tostring), (.fork|tostring), .default_branch] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *$'\n'* && "$metadata" != *$'\r'* ]] ||
  fail 'API returned empty or multiline repository metadata'
metadata_without_tabs="${metadata//$'\t'/}"
[[ $(( ${#metadata} - ${#metadata_without_tabs} )) -eq 7 ]] ||
  fail 'repository metadata must contain exactly eight tab-separated fields'
IFS=$'\t' read -r actual_name visibility private_flag actual_id archived disabled fork default_branch <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
[[ "$actual_id" =~ ^[1-9][0-9]*$ && "$actual_id" == "$approved_id" ]] ||
  fail 'GitHub numeric repository ID differs from independently reviewed approval'
[[ "$archived" == false && "$disabled" == false && "$fork" == false && "$default_branch" == main ]] ||
  fail 'destination is archived, disabled, forked or has a non-main default branch'
printf 'PRIVATE_CI_DESTINATION_VERIFIED_DIAGNOSTIC_ONLY: %s (private id=%s)\n' "$target" "$approved_id"
