#!/usr/bin/env bash
# Data-only check: not authorization to copy private source.
# Independent V3 approval + review must precede any source-bearing mirror.
set -euo pipefail
reject() { printf 'WITH_EXACT_TREE_REJECTED: %s\n' "$1" >&2; exit 1; }
[[ "$#" -eq 2 ]] || reject 'usage: verifier EXPECTED_TREE_SHA EXPECTED_BLOB_COUNT'
expected_tree="$1"
expected_count="$2"
[[ "$expected_tree" =~ ^[0-9a-f]{40}$ ]] || reject 'tree SHA must be 40 lowercase hex'
[[ "$expected_count" =~ ^[1-9][0-9]*$ ]] || reject 'blob count must be positive decimal'
[[ "${#expected_count}" -le 7 ]] || reject 'count exceeds hard bound'
(( expected_count >= 1 && expected_count <= 100000 )) || reject 'count outside hard bound'
command -v git >/dev/null 2>&1 || reject 'git missing'
[[ -d private-oreslang-source && ! -L private-oreslang-source ]] || reject 'source subtree missing or symlinked'
[[ "$(git rev-parse --is-inside-work-tree 2>/dev/null || true)" == true ]] || reject 'not a Git worktree'
[[ "$(git config --bool core.sparseCheckout || printf false)" != true ]] || reject 'sparse checkout forbidden'
[[ "$(git config --bool core.sparseCheckoutCone || printf false)" != true ]] || reject 'sparse checkout cone forbidden'
[[ "$(git cat-file -t HEAD:private-oreslang-source 2>/dev/null || true)" == tree ]] || reject 'source is not a Git tree'
actual_tree="$(git rev-parse --verify HEAD:private-oreslang-source 2>/dev/null || true)"
[[ "$actual_tree" == "$expected_tree" ]] || reject 'source subtree SHA differs from reviewed source'
entries="$(git ls-tree -r HEAD:private-oreslang-source)" || reject 'cannot list source tree'
actual_count="$(printf '%s\n' "$entries" | awk 'END { print NR }')"
blob_count="$(printf '%s\n' "$entries" | awk '$2=="blob" { n++ } END { print n+0 }')"
[[ "$actual_count" == "$expected_count" && "$blob_count" == "$expected_count" ]] || reject 'entry count or kind mismatch'
git diff --no-ext-diff --exit-code HEAD -- private-oreslang-source >/dev/null || reject 'source checkout is modified'
[[ -z "$(git clean -ndx -- private-oreslang-source)" ]] || reject 'untracked or ignored files in source checkout'
printf 'WITH_EXACT_TREE_PASS tree=%s blobs=%s\n' "$actual_tree" "$actual_count"
