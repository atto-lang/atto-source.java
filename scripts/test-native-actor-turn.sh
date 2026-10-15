#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  Linux|Darwin) ;;
  *) echo "native actor turn test requires Linux or macOS" >&2; exit 1 ;;
esac
mkdir -p target/native
sanitizers=""
if [ "${ORES_NATIVE_SANITIZE:-0}" = 1 ] && [ "${ORES_NATIVE_TSAN:-0}" = 1 ]; then
  echo "Cannot combine AddressSanitizer and ThreadSanitizer in one test executable" >&2
  exit 1
fi
if [ "${ORES_NATIVE_SANITIZE:-0}" = 1 ]; then
  sanitizers="-fsanitize=address,undefined -fno-omit-frame-pointer"
elif [ "${ORES_NATIVE_TSAN:-0}" = 1 ]; then
  sanitizers="-fsanitize=thread -fno-omit-frame-pointer"
fi
for opt in 0 2; do
  for suite in oresactorturn_test oresactorturn_scheduler_boundary_test; do
    # Each suite has its own main() and links the exact same native kernel.
    # The second suite proves the VM-only ABI contract, not Java/LLVM parity.
    "${CC:-cc}" -std=c11 "-O$opt" -g -Wall -Wextra -Werror -pedantic \
      -fstack-protector-strong -pthread $sanitizers -Isrc/main/c \
      src/main/c/oresactorturn.c "src/test/c/$suite.c" \
      -o "target/native/$suite-O$opt"
    "target/native/$suite-O$opt"
  done
done
