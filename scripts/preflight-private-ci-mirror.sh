#!/usr/bin/env bash
set -euo pipefail

# READ-ONLY preflight. Never copies private source or grants authorization.
# Run from an exact, clean checkout of the PRIVATE source repository before
# contemplating a transfer, after the reviewed allowlist policy is effective.
readonly SOURCE_REPO="ores-truffle-oreslang/oreslang-source.java"
readonly DEST_REPO="ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra"
readonly DEST_ID="1412142815"
readonly DEST_BASE_COMMIT="5e1079638ba322d0208631ef34fcbd3f129b40ab"
readonly DEST_BASE_TREE="1f2f5d5f816213192d13c179d0e9381d00fdeaf7"
readonly DEST_WORKFLOW_PATH=".github/workflows/private-oreslang-exact-tree.yml"
readonly DEST_WORKFLOW_BLOB="2d4b412ca8480d02fcd122a9cb7ee3a5d7d45227"
readonly SOURCE_ID="1400792274"
readonly POLICY_PATH=".github/private-ci-approved-repositories.txt"
readonly PREFLIGHT_PATH="scripts/preflight-private-ci-mirror.sh"
readonly DEST_GUARD_PATH="scripts/check-private-ci-destination.sh"
readonly WRAPPER_PATH="scripts/run-in-private-ci-destination.sh"
readonly PUBLISHER_PATH="scripts/publish-private-ci-source.py"
readonly APPROVAL_ISSUE="1084"
readonly MAINTAINER_APPROVER="the1mills"
readonly MAINTAINER_APPROVER_ID="2148448"
readonly SECURITY_APPROVER="ORESoftware"
readonly SECURITY_APPROVER_ID="11139560"

die() {
  printf 'PRIVATE_MIRROR_PREFLIGHT_DENIED: %s\n' "$1" >&2
  exit 1
}

usage() {
  echo "usage: bash preflight-private-ci-mirror.sh <source-pr-number> <source-commit-sha> <source-tree-sha> <destination-branch> [maintainer-approval-comment-id security-approval-comment-id]" >&2
  exit 2
}

gh_api() {
  GH_HOST=github.com GH_PROMPT_DISABLED=1 command gh api "$@"
}

require_destination_branch_absent() {
  local endpoint="repos/$DEST_REPO/git/ref/heads/$destination_branch"
  local output status=0
  output="$(GH_HOST=github.com GH_PROMPT_DISABLED=1 command gh api "$endpoint" 2>&1)" \
    || status="$?"
  if [[ "$status" -eq 0 ]]; then
    die "destination branch already exists; each export requires a fresh approval-bound ref"
  fi
  # Only an authenticated GitHub Not Found proves ref absence. Permission,
  # transport, rate-limit, or other API failures must not be treated as absence.
  [[ "$output" == *"HTTP 404"* ]] \
    || die "cannot prove destination branch is absent"
}

require_plan_feature_unavailable() {
  local endpoint="$1"
  local label="$2"
  local output status=0
  output="$(GH_HOST=github.com GH_PROMPT_DISABLED=1 command gh api "$endpoint" 2>&1)" \
    || status="$?"
  if [[ "$status" -eq 0 ]]; then
    die "$label is available; review-attested-v1 fallback is forbidden"
  fi
  # A generic 403/404 or integration-scope error is not proof that the
  # repository plan lacks the feature. Only GitHub's explicit plan-upgrade
  # denial may activate the unprotected-main fallback.
  [[ "$output" == *"Upgrade to GitHub Pro"* \
      && "$output" == *"make this repository public"* ]] \
    || die "cannot prove $label is unavailable on the current repository plan"
}

[[ "$#" -eq 4 || "$#" -eq 6 ]] || usage
source_pr="$1"
expected_commit="$2"
expected_tree="$3"
destination_branch="$4"
[[ "$source_pr" =~ ^[1-9][0-9]{0,8}$ ]] || usage
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || usage
[[ "$expected_tree" =~ ^[0-9a-f]{40}$ ]] || usage
[[ "$destination_branch" =~ ^ci/oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}$ ]] || usage

