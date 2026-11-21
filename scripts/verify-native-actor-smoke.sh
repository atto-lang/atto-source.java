#!/usr/bin/env bash
# Admission for an actual native compiler and one pinned Oreslang actor guest.
# The source-free adversarial harness mocks the CLI, not real guest execution.
set -euo pipefail

fail() { printf 'NATIVE_ACTOR_SMOKE_REJECTED: %s\n' "$1" >&2; exit 1; }
[[ $# -eq 5 ]] || fail 'usage: script /absolute/compiler compiler-sha256 valid.ores expected-git-blob expected-stdout'
binary="$1"
expected_compiler_sha256="$2"
fixture="$3"
expected_blob="$4"
expected_stdout="$5"
[[ "$binary" = /* && -f "$binary" && -x "$binary" && ! -L "$binary" ]] ||
  fail 'compiler must be an absolute executable regular file, not a symlink'
[[ "$expected_compiler_sha256" =~ ^[0-9a-f]{64}$ ]] ||
  fail 'compiler SHA-256 pin must be 64 lowercase hex characters'
command -v sha256sum >/dev/null 2>&1 || fail 'SHA-256 utility unavailable'
actual_compiler_sha256="$(sha256sum -- "$binary" | cut -d ' ' -f 1)"
[[ "$actual_compiler_sha256" == "$expected_compiler_sha256" ]] ||
  fail 'compiler binary changed since its pinned build receipt'
[[ -f "$fixture" && ! -L "$fixture" && -s "$fixture" ]] || fail 'missing or symlinked guest'
[[ "$expected_blob" =~ ^[0-9a-f]{40}$ ]] || fail 'invalid pinned Git blob'
[[ "$(git hash-object -- "$fixture")" = "$expected_blob" ]] ||
  fail 'guest source does not match its pinned Git blob'
command -v timeout >/dev/null 2>&1 || fail 'timeout unavailable'
mkdir -p target
[[ -d target && ! -L target ]] || fail 'untrusted target directory'
scratch="$(mktemp -d "$PWD/target/.native-actor-admission.XXXXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

# A failure in a different directory could just be a permission/IO denial.
# Test valid and invalid source within exactly the same temporary directory.
valid="$scratch/valid.ores"
invalid="$scratch/invalid.ores"
cp -- "$fixture" "$valid"
[[ "$(git hash-object -- "$valid")" = "$expected_blob" ]] ||
  fail 'staged valid guest was modified'
timeout --kill-after=5s 30s "$binary" --check "$valid" \
  > "$scratch/valid.stdout" 2> "$scratch/valid.stderr" ||
  fail 'known-good source failed checking in the challenge directory'

printf 'pub routine main(): void {\n  return @@@;\n}\n' > "$invalid"
status=0
timeout --kill-after=5s 15s "$binary" --check "$invalid" \
  > "$scratch/invalid.stdout" 2> "$scratch/invalid.stderr" || status=$?
[[ "$status" -eq 1 ]] ||
  fail "invalid syntax must be a compiler diagnostic (exit 1), got $status"
[[ ! -s "$scratch/invalid.stdout" && -s "$scratch/invalid.stderr" ]] ||
  fail 'invalid syntax must produce stderr only'
grep -Fq -- "$invalid:" "$scratch/invalid.stderr" ||
  fail 'missing diagnostic naming the challenged source'
grep -Eiq ':[0-9]+:[0-9]+: error: .*(expected|unexpected|syntax|parse|token|invalid character)' \
  "$scratch/invalid.stderr" ||
  fail 'rejection is not recognizably a positioned parser diagnostic'

timeout --kill-after=5s 30s "$binary" "$valid" > "$scratch/guest.stdout" \
  2> "$scratch/guest.stderr" || fail 'native guest did not execute successfully'
printf '%s' "$expected_stdout" > "$scratch/guest.expected"
cmp -s -- "$scratch/guest.expected" "$scratch/guest.stdout" ||
  fail 'native guest stdout differs byte-for-byte'
[[ ! -s "$scratch/guest.stderr" ]] || fail 'native guest emitted unexpected stderr'
printf 'NATIVE_AOT_ACTOR_GUEST_SMOKE_PASS=1 git_blob=%s compiler_sha256=%s\n' "$expected_blob" "$actual_compiler_sha256"
