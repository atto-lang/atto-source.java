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
sha_pattern='^[a-f0-9]{40}$'
[[ "$target" =~ $slug_pattern ]] ||
  fail 'destination must be an owner/repo slug'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
policy_dir="$script_dir/../.github"
[[ -d "$policy_dir" && ! -L "$policy_dir" ]] ||
  fail 'reviewed repository policy directory is missing or symlinked'
policy="$policy_dir/private-ci-approved-repositories.txt"
[[ -f "$policy" && -r "$policy" && ! -L "$policy" ]] ||
  fail 'reviewed policy file is missing, unreadable or symlinked'

approved_id=''
approved_base_commit=''
approved_base_tree=''
approved_workflow=''
while IFS= read -r candidate || [[ -n "$candidate" ]]; do
  [[ -z "$candidate" || "$candidate" =~ ^# ]] && continue
  IFS=' ' read -r slug repository_id base_commit base_tree workflow_sha extra <<< "$candidate"
  [[ -z "${extra:-}" && -n "${slug:-}" && -n "${repository_id:-}" \
      && -n "${base_commit:-}" && -n "${base_tree:-}" && -n "${workflow_sha:-}" ]] ||
    fail 'repository policy contains an invalid entry'
  [[ "$candidate" == "$slug $repository_id $base_commit $base_tree $workflow_sha" ]] ||
    fail 'repository policy entries require exactly one ASCII space'
  [[ "$slug" =~ $slug_pattern && "$repository_id" =~ $id_pattern \
      && "$base_commit" =~ $sha_pattern && "$base_tree" =~ $sha_pattern \
      && "$workflow_sha" =~ $sha_pattern ]] ||
    fail 'repository policy contains an invalid entry'
  if [[ "${slug,,}" == "${target,,}" ]]; then
    [[ -z "$approved_id" ]] ||
      fail 'repository policy contains duplicate destination entries'
    approved_id="$repository_id"
    approved_base_commit="$base_commit"
    approved_base_tree="$base_tree"
    approved_workflow="$workflow_sha"
  fi
done < "$policy"

[[ -n "$approved_id" ]] ||
  fail 'destination is absent from reviewed repository policy'
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is required'

metadata="$(GH_HOST=github.com gh api "repos/$target"   --jq '[.full_name, (.id|tostring), .visibility, (.private|tostring), .default_branch] | @tsv'   2>/dev/null)" ||
  fail 'cannot verify repository identity and visibility from live GitHub API'
[[ -n "$metadata" && "$metadata" != *$'\n'* ]] ||
  fail 'API returned empty or multiline repository metadata'
IFS=$'\t' read -r actual_name actual_id visibility private_flag default_branch <<< "$metadata"
[[ "${actual_name,,}" == "${target,,}" ]] ||
  fail 'API identity differs from requested destination'
[[ "$actual_id" == "$approved_id" ]] ||
  fail 'destination immutable repository ID differs from reviewed policy'
[[ "$visibility" == private && "$private_flag" == true ]] ||
  fail 'destination is not private'
[[ "$default_branch" =~ ^[A-Za-z0-9_.-]{1,80}$ ]] ||
  fail 'destination default branch is invalid'

base_ref="$(GH_HOST=github.com gh api "repos/$target/git/ref/heads/$default_branch" --jq '.object.sha' 2>/dev/null)" ||
  fail 'cannot verify reviewed destination base ref'
[[ "$base_ref" == "$approved_base_commit" ]] ||
  fail 'destination default branch differs from reviewed carrier base commit'

actual_base_tree="$(GH_HOST=github.com gh api "repos/$target/git/commits/$approved_base_commit" --jq '.tree.sha' 2>/dev/null)" ||
  fail 'cannot verify reviewed destination base tree'
[[ "$actual_base_tree" == "$approved_base_tree" ]] ||
  fail 'destination carrier base tree differs from reviewed policy'

workflow_metadata="$(GH_HOST=github.com gh api \
  "repos/$target/contents/.github/workflows/private-oreslang-exact-tree.yml?ref=$approved_base_commit" \
  --jq '[.type, .sha] | @tsv' 2>/dev/null)" ||
  fail 'cannot verify reviewed destination workflow'
[[ -n "$workflow_metadata" && "$workflow_metadata" != *\n'* ]] ||
  fail 'API returned invalid destination workflow metadata'
IFS=\t' read -r workflow_type actual_workflow_sha <<< "$workflow_metadata"
[[ "$workflow_type" == file && "$actual_workflow_sha" == "$approved_workflow" ]] ||
  fail 'destination exact-tree workflow differs from reviewed policy'

printf 'PRIVATE_CI_DESTINATION_APPROVED: %s id=%s base=%s tree=%s workflow=%s (private)\n' \
  "$target" "$approved_id" "$approved_base_commit" "$approved_base_tree" "$approved_workflow"
