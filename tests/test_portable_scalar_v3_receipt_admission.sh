#!/usr/bin/env bash
# Source-free adversarial v3 receipt controls, NOT a guest/LLVM/JVM pass.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verify="$root/scripts/verify_ores_test_receipts.sh"
ids="$root/tests/portable-scalar-v3.expected-ids.txt"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
f="$tmp/guest.log"
summary='ORES_TEST|SUMMARY|total=12|passed=12|failed=0|skipped=0|filtered=0'
emit() {
  while IFS= read -r id; do
    printf 'ORES_TEST|PASS|portable-scalar-v3/%s|\n' "$id"
  done < "$ids"
}
valid() { { printf 'compiler output\n'; emit; printf '%s\n' "$summary"; } > "$f"; }
reject() {
  if bash "$verify" "$f" portable-scalar-v3 12 "$ids" >/dev/null 2>&1; then
    echo "FAIL: accepted $1" >&2; exit 1
  fi
}
test "$(wc -l < "$ids")" -eq 12
test "$(sort -u "$ids" | wc -l)" -eq 12
valid; bash "$verify" "$f" portable-scalar-v3 12 "$ids"
valid; sed -i 's#portable-scalar-v3/near-max-sum#portable-scalar-v3/forged-extra#' "$f"; reject 'unexpected unique case'
valid; sed -i 's#portable-scalar-v3/near-max-sum#portable-scalar-v3/near-min-successor-two#' "$f"; reject 'duplicate and missing case'
valid; sed -i 's#portable-scalar-v3/near-max-sum#other-suite/near-max-sum#' "$f"; reject 'wrong suite'
valid; sed -i 's#portable-scalar-v3/near-max-sum#portable-scalar-v3/near-max-sum\\u007cforged#' "$f"; reject 'escaped lookalike'
valid; sed -i '/near-max-sum/d' "$f"; reject 'missing pass'
valid; sed -i 's#^ORES_TEST|PASS|portable-scalar-v3/near-max-sum#ORES_TEST|FAIL|portable-scalar-v3/near-max-sum#' "$f"; reject 'failed case'
valid; sed -i 's#passed=12#passed=11#' "$f"; reject 'wrong accounting'
valid; printf '%s\n' "$summary" >> "$f"; reject 'duplicate summary'
valid; printf 'ORES_TEST|PASS|portable-scalar-v3/near-max-sum|\n' >> "$f"; reject 'late event'
valid; printf 'Maven ORES_TEST|PASS|portable-scalar-v3/forged|\n' >> "$f"; reject 'hidden event'
valid; sed -i 's#portable-scalar-v3/near-max-sum#portable-scalar-v3/near-max-sum|extra#' "$f"; reject 'extra delimiter'
valid; sed -i 's#portable-scalar-v3/near-max-sum#portable-scalar-v3/near-max-sum\\x#' "$f"; reject 'invalid escape'
# Changed expected-ID inputs are also adversarial; no PR can silently rename a case.
valid; sed '1s/.*/forged-new-name/' "$ids" > "$tmp/changed.txt"
if bash "$verify" "$f" portable-scalar-v3 12 "$tmp/changed.txt" >/dev/null 2>&1; then
  echo 'FAIL: verifier trusted mutated expected IDs' >&2; exit 1
fi
echo 'PASS: v3 source-free exact-ID receipt adversarial controls'
