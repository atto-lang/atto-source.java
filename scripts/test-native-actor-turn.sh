#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  Linux|Darwin) ;;
  *) echo "native actor turn test requires Linux or macOS" >&2; exit 1 ;;
esac
mkdir -p target/native
sanitizers=""
if [ "${ORES_NATIVE_SANITIZE:-0}" = 1 ]; then
  sanitizers="-fsanitize=address,undefined -fno-omit-frame-pointer"
fi
for opt in 0 2; do
  "${CC:-cc}" -std=c11 "-O$opt" -g -Wall -Wextra -Werror -pedantic \
    -fstack-protector-strong -pthread $sanitizers -Isrc/main/c \
    src/main/c/oresactorturn.c src/test/c/oresactorturn_test.c \
    -o "target/native/oresactorturn-test-O$opt"
  "target/native/oresactorturn-test-O$opt"
done
