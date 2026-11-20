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
# The LLVM differential gate requires a committed regular executable script.
# Running the launcher via bash would conceal a broken 100644 Git file mode.
source_mode="$(git -C "$root" ls-files --stage -- scripts/run-source-parity.sh)"
if [[ ! "$source_mode" =~ ^100755[[:space:]] ]]; then
  echo "FAIL: source parity launcher must be tracked as Git mode 100755" >&2
  exit 1
fi
if [[ ! -x "$root/scripts/run-source-parity.sh" ]]; then
  echo "FAIL: source parity launcher is not executable" >&2
  exit 1
fi

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
