#!/usr/bin/env sh
set -eu

if [ -z "${JAVA_HOME:-}" ]; then
  java_bin="$(command -v java || true)"
  if [ -z "$java_bin" ]; then
    echo "JAVA_HOME is not set and java is not on PATH" >&2
    exit 1
  fi
  if command -v realpath >/dev/null 2>&1; then
    java_bin="$(realpath "$java_bin")"
  fi
  JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
fi

os="$(uname -s)"
case "$os" in
  Linux)
    jni_os="linux"
    output="target/native/liboresipc.so"
    shared_flags="-shared"
    linker_hardening="-Wl,-z,relro,-z,now"
    ;;
  Darwin)
    jni_os="darwin"
    output="target/native/liboresipc.dylib"
    shared_flags="-dynamiclib"
    linker_hardening=""
    ;;
  *)
    echo "native IPC actor transport build currently supports Linux/macOS hosts; got $os" >&2
    exit 1
    ;;
esac

cc_bin="${CC:-cc}"
mkdir -p target/native

"$cc_bin" \
  -std=c11 \
  -O2 \
  -fPIC \
  -pthread \
  -Wall \
  -Wextra \
  -Werror \
  -fstack-protector-strong \
  -fno-omit-frame-pointer \
  $shared_flags \
  $linker_hardening \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/$jni_os" \
  src/main/c/oresipc.c \
  -o "$output"

echo "built $output"
