#!/usr/bin/env bash
# Host-only, SHA-pinned JDK arithmetic regression gate; NOT a JUnit engine,
# Graal guest, Native Image, cross-backend, or hosted CI execution receipt.
set -euo pipefail

for executable in javac java git grep sed cmp mktemp cp chmod cat; do
  command -v "$executable" >/dev/null || {
    echo "missing required executable: $executable" >&2; exit 2;
  }
done
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
helper="$root/src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java"
probe="$root/src/test/java/dev/oreslang/StrictIntegerArithmeticDifferentialStressTest.java"
expected_helper='7cb1309f59310eda7dbfba0505fafce4ad0985e6'
expected_probe='31fe804b941435ccb8a96291c64b6d66e1e0842b'

# Snapshot once, then hash AND compile only the immutable copied bytes. This
# prevents a checkout edit between a hash check and javac from changing the
# executed source without changing the receipt.
for file in "$helper" "$probe"; do
  test -f "$file" && test ! -L "$file" || {
    echo "missing or symlinked source file: $file" >&2; exit 3;
  }
done
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
cp -- "$helper" "$tmp/StrictIntegerArithmetic.java"
cp -- "$probe" "$tmp/StrictIntegerArithmeticDifferentialStressTest.java"
helper="$tmp/StrictIntegerArithmetic.java"
probe="$tmp/StrictIntegerArithmeticDifferentialStressTest.java"
actual_helper="$(git hash-object -- "$helper")"
actual_probe="$(git hash-object -- "$probe")"
if [[ "$actual_helper" != "$expected_helper" ||
      "$actual_probe" != "$expected_probe" ]]; then
  echo 'STRICT_I64_INPUT_MISMATCH: unreviewed source/test blob; gate not executed' >&2
  exit 3
fi
echo "STRICT_I64_INPUT_BLOB|helper=$actual_helper|probe=$actual_probe"
# Mutation and compilation are performed only on the verified private snapshot.
chmod a-w "$helper" "$probe"
echo "STRICT_I64_TOOLCHAIN|$(javac -version 2>&1)|$(java -version 2>&1 | sed -n 1p)"

mkdir -p "$tmp/org/junit/jupiter/api"
# This disposable annotation is ONLY for the dependency-free standalone main.
# The actual committed test remains runnable by the real JUnit 5 engine in CI.
cat > "$tmp/org/junit/jupiter/api/Test.java" <<'JAVA'
package org.junit.jupiter.api;
public @interface Test {}
JAVA

javac -Xlint:all -d "$tmp/original" \
  "$tmp/org/junit/jupiter/api/Test.java" "$helper" "$probe"
java -ea -Xmx128m -cp "$tmp/original" \
  dev.oreslang.StrictIntegerArithmeticDifferentialStressTest > "$tmp/original.log" 2> "$tmp/original.err"
if [[ -s "$tmp/original.err" ||
      "$(cat "$tmp/original.log")" != 'STRICT_I64_DEEP_PROBE|PASS|checks=601343' ]]; then
  echo 'baseline did not produce the sole expected exact assertion-count receipt' >&2
  cat "$tmp/original.log" "$tmp/original.err" >&2
  exit 4
fi
cat "$tmp/original.log"

# For each semantic mutation, demand exactly one matching source anchor and a
# specific, deterministic arithmetic mismatch. Any unrelated AssertionError,
# JVM exception, class-loading failure, compile failure or surviving mutation
# is a FAIL, not a claimed test kill.
for mutant in rem mod pow xor; do
  mkdir -p "$tmp/$mutant"
  changed="$tmp/$mutant/StrictIntegerArithmetic.java"
  case "$mutant" in
    rem)
      anchor='return dividend % divisor;'
      reason='rem mismatch: -128, 3'
      sed 's/return dividend % divisor;/return Math.floorMod(dividend, divisor);/' \
        "$helper" > "$changed" ;;
    mod)
      anchor='return divisor > 0 ? remainder + divisor : remainder - divisor;'
      reason='mod mismatch: -128, -127'
      sed 's/return divisor > 0 ? remainder + divisor : remainder - divisor;/return remainder;/' \
        "$helper" > "$changed" ;;
    pow)
      anchor='result = Math.multiplyExact(result, base)'
      reason='power overflow did not throw'
      sed 's/result = Math.multiplyExact(result, base)/result *= base/' \
        "$helper" > "$changed" ;;
    xor)
      anchor='return left ^ right;'
      reason='bit_xor mismatch: -128, -128'
      sed 's/return left \^ right;/return left | right;/' \
        "$helper" > "$changed" ;;
  esac
  count="$(grep -Fc -- "$anchor" "$helper" || true)"
  if [[ "$count" != '1' ]] || cmp -s -- "$helper" "$changed"; then
    echo "mutation anchor missing/duplicated/unchanged: $mutant" >&2
    exit 5
  fi
  javac -Xlint:all -d "$tmp/$mutant/classes" \
    "$tmp/org/junit/jupiter/api/Test.java" "$changed" "$probe"
  if java -ea -Xmx128m -cp "$tmp/$mutant/classes" \
      dev.oreslang.StrictIntegerArithmeticDifferentialStressTest \
      > "$tmp/$mutant/out.log" 2>&1; then
    echo "semantic mutation survived: $mutant" >&2
    exit 6
  fi
  if ! grep -Fqx -- "Exception in thread \"main\" java.lang.AssertionError: $reason" \
      "$tmp/$mutant/out.log" ||
      ! grep -Fq -- "at dev.oreslang.StrictIntegerArithmeticDifferentialStressTest." \
      "$tmp/$mutant/out.log" ||
      grep -Fq -- "STRICT_I64_DEEP_PROBE|PASS" "$tmp/$mutant/out.log"; then
    echo "mutation failed for unrelated/incorrect reason: $mutant" >&2
    cat "$tmp/$mutant/out.log" >&2
    exit 7
  fi
  echo "STRICT_I64_MUTATION|KILLED|$mutant"
done

echo 'STRICT_I64_DEEP_GATE|PASS|mutants=4/4'
