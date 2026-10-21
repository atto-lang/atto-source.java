#!/usr/bin/env bash
set -euo pipefail

# Synthetic Git fixtures only: validates the release gate's SHA, tree and
# dirty-input checks. Never treats stub Maven executions as Oreslang tests.
root="$(cd "$(dirname "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

repo="$tmp/source"
mkdir -p "$repo/scripts" "$tmp/bin"
cp "$root/scripts/check-record-inference.sh" "$repo/scripts/"
printf '#!/usr/bin/env bash\nexit 0\n' > "$repo/scripts/check-record-parser.sh"
printf '#!/usr/bin/env bash\nexit 0\n' > "$repo/scripts/check-record-frontend.sh"
printf '#!/usr/bin/env bash\nexit 0\n' > "$repo/scripts/check-soa-snapshot.sh"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/bin/mvn"
chmod +x "$tmp/bin/mvn"
# The gate sees this harmless synthetic Maven stub, never a real compiler.
export PATH="$tmp/bin:$PATH"
printf '*.java\n' > "$repo/.gitignore"

git -C "$repo" init -q
git -C "$repo" -c user.name=CI-Gate -c user.email=ci-gate@example.invalid \
  add . >/dev/null
git -C "$repo" -c user.name=CI-Gate -c user.email=ci-gate@example.invalid \
  commit -qm 'synthetic source tree'
head="$(git -C "$repo" rev-parse HEAD)"
tree="$(git -C "$repo" rev-parse HEAD^{tree})"

assert_exit() {
  expected="$1"
  shift
  got=0
  (cd "$repo"; "$@" > "$tmp/output" 2>&1) || got="$?"
  if [[ "$got" != "$expected" ]]; then
    echo "expected exit $expected but got $got: $*" >&2
    cat "$tmp/output" >&2
    exit 1
  fi
}

assert_exit 2 bash scripts/check-record-inference.sh bad
assert_exit 2 bash scripts/check-record-inference.sh --tree bad
assert_exit 2 bash scripts/check-record-inference.sh --tree "$tree" extra
assert_exit 3 bash scripts/check-record-inference.sh 0000000000000000000000000000000000000000
assert_exit 3 bash scripts/check-record-inference.sh --tree 0000000000000000000000000000000000000000
assert_exit 0 bash scripts/check-record-inference.sh "$head"
assert_exit 0 bash scripts/check-record-inference.sh --commit "$head"
assert_exit 0 bash scripts/check-record-inference.sh --tree "$tree"

# Validate the missing-tool guard without depending on whether CI happens
# to have a global Maven binary. Give the test only the required early tools.
mkdir -p "$tmp/no-maven"
for tool in git java javac dirname; do
  ln -s "$(command -v "$tool")" "$tmp/no-maven/$tool"
done
assert_exit 5 env PATH="$tmp/no-maven" /bin/bash scripts/check-record-inference.sh --tree "$tree"

# A nested script checkout must not accidentally test the parent Git tree.
mkdir -p "$repo/nested/scripts"
cp "$repo/scripts/check-record-inference.sh" "$repo/nested/scripts/"
assert_exit 3 bash nested/scripts/check-record-inference.sh --tree "$tree"
rm -rf "$repo/nested"

printf 'untracked\n' > "$repo/untracked.txt"
assert_exit 4 bash scripts/check-record-inference.sh --tree "$tree"
rm "$repo/untracked.txt"

printf '// ignored Java code\n' > "$repo/injected.java"
assert_exit 4 bash scripts/check-record-inference.sh --tree "$tree"
rm "$repo/injected.java"

printf '\n# mutated\n' >> "$repo/.gitignore"
assert_exit 4 bash scripts/check-record-inference.sh --tree "$tree"
git -C "$repo" checkout -q -- .gitignore
printf '\n# staged\n' >> "$repo/.gitignore"
git -C "$repo" add .gitignore
assert_exit 4 bash scripts/check-record-inference.sh --tree "$tree"
git -C "$repo" reset -q --hard HEAD

# Different commit identity but identical full tree: allowed ONLY in tree mode.
other="$tmp/reconstructed"
mkdir "$other"
cp -a "$repo/." "$other/"
rm -rf "$other/.git"
git -C "$other" init -q
git -C "$other" -c user.name=Different -c user.email=other@example.invalid add .
git -C "$other" -c user.name=Different -c user.email=other@example.invalid \
  commit -qm 'different commit identity'
other_head="$(git -C "$other" rev-parse HEAD)"
other_tree="$(git -C "$other" rev-parse HEAD^{tree})"
[[ "$head" != "$other_head" && "$tree" == "$other_tree" ]] || {
  echo "invalid mirror fixture" >&2
  exit 1
}
repo="$other"
assert_exit 0 bash scripts/check-record-inference.sh --tree "$tree"
assert_exit 3 bash scripts/check-record-inference.sh --commit "$head"

echo 'RECORD_INFERENCE_GATE_TEST_PASS (synthetic Git fixture, no compiler test)'
