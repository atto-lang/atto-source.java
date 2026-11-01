#!/usr/bin/env bash
# Graal/JVM executes exact native Oreslang scalar zero-assert source.
# This is a lightweight scalar-only compatibility lane; full native
# ores.std/testing receipts are checked by separate JIT/AOT/hybrid jobs.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
for name in precedence-zero.ores i64-precision-zero.ores signed-opposite-zero.ores nested-subtraction-zero.ores min-boundary-zero.ores; do
  path="contracts/parity/native-scalar-zero-v1/$name"
  [[ -f "$path" && ! -L "$path" ]] || { echo "missing Oreslang fixture $name" >&2; exit 1; }
  timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="$path"
done
# Return value 1 is an intentionally *valid* Oreslang program but an assertion
# failure. Without guest exit propagation, the runner must fail closed.
mutant="$(mktemp --suffix=.ores)"
trap 'rm -f "$mutant"' EXIT
printf 'pub fnc main():int{return (2 + 3 * 4) - 13;}\n' > "$mutant"
if timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="$mutant"; then
  echo "nonzero Oreslang assertion was silently accepted" >&2
  exit 1
fi
# Compiler rejection is distinct from an assertion result.
invalid="contracts/parity/native-scalar-zero-v1/reject-removed-operator.ores"
if timeout 180s mvn -B -ntp -DskipTests exec:java -Dexec.args="--check $invalid"; then
  echo "removed Oreslang % operator was accepted" >&2
  exit 1
fi
echo "GRAAL_NATIVE_SCALAR_ZERO compiled_guest_zero=5 nonzero_mutant_rejected=1 invalid_source_rejected=1"
