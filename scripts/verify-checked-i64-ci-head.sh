#!/usr/bin/env bash
# Exact-source Git identity gate for checked-i64 CI. Caller supplies the
# PR's head SHA, not the pull_request synthetic merge SHA in GITHUB_SHA.
set -euo pipefail

fail() { printf 'CHECKED_I64_SOURCE_IDENTITY_FAILED: %s\n' "$*" >&2; exit 1; }

[[ "${EXPECTED_SOURCE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || fail 'missing or invalid expected source SHA-1'
actual="$(git rev-parse --verify HEAD 2>/dev/null)" || fail 'not a Git checkout'
[[ "$actual" == "$EXPECTED_SOURCE_SHA" ]] || fail "checked out $actual, expected $EXPECTED_SOURCE_SHA"
git diff --quiet HEAD -- || fail 'checkout has modified tracked files'
[[ -z "$(git ls-files --others --exclude-standard)" ]] || fail 'checkout has untracked files'
# Git-ignored paths (e.g. target/classes, .cache) can still alter builds.
[[ -z "$(git ls-files --others --ignored --exclude-standard)" ]] || fail 'checkout has ignored build inputs'
tree="$(git rev-parse --verify 'HEAD^{tree}' 2>/dev/null)" || fail 'no source tree'
[[ "$tree" =~ ^[0-9a-f]{40}$ ]] || fail 'invalid source tree identity'
printf 'CHECKED_I64_SOURCE_IDENTITY_OK commit=%s tree=%s\n' "$actual" "$tree"
