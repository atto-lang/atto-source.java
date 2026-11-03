#!/usr/bin/env bash
# Graal/JVM executes canonical AttoLang .atto scalar zero-assert source.
# This is a lightweight scalar-only compatibility lane; full native
# AttoLang stdlib test receipts are checked by separate JIT/AOT/hybrid jobs.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
fixtures="contracts/parity/native-scalar-zero-v1"
manifest="$fixtures/cases.tsv"
[[ "$(git hash-object "$manifest")" == a87f64af3e448ba10927eb2932900679896b283b ]] ||
  { echo "AttoLang scalar fixture manifest mismatch" >&2; exit 1; }
verify_fixture() {
  local name="$1" pinned
  pinned="$(awk -F '\t' -v name="$name" '$1==name {print $3}' "$manifest")"
  [[ "$pinned" =~ ^[0-9a-f]{40}$ && "$(git hash-object "$fixtures/$name")" == "$pinned" ]] ||
    { echo "native AttoLang fixture byte identity mismatch: $name" >&2; exit 1; }
}
for name in precedence-zero.ores i64-precision-zero.ores signed-opposite-zero.ores nested-subtraction-zero.ores min-boundary-zero.ores; do
  verify_fixture "$name"
  # Retain the shared legacy .ores ledger for the other backends; require
  # canonical .atto bytes to be identical to the reviewed cross-backend blob.
  path="contracts/parity/native-scalar-zero-v1/${name%.ores}.atto"
  [[ -f "$path" && ! -L "$path" && ! -L "$fixtures/$name" ]] || {
    echo "missing canonical AttoLang scalar fixture $name" >&2; exit 1;
  }
  pinned="$(awk -F '\t' -v name="$name" '$1==name {print $3}' "$manifest")"
  [[ "$(git hash-object "$path")" == "$pinned" ]] || {
    echo "AttoLang .atto source diverges from reviewed cross-backend fixture: $name" >&2; exit 1;
  }
  timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="$path"
done
# Return value 1 is an intentionally *valid* AttoLang program but an assertion
# failure. Without guest exit propagation, the runner must fail closed.
mutant="$(mktemp --suffix=.atto)"
trap 'rm -f "$mutant"' EXIT
printf 'pub fnc main():int{return (2 + 3 * 4) - 13;}\n' > "$mutant"
# The negative is meaningful only after a successful compiler check.
timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="--check $mutant"
if timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="$mutant"; then
  echo "nonzero AttoLang assertion was silently accepted" >&2
  exit 1
fi
# Compiler rejection is distinct from an assertion result.
verify_fixture "reject-removed-operator.ores"
invalid="contracts/parity/native-scalar-zero-v1/reject-removed-operator.atto"
if timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="--check $invalid"; then
  echo "removed AttoLang % operator was accepted" >&2
  exit 1
fi
echo "GRAAL_NATIVE_SCALAR_ZERO compiled_guest_zero=5 nonzero_mutant_rejected=1 invalid_source_rejected=1"
