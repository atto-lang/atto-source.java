#!/usr/bin/env bash
set -euo pipefail

# READ-ONLY preflight. Never copies private source or grants authorization.
# Run from an exact, clean checkout of the PRIVATE source repository before
# contemplating a transfer, after the reviewed allowlist policy is effective.
readonly SOURCE_REPO="ores-truffle-oreslang/oreslang-source.java"
readonly DEST_REPO="ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra"
readonly DEST_ID="1412142815"
readonly SOURCE_ID="1400792274"
readonly POLICY_PATH=".github/private-ci-approved-repositories.txt"
readonly PREFLIGHT_PATH="scripts/preflight-private-ci-mirror.sh"
readonly DEST_GUARD_PATH="scripts/check-private-ci-destination.sh"
readonly WRAPPER_PATH="scripts/run-in-private-ci-destination.sh"
readonly PUBLISHER_PATH="scripts/publish-private-ci-source.py"
readonly APPROVAL_ISSUE="1084"
readonly MAINTAINER_APPROVER="the1mills"
readonly SECURITY_APPROVER="ORESoftware"

die() {
  printf 'PRIVATE_MIRROR_PREFLIGHT_DENIED: %s\n' "$1" >&2
  exit 1
}
usage() {
  echo "usage: bash preflight-private-ci-mirror.sh <source-pr-number> <source-commit-sha> <source-tree-sha> [maintainer-approval-comment-id security-approval-comment-id]" >&2
  exit 2
}
[[ "$#" -eq 3 || "$#" -eq 5 ]] || usage
source_pr="$1"
expected_commit="$2"
expected_tree="$3"
[[ "$source_pr" =~ ^[1-9][0-9]{0,8}$ ]] || usage
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || usage
[[ "$expected_tree" =~ ^[0-9a-f]{40}$ ]] || usage

for tool in git gh; do
  command -v "$tool" >/dev/null 2>&1 || die "missing $tool"
done

actual_source_id="$(gh api "repos/$SOURCE_REPO" --jq '.id')" \
  || die "cannot read authenticated source repository"
[[ "$actual_source_id" == "$SOURCE_ID" ]] \
  || die "source GitHub repository ID mismatch"
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

# Consult authenticated main for the authoritative policy/preflight blobs.
# The running script must be byte-identical to the version on main, so an
# arbitrary feature branch cannot weaken export authorization.
main_sha="$(gh api "repos/$SOURCE_REPO/branches/main" --jq '.commit.sha')" \
  || die "cannot read authenticated source main"
main_protected="$(gh api "repos/$SOURCE_REPO/branches/main" --jq '.protected')" \
  || die "cannot read authenticated source branch protection"
[[ "$main_sha" =~ ^[0-9a-f]{40}$ ]] \
  || die "invalid GitHub source main SHA"
local_main="$(git rev-parse --verify refs/remotes/origin/main 2>/dev/null)" \
  || die "missing origin/main: fetch the trusted source main first"
[[ "$main_sha" == "$local_main" ]] \
  || die "origin/main is stale or does not match GitHub main"

policy_blob="$(git rev-parse "$main_sha:$POLICY_PATH" 2>/dev/null)" \
  || die "cannot resolve main private mirror policy blob"
preflight_blob="$(git rev-parse "$main_sha:$PREFLIGHT_PATH" 2>/dev/null)" \
  || die "cannot resolve main private mirror preflight blob"
guard_blob="$(git rev-parse "$main_sha:$DEST_GUARD_PATH" 2>/dev/null)" \
  || die "cannot resolve main destination guard blob"
wrapper_blob="$(git rev-parse "$main_sha:$WRAPPER_PATH" 2>/dev/null)" \
  || die "cannot resolve main destination wrapper blob"
publisher_blob="$(git rev-parse "$main_sha:$PUBLISHER_PATH" 2>/dev/null)" \
  || die "cannot resolve main private publisher blob"

verify_security_entry() {
  local path="$1"
  local main_entry source_entry
  main_entry="$(git ls-tree "$main_sha" -- "$path")" \
    || die "cannot inspect authoritative main export file: $path"
  source_entry="$(git ls-tree "$expected_commit" -- "$path")" \
    || die "cannot inspect source-head export file: $path"
  [[ -n "$main_entry" && "$source_entry" == "$main_entry" ]] \
    || die "source-head export file mode/blob differs from authoritative main: $path"
  [[ -f "$path" && ! -L "$path" ]] \
    || die "security-critical export path is not a regular non-symlink file: $path"
}

