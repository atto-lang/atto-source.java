#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/gh" <<'MOCK'
#!/usr/bin/env bash
[[ "${1:-}" == api ]] || exit 2
[[ "${2:-}" == repos/trusted/ci ]] || exit 2
[[ "${3:-}" == --jq && "${4:-}" == .visibility ]] || exit 2
[[ "${MOCK_API_FAILURE:-false}" != true ]] || exit 4
printf '%s\n' "${MOCK_VISIBILITY:-null}"
MOCK
chmod +x "$work/gh"
export PATH="$work:$PATH"
export ORESLANG_APPROVED_PRIVATE_CI_REPOS='trusted/ci,another/repo'
export MOCK_VISIBILITY=private

expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 || {
    cat "$work/out" >&2
    exit 1
  }
  grep -q 'PRIVATE_CI_DESTINATION_APPROVED' "$work/out"
}
expect_fail() {
  if "$script_dir/check-private-ci-destination.sh" "$@" > "$work/out" 2>&1; then
    printf 'Expected destination rejection, instead got:\n' >&2
    cat "$work/out" >&2
    exit 1
  fi
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}

expect_ok trusted/ci
for visibility in public internal null ''; do
  export MOCK_VISIBILITY="$visibility"
  expect_fail trusted/ci
done
export MOCK_VISIBILITY=private
expect_fail trusted/ci-extra
expect_fail other/repo
expect_fail trusted
expect_fail 'trusted/ci;echo unsafe'
expect_fail
export MOCK_API_FAILURE=true
expect_fail trusted/ci
unset MOCK_API_FAILURE
unset ORESLANG_APPROVED_PRIVATE_CI_REPOS
expect_fail trusted/ci
printf 'PASS: explicit allowlist, exact repo, private visibility, invalid input, missing auth and API errors all fail closed\n'
