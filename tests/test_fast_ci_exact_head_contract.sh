#!/usr/bin/env bash
# Source-free fast-CI contract checks; never credit these as guest execution.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="${1:-$root/.github/workflows/fast-ci.yml}"
[[ -f "$workflow" ]] || { echo "missing fast-ci workflow" >&2; exit 1; }

require_one() {
  local needle="$1" count
  count="$(grep -Fxc -- "$needle" "$workflow" || true)"
  [[ "$count" -eq 1 ]] || { printf 'expected exactly one line: %s (found %s)\n' "$needle" "$count" >&2; exit 1; }
}
line_of() {
  grep -nFx -- "$1" "$workflow" | head -n 1 | cut -d: -f1
}
require_one '          ref: ${{ github.event.pull_request.head.sha || github.sha }}'
require_one '          EXPECTED_HEAD: ${{ github.event.pull_request.head.sha || github.sha }}'
require_one '          [[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]]'
require_one '          test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"'
require_one '          git diff --quiet --exit-code'
require_one '          test -z "$(git ls-files --others --exclude-standard)"'
require_one '      - name: Verify exact source revision'
require_one '      - name: Verify fast-CI exact-head contract (source-free)'
require_one '        run: bash tests/test_fast_ci_exact_head_contract.sh'
require_one '          bash scripts/verify_ores_test_receipts.sh /tmp/ores-regressions-40-fast-jit.out stdlib 40 tests/native-testing-regressions-40.expected-ids.txt'

checkout="$(line_of '          ref: ${{ github.event.pull_request.head.sha || github.sha }}')"
exact="$(line_of '      - name: Verify exact source revision')"
contract="$(line_of '      - name: Verify fast-CI exact-head contract (source-free)')"
compiler="$(line_of '      - name: Verify compiler/runtime tests')"
[[ "$checkout" -lt "$exact" && "$exact" -lt "$contract" && "$contract" -lt "$compiler" ]] || {
  echo "source verification must precede compiler execution" >&2; exit 1;
}
if grep -Fq 'grep -Fqx "ORES_TEST|SUMMARY|total=40|' "$workflow"; then
  echo "weak 40-case summary grep remains" >&2; exit 1
fi

# Mutation controls ensure the checker fails if a lease or receipt gate disappears.
if [[ $# -eq 0 ]]; then
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  cp "$workflow" "$tmp"
  sed -i '/^          ref: /d' "$tmp"
  if bash "$0" "$tmp" >/dev/null 2>&1; then echo "accepted missing pinned ref" >&2; exit 1; fi
  cp "$workflow" "$tmp"
  sed -i '/^          test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"$/d' "$tmp"
  if bash "$0" "$tmp" >/dev/null 2>&1; then echo "accepted missing exact-head comparison" >&2; exit 1; fi
  cp "$workflow" "$tmp"
  sed -i 's# stdlib 40 tests/native-testing-regressions-40.expected-ids.txt# stdlib 40#' "$tmp"
  if bash "$0" "$tmp" >/dev/null 2>&1; then echo "accepted count-only 40-case verification" >&2; exit 1; fi
  cp "$workflow" "$tmp"
  sed -i '/bash scripts\/verify_ores_test_receipts.sh \/tmp\/ores-regressions-40-fast-jit.out stdlib 40 tests\/native-testing-regressions-40.expected-ids.txt/d' "$tmp"
  if bash "$0" "$tmp" >/dev/null 2>&1; then echo "accepted missing 40-case verifier" >&2; exit 1; fi
fi
echo "PASS: fast-CI exact-head and 40-case receipt contract"
