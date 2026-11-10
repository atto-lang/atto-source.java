#!/usr/bin/env bash
# Fail-closed SHA, index, and worktree verification for Java source qualification.
# Accept only clean tracked regular files at the exact requested commit.
set -euo pipefail

if (( $# != 1 )) || [[ ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'usage: verify-source-tree.sh EXPECTED_40_HEX_SHA' >&2
  exit 64
fi
root="$(cd "$(dirname "$0")/.." && pwd -P)"
expected="$1"
if ! actual="$(git -C "$root" rev-parse --verify HEAD)"; then
  echo 'failed to resolve exact source commit' >&2
  exit 65
fi
if [[ "$actual" != "$expected" ]]; then
  echo "source commit mismatch: expected $expected, got $actual" >&2
  exit 65
fi
if ! status="$(git -C "$root" status --porcelain --untracked-files=all)"; then
  echo 'Git worktree inventory failed' >&2
  exit 65
fi
if [[ -n "$status" ]]; then
  echo 'dirty source checkout' >&2
  exit 65
fi
if ! git -C "$root" diff --no-ext-diff --cached --quiet HEAD --; then
  echo 'Git index diverges from pinned HEAD' >&2
  exit 65
fi
# Pipefail is essential: process substitution would discard git ls-files errors.
git -C "$root" ls-files -v -z | while IFS= read -r -d '' record; do
  if [[ "$record" != 'H '* ]]; then
    echo 'unverified Git index flags' >&2
    exit 65
  fi
done || { echo 'Git index flag inventory failed' >&2; exit 65; }
git -C "$root" ls-files --stage -z | while IFS= read -r -d '' staged; do
  metadata="${staged%%$'\t'*}"
  relative="${staged#*$'\t'}"
  read -r mode blob stage <<< "$metadata"
  if [[ "$stage" != 0 || ( "$mode" != 100644 && "$mode" != 100755 ) ]]; then
    echo "unverified tracked file mode or stage: $relative" >&2
    exit 65
  fi
  if [[ ! -f "$root/$relative" || -L "$root/$relative" ]]; then
    echo "tracked source is not a regular file: $relative" >&2
    exit 65
  fi
  if ! observed="$(git -C "$root" hash-object -- "$root/$relative")"; then
    echo "failed to hash tracked source: $relative" >&2
    exit 65
  fi
  if [[ "$observed" != "$blob" ]]; then
    echo "tracked source differs from pinned Git blob: $relative" >&2
    exit 65
  fi
done || { echo 'Git tracked blob inventory failed' >&2; exit 65; }
# Build products may exist under target/, but ignored source elsewhere is unsafe.
git -C "$root" ls-files --others --ignored --exclude-standard -z | while IFS= read -r -d '' ignored; do
  if [[ "$ignored" == target/* ]]; then
    continue
  fi
  echo "ignored untracked input: $ignored" >&2
  exit 65
done || { echo 'Git ignored input inventory failed' >&2; exit 65; }
printf 'verified_java_source_sha=%s\n' "$actual"
