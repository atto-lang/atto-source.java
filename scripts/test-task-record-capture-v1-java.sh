#!/usr/bin/env bash
# Exact-head, real Java compiler / Graal guest tests. Not a cross-backend parity receipt.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
: "${EXPECTED_HEAD:?Pin the exact source commit SHA}"
[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || exit 64
[[ "$(git -C "$root" rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || {
  echo 'wrong source checkout' >&2; exit 78;
}
for required_tool in java timeout git sha256sum; do
  command -v "$required_tool" >/dev/null || { echo "missing $required_tool" >&2; exit 69; }
done
java_bin="$(command -v java)"
[[ "$java_bin" == /* ]] || { echo 'Java binary must be an absolute path' >&2; exit 69; }
[[ -f "$root/target/classes/dev/oreslang/launcher/OresMain.class" ]] || {
  echo 'Build Java classes before running the gate' >&2; exit 69;
}
[[ -s "$root/target/conformance-classpath.txt" ]] || {
  echo 'Resolve target/conformance-classpath.txt before running the gate' >&2; exit 69;
}
classpath="$root/target/classes:$(cat "$root/target/conformance-classpath.txt")"
launcher_class="$root/target/classes/dev/oreslang/launcher/OresMain.class"
launcher_sha256="$(sha256sum "$launcher_class" | cut -d ' ' -f 1)"
jvm=("$java_bin" "-XX:+UnlockExperimentalVMOptions" "-XX:+EnableJVMCI"
  "--sun-misc-unsafe-memory-access=allow" "--enable-native-access=ALL-UNNAMED"
  -cp "$classpath" dev.oreslang.launcher.OresMain)
tmp="$(mktemp -d "${TMPDIR:-/tmp}/atto-record-capture.XXXXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT
"$java_bin" -version > "$tmp/java-version.txt" 2>&1 || exit 69
grep -Eq '^(openjdk|java) version "25([."]|$)' "$tmp/java-version.txt" || {
  echo 'A JDK 25 runtime is required for this exact-head gate' >&2
  cat "$tmp/java-version.txt" >&2; exit 69
}
grep -iq 'GraalVM' "$tmp/java-version.txt" || {
  echo 'A verified GraalVM JDK 25 is required' >&2; exit 69
}
printf 'JAVA_TASK_RECORD_TOOLCHAIN|head=%s|launcher_class_sha256=%s|java_bin=%s\n' \
  "$EXPECTED_HEAD" "$launcher_sha256" "$java_bin"

fixture() {
  local name="$1"
  local path="tests/fixtures/task-record-capture-v1/$name.atto"
  local full="$root/$path"
  [[ -f "$full" && ! -L "$full" ]] || { echo "missing/nonregular fixture: $path" >&2; exit 78; }
  [[ "$(git -C "$root" rev-parse "HEAD:$path")" == "$(git hash-object --no-filters "$full")" ]] || {
    echo "fixture does not match exact source head: $path" >&2; exit 78;
  }
  printf '%s' "$full"
}

for name in owned-struct-task-accept scalar-copy-task-guest; do
  src="$(fixture "$name")"
  status=0
  timeout 90s "${jvm[@]}" --check "$src" > "$tmp/$name.out" 2> "$tmp/$name.err" || status=$?
  [[ "$status" == 0 ]] || {
    echo "positive source rejected: $name status=$status" >&2; cat "$tmp/$name.err" >&2; exit 1;
  }
  printf 'JAVA_TASK_RECORD_COMPILER_ACCEPT|head=%s|case=%s|source_sha256=%s\n' \
    "$EXPECTED_HEAD" "$name" "$(sha256sum "$src" | cut -d ' ' -f 1)"
done

# True guest positive canary (the owned-struct fixture above is admission-only).
src="$(fixture scalar-copy-task-guest)"
status=0
timeout 90s "${jvm[@]}" "$src" > "$tmp/guest.out" 2> "$tmp/guest.err" || status=$?
[[ "$status" == 0 && "$(cat "$tmp/guest.out")" == "42" ]] || {
  echo "Graal guest did not produce 42: status=$status" >&2
  cat "$tmp/guest.err" >&2; exit 1;
}
printf 'JAVA_TASK_RECORD_GUEST_PASS|head=%s|case=scalar-copy-task-guest|stdout=42|exit=0|launcher_class_sha256=%s\n' "$EXPECTED_HEAD" "$launcher_sha256"

# Negative must fail on the actual semantic Task Send gate, not on parser grammar,
# unsupported lowering, missing files, or an unexecuted timeout.
src="$(fixture readonly-record-task-reject)"
status=0
timeout 90s "${jvm[@]}" --check "$src" > "$tmp/reject.out" 2> "$tmp/reject.err" || status=$?
[[ "$status" != 0 && "$status" != 124 && "$status" != 137 ]] || {
  echo "readonly view was admitted, timed out or killed: status=$status" >&2; exit 1;
}
grep -Eiq "independent task capture 'view'.*non-Send execution capability" "$tmp/reject.err" || {
  echo "wrong negative diagnostic; do not count a parser rejection" >&2
  cat "$tmp/reject.err" >&2; exit 1;
}
[[ ! -s "$tmp/reject.out" ]] || { echo "rejected compiler unexpectedly emitted output" >&2; exit 1; }
printf 'JAVA_TASK_RECORD_COMPILER_REJECT|head=%s|case=readonly-record-task-reject|semantic=non-send|no_guest_execution=yes|source_sha256=%s|launcher_class_sha256=%s\n' "$EXPECTED_HEAD" "$(sha256sum "$src" | cut -d ' ' -f 1)" "$launcher_sha256"
