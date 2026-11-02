#!/usr/bin/env bash
# Source-free identity-manifest tests. These synthesize receipts: NEVER credit
# them as compiler-backed execution of any .ores program.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verifier="$root/scripts/verify_ores_test_receipts.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

checked=0
while IFS='|' read -r source_path id_path suite count; do
  src="$root/$source_path"
  ids="$root/$id_path"
  [[ -f "$src" && -f "$ids" && "$count" =~ ^[1-9][0-9]*$ ]] || {
    echo "missing native test fixture or expected identity list" >&2
    exit 1
  }
  grep -Fq "new TestSuite(\"$suite\"" "$src" || {
    echo "native suite name mismatch: $source_path" >&2
    exit 1
  }
  # Only top-level registrations inside the executable entrypoint count.
  # Nested human-mode suites are NOT machine receipt registrations.
  awk '/^pub routine main\(\): void \{/ { in_main = 1 } in_main { print }' "$src" |
    sed -nE 's/^[[:space:]]*new TestCase\("([A-Za-z0-9][A-Za-z0-9._\/-]*)", [A-Za-z_][A-Za-z0-9_]*, false, ""\),?[[:space:]]*$/\1/p' > "$tmp/extracted"
  if [[ "$(wc -l < "$tmp/extracted")" -ne "$count" ]] ||
     ! cmp -s "$ids" "$tmp/extracted"; then
    echo "trusted IDs differ from source registrations: $source_path" >&2
    exit 1
  fi
  : > "$tmp/receipts"
  while IFS= read -r id; do
    printf 'ORES_TEST|PASS|%s/%s|\n' "$suite" "$id" >> "$tmp/receipts"
  done < "$ids"
  printf 'ORES_TEST|SUMMARY|total=%s|passed=%s|failed=0|skipped=0|filtered=0\n' \
    "$count" "$count" >> "$tmp/receipts"
  bash "$verifier" "$tmp/receipts" "$suite" "$count" "$ids"
  # One forged identity must fail, even if count and total are unchanged.
  { printf 'ORES_TEST|PASS|%s/forged-id-not-in-manifest|\n' "$suite"
    tail -n +2 "$tmp/receipts"
  } > "$tmp/forged"
  if bash "$verifier" "$tmp/forged" "$suite" "$count" "$ids" >/dev/null 2>&1; then
    echo "accepted forged $suite test identity" >&2
    exit 1
  fi
  checked=$((checked + 1))
done <<'NATIVE_SUITES'
examples/native-testing-regressions-40.ores|tests/native-testing-regressions-40.expected-ids.txt|stdlib|40
tests/test_std_harness_regressions.ores|tests/test_std_harness_regressions.expected-ids.txt|native-testing-core|30
tests/test_std_harness_scalar_parity_batch4.ores|tests/test_std_harness_scalar_parity_batch4.expected-ids.txt|compiler-scalar-parity-batch4|24
tests/test_std_harness_meta_batch4.ores|tests/test_std_harness_meta_batch4.expected-ids.txt|harness-meta-batch4|12
tests/test_std_harness_accounting_batch5.ores|tests/test_std_harness_accounting_batch5.expected-ids.txt|harness-accounting-batch5|16
tests/portable-scalar-v2-20261101.ores|tests/portable-scalar-v2.expected-ids.txt|portable-scalar-v2|10
NATIVE_SUITES

[[ "$checked" -eq 6 ]] || { echo 'expected exactly six native suites' >&2; exit 1; }
echo 'PASS: six ordered source-to-ID manifests and six synthetic forged-ID rejections (source-free only)'
