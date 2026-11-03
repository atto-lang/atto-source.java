#!/usr/bin/env bash
# Source-free adversarial tests of the exact local raw-source verifier.
# Never copies private compiler Git trees or invokes the AttoLang compiler.
set -euo pipefail
export LC_ALL=C
root="$(git rev-parse --show-toplevel)"
verifier="$root/scripts/verify-atto-exact-head-source.sh"
[[ -f "$verifier" && ! -L "$verifier" ]] || {
  echo 'RAW_VERIFIER_TEST_DENIED: trusted verifier unavailable' >&2
  exit 2
}
tmp="$(mktemp -d "${RUNNER_TEMP:-/tmp}/atto-raw-verify.XXXXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT
cd "$tmp"
git init -q
git config user.name "AttoLang Source-Free Security Test"
git config user.email "atto-raw-test@example.invalid"
git config filter.ghost.clean "sed 's/MUTATED/ORIGINAL/g'"
printf '*.atto filter=ghost\n' > .gitattributes
printf 'ORIGINAL\n' > main.atto
git add .gitattributes main.atto
git commit -qm 'source-free synthetic input'
export ATTO_EXPECTED_HEAD="$(git rev-parse HEAD)"
bash "$verifier" > "$tmp/healthy.out"
grep -Fq 'ATTO_RAW_SOURCE_VERIFY_PASS tracked=2' "$tmp/healthy.out" || {
  echo 'RAW_VERIFIER_TEST_DENIED: clean source not verified' >&2
  exit 2
}
reject() {
  local expected="$1"
  if bash "$verifier" > "$tmp/last.out" 2> "$tmp/last.err"; then
    echo "RAW_VERIFIER_TEST_DENIED: unexpected pass: $expected" >&2
    exit 2
  fi
  grep -Fq "$expected" "$tmp/last.err" || {
    cat "$tmp/last.err" >&2
    echo "RAW_VERIFIER_TEST_DENIED: incorrect rejection: $expected" >&2
    exit 2
  }
}
printf 'MUTATED\n' > main.atto
# Git clean filters can make ordinary git diff appear unchanged. The
# verifier must compare unfiltered raw bytes against committed blob hashes.
git diff --quiet HEAD -- main.atto || {
  echo 'RAW_VERIFIER_TEST_DENIED: expected filter-adversarial setup missing' >&2
  exit 2
}
reject 'tracked raw blob differs from index: main.atto'
printf 'ORIGINAL\n' > main.atto
printf 'schema_version = "1"\n' > .atto.pkg.toml
reject 'untracked compiler/source/config input: .atto.pkg.toml'
rm -- .atto.pkg.toml
rm -- main.atto
ln -s /etc/hosts main.atto
reject 'tracked file missing or replaced by link: main.atto'
rm -- main.atto
printf 'ORIGINAL\n' > main.atto
original_pin="$ATTO_EXPECTED_HEAD"
export ATTO_EXPECTED_HEAD=0000000000000000000000000000000000000000
reject 'source HEAD changed'
export ATTO_EXPECTED_HEAD="$original_pin"
echo 'SOURCE_FREE_ATTO_RAW_VERIFIER_REGRESSIONS=5/5 PASS'
