#!/usr/bin/env bash
# Shared source-only safety controls for the real OTel guest runner.
# This file does NOT compile guests, certify compilation, or invent stdout.
# The launch path must independently prove a real GraalVM compiler/guest.
otel_guardrails_class_digest() {
  local dir="${1:?class directory is required}" links digest
  [[ -d "$dir" && ! -L "$dir" &&
    -f "$dir/dev/oreslang/launcher/OresMain.class" ]] || return 1
  links="$(find "$dir" -type l -print -quit)" || return 1
  [[ -z "$links" ]] || return 1
  digest="$(find "$dir" -type f -print0 |
    LC_ALL=C sort -z |
    xargs -0 sha256sum |
    sha256sum | cut -d' ' -f1)" || return 1
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$digest"
}

otel_guardrails_run_guest() {
  local output="${1:?output directory required}" status=0
  shift
  [[ -d "$output" && ! -L "$output" && $# -gt 0 ]] || return 1
  # Prevent stale output from a previous run being reused or accidentally
  # presented as evidence after a compiler crash or timeout.
  rm -f -- "$output/guest.stdout.jsonl" "$output/guest.stderr.txt"
  (
    # Bash file-size units on Linux are 1024-byte blocks.
    ulimit -f 1024 || exit 1
    timeout --signal=TERM --kill-after=5s 30s "$@"
  ) > "$output/guest.stdout.jsonl" 2> "$output/guest.stderr.txt" || status=$?
  [[ $status -eq 0 ]] || return "$status"
  [[ -f "$output/guest.stdout.jsonl" && -f "$output/guest.stderr.txt" ]] || return 1
  [[ ! -L "$output/guest.stdout.jsonl" && ! -L "$output/guest.stderr.txt" ]] || return 1
  [[ "$(wc -c < "$output/guest.stdout.jsonl")" -le 1048576 ]] || return 1
  [[ "$(wc -c < "$output/guest.stderr.txt")" -le 1048576 ]] || return 1
}

# Hash Maven's actual dependency JAR bytes in the order loaded by -cp.
# A classpath.txt digest alone cannot detect a modified dependency JAR.
otel_guardrails_classpath_digest() {
  local manifest="${1:?classpath manifest required}" path hash evidence=''
  [[ -f "$manifest" && -s "$manifest" && ! -L "$manifest" ]] || return 1
  local -a jars=()
  local IFS=:
  read -r -a jars < "$manifest" || true
  [[ ${#jars[@]} -gt 0 && ${#jars[@]} -le 256 ]] || return 1
  for path in "${jars[@]}"; do
    [[ "$path" == /* && -f "$path" && -s "$path" && ! -L "$path" ]] || return 1
    [[ "$path" != *$'\n'* && "$path" != *$'\r'* ]] || return 1
    hash="$(sha256sum "$path" | cut -d' ' -f1)" || return 1
    [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    evidence+="$path:$hash;"
  done
  printf '%s' "$evidence" | sha256sum | cut -d' ' -f1
}

# A zero-step GitHub job or the deprecated --no-fallback command-line flag
# cannot prove AOT. Inspect the actual native host binary without executing it.
otel_guardrails_native_host_digest() {
  local executable="${1:?native executable required}" hdr dynamic hash
  [[ -f "$executable" && -x "$executable" && ! -L "$executable" ]] || return 1
  command -v readelf >/dev/null || return 1
  hdr="$(readelf -h "$executable")" || return 1
  grep -Eq 'Type:[[:space:]]+(EXEC|DYN)' <<< "$hdr" || return 1
  dynamic="$(readelf -d "$executable")" || return 1
  # A genuine Native Image host must not use the regular managed Java launcher.
  if grep -Eq 'Shared library: \[(libjvm|libjli|libjava)\.so' <<< "$dynamic"; then
    return 1
  fi
  hash="$(sha256sum "$executable" | cut -d' ' -f1)" || return 1
  [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$hash"
}
