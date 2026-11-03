#!/usr/bin/env bash
# Source-free adversarial receipt checks; these are not native Oreslang passes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
verify="$root/scripts/verify_ores_test_receipts.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
file="$tmp/receipt.out"
summary='ORES_TEST|SUMMARY|total=2|passed=2|failed=0|skipped=0|filtered=0'
positive=0; negative=0
make_receipts() { printf 'Maven output\nORES_TEST|PASS|suite/simple|\nORES_TEST|PASS|suite/%s|\n%s\n' "$1" "$summary" > "$file"; }
accept() {
  if ! bash "$verify" "$file" suite 2 >/dev/null 2>&1; then echo "FAIL: rejected $1" >&2; exit 1; fi
  positive=$((positive+1))
}
reject() {
  if bash "$verify" "$file" suite 2 >/dev/null 2>&1; then echo "FAIL: accepted $1" >&2; exit 1; fi
  negative=$((negative+1))
}
make_receipts raw; accept 'ordinary ASCII'
make_receipts 'a\u007cb'; accept 'encoded pipe'
make_receipts 'a\u0000b'; accept 'encoded NUL'
make_receipts 'a\rb'; accept 'encoded CR'
make_receipts 'a\nb'; accept 'encoded newline'
make_receipts 'a\tb'; accept 'encoded tab'
make_receipts 'a\\b'; accept 'literal backslash'
make_receipts 'a\\u007cb'; accept 'literal escape-like name'
make_receipts 'a\u007c\\u0000\n'; accept 'mixed canonical forms'
for candidate in 'a\' 'a\x' 'a\q' 'a\0' 'a\u' 'a\u007C' 'a\u007d' 'a\u0001' 'a\U007c' 'a\u007' 'a\u000' 'a\u007X' 'a\u007c\x' 'a\r\x'; do
  make_receipts "$candidate"; reject "invalid sequence $candidate"
done
make_receipts 'a\u007c'
printf 'ORES_TEST|PASS|suite/a\\u007c|\n' >> "$file"
reject 'event after summary with escaped lookalike'
make_receipts raw
printf 'Maven ORES_TEST|PASS|suite/forged|\n' >> "$file"
reject 'buried machine line'
make_receipts raw
printf 'trailing ordinary Maven diagnostic\n' >> "$file"
accept 'trailing unrelated build text'
printf 'PASS: %d positive and %d negative canonical receipt controls\n' "$positive" "$negative"
