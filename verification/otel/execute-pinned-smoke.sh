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
# Never generate test artifacts inside the pinned, byte-verified source tree.
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target/otel/rust-build}"
pinned=verification/otel/pinned
guest="$pinned/tests/smoke.ores"
oracle="$pinned/tools/telemetry-check/Cargo.toml"
output="target/otel/$mode"
mkdir -p "$output"
# Candidate output must be produced by THIS invocation, never by a stale run.
rm -f -- "$output/guest.stdout.jsonl" "$output/guest.stderr.txt" \
  "$output/receipt.txt" "$output/build-info.json"

# The smoke is deliberately tiny; never allow an unexpectedly chatty or stuck
# guest to fill the runner disk or keep CI waiting indefinitely.
run_bounded_guest() {
  (
    ulimit -f 1024  # 1 MiB (1024 KiB blocks on the Linux runner)
    timeout --signal=TERM --kill-after=5s 30s "$@"
  ) > "$output/guest.stdout.jsonl" 2> "$output/guest.stderr.txt"
  test -f "$output/guest.stdout.jsonl"
  test "$(wc -c < "$output/guest.stdout.jsonl")" -le 1048576
  test "$(wc -c < "$output/guest.stderr.txt")" -le 1048576
}

# Hash every actual JVM bytecode class that the launcher loads rather than
# treating a Maven JAR hash as proof of the unpackaged classpath contents.
class_tree_digest() {
  test -f target/classes/dev/oreslang/launcher/OresMain.class
  test ! -L target/classes
  test -z "$(find target/classes -type l -print -quit)"
  find target/classes -type f -print0 |
    LC_ALL=C sort -z |
    xargs -0 sha256sum |
    sha256sum | cut -d' ' -f1
}


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
    test -s target/oreslang-source-0.1.0-SNAPSHOT.jar
    # Compiler classes and dependency classpath are independent build outputs;
    # hashing the JDK's java launcher does not identify the Oreslang compiler.
    compiler_jar_before="$(sha256sum target/oreslang-source-0.1.0-SNAPSHOT.jar | cut -d' ' -f1)"
    classpath_before="$(sha256sum target/classpath.txt | cut -d' ' -f1)"
    classes_before="$(class_tree_digest)"
    # Actual modular compiler launcher with linked .ores imports.
    run_bounded_guest "$java_bin" --enable-native-access=ALL-UNNAMED \
      -Dpolyglot.engine.WarnInterpreterOnly=false \
      -cp "target/classes:$(cat target/classpath.txt)" \
      dev.oreslang.launcher.OresMain --mode=jit --platform=linux "$guest"
    executable="$java_bin"
    ;;
  aot)
    test -x target/ores-aot
    executable=target/ores-aot
    ( ulimit -f 64; timeout --signal=TERM --kill-after=3s 10s \
      "$executable" --build-info ) > "$output/build-info.json"
    grep -Fq '"build_mode":"aot"' "$output/build-info.json"
    grep -Fq '"guest_execution":"interpreter-no-jit"' "$output/build-info.json"
    # An ELF no-fallback native binary, never a Java-managed fallback.
    test "$(head -c 4 "$executable" | od -An -tx1 | tr -d ' \n')" = 7f454c46
    aot_binary_before="$(sha256sum "$executable" | cut -d' ' -f1)"
    run_bounded_guest "$executable" --mode=aot --platform=linux "$guest"
    ;;
esac

# Keep the compiler build artifact and module path fixed across execution.
if [[ "$mode" == jvm ]]; then
  test "$(sha256sum target/oreslang-source-0.1.0-SNAPSHOT.jar | cut -d' ' -f1)" = "$compiler_jar_before"
  test "$(sha256sum target/classpath.txt | cut -d' ' -f1)" = "$classpath_before"
  test "$(class_tree_digest)" = "$classes_before"
else
  test "$(sha256sum "$executable" | cut -d' ' -f1)" = "$aot_binary_before"
fi

# The original Rust oracle rejects missing/extra records, malformed JSON,
# duplicate fields, corruption, all control chars, i64 and span relationships.
test -s "$output/guest.stdout.jsonl"
test "$(wc -c < "$output/guest.stdout.jsonl")" -le 1048576
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
  if [[ "$mode" == jvm ]]; then
    printf 'compiler_jar_sha256=%s\n' "$compiler_jar_before"
    printf 'classpath_manifest_sha256=%s\n' "$classpath_before"
    printf 'loaded_class_tree_sha256=%s\n' "$classes_before"
  else
    printf 'native_image_no_fallback=true\n'
  fi
  printf 'guest_stdout_sha256=%s\n' "$(sha256sum "$output/guest.stdout.jsonl" | cut -d' ' -f1)"
  printf 'guest_exit=0\n'
  printf 'rust_oracle_exit=0\n'
  printf 'independently_certified=false\n'
} > "$output/receipt.txt"
echo "OTEL_EXECUTED_CANDIDATE: $mode; source=$EXPECTED_SOURCE_SHA; oracle=pass; NOT_CERTIFIED"