verify_security_entry "$POLICY_PATH"
verify_security_entry "$PREFLIGHT_PATH"
verify_security_entry "$DEST_GUARD_PATH"
verify_security_entry "$WRAPPER_PATH"
verify_security_entry "$PUBLISHER_PATH"

policy="$(git show "$main_sha:$POLICY_PATH")" \
  || die "cannot read authoritative main allowlist"
[[ "$policy" != *
declare -A seen_destinations=()
while IFS= read -r entry || [[ -n "$entry" ]]; do
  [[ -z "$entry" || "$entry" == \#* ]] && continue
  [[ "$entry" =~ ^([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([1-9][0-9]*)$ ]] \
    || die "malformed private mirror allowlist line (expected owner/repo@numeric-id)"
  entry_repo="${BASH_REMATCH[1]}"
  entry_id="${BASH_REMATCH[2]}"
  [[ ! -v seen_destinations["$entry_repo"] ]] \
    || die "duplicate private mirror allowlist repository"
  seen_destinations["$entry_repo"]=1
  if [[ "$entry_repo" == "$DEST_REPO" ]]; then
    [[ "$entry_id" == "$DEST_ID" ]] \
      || die "allowlisted destination repository ID mismatch"
    found=$((found + 1))
  fi
done <<< "$policy"
[[ "$found" -eq 1 ]] \
  || die "destination is not uniquely approved on main"

auth_mode=""
if [[ "$main_protected" == "true" ]]; then
  auth_mode="protected-main"
elif [[ "$main_protected" == "false" ]]; then
  [[ "$#" -eq 5 ]] \
    || die "unprotected main requires review-attested-v1 comment IDs"
  maintainer_comment_id="$4"
  security_comment_id="$5"
  [[ "$maintainer_comment_id" =~ ^[1-9][0-9]*$ \
      && "$security_comment_id" =~ ^[1-9][0-9]*$ ]] \
    || die "invalid review-attested approval comment ID"
  [[ "$maintainer_comment_id" != "$security_comment_id" ]] \
    || die "maintainer and security approvals must be distinct comments"

  approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=$SOURCE_REPO source_repo_id=$SOURCE_ID source_pr=$source_pr source_commit=$expected_commit source_tree=$expected_tree destination_repo=$DEST_REPO destination_repo_id=$DEST_ID policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"

  verify_attestation_comment() {
    local comment_id="$1"
    local expected_login="$2"
    local -a fields=()
    mapfile -t fields < <(
      gh api "repos/$SOURCE_REPO/issues/comments/$comment_id" \
        --jq '.user.login, .author_association, .created_at, .updated_at, .issue_url, .body'
    ) || die "cannot read review-attested approval comment"
    [[ "${#fields[@]}" -eq 6 ]] \
      || die "approval comment must contain one canonical payload line"
    [[ "${fields[0]}" == "$expected_login" ]] \
      || die "approval comment author mismatch for $expected_login"
    case "${fields[1]}" in
      OWNER|MEMBER|COLLABORATOR) ;;
      *) die "approval author is not associated with the source repository" ;;
    esac
    [[ "${fields[2]}" == "${fields[3]}" ]] \
      || die "approval comment was edited after creation"
    [[ "${fields[4]}" == "https://api.github.com/repos/$SOURCE_REPO/issues/$APPROVAL_ISSUE" ]] \
      || die "approval comment is not on the designated security issue"
    [[ "${fields[5]}" == "$approval_payload" ]] \
      || die "approval comment does not exactly bind this export"
  }

  verify_attestation_comment "$maintainer_comment_id" "$MAINTAINER_APPROVER"
  verify_attestation_comment "$security_comment_id" "$SECURITY_APPROVER"
  auth_mode="review-attested-v1"
else
  die "unexpected source branch protection state"
fi

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

printf 'PRIVATE_MIRROR_PREFLIGHT_PASS auth_mode=%s source_pr=%s source_commit=%s source_tree=%s main_sha=%s policy_blob=%s preflight_blob=%s guard_blob=%s wrapper_blob=%s publisher_blob=%s dest_repo_id=%s\n' \
  "$auth_mode" "$source_pr" "$actual_commit" "$actual_tree" "$main_sha" "$policy_blob" "$preflight_blob" "$guard_blob" "$wrapper_blob" "$publisher_blob" "$actual_id"

# No transfer or workflow dispatch occurs. Human security-owner approval,
# workflow isolation, exact mirrored-tree verification, retention and actual
# executed CI tests remain independent mandatory gates.
\r'* ]] \
  || die "private mirror policy must use LF line endings only"
found=0
declare -A seen_destinations=()
while IFS= read -r entry || [[ -n "$entry" ]]; do
  [[ -z "$entry" || "$entry" == \#* ]] && continue
  [[ "$entry" =~ ^([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([1-9][0-9]*)$ ]] \
    || die "malformed private mirror allowlist line (expected owner/repo@numeric-id)"
  entry_repo="${BASH_REMATCH[1]}"
  entry_id="${BASH_REMATCH[2]}"
  [[ ! -v seen_destinations["$entry_repo"] ]] \
    || die "duplicate private mirror allowlist repository"
  seen_destinations["$entry_repo"]=1
  if [[ "$entry_repo" == "$DEST_REPO" ]]; then
    [[ "$entry_id" == "$DEST_ID" ]] \
      || die "allowlisted destination repository ID mismatch"
    found=$((found + 1))
  fi
done <<< "$policy"
[[ "$found" -eq 1 ]] \
  || die "destination is not uniquely approved on main"

auth_mode=""
if [[ "$main_protected" == "true" ]]; then
  auth_mode="protected-main"
elif [[ "$main_protected" == "false" ]]; then
  [[ "$#" -eq 5 ]] \
    || die "unprotected main requires review-attested-v1 comment IDs"
  maintainer_comment_id="$4"
  security_comment_id="$5"
  [[ "$maintainer_comment_id" =~ ^[1-9][0-9]*$ \
      && "$security_comment_id" =~ ^[1-9][0-9]*$ ]] \
    || die "invalid review-attested approval comment ID"
  [[ "$maintainer_comment_id" != "$security_comment_id" ]] \
    || die "maintainer and security approvals must be distinct comments"

  approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=$SOURCE_REPO source_repo_id=$SOURCE_ID source_pr=$source_pr source_commit=$expected_commit source_tree=$expected_tree destination_repo=$DEST_REPO destination_repo_id=$DEST_ID policy_blob=$policy_blob preflight_blob=$preflight_blob"

  verify_attestation_comment() {
    local comment_id="$1"
    local expected_login="$2"
    local -a fields=()
    mapfile -t fields < <(
      gh api "repos/$SOURCE_REPO/issues/comments/$comment_id" \
        --jq '.user.login, .author_association, .created_at, .updated_at, .issue_url, .body'
    ) || die "cannot read review-attested approval comment"
    [[ "${#fields[@]}" -eq 6 ]] \
      || die "approval comment must contain one canonical payload line"
    [[ "${fields[0]}" == "$expected_login" ]] \
      || die "approval comment author mismatch for $expected_login"
    case "${fields[1]}" in
      OWNER|MEMBER|COLLABORATOR) ;;
      *) die "approval author is not associated with the source repository" ;;
    esac
    [[ "${fields[2]}" == "${fields[3]}" ]] \
      || die "approval comment was edited after creation"
    [[ "${fields[4]}" == "https://api.github.com/repos/$SOURCE_REPO/issues/$APPROVAL_ISSUE" ]] \
      || die "approval comment is not on the designated security issue"
    [[ "${fields[5]}" == "$approval_payload" ]] \
      || die "approval comment does not exactly bind this export"
  }

  verify_attestation_comment "$maintainer_comment_id" "$MAINTAINER_APPROVER"
  verify_attestation_comment "$security_comment_id" "$SECURITY_APPROVER"
  auth_mode="review-attested-v1"
else
  die "unexpected source branch protection state"
fi

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

printf 'PRIVATE_MIRROR_PREFLIGHT_PASS auth_mode=%s source_pr=%s source_commit=%s source_tree=%s main_sha=%s policy_blob=%s preflight_blob=%s dest_repo_id=%s\n' \
  "$auth_mode" "$source_pr" "$actual_commit" "$actual_tree" "$main_sha" "$policy_blob" "$preflight_blob" "$actual_id"

# No transfer or workflow dispatch occurs. Human security-owner approval,
# workflow isolation, exact mirrored-tree verification, retention and actual
# executed CI tests remain independent mandatory gates.
