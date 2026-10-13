#!/usr/bin/env bash
set -euo pipefail
original_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/fixture/scripts" "$work/fixture/.github"
cp "$original_script_dir/check-private-ci-destination.sh" "$work/fixture/scripts/"
cp "$original_script_dir/run-in-private-ci-destination.sh" "$work/fixture/scripts/"
script_dir="$work/fixture/scripts"
policy="$work/fixture/.github/private-ci-approved-repositories.txt"
printf '# Approved for this isolated test only\ntrusted/ci\n' > "$policy"
cat > "$work/gh" <<'MOCK'
#!/usr/bin/env bash
[[ "${GH_HOST:-}" == github.com ]] || exit 2
[[ "${1:-}" == api && "${2:-}" == repos/trusted/ci ]] || exit 2
[[ "${3:-}" == --jq ]] || exit 2
[[ "${MOCK_API_FAILURE:-false}" != true ]] || exit 4
printf '%s\t%s\t%s%s\n' "${MOCK_NAME:-trusted/ci}" "${MOCK_VISIBILITY:-private}" "${MOCK_PRIVATE_FLAG:-true}" "${MOCK_EXTRA_LINE:-}"
MOCK
cat > "$work/record" <<'MOCK'
#!/usr/bin/env bash
printf '%s|%s\n' "${ORESLANG_VERIFIED_CI_DESTINATION:-missing}" "$1" >> "$MOCK_EXEC_LOG"
MOCK
chmod +x "$work/gh" "$work/record" "$script_dir"/*.sh
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
  if "$script_dir/run-in-private-ci-destination.sh" "$1" -- "$work/record" should-not-run > "$work/out" 2>&1; then
    echo 'Unsafe destination executed command' >&2; exit 1;
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_ok trusted/ci
"$script_dir/run-in-private-ci-destination.sh" trusted/ci -- "$work/record" 'argument with spaces' > "$work/out" 2>&1
grep -Fx 'trusted/ci|argument with spaces' "$MOCK_EXEC_LOG"
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
export MOCK_EXTRA_LINE=
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
printf 'PASS: approved private identity, reject parent symlinks and multiline metadata, no child execution\n'
