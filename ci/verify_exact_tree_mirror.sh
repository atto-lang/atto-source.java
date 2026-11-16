#!/usr/bin/env bash
# Token-free carrier admission: exact SHA-1 Git tree and independent SHA-256
# path/mode/blob manifest. Verification MUST run before any compiler execution.
# This script never transfers source or grants carrier/export authorization.
set -euo pipefail

fail() { printf 'EXACT_TREE_MIRROR_REJECTED: %s\n' "$*" >&2; exit 1; }
: "${EXPECTED_SOURCE_TREE_SHA:?pin a 40-character source tree SHA}"
: "${EXPECTED_SOURCE_MANIFEST_SHA256:?pin a separately reviewed ls-tree manifest SHA-256}"
[[ "$EXPECTED_SOURCE_TREE_SHA" =~ ^[0-9a-f]{40}$ ]] || fail 'malformed source tree SHA'
[[ "$EXPECTED_SOURCE_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail 'malformed manifest SHA-256'
command -v git >/dev/null || fail 'git missing'
command -v sha256sum >/dev/null || fail 'sha256sum missing'
command -v awk >/dev/null || fail 'awk missing'

[[ -z "$(git status --porcelain=v1 --untracked-files=all)" ]] || fail 'dirty or untracked worktree'
actual_tree="$(git rev-parse 'HEAD^{tree}')" || fail 'HEAD is not a Git commit'
[[ "$actual_tree" == "$EXPECTED_SOURCE_TREE_SHA" ]] || fail "tree mismatch: $actual_tree"
# ls-tree -z is unambiguous even for paths containing tabs/newlines, and its
# stream hashes file modes, names, object types and Git blob object IDs.
manifest="$(git ls-tree -r -z "$actual_tree" | sha256sum | awk '{print $1}')" || fail 'manifest generation failed'
[[ "$manifest" == "$EXPECTED_SOURCE_MANIFEST_SHA256" ]] || fail "manifest digest mismatch: $manifest"

blobs=0
while IFS= read -r -d '' entry; do
  metadata="${entry%%$'\t'*}"
  [[ "$metadata" != "$entry" ]] || fail 'ls-tree entry without path'
  path="${entry#*$'\t'}"
  [[ -n "$path" ]] || fail 'empty tree entry path'
  read -r mode type oid <<< "$metadata"
  [[ "$type" == blob ]] || fail "unsupported nonblob entry at $path ($type)"
  case "$mode" in
    100644|100755|120000) ;;
    *) fail "unsupported mode $mode at $path" ;;
  esac
  [[ "$oid" =~ ^[0-9a-f]{40}$ ]] || fail "invalid blob object ID for $path"
  # Reading and rehashing each blob is stronger than only trusting ls-tree's
  # references; it catches corrupt or missing carrier objects before checkout.
  calculated="$(git cat-file blob "$oid" | git hash-object --stdin)" || fail "cannot read blob at $path"
  [[ "$calculated" == "$oid" ]] || fail "blob mismatch for $path"
  ((blobs+=1))
done < <(git ls-tree -r -z "$actual_tree")
((blobs > 0)) || fail 'empty tree is not an executable source tree'

printf 'EXACT_TREE_MIRROR_PASS source_tree=%s manifest_sha256=%s verified_blobs=%s checkout_commit=%s\n' \
  "$actual_tree" "$manifest" "$blobs" "$(git rev-parse HEAD)"
