#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."

# Offline deny-by-default regression for the source-local immutable-SHA gate.
# Synthetic Git fixture only: no actual private source or external CI transfer.
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT HUP INT TERM
fixture="$temp/fixture"
mkdir -p "$fixture/scripts" "$temp/min-path"
cp scripts/test-native-actor-exact-tree-ci.sh "$fixture/scripts/"
git init -q "$fixture"
# Only tracked tree configuration is authoritative. Untracked ignored inputs
# must be rejected even though git status --porcelain hides them.
printf 'ignored-compiler-overlay\n' > "$fixture/.gitignore"
git -C "$fixture" config user.name "Native CI Fixture"
git -C "$fixture" config user.email "ci-fixture@example.invalid"
git -C "$fixture" add scripts .gitignore
git -C "$fixture" -c commit.gpgsign=false commit -qm "pin trusted fixture tree"
sha="$(git -C "$fixture" rev-parse HEAD)"
tree="$(git -C "$fixture" rev-parse 'HEAD^{tree}')"
zero=0000000000000000000000000000000000000000

# Guarantee no Maven binary appears on the synthetic PATH, independently of
# whether the actual CI host has Maven installed.
for cmd in git dirname uname; do
  ln -s "$(command -v "$cmd")" "$temp/min-path/$cmd"
done

deny() {
  label="$1"
  expected="$2"
  shift 2
  if "$@" > "$temp/out" 2>&1; then
    echo "FAIL: $label unexpectedly passed" >&2
    exit 1
  fi
  if ! grep -F "$expected" "$temp/out" >/dev/null 2>&1; then
    echo "FAIL: $label rejected for the wrong reason" >&2
    cat "$temp/out" >&2
    exit 1
  fi
  echo "NATIVE_CI_PREFLIGHT_DENIED $label"
}

gate="$fixture/scripts/test-native-actor-exact-tree-ci.sh"
deny missing-pins "pin reviewed 40-hex" \
  env -u ORES_CI_EXPECTED_COMMIT_SHA -u ORES_CI_EXPECTED_TREE_SHA \
  sh "$gate"
deny malformed-pin "CI pin must consist only" \
  env ORES_CI_EXPECTED_COMMIT_SHA=invalid \
      ORES_CI_EXPECTED_TREE_SHA="$tree" sh "$gate"
deny wrong-commit "differs from reviewed immutable pins" \
  env ORES_CI_EXPECTED_COMMIT_SHA="$zero" \
      ORES_CI_EXPECTED_TREE_SHA="$tree" sh "$gate"
deny wrong-tree "differs from reviewed immutable pins" \
  env ORES_CI_EXPECTED_COMMIT_SHA="$sha" \
      ORES_CI_EXPECTED_TREE_SHA="$zero" sh "$gate"

echo '# unreviewed modification' >> "$gate"
deny modified-source "modified or untracked inputs" \
  env ORES_CI_EXPECTED_COMMIT_SHA="$sha" \
      ORES_CI_EXPECTED_TREE_SHA="$tree" sh "$gate"
git -C "$fixture" checkout -q -- scripts/test-native-actor-exact-tree-ci.sh

mkdir -p "$fixture/src/main/c"
printf 'int unreviewed = 1;\n' > "$fixture/src/main/c/injected.c"
deny untracked-source "modified or untracked inputs" \
  env ORES_CI_EXPECTED_COMMIT_SHA="$sha" \
      ORES_CI_EXPECTED_TREE_SHA="$tree" sh "$gate"
rm "$fixture/src/main/c/injected.c"

# Non-Java project inputs are still part of the reviewed Git tree.
# Never accept an untracked .mvn, resource, stdlib, or root-level overlay.
printf 'untrusted Maven configuration\n' > "$fixture/.mvn-injected"
deny untracked-project-input "modified or untracked inputs" \
  env ORES_CI_EXPECTED_COMMIT_SHA="$sha" \
      ORES_CI_EXPECTED_TREE_SHA="$tree" sh "$gate"
rm "$fixture/.mvn-injected"

printf 'ignored but executable Maven/Compiler override\n' > "$fixture/ignored-compiler-overlay"
deny ignored-project-input "CI source checkout has ignored untracked inputs" \
  env ORES_CI_EXPECTED_COMMIT_SHA="$sha" \
      ORES_CI_EXPECTED_TREE_SHA="$tree" sh "$gate"
rm "$fixture/ignored-compiler-overlay"

deny absent-maven "Maven is required" \
  env PATH="$temp/min-path" ORES_CI_EXPECTED_COMMIT_SHA="$sha" \
      ORES_CI_EXPECTED_TREE_SHA="$tree" /bin/sh "$gate"
echo "NATIVE_ACTOR_CI_PREFLIGHT_NEGATIVE_TESTS_OK cases=9"
