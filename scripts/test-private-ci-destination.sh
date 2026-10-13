#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/gh" <<'MOCK'
#!/usr/bin/env bash
[[ "${GH_HOST:-}" == github.com ]] || exit 2
[[ "${1:-}" == api && "${2:-}" == repos/trusted/ci ]] || exit 2
[[ "${3:-}" == --jq ]] || exit 2
[[ "${MOCK_API_FAILURE:-false}" != true ]] || exit 4
printf '%s\t%s\t%s\n' "${MOCK_NAME:-trusted/ci}" "${MOCK_VISIBILITY:-private}" "${MOCK_PRIVATE_FLAG:-true}"
MOCK
cat > "$work/record" <<'MOCK'
#!/usr/bin/env bash
printf '%s|%s\n' "${ORESLANG_VERIFIED_CI_DESTINATION:-missing}" "$1" >> "$MOCK_EXEC_LOG"
MOCK
chmod +x "$work/gh" "$work/record"
export PATH="$work:$PATH"
export MOCK_EXEC_LOG="$work/executed"
export ORESLANG_APPROVED_PRIVATE_CI_REPOS='trusted/ci,another/repo'
expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 || {
    cat "$work/out" >&2; exit 1;
  }
  grep -q 'PRIVATE_CI_DESTINATION_APPROVED' "$work/out"
}
expect_fail() {
  if "$script_dir/check-private-ci-destination.sh" "$@" > "$work/out" 2>&1; then
    echo 'Unsafe destination passed preflight' >&2; exit 1;
  fi
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_wrapper_reject() {
  rm -f "$MOCK_EXEC_LOG"
  if "$script_dir/run-in-private-ci-destination.sh" "$1" -- "$work/record" should-not-run > "$work/out" 2>&1; then
    echo 'Unsafe destination executed child command' >&2; exit 1;
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_ok trusted/ci
"$script_dir/run-in-private-ci-destination.sh" trusted/ci -- "$work/record" 'argument with spaces' > "$work/out" 2>&1
grep -Fx 'trusted/ci|argument with spaces' "$MOCK_EXEC_LOG"
for vis in public internal null; do
  export MOCK_VISIBILITY="$vis"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
done
export MOCK_VISIBILITY=private
export MOCK_PRIVATE_FLAG=false
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
export MOCK_PRIVATE_FLAG=true
export MOCK_NAME=other/repo
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_NAME
expect_fail trusted/ci-extra
expect_fail other/repo
expect_fail invalid
expect_fail 'trusted/ci;echo unsafe'
expect_fail
expect_wrapper_reject other/repo
export MOCK_API_FAILURE=true
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_API_FAILURE
unset ORESLANG_APPROVED_PRIVATE_CI_REPOS
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'PASS: explicit allowlist, identity, visibility, auth failure, and command no-execution checks\n'
