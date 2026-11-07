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


# Exact same portable scalar-superstep guest as native LLVM backend.
superstep=contracts/stategraph-portability/shared-superstep-scalar.ores
expected_superstep_blob=054d4e9fe297c520513686feae076dc921f581ac
[[ -f "$superstep" && ! -L "$superstep" ]]
git ls-files --error-unmatch -- "$superstep" >/dev/null
[[ "$(git hash-object "$superstep")" = "$expected_superstep_blob" ]] ||
  { echo 'SG_JAVA_SHARED_SUPERSTEP_SOURCE_DRIFT' >&2; exit 1; }
[[ "$(git rev-parse "HEAD:$superstep")" = "$expected_superstep_blob" ]] ||
  { echo 'SG_JAVA_SHARED_SUPERSTEP_HEAD_DRIFT' >&2; exit 1; }
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$superstep" ||
  { echo 'SG_JAVA_SHARED_SUPERSTEP_CHECK_NOT_ACCEPTED' >&2; exit 1; }
guest_out="$(mktemp)"
guest_err="$(mktemp)"
set +e
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$superstep" --execute > "$guest_out" 2> "$guest_err"
superstep_status=$?
set -e
if [[ "$superstep_status" -ne 42 || -s "$guest_out" || -s "$guest_err" ]]; then
  cat "$guest_err" >&2
  rm -f "$guest_out" "$guest_err"
  echo "SG_JAVA_SHARED_SUPERSTEP_GUEST_NOT_ACCEPTED status=$superstep_status" >&2
  exit 1
fi
rm -f "$guest_out" "$guest_err"
printf 'SG_JAVA_SHARED_SUPERSTEP_NATIVE_PASS blob=%s guest_exit=42\n' "$expected_superstep_blob"


# Canonical bounded-recursion and branch guest shared with native LLVM.
cycle=contracts/stategraph-portability/shared-bounded-cycle.ores
cycle_blob=3c69cdf00c4e1390898ef0594eba76c54c0e2d7f
[[ -f "$cycle" && ! -L "$cycle" ]]
git ls-files --error-unmatch -- "$cycle" >/dev/null
[[ "$(git hash-object "$cycle")" = "$cycle_blob" ]] ||
  { echo 'SG_JAVA_SHARED_CYCLE_SOURCE_DRIFT' >&2; exit 1; }
[[ "$(git rev-parse "HEAD:$cycle")" = "$cycle_blob" ]] ||
  { echo 'SG_JAVA_SHARED_CYCLE_HEAD_DRIFT' >&2; exit 1; }
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$cycle" ||
  { echo 'SG_JAVA_SHARED_CYCLE_CHECK_NOT_ACCEPTED' >&2; exit 1; }
cycle_out="$(mktemp)"
cycle_err="$(mktemp)"
set +e
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$cycle" --execute > "$cycle_out" 2> "$cycle_err"
cycle_status=$?
set -e
if [[ "$cycle_status" -ne 42 || -s "$cycle_out" || -s "$cycle_err" ]]; then
  cat "$cycle_err" >&2
  rm -f "$cycle_out" "$cycle_err"
  echo "SG_JAVA_SHARED_CYCLE_GUEST_NOT_ACCEPTED status=$cycle_status" >&2
  exit 1
fi
rm -f "$cycle_out" "$cycle_err"
printf 'SG_JAVA_SHARED_CYCLE_NATIVE_PASS blob=%s guest_exit=42\n' "$cycle_blob"


# Canonical if/then source shared byte-identically with LLVM's native parser test.
canonical=contracts/stategraph-portability/shared-canonical-then.ores
canonical_blob=ad42a13d1542d8df6215bff42d056a489015c842
[[ -f "$canonical" && ! -L "$canonical" ]]
git ls-files --error-unmatch -- "$canonical" >/dev/null
[[ "$(git hash-object "$canonical")" = "$canonical_blob" ]]
[[ "$(git rev-parse "HEAD:$canonical")" = "$canonical_blob" ]]
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$canonical" ||
  { echo 'SG_JAVA_CANONICAL_THEN_CHECK_NOT_ACCEPTED' >&2; exit 1; }
canonical_out="$(mktemp)"
canonical_err="$(mktemp)"
set +e
timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$canonical" --execute > "$canonical_out" 2> "$canonical_err"
canonical_status=$?
set -e
if [[ "$canonical_status" -ne 42 || -s "$canonical_out" || -s "$canonical_err" ]]; then
  cat "$canonical_err" >&2
  rm -f "$canonical_out" "$canonical_err"
  echo "SG_JAVA_CANONICAL_THEN_GUEST_NOT_ACCEPTED status=$canonical_status" >&2
  exit 1
fi
rm -f "$canonical_out" "$canonical_err"
printf 'SG_JAVA_CANONICAL_THEN_NATIVE_PASS blob=%s guest_exit=42\n' "$canonical_blob"

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
