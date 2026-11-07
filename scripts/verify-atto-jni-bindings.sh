#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
java_source="${1:-src/main/java/dev/atto/runtime/NativeCarrierExecutor.java}"
native_source="${2:-src/main/c/oresthread.c}"
test -f "$java_source"
test -f "$native_source"

# A declaration without a matching exported JNI symbol compiles but fails at load time.
# Compare complete sorted multisets: duplicates, missing methods, and legacy names fail.
mapfile -t declared < <(
  sed -nE 's/^.*[[:space:]]native[[:space:]]+[^[:space:]]+[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*\(.*/\1/p' "$java_source" |
    LC_ALL=C sort
)
(( ${#declared[@]} >= 10 )) || {
  echo "::error::Unexpectedly missing Java native declarations" >&2
  exit 1
}

mapfile -t exported < <(
  grep -oE 'Java_[A-Za-z0-9_]+_NativeCarrierExecutor_[A-Za-z0-9_]+' "$native_source" |
    LC_ALL=C sort
)
(( ${#exported[@]} > 0 )) || {
  echo "::error::Missing JNI exports" >&2
  exit 1
}
mapfile -t expected < <(
  printf 'Java_dev_atto_runtime_NativeCarrierExecutor_%s\n' "${declared[@]}" | LC_ALL=C sort
)

if ! diff -u <(printf '%s\n' "${expected[@]}") <(printf '%s\n' "${exported[@]}"); then
  echo "::error::Native JNI exports do not match the dev.atto runtime class" >&2
  exit 1
fi
if grep -Fq 'Java_dev_oreslang_' "$native_source"; then
  echo "::error::Legacy JNI class symbols remain" >&2
  exit 1
fi
echo "JNI bridge verified: ${#declared[@]} Java native methods and ${#exported[@]} C exports"
