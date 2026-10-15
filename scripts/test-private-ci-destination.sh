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
printf '# Approved for this isolated test only\ntrusted/ci 123\n' > "$policy"
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
printf '%s\t%s\t%s\t%s\t%s\t%s%s\n' \
  "${MOCK_NAME:-trusted/ci}" "${MOCK_REPOSITORY_ID:-123}" \
  "${MOCK_VISIBILITY:-private}" "${MOCK_PRIVATE_FLAG:-true}" \
  "${MOCK_ARCHIVED:-false}" "${MOCK_DISABLED:-false}" "${MOCK_EXTRA_LINE:-}"
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
  if "$script_dir/run-in-private-ci-destination.sh" "$1" --source-dir "$work/fixture/source" --branch ci/oreslang-test --expected-source-tree 0000000000000000000000000000000000000000 --expected-source-commit 1111111111111111111111111111111111111111 > "$work/out" 2>&1; then
    echo 'Unsafe destination executed publishing' >&2; exit 1;
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_ok trusted/ci
export MOCK_REPOSITORY_ID=456
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_REPOSITORY_ID
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
for id in 0 -1 not-a-number true; do
  export MOCK_REPOSITORY_ID="$id"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
done
unset MOCK_REPOSITORY_ID
for inactive in MOCK_ARCHIVED MOCK_DISABLED; do
  export "$inactive"=true
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
  unset "$inactive"
done
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
printf 'trusted/ci 123\ntrusted/ci 123\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'trusted/ci 123\nTRUSTED/CI 123\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'trusted/ci 123\nother/repo 123\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'other/repo 123\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'trusted/ci #bad-comment\n' > "$policy"
expect_fail trusted/ci
for invalid_entry in 'trusted/ci' 'trusted/ci 0' 'trusted/ci 0123' 'trusted/ci -1' 'trusted/ci 123 #comment'; do
  printf '%s\n' "$invalid_entry" > "$policy"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
done
printf '# intentionally empty\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
rm "$policy"
expect_fail trusted/ci
ln -s "$work/fixture/scripts/check-private-ci-destination.sh" "$policy"
expect_fail trusted/ci
rm "$policy"
printf 'trusted/ci 123\n' > "$policy"
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
# The wrapper must not execute a caller-provided Bash startup script or use
# a caller-prepended fake bash/python interpreter before policy denial.
printf 'printf injected > "%s/injected"\n' "$work" > "$work/untrusted-startup"
for executable in bash python3; do
  printf '#!/bin/sh\nprintf injected > "%s/injected"\nexit 97\n' "$work" > "$work/$executable"
  chmod +x "$work/$executable"
done
rm -f "$work/injected"
if PATH="$work:$PATH" BASH_ENV="$work/untrusted-startup" PYTHONPATH="$work" \
  "$script_dir/run-in-private-ci-destination.sh" trusted/ci \
  --source-dir "$work/fixture/source" --branch ci/oreslang-test \
  --expected-source-tree 0000000000000000000000000000000000000000 \
  --expected-source-commit 1111111111111111111111111111111111111111 \
  > "$work/out" 2>&1; then
  echo 'Unsafe wrapper execution with hostile environment' >&2
  exit 1
fi
test ! -e "$work/injected" || { echo 'Hostile Bash/Python launcher executed' >&2; exit 1; }
grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
printf 'PASS: private identity, symlink directory and multiline API rejection, child not executed\n'
