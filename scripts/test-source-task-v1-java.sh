#!/usr/bin/env bash
# Source-local real Java/GraalVM .atto guest execution, NOT a portable Task<T> pass.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
: "${EXPECTED_HEAD:?Pin exact Java/GraalVM source head}"
[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || exit 64
[[ "$(git -C "$root" rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || { echo 'wrong source checkout' >&2; exit 78; }
command -v timeout >/dev/null || { echo 'timeout missing' >&2; exit 69; }
java_bin="$(command -v java)" || { echo 'JDK 25 / GraalVM missing' >&2; exit 69; }
[[ "$java_bin" == /* ]] || exit 69
class_file="$root/target/classes/dev/oreslang/launcher/OresMain.class"
cp_file="$root/target/conformance-classpath.txt"
[[ -f "$class_file" && -s "$cp_file" ]] || { echo 'Build via Maven and resolve classpath first' >&2; exit 69; }
classpath="$root/target/classes:$(cat "$cp_file")"
jvm=("$java_bin" "-XX:+UnlockExperimentalVMOptions" "-XX:+EnableJVMCI"
     "--sun-misc-unsafe-memory-access=allow" "--enable-native-access=ALL-UNNAMED"
     "-cp" "$classpath" "dev.oreslang.launcher.OresMain")
tmp="$(mktemp -d "${TMPDIR:-/tmp}/atto-task-java.XXXXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT
for name in typed-int eager-before-await; do
  path="tests/fixtures/task-v1-java/$name.atto"
  src="$root/$path"
  [[ -f "$src" && ! -L "$src" ]] || { echo "Invalid source $path" >&2; exit 78; }
  [[ "$(git -C "$root" rev-parse "HEAD:$path")" == "$(git hash-object --no-filters "$src")" ]] || { echo "Fixture blob differs from HEAD: $path" >&2; exit 78; }
  cp -- "$src" "$tmp/$name.atto"
  source_sha="$(sha256sum "$src" | cut -d' ' -f1)"
  check_code=0
  timeout 90s "${jvm[@]}" --check "$src" > "$tmp/check-$name.out" 2> "$tmp/check-$name.err" || check_code=$?
  [[ "$check_code" == 0 ]] || { echo "$name compiler rejected code $check_code" >&2; cat "$tmp/check-$name.err" >&2; exit 1; }
  run_code=0
  timeout 90s "${jvm[@]}" "$src" > "$tmp/run-$name.out" 2> "$tmp/run-$name.err" || run_code=$?
  [[ "$run_code" == 0 && "$(cat "$tmp/run-$name.out")" == 42 ]] || { echo "$name guest failed exit=$run_code" >&2; cat "$tmp/run-$name.err" >&2; exit 1; }
  cmp -s "$src" "$tmp/$name.atto" || { echo "$name source modified" >&2; exit 1; }
  printf 'JAVA_FUTURE_TASK_ATTO_GUEST_PASS|head=%s|case=%s|guest_stdout=42|guest_exit=0|source_sha256=%s|launcher_sha256=%s\n' \
      "$EXPECTED_HEAD" "$name" "$source_sha" "$(sha256sum "$class_file" | cut -d' ' -f1)"
done

# Source-only compiler-negative checks. Never execute unsafe guest programs.
for name in reject-live-guard reject-moved-capture; do
  path="tests/fixtures/task-v1-java/$name.atto"
  src="$root/$path"
  [[ -f "$src" && ! -L "$src" ]] || { echo "Missing unsafe fixture $path" >&2; exit 78; }
  [[ "$(git -C "$root" rev-parse "HEAD:$path")" == "$(git hash-object --no-filters "$src")" ]] || { echo "Unsafe fixture differs from HEAD" >&2; exit 78; }
  result=0
  timeout 90s "${jvm[@]}" --check "$src" > "$tmp/check-$name.out" 2> "$tmp/check-$name.err" || result=$?
  [[ "$result" != 0 && "$result" != 124 ]] || { echo "Unsafe task admitted or timed out: $name" >&2; exit 1; }
  case "$name" in
    reject-live-guard) grep -Eiq "MutexGuard|mutex.?guard|live.*guard|guard.*capture" "$tmp/check-$name.err" ;;
    reject-moved-capture) grep -Eiq "moved|capture" "$tmp/check-$name.err" ;;
  esac || { echo "Wrong diagnostic for $name" >&2; cat "$tmp/check-$name.err" >&2; exit 1; }
  printf "JAVA_TASK_CAPTURE_REJECT_PASS|head=%s|case=%s|no_guest_execution=yes\n" "$EXPECTED_HEAD" "$name"
done
