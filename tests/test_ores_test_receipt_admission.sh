#!/usr/bin/env bash
# Source-free negative controls. Do not count these as Oreslang guest tests.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verify="$root/scripts/verify_ores_test_receipts.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
file="$tmp/receipt.log"
summary='ORES_TEST|SUMMARY|total=2|passed=2|failed=0|skipped=0|filtered=0'
valid() {
  printf 'maven output\nORES_TEST|PASS|suite/a|\nORES_TEST|PASS|suite/b|\n%s\n' "$summary" > "$file"
}
reject() {
  local label="$1"
  if bash "$verify" "$file" suite 2 >/dev/null 2>&1; then
    printf 'FAIL: accepted %s\n' "$label" >&2
    exit 1
  fi
  printf 'PASS: rejected %s\n' "$label"
}
valid
bash "$verify" "$file" suite 2
printf 'PASS: accepted valid receipt\n'
valid; sed -i 's#suite/b#suite/a#' "$file"; reject 'duplicate identity'
valid; sed -i 's#suite/b|#suite/b|extra|#' "$file"; reject 'extra field'
valid; sed -i 's#suite/b#other/b#' "$file"; reject 'wrong suite'
valid; sed -i 's#suite/b|#suite/b|unexpected#' "$file"; reject 'nonempty PASS detail'
valid; printf '%s\n' "$summary" >> "$file"; reject 'duplicate summary'
valid; sed -i 's#filtered=0#filtered=0|forged#' "$file"; reject 'summary suffix'
valid; sed -i '/suite\/b/d' "$file"; reject 'missing PASS'
valid; sed -i 's#PASS|suite/b#FAIL|suite/b#' "$file"; reject 'FAIL status'
valid; { printf '%s\n' "$summary"; printf 'ORES_TEST|PASS|suite/a|\nORES_TEST|PASS|suite/b|\n'; } > "$file"; reject 'summary before PASS'
valid; printf 'ORES_TEST_BOGUS\n' >> "$file"; reject 'malformed ORES_TEST line'
valid; sed -i 's#suite/b#suite/b\r#' "$file"; reject 'raw CR in identity'
valid; sed -i 's#suite/b#suite/b\t#' "$file"; reject 'raw tab in identity'
valid; printf 'ORES_TEST|PASS|suite/a|\nORES_TEST|PASS|suite/b|\000\n%s\n' "$summary" > "$file"; reject 'raw NUL in detail'
valid; if bash "$verify" "$file" suite 0 >/dev/null 2>&1; then echo 'FAIL: accepted zero expected' >&2; exit 1; fi
printf 'PASS: rejected zero expected count\n'
