#!/usr/bin/env bash
# Source-free, publisher-free adversarial test for the standalone Bash guard.
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/fixture/scripts" "$work/fixture/.github" "$work/bin"
cp "$source_dir/check-private-ci-destination.sh" "$work/fixture/scripts/"
policy="$work/fixture/.github/private-ci-approved-repositories.txt"
guard="$work/fixture/scripts/check-private-ci-destination.sh"
# Only the disposable fixture uses this synthetic destination and numeric ID.
sed -i 's@chapter-publishing-test/zed-pkg-integration-e2e@trusted/ci@g; s@1344442382@123456789@g' "$guard"
printf 'trusted/ci\n' > "$policy"
cat > "$work/bin/gh" <<'MOCK'
#!/usr/bin/env bash
[[ "${GH_HOST:-}" == github.com ]] || exit 41
[[ "${1:-}" == api && "${2:-}" == repos/trusted/ci && "${3:-}" == --jq ]] || exit 42
[[ " $* " != *' --method POST '* && "${2:-}" != *'/git/'* ]] || exit 43
printf 'GET %s\n' "$2" >> "$MOCK_READ_LOG"
[[ "${MOCK_FAIL:-false}" != true ]] || exit 44
printf '%s\t%s\t%s\t%s\t%s\t%s%s\n' \
  "${MOCK_ID-123456789}" "${MOCK_NAME-trusted/ci}" \
  "${MOCK_VISIBILITY-private}" "${MOCK_PRIVATE-true}" \
  "${MOCK_ARCHIVED-false}" "${MOCK_DISABLED-false}" "${MOCK_SUFFIX-}"
MOCK
chmod +x "$work/bin/gh" "$guard"
export PATH="$work/bin:$PATH" MOCK_READ_LOG="$work/read-log"
case_count=0
run_case() {
  local expectation="$1"; shift
  : > "$MOCK_READ_LOG"
  if "$guard" "$@" > "$work/out" 2>&1; then
    [[ "$expectation" == ok ]] || { echo "Unexpected authorization: $*" >&2; exit 1; }
    grep -q '^PRIVATE_CI_DESTINATION_APPROVED:' "$work/out"
  else
    [[ "$expectation" == denied ]] || { cat "$work/out" >&2; exit 1; }
    grep -q '^PRIVATE_CI_DESTINATION_REJECTED:' "$work/out"
  fi
  # The guard is read-only: mock refuses any GitHub writes, and every API
  # request must be a single metadata read of the synthetic destination.
  if [[ -s "$MOCK_READ_LOG" ]]; then
    ! grep -vFx 'GET repos/trusted/ci' "$MOCK_READ_LOG"
  fi
  ((case_count+=1))
}
run_case ok trusted/ci
# An identity pin is NOT a reviewed policy approval.
run_case denied ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra
for property in MOCK_ID MOCK_NAME MOCK_VISIBILITY MOCK_PRIVATE MOCK_ARCHIVED MOCK_DISABLED; do
  for bad in '' null false 999999999 other/repo public internal true; do
    export "$property=$bad"
    if [[ "$property:$bad" == MOCK_ARCHIVED:false || "$property:$bad" == MOCK_DISABLED:false || "$property:$bad" == MOCK_PRIVATE:true || "$property:$bad" == MOCK_VISIBILITY:private || "$property:$bad" == MOCK_NAME:trusted/ci || "$property:$bad" == MOCK_ID:123456789 ]]; then
      run_case ok trusted/ci
    else
      run_case denied trusted/ci
    fi
  done
  unset "$property"
done
export MOCK_FAIL=true
run_case denied trusted/ci
unset MOCK_FAIL
export MOCK_SUFFIX=$'\nspoofed/repo\tprivate\ttrue'
run_case denied trusted/ci
unset MOCK_SUFFIX
for invalid in other/repo trusted 'trusted/ci;echo unsafe' 'trusted/../ci' 'trusted/ci ' $'trusted/ci\nother/repo'; do
  run_case denied "$invalid"
done
run_case denied
for invalid_policy in 'other/repo' 'trusted/ci #bad-comment' '# empty approval' 'trusted/ci ' 'trusted/ci/' ; do
  printf '%s\n' "$invalid_policy" > "$policy"
  run_case denied trusted/ci
done
rm "$policy"
run_case denied trusted/ci
ln -s "$guard" "$policy"
run_case denied trusted/ci
rm "$policy"
printf 'trusted/ci\n' > "$policy"
mv "$work/fixture/.github" "$work/real-policy"
ln -s "$work/real-policy" "$work/fixture/.github"
run_case denied trusted/ci
printf 'PASS: %d standalone Bash guard scenarios, with no publisher or source checkout\n' "$case_count"
