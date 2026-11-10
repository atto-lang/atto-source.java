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
printf '# Approved for this isolated test only\ntrusted/ci 246810\n' > "$policy"
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
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%s\n' "${MOCK_NAME:-trusted/ci}" "${MOCK_VISIBILITY:-private}" "${MOCK_PRIVATE_FLAG:-true}" "${MOCK_REPOSITORY_ID:-246810}" "${MOCK_ARCHIVED:-false}" "${MOCK_DISABLED:-false}" "${MOCK_FORK:-false}" "${MOCK_DEFAULT_BRANCH-main}" "${MOCK_EXTRA_LINE:-}"
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
export MOCK_REPOSITORY_ID=246811
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
export MOCK_REPOSITORY_ID=invalid
expect_fail trusted/ci
unset MOCK_REPOSITORY_ID
for state in MOCK_ARCHIVED MOCK_DISABLED MOCK_FORK; do
  export "$state"=true
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
  export "$state"=null
  expect_fail trusted/ci
  unset "$state"
done
for branch in develop null ''; do
  export MOCK_DEFAULT_BRANCH="$branch"
  expect_fail trusted/ci
  unset MOCK_DEFAULT_BRANCH
done
export MOCK_EXTRA_LINE=$'\tunexpected'
expect_fail trusted/ci
unset MOCK_EXTRA_LINE
export MOCK_EXTRA_LINE=$'\t'
expect_fail trusted/ci
unset MOCK_EXTRA_LINE
printf 'trusted/ci 246810\nTRUSTED/CI 246811\n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 246810\nother/repo 246810\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'trusted/ci 246810\nother/repo 246811\n' > "$policy"
expect_ok trusted/ci
printf 'trusted/ci 0246810\n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 246810junk\n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 246810 \n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 246810\n' > "$policy"
expect_ok trusted/ci
printf 'trusted/ci 246810\r\n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 246810\n' > "$policy"
expect_ok trusted/ci
for quarantined_id in 1323575966 1323598260 1412142815 1370782550 1324139596 1350751074; do
  printf 'trusted/ci %s\n' "$quarantined_id" > "$policy"
  export MOCK_REPOSITORY_ID="$quarantined_id"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
done
unset MOCK_REPOSITORY_ID

for quarantined_slug in \
  zed-pkg-test/java-app \
  scintilla-run-test/gleam-runner-conformance \
  ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra \
  agent-pontifex-test/agent-pontifex-e2e-tests \
  led-dynamo/leddy-e2e \
  ClaimGraph-test/claimgraph-e2e \
  CLAIMGRAPH-TEST/CLAIMGRAPH-E2E; do
  printf '%s 246810\n' "$quarantined_slug" > "$policy"
  expect_fail "$quarantined_slug"
  expect_wrapper_reject "$quarantined_slug"
done

printf 'trusted/ci 246810\n' > "$policy"
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
export MOCK_EXTRA_LINE=$'\r'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_EXTRA_LINE
printf 'other/repo 246810\n' > "$policy"
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
printf 'trusted/ci 246810\n' > "$policy"
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
printf 'PASS: private identity, symlink directory and multiline API rejection, child not executed\n'
