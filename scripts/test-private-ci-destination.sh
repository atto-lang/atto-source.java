#!/usr/bin/env bash
set -euo pipefail
original_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/fixture/scripts" "$work/fixture/.github"
cp "$original_script_dir/check-private-ci-destination.sh" "$work/fixture/scripts/"
cp "$original_script_dir/run-in-private-ci-destination.sh" "$work/fixture/scripts/"
cp "$original_script_dir/publish-private-ci-source.py" "$work/fixture/scripts/"
cp "$original_script_dir/publish-private-ci-source-v3.py" "$work/fixture/scripts/"
script_dir="$work/fixture/scripts"
policy="$work/fixture/.github/private-ci-approved-repositories.txt"
printf '# Approved for this isolated test only\ntrusted/ci\n' > "$policy"
cat > "$work/gh" <<'MOCK'
#!/usr/bin/env bash
[[ "${GH_HOST:-}" == github.com ]] || exit 2
[[ "${1:-}" == api && "${2:-}" == repos/trusted/ci ]] || exit 2
if [[ " $* " == *" --method POST "* || "${2:-}" == *"/git/"* ]]; then
  printf 'unexpected-write\n' >> "$MOCK_EXEC_LOG"
  exit 98
fi
[[ "${3:-}" == --jq ]] || exit 2
[[ "${MOCK_API_FAILURE:-false}" != true ]] || exit 4
printf '%s\t%s\t%s%s\n' "${MOCK_NAME:-trusted/ci}" "${MOCK_VISIBILITY:-private}" "${MOCK_PRIVATE_FLAG:-true}" "${MOCK_EXTRA_LINE:-}"
MOCK
mkdir -p "$work/fixture/source"
printf 'pub fnc main(): int { return 42; }\n' > "$work/fixture/source/main.ores"
chmod +x "$work/gh" "$script_dir"/*.sh
export PATH="$work:$PATH" MOCK_EXEC_LOG="$work/executed"
expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 || { cat "$work/out" >&2; exit 1; }
  grep -q 'PRIVATE_CI_DESTINATION_VERIFIED_DIAGNOSTIC_ONLY' "$work/out"
}
expect_fail() {
  if "$script_dir/check-private-ci-destination.sh" "$@" > "$work/out" 2>&1; then echo 'Unsafe approval' >&2; exit 1; fi
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_wrapper_reject() {
  rm -f "$MOCK_EXEC_LOG"
  if "$script_dir/run-in-private-ci-destination.sh" "$1" --source-dir "$work/fixture/source" --source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa --source-pr 7 --test-plan ci/test-plans/synthetic.json > "$work/out" 2>&1; then
    echo 'Unsafe destination executed publishing' >&2; exit 1;
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_ok trusted/ci
for visibility in public internal null; do
  export MOCK_VISIBILITY="$visibility"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
done
export MOCK_VISIBILITY=private MOCK_PRIVATE_FLAG=false
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
export MOCK_PRIVATE_FLAG=true MOCK_NAME=other/repo
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_NAME
expect_fail trusted/ci-extra
expect_fail other/repo
expect_fail trusted
expect_fail 'trusted/ci;echo unsafe'
expect_fail
expect_wrapper_reject other/repo
export MOCK_API_FAILURE=true
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_API_FAILURE
export MOCK_EXTRA_LINE=$'\nspoofed/repo\tprivate\ttrue'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_EXTRA_LINE
printf 'other/repo\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'trusted/ci #bad-comment\n' > "$policy"
expect_fail trusted/ci
printf '# intentionally empty\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
rm "$policy"
expect_fail trusted/ci
ln -s "$work/fixture/scripts/check-private-ci-destination.sh" "$policy"
expect_fail trusted/ci
rm "$policy"
printf 'trusted/ci\n' > "$policy"
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
# Source-free launcher trust-boundary test: replace the publisher with a stub
# and prove caller PATH/PYTHONPATH/shell startup hooks cannot intercept execution.
mv "$script_dir/publish-private-ci-source-v3.py" "$work/publisher-v3-original.py"
cat > "$script_dir/publish-private-ci-source-v3.py" <<'PY'
import os
import sys
assert sys.flags.isolated == 1
assert sys.dont_write_bytecode
assert sys.executable == "/usr/bin/python3"
assert os.environ["PATH"] == "/usr/bin:/bin"
assert "/tmp/poison-python" not in sys.path
assert len(sys.argv) == 10
print("SOURCE_FREE_V3_LAUNCHER_OK")
PY
mkdir -p "$work/poison-path" "$work/poison-python"
for executable in python3 dirname bash sh; do
  cat > "$work/poison-path/$executable" <<'FAKE'
#!/bin/sh
printf 'unsafe executable invoked\n' >> "$LAUNCHER_UNSAFE_MARKER"
exit 90
FAKE
  chmod +x "$work/poison-path/$executable"
done
cat > "$work/poison-python/sitecustomize.py" <<'PY'
import os
open(os.environ["LAUNCHER_UNSAFE_MARKER"], "a").write("sitecustomize executed\n")
PY
printf '%s\n' 'printf "shell startup hook executed\n" >> "$LAUNCHER_UNSAFE_MARKER"' > "$work/startup-hook"
rm -f "$work/launcher-unsafe"
PATH="$work/poison-path:$PATH" \
PYTHONPATH="$work/poison-python" \
BASH_ENV="$work/startup-hook" ENV="$work/startup-hook" \
LAUNCHER_UNSAFE_MARKER="$work/launcher-unsafe" \
  /bin/sh "$script_dir/run-in-private-ci-destination.sh" \
  trusted/ci --source-dir "$work/fixture/source" \
  --source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --source-pr 7 --test-plan ci/test-plans/synthetic.json \
  > "$work/out" 2>&1 || { cat "$work/out" >&2; exit 1; }
grep -Fxq 'SOURCE_FREE_V3_LAUNCHER_OK' "$work/out"
test ! -e "$work/launcher-unsafe"
mv "$work/publisher-v3-original.py" "$script_dir/publish-private-ci-source-v3.py"

# Regression: PR events must never earn a publisher receipt for GitHub's
# synthetic pull_request merge commit. Check original SHA at both boundaries.
workflow="$original_script_dir/../.github/workflows/private-ci-mirror-preflight.yml"
test -f "$workflow"
expected_expression="$(cat <<'EOF'
${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}
EOF
)"
grep -Fq "ref: $expected_expression" "$workflow"
grep -Fq "EXPECTED_SOURCE_SHA: $expected_expression" "$workflow"
grep -Fq '[[ "$actual" == "$EXPECTED_SOURCE_SHA" ]]' "$workflow"
grep -Fq "PRIVATE_PUBLISHER_SOURCE_SHA_MISMATCH" "$workflow"
grep -Fq "PRIVATE_PUBLISHER_POST_TEST_HEAD_MISMATCH" "$workflow"
grep -Fq "PRIVATE_PUBLISHER_REGRESSIONS_COMPLETE_SHA=%s PASSES=3" "$workflow"
printf 'PASS: publisher PR checkout and triple-regression receipt bind exact HEAD\n'

# Source-free negative tests for the exact-tree worktree evidence tripwire.
# These tests neither inspect nor export Oreslang compiler source.
grep -Fq 'EXPECTED_SOURCE_TREE=$tree' "$workflow"
grep -Fq "PRIVATE_PUBLISHER_TRACKED_SOURCE_DIRTY" "$workflow"
grep -Fq "PRIVATE_PUBLISHER_INDEX_DIRTY" "$workflow"
grep -Fq "PRIVATE_PUBLISHER_UNTRACKED_FILES" "$workflow"
grep -Fq "PRIVATE_PUBLISHER_TREE_MISMATCH" "$workflow"
grep -Fq "PRIVATE_PUBLISHER_REGRESSIONS_COMPLETE_SHA=%s TREE=%s PASSES=3" "$workflow"

mkdir -p "$work/exact-head-oracle"
git -C "$work/exact-head-oracle" init -q
git -C "$work/exact-head-oracle" config user.email ci-fixture@example.invalid
git -C "$work/exact-head-oracle" config user.name CI-Fixture
printf 'stable\\n' > "$work/exact-head-oracle/checked.txt"
git -C "$work/exact-head-oracle" add checked.txt
git -C "$work/exact-head-oracle" commit -qm source-free
(
  cd "$work/exact-head-oracle"
  git diff --quiet --exit-code HEAD --
  git diff --cached --quiet --exit-code HEAD --
  test -z "$(git ls-files --others --exclude-standard)"
  expected="$(git rev-parse --verify 'HEAD^{tree}')"
  test "$expected" = "$(git rev-parse --verify 'HEAD^{tree}')"

  printf 'modified\\n' > checked.txt
  if git diff --quiet --exit-code HEAD --; then
    echo 'Tracked mutation escaped publisher worktree gate' >&2
    exit 1
  fi
  git restore checked.txt

  printf 'staged\\n' > checked.txt
  git add checked.txt
  if git diff --cached --quiet --exit-code HEAD --; then
    echo 'Index mutation escaped publisher worktree gate' >&2
    exit 1
  fi
  git reset -q HEAD -- checked.txt
  git restore checked.txt

  printf 'injected\\n' > extra.txt
  if test -z "$(git ls-files --others --exclude-standard)"; then
    echo 'Untracked source escaped publisher worktree gate' >&2
    exit 1
  fi
  rm extra.txt
  git diff --quiet --exit-code HEAD --
  git diff --cached --quiet --exit-code HEAD --
  test -z "$(git ls-files --others --exclude-standard)"
)
printf 'PASS: publisher worktree oracle rejects tracked/staged/untracked mutations\\n'


printf 'PASS: private identity, symlink directory, multiline API, and launcher injection rejection\n'
