#!/usr/bin/env bash
# Host-only adversarial checks of the differential gate itself. No GitHub CI
# or compiled AttoLang guest evidence is asserted by these tests.
set -euo pipefail
for binary in bash cp grep ln mktemp mv rm sed; do
  command -v "$binary" >/dev/null || { echo "missing $binary" >&2; exit 2; }
done
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture="$tmp/fixture"
mkdir -p "$fixture/scripts" "$fixture/src/main/java/dev/oreslang/runtime" \
  "$fixture/src/test/java/dev/oreslang"
helper="$fixture/src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java"
probe="$fixture/src/test/java/dev/oreslang/StrictIntegerArithmeticDifferentialStressTest.java"
gate="$fixture/scripts/test_strict_i64_differential.sh"
original_helper="$root/src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java"
original_probe="$root/src/test/java/dev/oreslang/StrictIntegerArithmeticDifferentialStressTest.java"
original_gate="$root/scripts/test_strict_i64_differential.sh"
for original in "$original_helper" "$original_probe" "$original_gate"; do
  test -f "$original" && test ! -L "$original" || exit 2
done
reset_fixture() {
  cp "$original_helper" "$helper"
  cp "$original_probe" "$probe"
  cp "$original_gate" "$gate"
}
reject_with() {
  local label="$1" expected_exit="$2" expected_text="$3"; shift 3
  local actual_exit=0
  "$@" > "$tmp/stdout" 2> "$tmp/stderr" || actual_exit=$?
  if [[ "$actual_exit" -ne "$expected_exit" ]] ||
     ! grep -Fq -- "$expected_text" "$tmp/stderr"; then
    echo "gate negative failed: $label (exit=$actual_exit, expected=$expected_exit)" >&2
    cat "$tmp/stdout" "$tmp/stderr" >&2
    exit 9
  fi
  echo "STRICT_I64_GATE_NEGATIVE|PASS|$label"
}

reset_fixture
printf '\n// deliberately altered test input\n' >> "$helper"
reject_with source_blob 3 'STRICT_I64_INPUT_MISMATCH' bash "$gate"

reset_fixture
printf '\n// deliberately altered test oracle\n' >> "$probe"
reject_with test_blob 3 'STRICT_I64_INPUT_MISMATCH' bash "$gate"

reset_fixture
mv "$probe" "$tmp/valid_probe"
ln -s "$tmp/valid_probe" "$probe"
reject_with symlink_probe 3 'missing or symlinked source file' bash "$gate"
rm "$probe"

reset_fixture
sed 's/rem mismatch: -128, 3/rem mismatch: 777, 777/' "$gate" > "$tmp/modified_gate"
cp "$tmp/modified_gate" "$gate"
reject_with wrong_mutant_reason 7 'mutation failed for unrelated/incorrect reason: rem' bash "$gate"

echo 'STRICT_I64_GATE_NEGATIVES|PASS|cases=4'
