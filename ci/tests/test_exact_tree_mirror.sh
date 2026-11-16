#!/usr/bin/env bash
# Synthetic-only security regression test; contains no private compiler source.
set -euo pipefail
script="$(cd "$(dirname "$0")/.." && pwd)/verify_exact_tree_mirror.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q -b main
git config user.email source-free@example.invalid
git config user.name 'Source-free CI gate'
mkdir -p 'nested dir'
printf '#!/bin/sh\nprintf ok\\n\n' > 'nested dir/executable.sh'
chmod +x 'nested dir/executable.sh'
printf 'hello\n' > 'nested dir/data with spaces.txt'
ln -s 'nested dir/data with spaces.txt' 'linked data'
# Exercise an unusual but legal path containing a tab as part of manifest hashing.
printf 'tabbed\n' > $'name\twith tab'
git add .
git commit -q -m 'source-free fixture'
tree="$(git rev-parse 'HEAD^{tree}')"
manifest="$(git ls-tree -r -z HEAD | sha256sum | awk '{print $1}')"
export EXPECTED_SOURCE_TREE_SHA="$tree" EXPECTED_SOURCE_MANIFEST_SHA256="$manifest"
"$script" | grep -F 'EXACT_TREE_MIRROR_PASS'
reject() {
  local label="$1"; shift
  if "$@" >"$tmp/out" 2>&1; then
    echo "negative $label: unexpectedly accepted" >&2; exit 1
  fi
  grep -q 'EXACT_TREE_MIRROR_REJECTED:' "$tmp/out" || {
    echo "negative $label: not a fail-closed rejection" >&2; cat "$tmp/out"; exit 1;
  }
  printf 'REJECTED: %s\n' "$label"
}
reject tree_mismatch env EXPECTED_SOURCE_TREE_SHA=0000000000000000000000000000000000000000 "$script"
reject manifest_mismatch env EXPECTED_SOURCE_MANIFEST_SHA256="$(printf '%064d' 0)" "$script"
reject malformed_tree env EXPECTED_SOURCE_TREE_SHA=bad "$script"
reject malformed_manifest env EXPECTED_SOURCE_MANIFEST_SHA256=bad "$script"
printf 'dirty\n' >> 'nested dir/data with spaces.txt'
reject dirty_worktree "$script"
git checkout -q -- 'nested dir/data with spaces.txt'
printf 'untracked\n' > no-tracked-source
reject untracked_source "$script"
rm no-tracked-source
# A Gitlink is a pointer into another repository, not an embedded source blob.
git update-index --add --cacheinfo 160000,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,submodule
git commit -q -m 'synthetic gitlink'
export EXPECTED_SOURCE_TREE_SHA="$(git rev-parse 'HEAD^{tree}')"
export EXPECTED_SOURCE_MANIFEST_SHA256="$(git ls-tree -r -z HEAD | sha256sum | awk '{print $1}')"
reject unsupported_gitlink "$script"
echo 'SOURCE_FREE_MIRROR_GATE_PASS positive=1 negatives=7'
