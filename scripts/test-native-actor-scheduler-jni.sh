#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."

# No Maven/Truffle download: exercise the exact Java scheduler and native C
# kernel on the JVM. This supplements, but never replaces, real guest tests.
sh scripts/test-native-actor-jni.sh

mkdir -p target/native-jni/scheduler-classes
stub_dir="$(mktemp -d)"
trap 'rm -rf "$stub_dir"' EXIT HUP INT TERM

# OresFuture references ActorRuntime only for the *blocking host-read*
# actor-thread check. Its guest/compiler implementation must not be replaced
# in a full Maven run; this stub exists exclusively for isolated scheduler
# admission and JNI lifetime regression tests.
cat > "$stub_dir/ActorRuntime.java" <<'EOF'
package dev.oreslang.runtime;
final class ActorRuntime {
    private ActorRuntime() { }
    static boolean inActorExecution() { return false; }
}
EOF

javac -Xlint:all -Werror -sourcepath "$stub_dir" \
  -d target/native-jni/scheduler-classes \
  "$stub_dir/ActorRuntime.java" \
  src/main/java/dev/oreslang/interop/MixedInteropBridge.java \
  src/main/java/dev/oreslang/runtime/ActorTurnWatchdog.java \
  src/main/java/dev/oreslang/runtime/OresFuture.java \
  src/main/java/dev/oreslang/runtime/OresScheduler.java \
  src/main/java/dev/oreslang/runtime/NativeActorTurnKernel.java \
  src/test/java/dev/oreslang/runtime/NativeActorTurnSchedulerStandaloneSmoke.java

java -Dores.native.actor.turn.experimental=true \
  -Djava.library.path=target/native-jni/lib \
  -cp target/native-jni/scheduler-classes \
  dev.oreslang.runtime.NativeActorTurnSchedulerStandaloneSmoke
