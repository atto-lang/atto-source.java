#!/usr/bin/env bash
# Validate native ORES_TEST machine receipts from one compiler-backed guest run.
# This checks format and accounting only; it does NOT authenticate the emitter.
set -euo pipefail
if [[ $# -ne 3 || ! -f "${1:-}" || ! "${2:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ || ! "${3:-}" =~ ^[1-9][0-9]*$ ]]; then
  echo 'usage: verify_ores_test_receipts.sh OUTPUT_FILE SUITE_NAME POSITIVE_EXPECTED_COUNT' >&2
  exit 2
fi
LC_ALL=C awk -F '|' -v suite="$2" -v expected="$3" '
  BEGIN {
    summary = "ORES_TEST|SUMMARY|total=" expected "|passed=" expected "|failed=0|skipped=0|filtered=0"
    prefix = suite "/"
  }
  # A marker hidden behind Maven text, ANSI/control bytes or whitespace is
  # not an ordinary log line: reject it rather than silently ignoring it.
  /^ORES_TEST/ || index($0, "ORES_TEST|") {
    if (saw_summary || $0 ~ /[[:cntrl:]]/) { bad = 1; next }
    if ($0 == summary) { saw_summary = 1; next }
    if (NF != 4 || $1 != "ORES_TEST" || $2 != "PASS" || index($3, prefix) != 1 || length($3) <= length(prefix) || $4 != "") {
      bad = 1; next
    }
    if (seen[$3]++) bad = 1
    pass_count++
  }
  END {
    if (bad || !saw_summary || pass_count != expected) {
      print "invalid ORES_TEST receipts: malformed, duplicated, missing or misordered events" > "/dev/stderr"
      exit 1
    }
  }
' "$1"