for tool in git gh; do
  command -v "$tool" >/dev/null 2>&1 || die "missing $tool"
done

actual_source_id="$(gh_api "repos/$SOURCE_REPO" --jq '.id')" \
  || die "cannot read authenticated source repository"
[[ "$actual_source_id" == "$SOURCE_ID" ]] \
  || die "source GitHub repository ID mismatch"

actual_root="$(git rev-parse --show-toplevel 2>/dev/null)" \
  || die "not in a Git checkout"
physical_pwd="$(pwd -P)"
[[ "$actual_root" == "$physical_pwd" ]] \
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
pr_head="$(gh_api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.head.sha')" \
  || die "cannot resolve authenticated source PR"
pr_state="$(gh_api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.state')" \
  || die "cannot read source PR state"
pr_base="$(gh_api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.base.ref')" \
  || die "cannot read source PR base"
pr_repo="$(gh_api "repos/$SOURCE_REPO/pulls/$source_pr" --jq '.head.repo.full_name')" \
  || die "cannot read source PR repository"
[[ "$pr_head" == "$expected_commit" && "$pr_state" == "open" \
    && "$pr_base" == "main" && "$pr_repo" == "$SOURCE_REPO" ]] \
  || die "source checkout is not the live head of an open same-repo PR"

# Consult authenticated main for the authoritative policy/export-program blobs.
# The running source head must contain byte/mode-identical security programs.
main_sha="$(gh_api "repos/$SOURCE_REPO/branches/main" --jq '.commit.sha')" \
  || die "cannot read authenticated source main"
main_protected="$(gh_api "repos/$SOURCE_REPO/branches/main" --jq '.protected')" \
  || die "cannot read authenticated source branch protection state"
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
[[ "$policy" != *$'\r'* ]] \
  || die "private mirror policy must use LF line endings only"

