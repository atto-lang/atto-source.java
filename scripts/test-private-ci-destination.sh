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
approved_id=1412142815
approved_workflow=ef7b0caaa1aedb56864f01fad14bfe88321f1ab1
printf '# Approved for this isolated test only\ntrusted/ci %s %s\n'   "$approved_id" "$approved_workflow" > "$policy"

cat > "$work/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "${GH_HOST:-}" == github.com ]] || exit 2
[[ "${1:-}" == api ]] || exit 2
route="${2:-}"
if [[ " $* " == *" --method POST "* || "$route" == *"/git/"* ]]; then
  printf 'unexpected-write\n' >> "$MOCK_EXEC_LOG"
  exit 98
fi
[[ "${3:-}" == --jq ]] || exit 2
[[ "${MOCK_API_FAILURE:-false}" != true ]] || exit 4
case "$route" in
  repos/trusted/ci)
    printf '%s\t%s\t%s\t%s\t%s%s\n'       "${MOCK_NAME:-trusted/ci}"       "${MOCK_ID:-1412142815}"       "${MOCK_VISIBILITY:-private}"       "${MOCK_PRIVATE_FLAG:-true}"       "${MOCK_DEFAULT_BRANCH:-main}"       "${MOCK_EXTRA_LINE:-}"
    ;;
  'repos/trusted/ci/contents/.github/workflows/private-oreslang-exact-tree.yml?ref=main')
    printf '%s\t%s%s\n'       "${MOCK_WORKFLOW_TYPE:-file}"       "${MOCK_WORKFLOW_SHA:-ef7b0caaa1aedb56864f01fad14bfe88321f1ab1}"       "${MOCK_WORKFLOW_EXTRA_LINE:-}"
    ;;
  *) exit 2 ;;
esac
MOCK

mkdir -p "$work/fixture/source"
printf 'pub fnc main(): int { return 42; }\n' > "$work/fixture/source/main.ores"
chmod +x "$work/gh" "$script_dir"/*.sh
export PATH="$work:$PATH" MOCK_EXEC_LOG="$work/executed"

expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 ||
    { cat "$work/out" >&2; exit 1; }
  grep -q 'PRIVATE_CI_DESTINATION_APPROVED' "$work/out"
}
expect_fail() {
  if "$script_dir/check-private-ci-destination.sh" "$@" > "$work/out" 2>&1; then
    echo 'Unsafe approval' >&2
    exit 1
  fi
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}
expect_wrapper_reject() {
  rm -f "$MOCK_EXEC_LOG"
  if "$script_dir/run-in-private-ci-destination.sh" "$1"       --source-dir "$work/fixture/source"       --branch ci/oreslang-test       --source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa       > "$work/out" 2>&1; then
    echo 'Unsafe destination executed publishing' >&2
    exit 1
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
export MOCK_PRIVATE_FLAG=true

export MOCK_NAME=other/repo
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_NAME

export MOCK_ID=999
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_ID

export MOCK_WORKFLOW_SHA=ffffffffffffffffffffffffffffffffffffffff
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_SHA

export MOCK_WORKFLOW_TYPE=dir
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_TYPE

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

export MOCK_EXTRA_LINE=$'\nspoofed/repo\t1412142815\tprivate\ttrue\tmain'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_EXTRA_LINE

export MOCK_WORKFLOW_EXTRA_LINE=$'\nfile\tef7b0caaa1aedb56864f01fad14bfe88321f1ab1'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_EXTRA_LINE

printf 'other/repo 9 %s\n' "$approved_workflow" > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'trusted/ci\n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 1412142815\n' > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 1412142815 %s extra\n' "$approved_workflow" > "$policy"
expect_fail trusted/ci
printf 'trusted/ci 1412142815  bad\n' > "$policy"
expect_fail trusted/ci

printf '# intentionally empty\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

rm "$policy"
expect_fail trusted/ci
ln -s "$work/fixture/scripts/check-private-ci-destination.sh" "$policy"
expect_fail trusted/ci

rm "$policy"
printf 'trusted/ci %s %s\n' "$approved_id" "$approved_workflow" > "$policy"
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'PASS: private slug, immutable ID, reviewed workflow, symlink and multiline rejection\n'
