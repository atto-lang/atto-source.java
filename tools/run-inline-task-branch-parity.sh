#!/usr/bin/env bash
# Identical .NET source fixtures; independent from the original four-case gate.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
test "$(git rev-parse HEAD)" = "${EXPECTED_HEAD:?}"
head=$(git rev-parse HEAD)
tree=$(git rev-parse 'HEAD^{tree}')
mode=${1:?mode required}
case "$mode" in
  jvm)
    entry=target/classes/dev/oreslang/launcher/OresMain.class
    command=(java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -cp "target/classes:$(cat target/conformance-classpath.txt)" dev.oreslang.launcher.OresMain)
    ;;
  native)
    entry=target/ores-aot
    test -x "$entry"
    command=("$PWD/$entry")
    ;;
  *) echo "Unknown mode: $mode" >&2; exit 2 ;;
esac
test -f "$entry" && test ! -L "$entry"
entry_digest=$(shasum -a 256 "$entry" | cut -d' ' -f1)
if command -v timeout >/dev/null; then
  limit=timeout
elif command -v gtimeout >/dev/null; then
  limit=gtimeout
else
  echo "No bounded subprocess runner available" >&2
  exit 2
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
passed=0
total=0
while read -r name blob; do
  total=$((total+1))
  source="tests/fixtures/inline-task-net-parity/$name.atto"
  test -f "$source" && test ! -L "$source"
  test "$(git hash-object "$source")" = "$blob"
  test "$(git rev-parse "HEAD:$source")" = "$blob"
  check=0
  run=not_executed
  "$limit" 90 "${command[@]}" --no-prompt --check "$source" >"$work/check.out" 2>"$work/check.err" || check=$?
  : > "$work/run.out"
  : > "$work/run.err"
  if [[ "$check" == 0 ]]; then
    run=0
    "$limit" 90 "${command[@]}" --no-prompt "$source" >"$work/run.out" 2>"$work/run.err" || run=$?
  fi
  test "$(git hash-object "$source")" = "$blob"
  printf 'case=%s head=%s tree=%s source_blob=%s mode=%s platform=%s entry_sha256=%s check=%s run=%s\n' "$name" "$head" "$tree" "$blob" "$mode" "$(uname -sm)" "$entry_digest" "$check" "$run"
  for stream in check.out check.err run.out run.err; do
    printf '%s:\n' "$stream"
    cat "$work/$stream"
    printf '\n'
  done
  if [[ "$check" == 0 && "$run" == 0 && "$(cat "$work/run.out")" == 42 ]]; then
    passed=$((passed+1))
  fi
done <<'CASES'
inline-shortcircuit-writeback adea3e544fd92d4b79387f7dc9881e36ecae65f3
inline-conditional-writeback 84c0e81f83361e00be1f3441fc4171546654609f
inline-match-writeback 2a85a1d585bb8b3ff98afcea5d0f201c911c8de3
inline-ternary-writeback 3c756a785b22eea6b3d076f1c506c642dce4a839
CASES
printf 'INLINE_TASK_BRANCH_WRITEBACK_JAVA %d/%d\n' "$passed" "$total"
test "$total" = 4 && test "$passed" = "$total"
