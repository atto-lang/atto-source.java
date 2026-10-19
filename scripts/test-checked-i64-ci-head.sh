#!/usr/bin/env bash
# Source-free proof that CI cannot mistake a PR merge SHA for the real head.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-checked-i64-ci-head.sh"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
repo="$root/test-repo"
mkdir -p "$repo"
git -C "$repo" init -q
git -C "$repo" config user.email ci-test@example.invalid
git -C "$repo" config user.name 'CI Contract Test'
printf 'source head A\n' > "$repo/source.txt"
git -C "$repo" add source.txt
git -C "$repo" commit -qm 'source head A'
old="$(git -C "$repo" rev-parse HEAD)"
printf 'source head B\n' > "$repo/source.txt"
git -C "$repo" commit -qam 'source head B'
head="$(git -C "$repo" rev-parse HEAD)"
(cd "$repo" && EXPECTED_SOURCE_SHA="$head" bash "$script" >/dev/null)
if (cd "$repo" && EXPECTED_SOURCE_SHA="$old" bash "$script" >/dev/null 2>&1); then echo 'FAIL: mismatched head admitted' >&2; exit 1; fi
if (cd "$repo" && EXPECTED_SOURCE_SHA=bogus bash "$script" >/dev/null 2>&1); then echo 'FAIL: invalid identity admitted' >&2; exit 1; fi
printf 'dirty\n' >> "$repo/source.txt"
if (cd "$repo" && EXPECTED_SOURCE_SHA="$head" bash "$script" >/dev/null 2>&1); then echo 'FAIL: dirty checkout admitted' >&2; exit 1; fi
git -C "$repo" checkout -- source.txt
printf 'untracked source\n' > "$repo/untracked.ores"
if (cd "$repo" && EXPECTED_SOURCE_SHA="$head" bash "$script" >/dev/null 2>&1); then echo 'FAIL: untracked checkout admitted' >&2; exit 1; fi
printf '5/5 checked-i64 exact-checkout source-free cases passed\n'
