#!/usr/bin/env bash
set -euo pipefail

# READ-ONLY preflight. Never copies private source or grants authorization.
# Run from an exact, clean checkout of the PRIVATE source repository before
# contemplating a transfer, after the reviewed allowlist policy is effective.
readonly SOURCE_REPO="ores-truffle-oreslang/oreslang-source.java"
readonly DEST_REPO="ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra"
readonly DEST_ID="1412142815"
readonly POLICY_PATH=".github/private-ci-approved-repositories.txt"

die() {
  printf 'PRIVATE_MIRROR_PREFLIGHT_DENIED: %s\n' "$1" >&2
  exit 1
}
usage() {
  echo "usage: bash preflight-private-ci-mirror.sh <source-pr-number> <source-commit-sha> <source-tree-sha>" >&2
  exit 2
}
[[ "$#" -eq 3 ]] || usage
source_pr="$1"
expected_commit="$2"
expected_tree="$3"
[[ "$source_pr" =~ ^[1-9][0-9]{0,8}$ ]] || usage
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || usage
[[ "$expected_tree" =~ ^[0-9a-f]{40}$ ]] || usage

for tool in git gh; do
  command -v "$tool" >/dev/null 2>&1 || die "missing $tool"
done
actual_root="$(git rev-parse --show-toplevel 2>/dev/null)" \
  || die "not in a Git checkout"
[[ "$actual_root" == "$PWD" ]] \
  || die "run at the top of the source repository checkout"
actual_commit="$(git rev-parse --verify HEAD^{commit})" \
  || die "cannot resolve HEAD commit"
actual_tree="$(git rev-parse --verify HEAD^{tree})" \
  || die "cannot resolve HEAD tree"
[[ "$actual_commit" == "$expected_commit" ]] \
  || die "source commit mismatch"
[[ "$actual_tree" == "$expected_tree" ]] \
  || die "source tree mismatch"
[[ -z "$(git status --porcelain=v1 --untracked-files=all --ignored)" ]] \
  || die "dirty tracked, untracked or ignored source checkout"

# Bind the checkout to the current head of an open, same-repo pull request.
# A caller-supplied but internally consistent commit/tree is insufficient.
pr_head="$(gh api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.head.sha')" \
  || die "cannot resolve authenticated source PR"
pr_state="$(gh api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.state')" \
  || die "cannot read source PR state"
pr_base="$(gh api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.base.ref')" \
  || die "cannot read source PR base"
pr_repo="$(gh api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.head.repo.full_name')" \
  || die "cannot read source PR repository"
[[ "$pr_head" == "$expected_commit" && "$pr_state" == "open" \
    && "$pr_base" == "main" && "$pr_repo" == "$SOURCE_REPO" ]] \
  || die "source checkout is not the live head of an open same-repo PR"

# Consult the *remote protected main policy*, never an arbitrary PR branch.
# The local origin/main must be fresh and identical to GitHub's verified main.
main_sha="$(gh api "repos/$SOURCE_REPO/branches/main" --jq '.commit.sha')" \
  || die "cannot read authenticated source main"
main_protected="$(gh api "repos/$SOURCE_REPO/branches/main" --jq '.protected')" \
  || die "cannot read authenticated source branch protection"
[[ "$main_protected" == "true" ]] \
  || die "source main is not protected"
[[ "$main_sha" =~ ^[0-9a-f]{40}$ ]] \
  || die "invalid GitHub source main SHA"
local_main="$(git rev-parse --verify refs/remotes/origin/main 2>/dev/null)" \
  || die "missing origin/main: fetch the trusted source main first"
[[ "$main_sha" == "$local_main" ]] \
  || die "origin/main is stale or does not match GitHub main"

# Only the protected main revision is authoritative for private code export.
policy="$(git show "$main_sha:$POLICY_PATH")" \
  || die "cannot read the protected main allowlist"
found=0
declare -A seen_destinations=()
while IFS= read -r entry || [[ -n "$entry" ]]; do
  [[ -z "$entry" || "$entry" == \#* ]] && continue
  # Check all effective policy lines, not only the matching destination.
  [[ "$entry" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] \
    || die "malformed private mirror allowlist line"
  [[ ! -v seen_destinations["$entry"] ]] \
    || die "duplicate private mirror allowlist destination"
  seen_destinations["$entry"]=1
  if [[ "$entry" == "$DEST_REPO" ]]; then
    found=$((found + 1))
  fi
done <<< "$policy"
[[ "$found" -eq 1 ]] \
  || die "destination is not uniquely approved on protected main"

# Verify the immutable private destination *at time of transfer*, not merely
# a reusable name from a previously observed passing workflow.
IFS=$'\t' read -r actual_id actual_name actual_private actual_push < <(
  gh api "repos/$DEST_REPO" \
    --jq '[.id, .full_name, .private, (.permissions.push // false)] | @tsv'
) || die "cannot read authenticated destination repository"
[[ "$actual_id" == "$DEST_ID" ]] \
  || die "destination GitHub repository ID mismatch"
[[ "$actual_name" == "$DEST_REPO" ]] \
  || die "destination full name mismatch"
[[ "$actual_private" == "true" ]] \
  || die "destination must remain private"
[[ "$actual_push" == "true" ]] \
  || die "insufficient destination permissions"

printf 'PRIVATE_MIRROR_PREFLIGHT_PASS source_pr=%s source_commit=%s source_tree=%s main_policy_sha=%s dest_repo_id=%s\n' \
  "$source_pr" "$actual_commit" "$actual_tree" "$main_sha" "$actual_id"

# No transfer or workflow dispatch occurs. Human security-owner approval,
# workflow isolation, exact mirrored-tree verification, retention and actual
# executed CI tests remain independent mandatory gates.
