#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != Linux ]; then
  echo 'JNI actor-turn smoke currently requires Linux and a JDK 21+ toolchain' >&2
  exit 1
fi
if ! command -v javac >/dev/null 2>&1; then
  echo 'JDK javac is required' >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
  echo 'JAVA_HOME must point to a full JDK installation' >&2
  exit 1
fi
mkdir -p target/native-jni/classes target/native-jni/lib
javac -Xlint:all -Werror -d target/native-jni/classes \
  src/main/java/dev/oreslang/runtime/NativeActorTurnKernel.java \
  src/test/java/dev/oreslang/runtime/NativeActorTurnKernelStandaloneSmoke.java
"${CC:-cc}" -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic \
  -fstack-protector-strong -fPIC -shared -pthread \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -Isrc/main/c \
  src/main/c/oresactorturn.c src/main/c/oresactorturnjni.c \
  -o target/native-jni/lib/liboresactorturnjni.so
java -Djava.library.path=target/native-jni/lib -cp target/native-jni/classes \
  dev.oreslang.runtime.NativeActorTurnKernelStandaloneSmoke
