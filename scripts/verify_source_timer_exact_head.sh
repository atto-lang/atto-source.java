#!/usr/bin/env bash
# Source-local fallback only: never copy private source to another organization.
# Usage: bash scripts/verify_source_timer_exact_head.sh HEAD_SHA TREE_SHA [focused|full]
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
test "$#" -ge 2 && test "$#" -le 3 || fail "usage: $0 HEAD_SHA TREE_SHA [focused|full]"
expected_head="$1"; expected_tree="$2"; mode="focused"
if test "$#" -eq 3; then mode="$3"; fi
[[ "$expected_head" =~ ^[0-9a-f]{40}$ ]] || fail "expected commit must be a full SHA"
[[ "$expected_tree" =~ ^[0-9a-f]{40}$ ]] || fail "expected tree must be a full SHA"
case "$mode" in focused|full) ;; *) fail "mode must be focused or full";; esac
command -v git >/dev/null && command -v java >/dev/null && command -v mvn >/dev/null ||
  fail "git, java and mvn are required"
root="$(git rev-parse --show-toplevel)" || fail "not a Git checkout"
cd "$root"
head="$(git rev-parse HEAD)"; tree="$(git rev-parse 'HEAD^{tree}')"
test "$head" = "$expected_head" || fail "HEAD mismatch: $head"
test "$tree" = "$expected_tree" || fail "root tree mismatch: $tree"
git diff --quiet HEAD -- || fail "tracked checkout is dirty"
test -z "$(git ls-files --others --exclude-standard)" || fail "untracked nonignored files exist"
printf 'EXACT_HEAD=%s\nEXACT_ROOT_TREE=%s\n' "$head" "$tree"
java_version="$(java -version 2>&1)"
printf '%s\n' "$java_version"
printf '%s\n' "$java_version" | head -n 1 | grep -Eq 'version "25([.]|")' ||
  fail "JDK 25 is required"
mvn -version
# Existing loopback TCP regression proves a pending actor read frees the only carrier.
selectors="SourceTimerLanguageTest,OresTimersTest,AsyncAwaitLanguageTest,FuturePipelineProvenanceTest#typingPreservesProvenanceAndCurrentFutureBoundaries,MemberSuspensionLanguageTest#importedPolymorphicPullCannotSuspendWithBorrowedReceiver,AsyncNativeIoTest#delayedTcpReadReleasesSingleActorCarrierAndSupportsWriteAndEof"
suites=(SourceTimerLanguageTest OresTimersTest AsyncAwaitLanguageTest FuturePipelineProvenanceTest MemberSuspensionLanguageTest AsyncNativeIoTest)
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
# clean prevents stale XML reports from masquerading as new test execution.
set +e
mvn -B -ntp -Dores.runtime.carriers=native "-Dtest=$selectors" clean test 2>&1 | tee "$log"
maven_result="${PIPESTATUS[0]}"
set -e
proof_dir="target/source-timer-verification"
mkdir -p "$proof_dir"
cp "$log" "$proof_dir/focused-maven.log"
receipt="$proof_dir/receipt.txt"
{
  printf 'EXACT_HEAD=%s\nEXACT_ROOT_TREE=%s\n' "$head" "$tree"
  printf 'MODE=%s\nFOCUSED_EXIT=%s\n' "$mode" "$maven_result"
  printf 'TIMESTAMP_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$receipt"
test "$maven_result" -eq 0 || fail "Maven focused test failed: $proof_dir/focused-maven.log"
for suite in "${suites[@]}"; do
  report="$(find target/surefire-reports -maxdepth 1 -type f -name "TEST-*.$suite.xml" -print -quit)"
  test -n "$report" || fail "missing Surefire report: $suite"
  suite_line="$(grep -m1 '<testsuite ' "$report")" || fail "invalid Surefire XML: $suite"
  printf '%s\n' "$suite_line" | grep -Eq 'tests="[1-9][0-9]*"' || fail "zero tests: $suite"
  printf '%s\n' "$suite_line" | grep -Eq 'errors="0"' || fail "errors: $suite"
  printf '%s\n' "$suite_line" | grep -Eq 'failures="0"' || fail "failures: $suite"
  printf 'EXECUTED_SUITE=%s REPORT=%s %s\n' "$suite" "$report" "$suite_line" | tee -a "$receipt"
done
printf 'FOCUSED_PROOF=PASS\n' | tee -a "$receipt"
if test "$mode" = full; then
  set +e
  mvn -B -ntp -Dores.runtime.carriers=native verify 2>&1 | tee "$log"
  full_result="${PIPESTATUS[0]}"
  set -e
  cp "$log" "$proof_dir/full-maven.log"
  printf 'FULL_VERIFY_EXIT=%s\n' "$full_result" | tee -a "$receipt"
  test "$full_result" -eq 0 || fail "full suite is red; compare against the pinned #2202 base"
fi
printf 'SOURCE_LOCAL_VERIFICATION=PASS receipt=%s\n' "$receipt"
