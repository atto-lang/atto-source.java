#!/usr/bin/env bash
# Validate native ORES_TEST machine receipts from one compiler-backed guest run.
# Format, count, optional exact case IDs only; it does NOT authenticate the emitter.
set -euo pipefail
if [[ ( $# -ne 3 && $# -ne 4 ) || ! -f "${1:-}" || ! "${2:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ || ! "${3:-}" =~ ^[1-9][0-9]*$ || ( $# -eq 4 && ! -f "${4:-}" ) ]]; then
  echo 'usage: verify_ores_test_receipts.sh OUTPUT_FILE SUITE_NAME POSITIVE_EXPECTED_COUNT [EXPECTED_CASE_IDS_FILE]' >&2
  exit 2
fi
LC_ALL=C awk -F '|' -v suite="$2" -v expected="$3" -v expected_file="${4:-}" '
  BEGIN {
    summary = "ORES_TEST|SUMMARY|total=" expected "|passed=" expected "|failed=0|skipped=0|filtered=0"
    prefix = suite "/"
    if (expected_file != "") {
      while ((result = getline id < expected_file) > 0) {
        # File is one *unescaped* ASCII case name per line. No blank
        # lines, duplicates, delimiters, controls or leading/trailing spaces.
        if (id !~ /^[A-Za-z0-9][A-Za-z0-9._/-]*$/ ||
            allowed[prefix id]++) invalid_ids = 1
        listed++
      }
      if (result < 0 || listed != expected) invalid_ids = 1
      close(expected_file)
    }
  }
  # Exactly the escaping vocabulary of stdlib/testing.ores. Reject lone or
  # unknown escapes rather than letting distinct case IDs decode identically.
  function canonical_field(value, i, next_ch) {
    for (i=1; i<=length(value); i++) {
      if (substr(value,i,1)!="\\") continue
      next_ch=substr(value,i+1,1)
      if (next_ch=="\\" || next_ch=="r" || next_ch=="n" || next_ch=="t") i++
      else if (substr(value,i+1,5)=="u007c" || substr(value,i+1,5)=="u0000") i+=5
      else return 0
    }
    return 1
  }
  # A marker hidden behind Maven text, ANSI/control bytes or whitespace is
  # not an ordinary log line: reject it rather than silently ignoring it.
  /^ORES_TEST/ || index($0, "ORES_TEST|") {
    if (saw_summary || $0 ~ /[[:cntrl:]]/) { bad = 1; next }
    if ($0 == summary) { saw_summary = 1; next }
    if (NF != 4 || $1 != "ORES_TEST" || $2 != "PASS" || index($3, prefix) != 1 || length($3) <= length(prefix) || $4 != "" || !canonical_field($3)) {
      bad = 1; next
    }
    if (seen[$3]++) bad = 1
    if (expected_file != "" && !allowed[$3]) bad = 1
    pass_count++
  }
  END {
    if (bad || invalid_ids || !saw_summary || pass_count != expected) {
      print "invalid ORES_TEST receipts: malformed, duplicated, missing or misordered events" > "/dev/stderr"
      exit 1
    }
  }
' "$1"
