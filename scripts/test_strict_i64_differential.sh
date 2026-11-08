#!/usr/bin/env bash
# Dependency-free host arithmetic probe, NOT compiler/JUnit/guest CI evidence.
# Run from any directory with a JDK (21+) and standard POSIX shell utilities.
set -euo pipefail

command -v javac >/dev/null || { echo 'missing javac' >&2; exit 2; }
command -v java >/dev/null || { echo 'missing java' >&2; exit 2; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
helper="$root/src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java"
probe="$root/src/test/java/dev/oreslang/StrictIntegerArithmeticDifferentialStressTest.java"
test -f "$helper" && test -f "$probe"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/org/junit/jupiter/api"

# The stored class is a JUnit 5 test. This disposable annotation-only stub
# allows the EXACT unchanged test class to compile without a Maven download.
# No substitute for running it with the actual JUnit engine is claimed.
cat > "$tmp/org/junit/jupiter/api/Test.java" <<'JAVA'
package org.junit.jupiter.api;
public @interface Test {}
JAVA

javac -Xlint:all -d "$tmp/original" \
  "$tmp/org/junit/jupiter/api/Test.java" "$helper" "$probe"
java -ea -cp "$tmp/original" \
  dev.oreslang.StrictIntegerArithmeticDifferentialStressTest > "$tmp/original.log"
grep -Fx 'STRICT_I64_DEEP_PROBE|PASS|checks=374618' "$tmp/original.log"

# Verify that the oracle actually rejects representative semantic regressions.
# Source mutations are temporary; neither repository source nor PR branches
# are touched. A mutant counts as killed ONLY on a compiled assertion failure.
for mutant in rem mod pow xor; do
  mkdir -p "$tmp/$mutant"
  changed="$tmp/$mutant/StrictIntegerArithmetic.java"
  case "$mutant" in
    rem)
      sed 's/return dividend % divisor;/return Math.floorMod(dividend, divisor);/' \
        "$helper" > "$changed" ;;
    mod)
      sed 's/return divisor > 0 ? remainder + divisor : remainder - divisor;/return remainder;/' \
        "$helper" > "$changed" ;;
    pow)
      sed 's/result = Math.multiplyExact(result, base)/result *= base/' \
        "$helper" > "$changed" ;;
    xor)
      sed 's/return left \^ right;/return left | right;/' \
        "$helper" > "$changed" ;;
  esac
  if cmp -s "$helper" "$changed"; then
    echo "mutation did not change source: $mutant" >&2
    exit 3
  fi
  javac -Xlint:all -d "$tmp/$mutant/classes" \
    "$tmp/org/junit/jupiter/api/Test.java" "$changed" "$probe"
  if java -ea -cp "$tmp/$mutant/classes" \
      dev.oreslang.StrictIntegerArithmeticDifferentialStressTest \
      > "$tmp/$mutant/out.log" 2>&1; then
    echo "mutation survived: $mutant" >&2
    exit 4
  fi
  if ! grep -q 'java.lang.AssertionError:' "$tmp/$mutant/out.log"; then
    echo "mutation failed for non-assertion reason: $mutant" >&2
    cat "$tmp/$mutant/out.log" >&2
    exit 5
  fi
  echo "STRICT_I64_MUTATION|KILLED|$mutant"
done

echo 'STRICT_I64_DEEP_GATE|PASS|mutants=4/4'
