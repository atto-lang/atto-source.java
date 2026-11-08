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
  "$fixture/src/test/java/dev/oreslang" "$fixture/contracts/v1"
helper="$fixture/src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java"
probe="$fixture/src/test/java/dev/oreslang/StrictIntegerArithmeticDifferentialStressTest.java"
gate="$fixture/scripts/test_strict_i64_differential.sh"
contract="$fixture/contracts/v1/strict-named-integer-arithmetic-v1.json"
contract_test="$fixture/src/test/java/dev/oreslang/StrictIntegerContractV1OracleTest.java"
original_helper="$root/src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java"
original_probe="$root/src/test/java/dev/oreslang/StrictIntegerArithmeticDifferentialStressTest.java"
original_gate="$root/scripts/test_strict_i64_differential.sh"
original_contract="$root/contracts/v1/strict-named-integer-arithmetic-v1.json"
original_contract_test="$root/src/test/java/dev/oreslang/StrictIntegerContractV1OracleTest.java"
for original in "$original_helper" "$original_probe" "$original_gate" "$original_contract" "$original_contract_test"; do
  test -f "$original" && test ! -L "$original" || exit 2
done
reset_fixture() {
  cp "$original_helper" "$helper"
  cp "$original_probe" "$probe"
  cp "$original_gate" "$gate"
  cp "$original_contract" "$contract"
  cp "$original_contract_test" "$contract_test"
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

reset_fixture
rm "$helper"
reject_with missing_helper 3 'missing or symlinked source file' bash "$gate"

reset_fixture
mv "$helper" "$tmp/valid_helper"
ln -s "$tmp/valid_helper" "$helper"
reject_with symlink_helper 3 'missing or symlinked source file' bash "$gate"
rm "$helper"

reset_fixture
sed 's/STRICT_I64_DEEP_PROBE|PASS|checks=601343/STRICT_I64_DEEP_PROBE|PASS|checks=123/' \
  "$gate" > "$tmp/modified_gate"
cp "$tmp/modified_gate" "$gate"
reject_with wrong_baseline_receipt 4 'baseline did not produce the sole expected' bash "$gate"

reset_fixture
sed "s/anchor='return dividend % divisor;'/anchor='return dividend % NOT_PRESENT;'/" \
  "$gate" > "$tmp/modified_gate"
cp "$tmp/modified_gate" "$gate"
reject_with missing_mutation_anchor 5 'mutation anchor missing/duplicated/unchanged: rem' bash "$gate"

reset_fixture
sed 's/at dev.oreslang.StrictIntegerArithmeticDifferentialStressTest./at FAKE.TEST./' \
  "$gate" > "$tmp/modified_gate"
cp "$tmp/modified_gate" "$gate"
reject_with spoofed_stack_frame 7 'mutation failed for unrelated/incorrect reason: rem' bash "$gate"

reset_fixture
printf '\n// test mutated JSON contract source\n' >> "$contract"
reject_with contract_blob 3 'STRICT_I64_INPUT_MISMATCH' bash "$gate"

reset_fixture
printf '\n// altered canonical test oracle\n' >> "$contract_test"
reject_with contract_test_blob 3 'STRICT_I64_INPUT_MISMATCH' bash "$gate"

reset_fixture
mv "$contract" "$tmp/canonical_contract"
ln -s "$tmp/canonical_contract" "$contract"
reject_with symlink_contract 3 'missing or symlinked source file' bash "$gate"
rm "$contract"

reset_fixture
rm "$contract"
reject_with missing_contract 3 'missing or symlinked source file' bash "$gate"

reset_fixture
sed 's/STRICT_I64_CONTRACT|PASS|cases=26|errors=4/STRICT_I64_CONTRACT|PASS|cases=0|errors=0/' \
  "$gate" > "$tmp/modified_gate"
cp "$tmp/modified_gate" "$gate"
reject_with forged_contract_receipt 8 'canonical contract did not produce the exact expected receipt' bash "$gate"

echo 'STRICT_I64_GATE_NEGATIVES|PASS|cases=14' 
