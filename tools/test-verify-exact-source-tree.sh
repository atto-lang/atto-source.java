#!/usr/bin/env bash
# Offline, source-free tests for exact-tree proof before private CI builds.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
checker="$script_dir/verify-exact-source-tree.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
git init -q
git config user.email ci-test@example.invalid
git config user.name CI-Test
mkdir source
printf 'pub fnc main(): int { return 42; }\n' > source/main.ores
printf 'unrelated\n' > other.txt
git add .
git commit -qm baseline
expected="$(git rev-parse 'HEAD:source')"

assert_status() {
  local code="$1" label="$2"
  shift 2
  local actual=0
  bash "$checker" "$@" > "$work/output" 2>&1 || actual=$?
  if [[ "$actual" != "$code" ]]; then
    printf '%s: expected exit %s, got %s\n' "$label" "$code" "$actual" >&2
    cat "$work/output" >&2
    exit 1
  fi
  printf 'PASS %s (exit %s)\n' "$label" "$actual"
}
assert_status 0 'clean exact subtree' "$expected" source
assert_status 66 'wrong tree rejected' '0000000000000000000000000000000000000000' source
assert_status 64 'invalid tree SHA rejected' invalid source
assert_status 64 'escaping subdirectory rejected' "$expected" ../source
printf 'tampered\n' > source/main.ores
assert_status 67 'modified tracked file rejected' "$expected" source
git restore -- source/main.ores
printf 'staged\n' > source/main.ores
git add source/main.ores
assert_status 67 'staged modification rejected' "$expected" source
git restore --staged --worktree -- source/main.ores
printf 'untracked\n' > source/new.ores
assert_status 67 'untracked source rejected' "$expected" source
rm source/new.ores
printf 'outside subtree\n' > other.txt
assert_status 0 'other destination files excluded from subtree' "$expected" source
assert_status 67 'root proof notices non-source modifications' "$(git rev-parse 'HEAD^{tree}')" .
echo 'SOURCE_TREE_GATE_TESTS_PASS'
