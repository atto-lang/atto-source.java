#!/bin/bash
# Real JVM/Truffle Oreslang guest verification; not Native Image AOT.
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(git rev-parse HEAD)" = "${EXPECTED_SOURCE_SHA:?pin exact source SHA}"
test -s target/classes/dev/oreslang/launcher/OresMain.class
test -s target/conformance-classpath.txt
test -f contracts/v1/java_probe.py
test ! -L contracts/v1/java_probe.py
printf 'JAVA_STATEGRAPH_GUEST_BEGIN head=%s tree=%s launcher_sha256=%s\n' \
  "$(git rev-parse HEAD)" "$(git rev-parse 'HEAD^{tree}')" \
  "$(sha256sum target/classes/dev/oreslang/launcher/OresMain.class | cut -d' ' -f1)"
# Canonical byte-identical scalar source, shared with LLVM/Wasm/Cranelift/.NET.
shared=contracts/stategraph-portability/shared-scalar.ores
[[ "$(git hash-object "$shared")" = "ec1cb5e6cc38c26842d724ccacb0df028b702c3f" ]] || {
  echo 'SG_JAVA_SHARED_SCALAR_SOURCE_MISMATCH' >&2; exit 1;
}
git ls-files --error-unmatch -- "$shared" >/dev/null
[[ -f "$shared" && ! -L "$shared" ]]
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$shared"
common_out="$(mktemp)"
common_err="$(mktemp)"
set +e
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$shared" --execute >"$common_out" 2>"$common_err"
common_status=$?
set -e
if [[ "$common_status" -ne 42 || -s "$common_out" ]]; then
  cat "$common_err" >&2
  rm -f "$common_out" "$common_err"
  echo "SG_JAVA_COMMON_SCALAR_NOT_ACCEPTED status=$common_status" >&2
  exit 1
fi
rm -f "$common_out" "$common_err"
printf 'SG_JAVA_COMMON_SCALAR_NATIVE_PASS blob=%s guest_exit=42 source_only=no\n' \
  "$(git rev-parse "HEAD:$shared")"

# One byte-identical generic userland Oreslang program across all backends.
generic=contracts/stategraph-portability/shared-generic-state.ores
[[ -f "$generic" && ! -L "$generic" ]] || { echo 'SG_JAVA_GENERIC_SOURCE_MISSING' >&2; exit 1; }
git ls-files --error-unmatch -- "$generic" >/dev/null
[[ "$(git hash-object "$generic")" = "c3a67b837a73f427a6157c446cc6e3989e9a66da" ]] ||
  { echo 'SG_JAVA_GENERIC_SOURCE_DRIFT' >&2; exit 1; }
[[ "$(git rev-parse "HEAD:$generic")" = "c3a67b837a73f427a6157c446cc6e3989e9a66da" ]]
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$generic" ||
  { echo 'SG_JAVA_GENERIC_TYPECHECK_NOT_ACCEPTED' >&2; exit 1; }
common_out="$(mktemp)"
common_err="$(mktemp)"
set +e
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$generic" --execute > "$common_out" 2> "$common_err"
generic_status=$?
set -e
if [[ "$generic_status" -ne 42 || -s "$common_out" ]]; then
  cat "$common_err" >&2
  echo "SG_JAVA_GENERIC_GUEST_NOT_ACCEPTED status=$generic_status" >&2
  exit 1
fi
rm -f "$common_out" "$common_err"
printf 'SG_JAVA_GENERIC_NATIVE_GUEST_PASS blob=%s result=42\n' "$(git rev-parse "HEAD:$generic")"

for name in scalar branch nominal; do
  file="tests/stategraph-portability/$name.ores"
  git ls-files --error-unmatch -- "$file" >/dev/null
  test -f "$file" && test ! -L "$file"
  timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$file"
  output="$(mktemp)"
  error="$(mktemp)"
  set +e
  timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$file" --execute > "$output" 2> "$error"
  status=$?
  set -e
  if [[ "$status" != 42 || -s "$output" ]]; then
    cat "$error" >&2
    rm -f "$output" "$error"
    echo "JAVA_STATEGRAPH_GUEST_NOT_ACCEPTED $name status=$status" >&2
    exit 1
  fi
  rm -f "$output" "$error"
  printf 'JAVA_STATEGRAPH_GUEST_PASS case=%s blob=%s result=42\n' \
    "$name" "$(git rev-parse "HEAD:$file")"
done
echo 'JAVA_STATEGRAPH_NATIVE_JVM_PASS count=3 (NOT Native Image AOT or full graph parity)'