found=0
declare -A seen_destinations=()
while IFS= read -r entry || [[ -n "$entry" ]]; do
  [[ -z "$entry" || "$entry" == \#* ]] && continue
  [[ "$entry" =~ ^([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*)@([1-9][0-9]*)$ ]] \
    || die "malformed private mirror allowlist line (expected owner/repo@numeric-id)"
  entry_repo="${BASH_REMATCH[1]}"
  entry_id="${BASH_REMATCH[2]}"
  entry_key="${entry_repo,,}"
  [[ ! -v seen_destinations["$entry_key"] ]] \
    || die "duplicate private mirror allowlist repository"
  seen_destinations["$entry_key"]=1
  if [[ "$entry_key" == "${DEST_REPO,,}" ]]; then
    [[ "$entry_id" == "$DEST_ID" ]] \
      || die "allowlisted destination repository ID mismatch"
    found=$((found + 1))
  fi
done <<< "$policy"
[[ "$found" -eq 1 ]] \
  || die "destination is not uniquely approved on main"

# Bind authorization to the exact reviewed carrier content, not merely the
# destination repository identity. Resolve the carrier by immutable Git object.
IFS=$'\t' read -r actual_id actual_name actual_private actual_push < <(
  gh_api "repos/$DEST_REPO" --jq '[.id, .full_name, .private, (.permissions.push // false)] | @tsv'
) || die "cannot read authenticated destination repository"
[[ "$actual_id" == "$DEST_ID" && "$actual_name" == "$DEST_REPO" \
    && "$actual_private" == "true" && "$actual_push" == "true" ]] \
  || die "destination identity, privacy or permissions mismatch"
IFS=$'\t' read -r dest_base_commit dest_base_tree < <(
  gh_api "repos/$DEST_REPO/git/commits/$DEST_BASE_COMMIT" --jq '[.sha, .tree.sha] | @tsv'
) || die "cannot resolve reviewed destination carrier commit"
[[ "$dest_base_commit" == "$DEST_BASE_COMMIT" && "$dest_base_tree" == "$DEST_BASE_TREE" ]] \
  || die "destination reviewed carrier commit/tree mismatch"
dest_workflow_blob="$(gh_api "repos/$DEST_REPO/contents/$DEST_WORKFLOW_PATH?ref=$DEST_BASE_COMMIT" --jq '.sha')" \
  || die "cannot resolve reviewed destination exact-tree workflow"
[[ "$dest_workflow_blob" == "$DEST_WORKFLOW_BLOB" ]] \
  || die "destination exact-tree workflow differs from reviewed blob"
require_destination_branch_absent

auth_mode=""
if [[ "$main_protected" == "true" ]]; then
  # "protected=true" alone is not enough. Require concrete anti-bypass review
  # properties for the security-owned files on authoritative main.
  protection="$(gh_api "repos/$SOURCE_REPO/branches/main/protection" --jq \
    '[
      (.required_pull_request_reviews.require_code_owner_reviews // false),
      (.required_pull_request_reviews.required_approving_review_count // 0),
      (.required_pull_request_reviews.dismiss_stale_reviews // false),
      (.required_pull_request_reviews.require_last_push_approval // false),
      (.enforce_admins.enabled // false),
      (.allow_force_pushes.enabled // false),
      (.allow_deletions.enabled // false),
      ((.required_pull_request_reviews.bypass_pull_request_allowances.users // []) | length),
      ((.required_pull_request_reviews.bypass_pull_request_allowances.teams // []) | length),
      ((.required_pull_request_reviews.bypass_pull_request_allowances.apps // []) | length)
    ] | @tsv')" \
    || die "cannot verify effective protected-main review controls"
  [[ -n "$protection" && "$protection" != *$'\n'* ]] \
    || die "invalid protected-main review control response"
  IFS=$'\t' read -r require_codeowners approval_count dismiss_stale last_push \
    enforce_admins allow_force allow_delete bypass_users bypass_teams bypass_apps <<< "$protection"
  [[ "$require_codeowners" == "true" ]] \
    || die "protected main does not require code-owner review"
  [[ "$approval_count" =~ ^[1-9][0-9]*$ ]] \
    || die "protected main does not require an approving review"
  [[ "$dismiss_stale" == "true" && "$last_push" == "true" ]] \
    || die "protected main does not invalidate stale/pre-last-push approvals"
  [[ "$enforce_admins" == "true" ]] \
    || die "protected main does not enforce protections for administrators"
  [[ "$allow_force" == "false" && "$allow_delete" == "false" ]] \
    || die "protected main permits force-push or deletion"
  [[ "$bypass_users" == "0" && "$bypass_teams" == "0" && "$bypass_apps" == "0" ]] \
    || die "protected main has pull-request review bypass actors"
  auth_mode="protected-main"
elif [[ "$main_protected" == "false" ]]; then
  # Fallback is intentionally per-export and independently attested. It remains
  # inert unless this fallback implementation itself has first been reviewed and
  # merged to authoritative main. An unprotected branch by itself is NOT enough:
  # prove that both classic protection and repository rulesets are unavailable
  # specifically because of the current private-repository plan.
  require_plan_feature_unavailable \
    "repos/$SOURCE_REPO/branches/main/protection" "classic branch protection"
  require_plan_feature_unavailable \
    "repos/$SOURCE_REPO/rulesets" "repository rulesets"
  [[ "$#" -eq 6 ]] \
    || die "unprotected main requires review-attested-v1 comment IDs"
  maintainer_comment_id="$5"
  security_comment_id="$6"
  [[ "$maintainer_comment_id" =~ ^[1-9][0-9]*$ \
      && "$security_comment_id" =~ ^[1-9][0-9]*$ ]] \
    || die "invalid review-attested approval comment ID"
  [[ "$maintainer_comment_id" != "$security_comment_id" ]] \
    || die "maintainer and security approvals must be distinct comments"

  approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=$SOURCE_REPO source_repo_id=$SOURCE_ID source_pr=$source_pr source_commit=$expected_commit source_tree=$expected_tree destination_repo=$DEST_REPO destination_repo_id=$DEST_ID destination_branch=$destination_branch destination_base_commit=$dest_base_commit destination_base_tree=$dest_base_tree destination_workflow_blob=$dest_workflow_blob policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"

  verify_attestation_comment() {
    local comment_id="$1"
    local expected_login="$2"
    local expected_user_id="$3"
    local -a fields=()
    mapfile -t fields < <(
      gh_api "repos/$SOURCE_REPO/issues/comments/$comment_id" \
        --jq '.user.login, (.user.id|tostring), .author_association, .created_at, .updated_at, .issue_url, .body'
    ) || die "cannot read review-attested approval comment"
    [[ "${#fields[@]}" -eq 7 ]] \
      || die "approval comment must contain one canonical payload line"
    [[ "${fields[0]}" == "$expected_login" && "${fields[1]}" == "$expected_user_id" ]] \
      || die "approval comment author identity mismatch for $expected_login"
    case "${fields[2]}" in
      OWNER|MEMBER|COLLABORATOR) ;;
      *) die "approval author is not associated with the source repository" ;;
    esac
    [[ "${fields[3]}" == "${fields[4]}" ]] \
      || die "approval comment was edited after creation"
    [[ "${fields[5]}" == "https://api.github.com/repos/$SOURCE_REPO/issues/$APPROVAL_ISSUE" ]] \
      || die "approval comment is not on the designated security issue"
    [[ "${fields[6]}" == "$approval_payload" ]] \
      || die "approval comment does not exactly bind this export"
  }

  verify_attestation_comment "$maintainer_comment_id" \
    "$MAINTAINER_APPROVER" "$MAINTAINER_APPROVER_ID"
  verify_attestation_comment "$security_comment_id" \
    "$SECURITY_APPROVER" "$SECURITY_APPROVER_ID"
  auth_mode="review-attested-v1"
else
  die "unexpected source branch protection state"
fi

# Revalidate destination identity and immutable reviewed carrier after authorization.
IFS=$'\t' read -r final_id final_name final_private final_push < <(
  gh_api "repos/$DEST_REPO" --jq '[.id, .full_name, .private, (.permissions.push // false)] | @tsv'
) || die "cannot re-read authenticated destination repository"
[[ "$final_id" == "$actual_id" && "$final_id" == "$DEST_ID" \
    && "$final_name" == "$DEST_REPO" && "$final_private" == "true" && "$final_push" == "true" ]] \
  || die "destination identity, privacy or permissions changed during authorization"
IFS=$'\t' read -r final_base_commit final_base_tree < <(
  gh_api "repos/$DEST_REPO/git/commits/$DEST_BASE_COMMIT" --jq '[.sha, .tree.sha] | @tsv'
) || die "cannot re-resolve reviewed destination carrier commit"
[[ "$final_base_commit" == "$dest_base_commit" && "$final_base_commit" == "$DEST_BASE_COMMIT" \
    && "$final_base_tree" == "$dest_base_tree" && "$final_base_tree" == "$DEST_BASE_TREE" ]] \
  || die "reviewed destination carrier commit/tree is unavailable or changed"
final_workflow_blob="$(gh_api "repos/$DEST_REPO/contents/$DEST_WORKFLOW_PATH?ref=$DEST_BASE_COMMIT" --jq '.sha')" \
  || die "cannot re-resolve reviewed destination exact-tree workflow"
[[ "$final_workflow_blob" == "$dest_workflow_blob" && "$final_workflow_blob" == "$DEST_WORKFLOW_BLOB" ]] \
  || die "destination exact-tree workflow changed during authorization"
require_destination_branch_absent

printf 'PRIVATE_MIRROR_PREFLIGHT_PASS auth_mode=%s source_pr=%s source_commit=%s source_tree=%s destination_branch=%s destination_base_commit=%s destination_base_tree=%s destination_workflow_blob=%s main_sha=%s policy_blob=%s preflight_blob=%s guard_blob=%s wrapper_blob=%s publisher_blob=%s dest_repo_id=%s\n' \
  "$auth_mode" "$source_pr" "$actual_commit" "$actual_tree" "$destination_branch" \
  "$dest_base_commit" "$dest_base_tree" "$dest_workflow_blob" "$main_sha" \
  "$policy_blob" "$preflight_blob" "$guard_blob" "$wrapper_blob" "$publisher_blob" "$actual_id"

# No transfer or workflow dispatch occurs. Human security-owner approval,
# workflow isolation, exact source-subtree verification, retention and actual
# executed CI tests remain independent mandatory gates.
