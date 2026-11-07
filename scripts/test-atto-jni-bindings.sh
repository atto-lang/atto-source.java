#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
java="src/main/java/dev/atto/runtime/NativeCarrierExecutor.java"
native="src/main/c/oresthread.c"
verify="scripts/verify-atto-jni-bindings.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

bash "$verify" "$java" "$native"

# A renamed class prefix, a missing method, and a duplicate export must all fail closed.
sed 's/Java_dev_atto_runtime_NativeCarrierExecutor_nativeStart/Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeStart/g' "$native" > "$tmp/legacy.c"
if bash "$verify" "$java" "$tmp/legacy.c" >"$tmp/legacy.log" 2>&1; then
  echo "::error::Legacy JNI rename mutation survived" >&2
  exit 1
fi

sed 's/Java_dev_atto_runtime_NativeCarrierExecutor_nativeStart/Java_dev_atto_runtime_NativeCarrierExecutor_missingStart/g' "$native" > "$tmp/missing.c"
if bash "$verify" "$java" "$tmp/missing.c" >"$tmp/missing.log" 2>&1; then
  echo "::error::Missing JNI method mutation survived" >&2
  exit 1
fi

cp "$native" "$tmp/duplicate.c"
printf '\n/* duplicate symbol negative canary: Java_dev_atto_runtime_NativeCarrierExecutor_nativeStart */\n' >> "$tmp/duplicate.c"
if bash "$verify" "$java" "$tmp/duplicate.c" >"$tmp/duplicate.log" 2>&1; then
  echo "::error::Duplicate JNI export mutation survived" >&2
  exit 1
fi

sed 's/private static native int nativeCurrentCpu()/private static int nativeCurrentCpu()/g' "$java" > "$tmp/declaration.java"
if bash "$verify" "$tmp/declaration.java" "$native" >"$tmp/declaration.log" 2>&1; then
  echo "::error::Missing Java native declaration mutation survived" >&2
  exit 1
fi

echo "JNI bridge negative mutations rejected: 4/4"
