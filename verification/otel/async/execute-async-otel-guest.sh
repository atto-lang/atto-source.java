#!/usr/bin/env bash
# REAL native Oreslang guest integration; never accept synthetic output.
set -euo pipefail
if [[ $# != 1 || ( "$1" != jvm && "$1" != aot ) ]]; then
  echo "usage: execute-async-otel-guest.sh jvm|aot" >&2
  exit 64
fi
mode="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$root"
test -n "${EXPECTED_SOURCE_SHA:-}"
test "$(git rev-parse HEAD)" = "$EXPECTED_SOURCE_SHA"
test -z "$(git status --porcelain --untracked-files=all -- . ':!target')"
test ! -L target
test ! -L target/otel
test ! -L target/otel/async
pinned=verification/otel/async/pinned
out=target/otel/async/$mode
test ! -L "$out"
mkdir -p "$out"
# Receipts must be from THIS exact invocation. A failed second fixture may
# otherwise leave a successful-looking first fixture receipt from an earlier
# run, or leave the first one behind after a late compiler/oracle failure.
for receipt_fixture in smoke span-finish-admission-retry; do
  receipt_dir="$out/$receipt_fixture"
  test ! -L "$receipt_dir"
  mkdir -p "$receipt_dir"
  test ! -L "$receipt_dir"
  rm -f -- "$receipt_dir/receipt.txt"
done
otel_async_run_complete=false
otel_async_cleanup_receipts() {
  if [[ "$otel_async_run_complete" != true ]]; then
    rm -f -- "$out/smoke/receipt.txt" "$out/span-finish-admission-retry/receipt.txt"
  fi
}
trap otel_async_cleanup_receipts EXIT
source verification/otel/async/guardrails.sh

verify_blob() {
  local sha="$1" path="$2"
  test -f "$path" && test ! -L "$path"
  test "$(git hash-object --no-filters "$path")" = "$sha" || {
    echo "OTEL_ASYNC_SOURCE_INTEGRITY_FAILURE: $path" >&2; exit 65;
  }
}
verify_blob 0f53db094ed9794b271b341aa889cb51656b4812 "$pinned/src/otel.ores"
verify_blob 6480413e7a6f8b6bef1a8a279f777f8293dc9937 "$pinned/tests/smoke.ores"
verify_blob 7d72f983cf09ede66ea8a62c2e17e60f7b03f6ba "$pinned/tests/span-finish-admission-retry.ores"
verify_blob 55fbd9508b735d0248c80339234cd82eb533275a "$pinned/tools/telemetry-check/Cargo.toml"
verify_blob cc5a82761a5188f1a2ed4f9ec67e566ddfed7f45 "$pinned/tools/telemetry-check/Cargo.lock"
verify_blob baee942bd7860cd327d34b6c4ab78c03f21cd0ba "$pinned/tools/telemetry-check/src/main.rs"

: "${JAVA_HOME:?Require GraalVM JDK 25}"
test -x "$JAVA_HOME/bin/java"
source_tree="$(git rev-parse 'HEAD^{tree}')"
oracle="$pinned/tools/telemetry-check/Cargo.toml"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target/otel/async/rust-build}"
if [[ "$mode" == jvm ]]; then
  test -s target/classpath.txt && test ! -L target/classpath.txt
  test -s target/oreslang-source-0.1.0-SNAPSHOT.jar
  test ! -L target/oreslang-source-0.1.0-SNAPSHOT.jar
  compiler_jar_sha="$(sha256sum target/oreslang-source-0.1.0-SNAPSHOT.jar | cut -d' ' -f1)"
  classpath_sha="$(sha256sum target/classpath.txt | cut -d' ' -f1)"
  classes_sha="$(otel_guardrails_class_digest target/classes)"
  deps_sha="$(otel_guardrails_classpath_digest target/classpath.txt)"
else
  test -x "$JAVA_HOME/bin/native-image"
  native_sha="$(otel_guardrails_native_host_digest target/ores-aot)"
  timeout --signal=TERM --kill-after=3s 10s target/ores-aot --build-info > "$out/build-info.json"
  grep -Fq '"build_mode":"aot"' "$out/build-info.json"
  grep -Fq '"guest_execution":"interpreter-no-jit"' "$out/build-info.json"
  "$JAVA_HOME/bin/native-image" --version > "$out/native-image-version.txt"
  grep -q 'GraalVM' "$out/native-image-version.txt"
fi

for fixture in smoke span-finish-admission-retry; do
  case_out="$out/$fixture"
  test ! -L "$case_out"
  mkdir -p "$case_out"
  if [[ "$mode" == jvm ]]; then
    otel_guardrails_run_guest "$case_out" "$JAVA_HOME/bin/java" \
      --enable-native-access=ALL-UNNAMED -Dpolyglot.engine.WarnInterpreterOnly=false \
      -cp "target/classes:$(cat target/classpath.txt)" \
      dev.oreslang.launcher.OresMain --mode=jit --platform=linux \
      "$pinned/tests/$fixture.ores"
  else
    otel_guardrails_run_guest "$case_out" target/ores-aot \
      --mode=aot --platform=linux "$pinned/tests/$fixture.ores"
  fi
  test -s "$case_out/guest.stdout.jsonl"
  stdout_sha="$(sha256sum "$case_out/guest.stdout.jsonl" | cut -d' ' -f1)"
  if [[ "$fixture" == smoke ]]; then check=--check-smoke-output
  else check=--check-finish-retry-output; fi
  cargo +1.95.0 run --quiet --locked --manifest-path "$oracle" -- \
    "$check" "$case_out/guest.stdout.jsonl"
  test "$stdout_sha" = "$(sha256sum "$case_out/guest.stdout.jsonl" | cut -d' ' -f1)"
  {
    printf 'classification=REAL_GUEST_EXECUTION_CANDIDATE_UNATTESTED\n'
    printf 'independently_certified=false\n'
    printf 'profile=%s\nfixture=%s\n' "$mode" "$fixture"
    printf 'source_sha=%s\nsource_tree_sha=%s\n' "$EXPECTED_SOURCE_SHA" "$source_tree"
    printf 'guest_exit=0\nrust_oracle_exit=0\n'
    printf 'stdout_sha256=%s\n' "$stdout_sha"
    if [[ "$mode" == jvm ]]; then
      printf 'compiler_jar_sha256=%s\nclasspath_sha256=%s\n' "$compiler_jar_sha" "$classpath_sha"
      printf 'classes_sha256=%s\ndependency_jars_sha256=%s\n' "$classes_sha" "$deps_sha"
    else
      printf 'native_host_elf_sha256=%s\n' "$native_sha"
      printf 'guest_machine_code_aot=false\n'
    fi
  } > "$case_out/receipt.txt"
done
if [[ "$mode" == jvm ]]; then
  test "$compiler_jar_sha" = "$(sha256sum target/oreslang-source-0.1.0-SNAPSHOT.jar | cut -d' ' -f1)"
  test "$classpath_sha" = "$(sha256sum target/classpath.txt | cut -d' ' -f1)"
  test "$classes_sha" = "$(otel_guardrails_class_digest target/classes)"
  test "$deps_sha" = "$(otel_guardrails_classpath_digest target/classpath.txt)"
else
  test "$native_sha" = "$(otel_guardrails_native_host_digest target/ores-aot)"
fi
otel_async_run_complete=true
echo "OTEL_ASYNC_REAL_GUEST_OUTPUT_VALIDATED $mode; independent CI attestation pending"
