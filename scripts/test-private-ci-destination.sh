#!/usr/bin/env bash
set -euo pipefail
original_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/fixture/scripts" "$work/fixture/.github"
cp "$original_script_dir/check-private-ci-destination.sh" "$work/fixture/scripts/"
cp "$original_script_dir/run-in-private-ci-destination.sh" "$work/fixture/scripts/"
cp "$original_script_dir/publish-private-ci-source.py" "$work/fixture/scripts/"
script_dir="$work/fixture/scripts"
policy="$work/fixture/.github/private-ci-approved-repositories.txt"
printf '# Approved for this isolated test only\ntrusted/ci@424242\n' > "$policy"
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
printf '%s\t%s\t%s\t%s%s\n' "${MOCK_ID:-424242}" "${MOCK_NAME:-trusted/ci}" "${MOCK_VISIBILITY:-private}" "${MOCK_PRIVATE_FLAG:-true}" "${MOCK_EXTRA_LINE:-}"
MOCK
mkdir -p "$work/fixture/source"
printf 'pub fnc main(): int { return 42; }\n' > "$work/fixture/source/main.ores"
chmod +x "$work/gh" "$script_dir"/*.sh
export PATH="$work:$PATH" MOCK_EXEC_LOG="$work/executed"
expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 || { cat "$work/out" >&2; exit 1; }
  grep -q 'PRIVATE_CI_DESTINATION_APPROVED' "$work/out"
}
expect_fail() {
  if "$script_dir/check-private-ci-destination.sh" "$@" > "$work/out" 2>&1; then echo 'Unsafe approval' >&2; exit 1; fi
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_wrapper_reject() {
  rm -f "$MOCK_EXEC_LOG"
  if "$script_dir/run-in-private-ci-destination.sh" "$1" --source-dir "$work/fixture/source" --branch ci/oreslang-test --expected-source-tree 0000000000000000000000000000000000000000 --source-pr 1286 --expected-source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa > "$work/out" 2>&1; then
    echo 'Unsafe destination executed publishing' >&2; exit 1;
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_wrapper_merged_reject() {
  rm -f "$MOCK_EXEC_LOG"
  if "$script_dir/run-in-private-ci-destination.sh" "$1" --source-dir "$work/fixture/source" --branch ci/oreslang-merged-test --expected-source-tree 0000000000000000000000000000000000000000 --source-merged-pr 1286 --expected-source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa > "$work/out" 2>&1; then
    echo 'Unsafe merged-main destination executed publishing' >&2
    exit 1
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_ok trusted/ci
# A reused owner/name with a different immutable repository ID must fail.
export MOCK_ID=424243
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_ID
for unsafe in 'trusted/ci' 'trusted/ci@000424242' 'trusted/ci@0' 'trusted/ci@424242 # comment'; do
  printf '%s\n' "$unsafe" > "$policy"
  expect_fail trusted/ci
done
printf 'trusted/ci@424242\nTRUSTED/CI@424242\n' > "$policy"
expect_fail trusted/ci
printf '# Approved for this isolated test only\ntrusted/ci@424242\n' > "$policy"
for visibility in public internal null; do
  export MOCK_VISIBILITY="$visibility"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
  expect_wrapper_merged_reject trusted/ci
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
expect_wrapper_merged_reject other/repo
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
printf 'trusted/ci@424242\n' > "$policy"
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
# A source-free publisher stub verifies the shell entrypoint itself.
# The real publisher is not executed here and no GitHub request is made.
mv "$script_dir/publish-private-ci-source.py" "$work/private-publisher-original.py"
cat > "$script_dir/publish-private-ci-source.py" <<'STUB'
import os, sys
assert sys.flags.isolated == 1
assert sys.dont_write_bytecode
assert sys.executable == "/usr/bin/python3"
assert os.environ["PATH"] == "/usr/bin:/bin"
assert os.environ["PYTHONPATH"] not in sys.path
assert len(sys.argv) == 12
print("SOURCE_FREE_PRIVATE_WRAPPER_OK=" + sys.argv[8])
STUB
mkdir -p "$work/path-poison" "$work/pythonpath"
for executable in python3 dirname sh bash; do
  cat > "$work/path-poison/$executable" <<'FAKE'
#!/bin/sh
printf 'unsafely executed\n' >> "$WRAPPER_UNSAFE_MARKER"
FAKE
  chmod +x "$work/path-poison/$executable"
done
cat > "$work/startup-hook" <<'HOOK'
printf 'unsafely executed\n' >> "$WRAPPER_UNSAFE_MARKER"
HOOK
cat > "$work/pythonpath/sitecustomize.py" <<'SITE'
import os
open(os.environ["WRAPPER_UNSAFE_MARKER"], "w").write("unsafely executed")
SITE
for source_selector in --source-pr --source-merged-pr; do
  rm -f "$work/wrapper-unsafe-marker"
  PATH="$work/path-poison:$PATH" \
  BASH_ENV="$work/startup-hook" ENV="$work/startup-hook" \
  PYTHONPATH="$work/pythonpath" \
  WRAPPER_UNSAFE_MARKER="$work/wrapper-unsafe-marker" \
    /bin/sh "$script_dir/run-in-private-ci-destination.sh" \
       trusted/ci --source-dir "$work/fixture/source" \
       --branch ci/oreslang-test \
       --expected-source-tree 0000000000000000000000000000000000000000 \
       "$source_selector" 1286 \
       --expected-source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
       > "$work/out" 2>&1 || { cat "$work/out" >&2; exit 1; }
  grep -Fxq "SOURCE_FREE_PRIVATE_WRAPPER_OK=$source_selector" "$work/out"
  test ! -e "$work/wrapper-unsafe-marker"
done
mv "$work/private-publisher-original.py" "$script_dir/publish-private-ci-source.py"
printf 'PASS: both launcher modes resist PATH, BASH_ENV and PYTHONPATH injection\n'
printf 'PASS: private identity, symlink directory and multiline API rejection, child not executed\n'
