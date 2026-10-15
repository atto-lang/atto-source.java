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
# The slug allowlist alone is not enough: a deleted/recreated repository can
# have the same name and private visibility but a different immutable ID.
# Keep this pin synchronized with the security-reviewed Python publisher.
case "${target,,}" in
  chapter-publishing-test/zed-pkg-integration-e2e) expected_id=1344442382 ;;
  *) fail 'destination numeric identity is not pinned in reviewed code' ;;
esac
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'
metadata="$(GH_HOST=github.com gh api "repos/$target" --jq '[.id, .full_name, .visibility, (.private|tostring), (.archived|tostring), (.disabled|tostring)] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *
\n'* ]] ||
  fail 'API returned empty or multiline repository metadata'
IFS=
\t' read -r actual_id actual_name visibility private_flag archived_flag disabled_flag <<< "$metadata"
[[ "$actual_id" =~ ^[0-9]+$ && "$actual_id" == "$expected_id" ]] ||
  fail 'GitHub repository numeric ID differs from reviewed destination'
[[ "${actual_name,,}" == "${target,,}" ]] || fail 'API identity differs from requested destination'
[[ "$visibility" == private && "$private_flag" == true ]] || fail 'destination is not private'
[[ "$archived_flag" == false && "$disabled_flag" == false ]] ||
  fail 'destination is archived, disabled or metadata is incomplete'
printf 'PRIVATE_CI_DESTINATION_APPROVED: %s (private, reviewed ID %s)\n' "$target" "$expected_id"
