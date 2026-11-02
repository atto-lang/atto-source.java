#!/usr/bin/env bash
# Source-free negative mutation testing: not evidence of guest execution.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verifier="$root/scripts/verify_ores_test_receipts.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
receipt="$tmp/receipt.log"
ids="$tmp/expected-ids.txt"
expected='ORES_TEST|SUMMARY|total=2|passed=2|failed=0|skipped=0|filtered=0'
good() {
  printf 'a\nb\n' > "$ids"
  printf 'Unrelated Maven diagnostic\nORES_TEST|PASS|suite/a|\nORES_TEST|PASS|suite/b|\n%s\n' "$expected" > "$receipt"
}
reject() {
  local label="$1"
  if bash "$verifier" "$receipt" suite 2 "$ids" >/dev/null 2>&1; then
    printf 'FAIL: accepted %s\n' "$label" >&2
    exit 1
  fi
}
good
bash "$verifier" "$receipt" suite 2 "$ids"
# The existing three-argument verifier deliberately only checks shape/count.
# A forged name passes it; exact expected IDs must be passed for real suites.
good; sed -i 's#suite/b#suite/c#' "$receipt"
bash "$verifier" "$receipt" suite 2
reject 'invented PASS ID despite matching summary/count'
good
printf 'ORES_TEST|PASS|suite/b|\nORES_TEST|PASS|suite/a|\n%s\n' "$expected" > "$receipt"
reject 'reordered PASS IDs'
good; printf 'a\na\n' > "$ids"; reject 'duplicate expected IDs'
good; printf 'a\n' > "$ids"; reject 'incomplete expected set'
good; printf 'a\nb\nc\n' > "$ids"; reject 'oversized expected set'
good; printf 'a\n\n' > "$ids"; reject 'empty expected name'
good; printf 'a\n/b\n' > "$ids"; reject 'slash-leading name'
good; printf 'a\nb|forged\n' > "$ids"; reject 'delimiter in trusted ID'
good; printf 'a\nb\\n\n' > "$ids"; reject 'backslash in trusted ID'
good; printf 'a\nb\r\n' > "$ids"; reject 'raw carriage return'
good; printf 'a\nb\t\n' > "$ids"; reject 'tab in ID'
good; printf 'a\nb\000\n' > "$ids"; reject 'NUL in trusted expected case'
good; printf 'a\n b\n' > "$ids"; reject 'whitespace-prefixed case'
good; printf 'a\nb \n' > "$ids"; reject 'whitespace-suffixed case'
good; printf 'a\nb\n' > "$ids"; sed -i 's#suite/a#suite/x#' "$receipt"; reject 'wrong first ID'
good; sed -i 's#PASS|suite/b#SKIP|suite/b#' "$receipt"; reject 'skip instead of declared PASS'
good; printf 'a\nb\n' > "$ids"; printf 'unrelated compiler log line\n' >> "$receipt"
bash "$verifier" "$receipt" suite 2 "$ids"
# Use the real 16-ID portable fixture too, ensuring the trusted fixture itself
# has no accidental duplicate/invalid identities and can validate exact events.
portable="$root/tests/portable-scalar-v1.expected-ids.txt"
[ "$(wc -l < "$portable")" -eq 16 ]
summary16='ORES_TEST|SUMMARY|total=16|passed=16|failed=0|skipped=0|filtered=0'
: > "$receipt"
while IFS= read -r case_id; do
  printf 'ORES_TEST|PASS|portable-scalar-v1/%s|\n' "$case_id" >> "$receipt"
done < "$portable"
printf '%s\n' "$summary16" >> "$receipt"
bash "$verifier" "$receipt" portable-scalar-v1 16 "$portable"
# Source-free adversarial replay of the *actual ordered 40-case inventory*.
# Synthetic logs verify the verifier only; do not credit guest test passes.
expected40="$root/tests/native-testing-regressions-40.expected-ids.txt"
[ "$(wc -l < "$expected40")" -eq 40 ]
summary40='ORES_TEST|SUMMARY|total=40|passed=40|failed=0|skipped=0|filtered=0'
emit40() {
  : > "$receipt"
  while IFS= read -r case_id; do
    printf 'ORES_TEST|PASS|stdlib/%s|\n' "$case_id" >> "$receipt"
  done < "$expected40"
  printf '%s\n' "$summary40" >> "$receipt"
}
reject40() {
  local label="$1"
  if bash "$verifier" "$receipt" stdlib 40 "$expected40" >/dev/null 2>&1; then
    printf 'FAIL: accepted 40-case mutation: %s\n' "$label" >&2
    exit 1
  fi
}
emit40
bash "$verifier" "$receipt" stdlib 40 "$expected40"
emit40
sed -i '0,/^ORES_TEST|PASS|stdlib\/truth\/true-accept|$/s#^ORES_TEST|PASS|stdlib/truth/true-accept|$#ORES_TEST|PASS|stdlib/truth/forged-substitution|#' "$receipt"
reject40 'substituted passing case with same 40 count'
emit40
sed -i 's#^ORES_TEST|PASS|stdlib/truth/true-reject|$#ORES_TEST|PASS|stdlib/truth/true-accept|#' "$receipt"
reject40 'duplicated passing case'
emit40
sed -i '/^ORES_TEST|PASS|stdlib\/outcome\/fail-message|$/d' "$receipt"
reject40 'one missing pass'
emit40
printf 'ORES_TEST|PASS|stdlib/outcome/extra-after-summary|\n' >> "$receipt"
reject40 'event after summary'
emit40
sed -i 's#^ORES_TEST|SUMMARY|total=40#ORES_TEST|SUMMARY|total=41#' "$receipt"
reject40 'inflated summary'
emit40
sed -i 's#^ORES_TEST|PASS|stdlib/truth/true-reject|$#ORES_TEST|PASS|stdlib/truth/true-reject|unexpected-detail#' "$receipt"
reject40 'passing case with nonempty failure detail'
printf 'PASS: 16 exact-case mutations, 16-case and 40-case pinned synthetic inventories, six extra 40-case negatives (source-free only)\n'

