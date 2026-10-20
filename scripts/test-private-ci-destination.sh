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
approved_base_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
approved_base_tree=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
approved_workflow=ffffffffffffffffffffffffffffffffffffffff
printf '# Approved for this isolated test only\ntrusted/ci %s %s %s %s\n' \
  "$approved_id" "$approved_base_commit" "$approved_base_tree" "$approved_workflow" > "$policy"

cat > "$work/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "${GH_HOST:-}" == github.com ]] || exit 2
[[ "${1:-}" == api ]] || exit 2
route="${2:-}"
if [[ " $* " == *" --method POST "* ]]; then
  printf 'unexpected-write\n' >> "$MOCK_EXEC_LOG"
  exit 98
fi
[[ "${3:-}" == --jq ]] || exit 2
[[ "${MOCK_API_FAILURE:-false}" != true ]] || exit 4

base_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
case "$route" in
  repos/trusted/ci)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%s\n' \
      "${MOCK_NAME:-trusted/ci}" \
      "${MOCK_ID:-1412142815}" \
      "${MOCK_VISIBILITY:-private}" \
      "${MOCK_PRIVATE_FLAG:-true}" \
      "${MOCK_ARCHIVED_FLAG:-false}" \
      "${MOCK_DISABLED_FLAG:-false}" \
      "${MOCK_FORK_FLAG:-false}" \
      "${MOCK_DEFAULT_BRANCH:-main}" \
      "${MOCK_EXTRA_LINE:-}"
    ;;
  repos/trusted/ci/git/ref/heads/main)
    printf '%s%s\n' "${MOCK_BASE_COMMIT:-$base_commit}" "${MOCK_BASE_REF_EXTRA_LINE:-}"
    ;;
  repos/trusted/ci/git/commits/$base_commit)
    printf '%s%s\n' "${MOCK_BASE_TREE:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}" "${MOCK_BASE_TREE_EXTRA_LINE:-}"
    ;;
  "repos/trusted/ci/contents/.github/workflows/private-oreslang-exact-tree.yml?ref=$base_commit")
    printf '%s\t%s%s\n' \
      "${MOCK_WORKFLOW_TYPE:-file}" \
      "${MOCK_WORKFLOW_SHA:-ffffffffffffffffffffffffffffffffffffffff}" \
      "${MOCK_WORKFLOW_EXTRA_LINE:-}"
    ;;
  *) exit 2 ;;
esac
MOCK

