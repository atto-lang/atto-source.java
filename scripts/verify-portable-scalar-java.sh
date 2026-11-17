#!/usr/bin/env bash
# Canonical native .ores scalar guest execution using pinned JVM/Graal language frontend.
# --mode=aot disables guest JIT; this is JVM-host execution, not Native Image.
set -euo pipefail
export LC_ALL=C
die() { printf 'JAVA_PORTABLE_SCALAR_NOT_ACCEPTED: %s\n' "$*" >&2; exit 1; }
cd "$(dirname "$0")/.."
[[ "${EXPECTED_SOURCE_SHA:-}" =~ ^[a-f0-9]{40}$ ]] || die "missing expected exact-head Git SHA"
[[ "$(git rev-parse HEAD)" == "$EXPECTED_SOURCE_SHA" ]] || die "source head mismatch"
[[ -d tests/portable-scalar && ! -L tests/portable-scalar ]] || die "nonregular corpus directory"
[[ -z "$(git status --porcelain --untracked-files=normal -- tests/portable-scalar)" ]] || die "dirty Oreslang source fixtures"
jar="target/oreslang-source-0.1.0-SNAPSHOT.jar"
native="target/native/liboresthread.so"
deps="target/conformance-classpath.txt"
[[ -s "$jar" && -s "$native" && -s "$deps" ]] || die "real compiled Java compiler/runtime artifacts missing"
java="$(command -v java)" || die "JDK missing"
[[ -x "$java" ]] || die "Java executable not found"
classpath="$PWD/$jar:$(cat "$deps")"
jar_sha="$(sha256sum "$jar" | cut -d ' ' -f 1)"
native_sha="$(sha256sum "$native" | cut -d ' ' -f 1)"
java_sha="$(sha256sum "$java" | cut -d ' ' -f 1)"
limit="$(command -v timeout || command -v gtimeout || true)"
[[ -n "$limit" ]] || die "bounded JVM guest execution unavailable"
tmp="$(mktemp -d)" || die "cannot allocate bounded guest scratch"
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"
count=0
: > "$tmp/seen"
while IFS='|' read -r source expected blob extra || [[ -n "$source" ]]; do
  [[ -z "$extra" && "$source" =~ ^tests/portable-scalar/[0-9]{2}-[a-z0-9-]+\.ores$ ]] || die "invalid portable source identity"
  [[ "$expected" == 42 && "$blob" =~ ^[a-f0-9]{40}$ ]] || die "invalid scalar oracle/blob"
  [[ -f "$source" && ! -L "$source" ]] || die "nonregular .ores source"
  git ls-files --error-unmatch -- "$source" >/dev/null || die "untracked .ores source"
  ! grep -Fqx "$source" "$tmp/seen" || die "duplicate .ores source"
  printf '%s\n' "$source" >> "$tmp/seen"
  ((++count <= 6)) || die "too many cases"
  [[ "$(git rev-parse "HEAD:$source")" == "$blob" ]] || die "reviewed Git blob mismatch"
  [[ "$(git hash-object -- "$source")" == "$blob" ]] || die "fixture bytes changed"
  launcher=(env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS \
    "$java" --enable-native-access=ALL-UNNAMED \
    -Dpolyglot.engine.WarnInterpreterOnly=false \
    "-Dores.thread.native.path=$PWD/$native" -cp "$classpath" \
    dev.oreslang.launcher.OresMain --mode=aot)
  "$limit" -k 3s 30s "${launcher[@]}" --check "$source" > "$tmp/check.stdout" 2> "$tmp/check.stderr" || {
    cat "$tmp/check.stderr" >&2
    die "native Oreslang compiler/type checker rejected source: $source"
  }
  set +e
  "$limit" -k 3s 30s "${launcher[@]}" "$source" > "$tmp/guest.stdout" 2> "$tmp/guest.stderr"
  code=$?
  set -e
  [[ "$code" -eq "$expected" ]] || die "JVM guest returned $code instead of $expected"
  [[ ! -s "$tmp/guest.stdout" && ! -s "$tmp/guest.stderr" ]] || die "unexpected guest output"
  [[ "$(git hash-object -- "$source")" == "$blob" ]] || die "source mutated while executing"
  [[ "$(sha256sum "$jar" | cut -d ' ' -f 1)" == "$jar_sha" ]] || die "compiler JAR changed during run"
  [[ "$(sha256sum "$native" | cut -d ' ' -f 1)" == "$native_sha" ]] || die "native carrier library changed"
  [[ "$(sha256sum "$java" | cut -d ' ' -f 1)" == "$java_sha" ]] || die "JVM changed"
  printf 'JAVA_PORTABLE_SCALAR_PASS source=%s git_blob=%s\n' "$source" "$blob"
done < tests/portable-scalar/oracles.tsv
[[ "$count" -eq 6 ]] || die "missing scalar cases"
[[ "$(git ls-files 'tests/portable-scalar/*.ores' | wc -l | tr -d ' ')" == 6 ]] || die "unmanifested .ores sources"
[[ -z "$(git status --porcelain --untracked-files=normal -- tests/portable-scalar)" ]] || die "checkout changed during JVM execution"
printf 'JAVA_PORTABLE_SCALAR_ACCEPTED source_sha=%s compiler_jar_sha256=%s cases=6\n' "$EXPECTED_SOURCE_SHA" "$jar_sha"
