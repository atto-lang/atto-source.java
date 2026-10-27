#!/usr/bin/env bash
set -euo pipefail
# Source-free tests of the native-image verification gate itself.
# A mock launcher tests the gate, not Oreslang guest execution.
root="$(cd "$(dirname "$0")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

cat > "$work/launch-success" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "--mode=aot" ]]
[[ "${2:-}" == "--platform=linux" ]]
case "${3:-}" in
  */future-pipeline-mixed.ores) printf '44\n' ;;
  */future-pipeline-consumed.ores) printf '22' ;;
  */future-pipeline-failure.ores) printf 'true' ;;
  *) exit 17 ;;
esac
MOCK
cat > "$work/launch-wrong-output" <<'MOCK'
#!/usr/bin/env bash
printf 'not-44\n'
MOCK
cat > "$work/launch-crashes" <<'MOCK'
#!/usr/bin/env bash
exit 37
MOCK
chmod +x "$work"/launch-*

assert_exit() {
  local expected="$1" actual
  shift
  if "$@" > "$work/stdout" 2> "$work/stderr"; then
    actual=0
  else
    actual=$?
  fi
  if [[ "$actual" != "$expected" ]]; then
    printf 'Unexpected exit status (expected %s, got %s)\n' "$expected" "$actual" >&2
    cat "$work/stdout" "$work/stderr" >&2
    exit 1
  fi
}

assert_exit 0 bash "$root/scripts/test-future-pipeline-native.sh" \
  "$work/launch-success" --mode=aot --platform=linux
grep -Fxq "future-pipeline-mixed: ok" "$work/stdout"
grep -Fxq "future-pipeline-consumed: ok" "$work/stdout"
grep -Fxq "future-pipeline-failure: ok" "$work/stdout"
printf 'valid three-fixture gate: ok\n'

assert_exit 2 bash "$root/scripts/test-future-pipeline-native.sh"
grep -Fq "usage:" "$work/stderr"
printf 'missing launcher rejected: ok\n'

assert_exit 1 bash "$root/scripts/test-future-pipeline-native.sh" \
  "$work/launch-wrong-output" --mode=aot --platform=linux
grep -Fq "Future pipeline mixed: unexpected stdout" "$work/stderr"
printf 'unexpected stdout rejected: ok\n'

assert_exit 37 bash "$root/scripts/test-future-pipeline-native.sh" \
  "$work/launch-crashes" --mode=aot --platform=linux
grep -Fq "Future pipeline mixed: launcher failed with exit status 37" "$work/stderr"
printf 'launcher crash reported and propagated: ok\n'

assert_exit 127 bash "$root/scripts/test-future-pipeline-native.sh" \
  "$work/does-not-exist" --mode=aot --platform=linux
grep -Fq "Future pipeline mixed: launcher failed with exit status 127" "$work/stderr"
printf 'missing native image rejected: ok\n'
