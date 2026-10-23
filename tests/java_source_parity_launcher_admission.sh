#!/usr/bin/env bash
# Source-free launcher admission test; NOT a Java/Graal guest execution test.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd -P)"
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
mkdir -p "$sandbox/scripts" "$sandbox/fixtures"
cp "$root/scripts/run-source-parity.sh" "$sandbox/scripts/run-source-parity.sh"
chmod +x "$sandbox/scripts/run-source-parity.sh"
bash -n "$sandbox/scripts/run-source-parity.sh"
launcher="$sandbox/scripts/run-source-parity.sh"

expect_failure() {
  local name="$1" expected="$2" message="$3"
  shift 3
  local observed=0
  "$launcher" "$@" > "$sandbox/stdout" 2> "$sandbox/stderr" || observed="$?"
  if [[ "$observed" != "$expected" ]] || ! grep -Fq "$message" "$sandbox/stderr"; then
    echo "FAIL $name: expected code $expected, got $observed" >&2
    cat "$sandbox/stderr" >&2
    exit 1
  fi
  if [[ -s "$sandbox/stdout" ]]; then
    echo "FAIL $name: denial wrote to stdout" >&2
    exit 1
  fi
  echo "PASS Java launcher admission: $name"
}

expect_failure "missing file argument" 64 "usage:"
expect_failure "nonexistent source file" 66 "expected a readable .ores source file" "$sandbox/fixtures/absent.ores"
printf 'pub fnc main():int{return 0;}\n' > "$sandbox/fixtures/main.ores"
expect_failure "missing built Java runtime" 69 "Java runtime not built" "$sandbox/fixtures/main.ores"
echo "PASS synthetic Java launcher admission; no guest execution inferred"
