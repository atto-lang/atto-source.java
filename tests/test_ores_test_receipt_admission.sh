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

# Additional adversarial accounting, event ordering, byte and argument controls.
valid; sed -i 's#total=2#total=3#' "$file"; reject 'incorrect total'
valid; sed -i 's#passed=2#passed=1#' "$file"; reject 'incorrect passed count'
valid; sed -i 's#failed=0#failed=1#' "$file"; reject 'nonzero failure summary'
valid; sed -i 's#skipped=0#skipped=1#' "$file"; reject 'nonzero skipped summary'
valid; sed -i 's#filtered=0#filtered=1#' "$file"; reject 'nonzero filtered summary'
valid; sed -i 's#PASS|suite/b#SKIP|suite/b#' "$file"; reject 'SKIP status'
valid; sed -i 's#PASS|suite/b#pass|suite/b#' "$file"; reject 'lowercase status'
valid; sed -i 's#suite/b#suite/#' "$file"; reject 'empty case identity'
valid; sed -i 's#suite/b#suite#' "$file"; reject 'missing suite separator'
valid; sed -i '/^ORES_TEST|SUMMARY|/d' "$file"; reject 'missing summary'
valid; sed -i 's#ORES_TEST|SUMMARY|#ORES_TEST|summary|#' "$file"; reject 'lowercase summary tag'
valid; printf 'ORES_TEST|PASS|suite/c|\n' >> "$file"; reject 'event after summary'
valid; sed -i 's#suite/b#suite/b\x7f#' "$file"; reject 'raw DEL in identity'
valid; sed -i 's#suite/b#suite/b\v#' "$file"; reject 'raw vertical tab in identity'
valid; sed -i 's#suite/b#suite/b\f#' "$file"; reject 'raw form feed in identity'
valid; if bash "$verify" "$file" suite 02 >/dev/null 2>&1; then echo 'FAIL: accepted leading-zero expected count' >&2; exit 1; fi
printf 'PASS: rejected leading-zero expected count\n'
valid; if bash "$verify" "$file" 'suite|injected' 2 >/dev/null 2>&1; then echo 'FAIL: accepted invalid suite name' >&2; exit 1; fi
printf 'PASS: rejected invalid suite name\n'
valid; if bash "$verify" "$file" suite -2 >/dev/null 2>&1; then echo 'FAIL: accepted negative expected count' >&2; exit 1; fi
printf 'PASS: rejected negative expected count\n'
valid; if bash "$verify" "$tmp/nonexistent" suite 2 >/dev/null 2>&1; then echo 'FAIL: accepted missing receipt file' >&2; exit 1; fi
printf 'PASS: rejected missing receipt file\n'
valid; printf 'trailing ordinary Maven output\n' >> "$file"
bash "$verify" "$file" suite 2
printf 'PASS: accepted valid receipts with trailing non-receipt compiler output\n'