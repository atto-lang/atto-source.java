#!/usr/bin/env bash
# This is a REAL Oreslang compiler + guest execution gate, not a static fixture
# emitter. Never mark unexecuted GitHub jobs as passed.
set -euo pipefail

if [[ $# -ne 1 || ( "$1" != "jvm" && "$1" != "aot" ) ]]; then
  echo "usage: execute-pinned-smoke.sh jvm|aot" >&2
  exit 64
fi
mode="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$root"
pinned=verification/otel/pinned
guest="$pinned/tests/smoke.ores"
oracle="$pinned/tools/telemetry-check/Cargo.toml"
output="target/otel/$mode"
mkdir -p "$output"

# Exact raw blob comparisons against the original OTel repository; the
# canonical .ores fixture resolves ../src/otel relative to the snapshot.
verify_blob() {
  local expected="$1" path="$2"
  test -f "$path"
  test ! -L "$path"
  local actual
  actual="$(git hash-object --no-filters "$path")"
  if [[ "$actual" != "$expected" ]]; then
    echo "OTEL_SOURCE_MISMATCH: unapproved guest or Rust checker bytes" >&2
    exit 65
  fi
}
verify_blob c22a8841b868cd89dd4cb720435d9585fbbb05cc "$guest"
verify_blob 6a1b9b940f10e4b273ae2b02be5cfff5e0572afc "$pinned/src/otel.ores"
verify_blob bb007125dff7b207f34acdde22f91f61283db256 "$oracle"
verify_blob cc5a82761a5188f1a2ed4f9ec67e566ddfed7f45 "$pinned/tools/telemetry-check/Cargo.lock"
verify_blob 2f91087449a58e7b5756d23868860dbcb82e4847 "$pinned/tools/telemetry-check/src/main.rs"

# Do not claim verified git identity if checkout is dirty before any build.
# CI asserts the original PR SHA in the preceding checkout step; local users
# must supply an independently pinned original HEAD.
test -n "${EXPECTED_SOURCE_SHA:-}"
test "$(git rev-parse HEAD)" = "$EXPECTED_SOURCE_SHA"
test -z "$(git status --porcelain --untracked-files=all -- . ':!target')"
source_tree="$(git rev-parse 'HEAD^{tree}')"

case "$mode" in
  jvm)
    : "${JAVA_HOME:?Require reviewed GraalVM JAVA_HOME}"
    java_bin="$JAVA_HOME/bin/java"
    test -x "$java_bin"
    test -s target/classpath.txt
    # Actual modular compiler launcher with linked .ores imports.
    "$java_bin" --enable-native-access=ALL-UNNAMED \
      -Dpolyglot.engine.WarnInterpreterOnly=false \
      -cp "target/classes:$(cat target/classpath.txt)" \
      dev.oreslang.launcher.OresMain --mode=jit --platform=linux "$guest" \
      > "$output/guest.stdout.jsonl" 2> "$output/guest.stderr.txt"
    executable="$java_bin"
    ;;
  aot)
    test -x target/ores-aot
    executable=target/ores-aot
    "$executable" --build-info > "$output/build-info.json"
    grep -Fq '"build_mode":"aot"' "$output/build-info.json"
    grep -Fq '"guest_execution":"interpreter-no-jit"' "$output/build-info.json"
    # An ELF no-fallback native binary, never a Java-managed fallback.
    test "$(head -c 4 "$executable" | od -An -tx1 | tr -d ' \n')" = 7f454c46
    "$executable" --mode=aot --platform=linux "$guest" \
      > "$output/guest.stdout.jsonl" 2> "$output/guest.stderr.txt"
    ;;
esac

# The original Rust oracle rejects missing/extra records, malformed JSON,
# duplicate fields, corruption, all control chars, i64 and span relationships.
test -s "$output/guest.stdout.jsonl"
cargo +1.95.0 run --quiet --locked --manifest-path "$oracle" -- \
  --check-smoke-output "$output/guest.stdout.jsonl"
# Record hashes only. Synthetic guest stdout, not compiler sources, may be
# retained as a short-lived artifact for independent verifier review.
{
  printf 'classification=REAL_GUEST_EXECUTION_LOCAL_CANDIDATE_UNATTESTED\n'
  printf 'runtime=%s\n' "$mode"
  printf 'source_sha=%s\n' "$EXPECTED_SOURCE_SHA"
  printf 'source_tree_sha=%s\n' "$source_tree"
  printf 'pinned_fixture_git_blob=%s\n' c22a8841b868cd89dd4cb720435d9585fbbb05cc
  printf 'pinned_library_git_blob=%s\n' 6a1b9b940f10e4b273ae2b02be5cfff5e0572afc
  printf 'executable_sha256=%s\n' "$(sha256sum "$executable" | cut -d' ' -f1)"
  printf 'guest_stdout_sha256=%s\n' "$(sha256sum "$output/guest.stdout.jsonl" | cut -d' ' -f1)"
  printf 'guest_exit=0\n'
  printf 'rust_oracle_exit=0\n'
  printf 'independently_certified=false\n'
} > "$output/receipt.txt"
echo "OTEL_EXECUTED_CANDIDATE: $mode; source=$EXPECTED_SOURCE_SHA; oracle=pass; NOT_CERTIFIED"
