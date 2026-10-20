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
printf '# Approved for this isolated test only\ntrusted/ci@12345\n' > "$policy"
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
printf '%s\t%s\t%s\t%s%s\n' \
  "${MOCK_ID:-12345}" \
  "${MOCK_NAME:-trusted/ci}" \
  "${MOCK_VISIBILITY:-private}" \
  "${MOCK_PRIVATE_FLAG:-true}" \
  "${MOCK_EXTRA_LINE:-}"
MOCK
mkdir -p "$work/fixture/source"
printf 'pub fnc main(): int { return 42; }\n' > "$work/fixture/source/main.ores"
chmod +x "$work/gh" "$script_dir"/*.sh
export PATH="$work:$PATH" MOCK_EXEC_LOG="$work/executed"

expect_ok() {
  "$script_dir/check-private-ci-destination.sh" "$1" > "$work/out" 2>&1 || {
    cat "$work/out" >&2
    exit 1
  }
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
  if "$script_dir/run-in-private-ci-destination.sh" "$1" \
      --source-dir "$work/fixture/source" --branch ci/oreslang-test \
      > "$work/out" 2>&1; then
    echo 'Unsafe destination executed publishing' >&2
    exit 1
  fi
  test ! -e "$MOCK_EXEC_LOG"
  grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"
}

expect_ok trusted/ci

# The retained .sh launcher is Python in isolated mode. Caller-controlled shell
# startup hooks and Python import hooks must not execute before its own argument
# validation, even when an attacker supplies both environment variables.
startup_marker="$work/startup-hook-executed"
cat > "$work/malicious-bash-env.sh" <<EOF
printf 'bash-env\\n' >> "$startup_marker"
EOF
mkdir -p "$work/python-hook"
cat > "$work/python-hook/sitecustomize.py" <<EOF
from pathlib import Path
Path(r"$startup_marker").write_text("pythonpath\\n")
EOF
rm -f "$startup_marker"
if env \
    BASH_ENV="$work/malicious-bash-env.sh" \
    PYTHONPATH="$work/python-hook" \
    "$script_dir/run-in-private-ci-destination.sh" > "$work/out" 2>&1; then
  echo 'Unsafe launcher accepted malformed invocation' >&2
  exit 1
fi
test ! -e "$startup_marker"
grep -q 'PRIVATE_CI_DESTINATION_REJECTED' "$work/out"

for visibility in public internal null; do
  export MOCK_VISIBILITY="$visibility"
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

export MOCK_ID=67890
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_ID

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

export MOCK_EXTRA_LINE=$'\n67890\tspoofed/repo\tprivate\ttrue'
expect_fail trusted/ci
expect_wrapper_reject trusted/ci
unset MOCK_EXTRA_LINE

printf 'other/repo@777\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'trusted/ci #bad-comment\n' > "$policy"
expect_fail trusted/ci

printf 'trusted/ci@12345\nTRUSTED/CI@67890\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'trusted/ci@12345\r\n' > "$policy"
expect_fail trusted/ci

printf '# intentionally empty\n' > "$policy"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

rm "$policy"
expect_fail trusted/ci
ln -s "$work/fixture/scripts/check-private-ci-destination.sh" "$policy"
expect_fail trusted/ci

rm "$policy"
printf 'trusted/ci@12345\n' > "$policy"
mv "$work/fixture/.github" "$work/approved-policy-directory"
ln -s "$work/approved-policy-directory" "$work/fixture/.github"
expect_fail trusted/ci
expect_wrapper_reject trusted/ci

printf 'PASS: immutable repo ID, private identity, duplicate/CRLF policy, symlink directory and multiline API rejection; child not executed\n'