mkdir -p "$work/fixture/source"
printf 'pub fnc main(): int { return 42; }\n' > "$work/fixture/source/main.ores"
chmod +x "$work/gh" "$script_dir"/*.sh
export PATH="$work:$PATH" MOCK_EXEC_LOG="$work/executed"

reset_policy() {
  printf '# Approved for this isolated test only\ntrusted/ci %s %s %s %s\n' \
    "$approved_id" "$approved_base_commit" "$approved_base_tree" "$approved_workflow" > "$policy"
}
expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 ||
    { cat "$work/out" >&2; exit 1; }
  grep -q 'PRIVATE_CI_DESTINATION_APPROVED' "$work/out"
  grep -q "base=$approved_base_commit" "$work/out"
  grep -q "tree=$approved_base_tree" "$work/out"
  grep -q "workflow=$approved_workflow" "$work/out"
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
  if "$script_dir/run-in-private-ci-destination.sh" "$1" \
      --source-dir "$work/fixture/source" \
      --branch ci/oreslang-test \
      --source-commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
      > "$work/out" 2>&1; then
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
export MOCK_VISIBILITY=private

export MOCK_PRIVATE_FLAG=false
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_PRIVATE_FLAG

for variable in MOCK_ARCHIVED_FLAG MOCK_DISABLED_FLAG MOCK_FORK_FLAG; do
  export "$variable=true"
  expect_fail trusted/ci
  expect_wrapper_reject trusted/ci
  unset "$variable"
done

export MOCK_NAME=other/repo
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_NAME

export MOCK_ID=999
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_ID

export MOCK_BASE_COMMIT=cccccccccccccccccccccccccccccccccccccccc
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_BASE_COMMIT

export MOCK_BASE_TREE=cccccccccccccccccccccccccccccccccccccccc
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_BASE_TREE

export MOCK_WORKFLOW_SHA=cccccccccccccccccccccccccccccccccccccccc
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_SHA

export MOCK_WORKFLOW_TYPE=dir
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_TYPE

export MOCK_API_FAILURE=true
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_API_FAILURE

export MOCK_EXTRA_LINE=
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_EXTRA_LINE

export MOCK_BASE_REF_EXTRA_LINE=$'\ncccccccccccccccccccccccccccccccccccccccc'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_BASE_REF_EXTRA_LINE

export MOCK_BASE_TREE_EXTRA_LINE=$'\ncccccccccccccccccccccccccccccccccccccccc'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_BASE_TREE_EXTRA_LINE

export MOCK_WORKFLOW_EXTRA_LINE=$'\nfile\tffffffffffffffffffffffffffffffffffffffff'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_EXTRA_LINE

expect_fail trusted/ci-extra
expect_fail other/repo
expect_fail trusted
expect_fail 'trusted/ci;echo unsafe'
expect_fail
expect_wrapper_reject other/repo

printf 'other/repo 9 %s %s %s\n' \
  "$approved_base_commit" "$approved_base_tree" "$approved_workflow" > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

for malformed in \
  "trusted/ci" \
  "trusted/ci 1412142815" \
  "trusted/ci 1412142815 $approved_base_commit" \
  "trusted/ci 1412142815 $approved_base_commit $approved_base_tree" \
  "trusted/ci 1412142815 $approved_base_commit $approved_base_tree $approved_workflow extra" \
  "trusted/ci 1412142815  $approved_base_commit $approved_base_tree $approved_workflow"; do
  printf '%s\n' "$malformed" > "$policy"
  expect_fail trusted/ci
done

printf '# intentionally empty\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

rm "$policy"
expect_fail trusted/ci
ln -s "$work/fixture/scripts/check-private-ci-destination.sh" "$policy"
expect_fail trusted/ci

rm "$policy"
reset_policy
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'PASS: private slug/id + reviewed carrier base/tree/workflow pinning, symlink and multiline rejection\n'
\nspoofed/repo\t1412142815\tprivate\ttrue\tfalse\tfalse\tfalse\tmain'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_EXTRA_LINE

export MOCK_BASE_REF_EXTRA_LINE=$'\ncccccccccccccccccccccccccccccccccccccccc'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_BASE_REF_EXTRA_LINE

export MOCK_BASE_TREE_EXTRA_LINE=$'\ncccccccccccccccccccccccccccccccccccccccc'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_BASE_TREE_EXTRA_LINE

export MOCK_WORKFLOW_EXTRA_LINE=$'\nfile\tffffffffffffffffffffffffffffffffffffffff'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_WORKFLOW_EXTRA_LINE

expect_fail trusted/ci-extra
expect_fail other/repo
expect_fail trusted
expect_fail 'trusted/ci;echo unsafe'
expect_fail
expect_wrapper_reject other/repo

printf 'other/repo 9 %s %s %s\n' \
  "$approved_base_commit" "$approved_base_tree" "$approved_workflow" > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

for malformed in \
  "trusted/ci" \
  "trusted/ci 1412142815" \
  "trusted/ci 1412142815 $approved_base_commit" \
  "trusted/ci 1412142815 $approved_base_commit $approved_base_tree" \
  "trusted/ci 1412142815 $approved_base_commit $approved_base_tree $approved_workflow extra" \
  "trusted/ci 1412142815  $approved_base_commit $approved_base_tree $approved_workflow"; do
  printf '%s\n' "$malformed" > "$policy"
  expect_fail trusted/ci
done

printf '# intentionally empty\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

rm "$policy"
expect_fail trusted/ci
ln -s "$work/fixture/scripts/check-private-ci-destination.sh" "$policy"
expect_fail trusted/ci

rm "$policy"
reset_policy
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'PASS: private slug/id + reviewed carrier base/tree/workflow pinning, symlink and multiline rejection\n'
