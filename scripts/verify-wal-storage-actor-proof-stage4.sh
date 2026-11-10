#!/bin/sh
# Stage 4; keep host-shim proof and REAL ActorRuntime Maven proof separate.
# Source, toolchain and tree must be pinned by the inherited host verifier.
set -eu
export LC_ALL=C
sh scripts/verify-wal-storage-actor-gate.sh
command -v mvn >/dev/null 2>&1 || {
  echo 'NOT_EXECUTED: Maven/Graal build unavailable; cannot count real-runtime test' >&2
  exit 1
}
# Fail closed on missing dependencies or absence of the real ActorRuntime test.
test -f src/test/java/dev/oreslang/runtime/WalStorageActorProofRuntimeTest.java
mvn -B -ntp -Dtest=WalStorageActorProofRuntimeTest test
printf 'WAL_REAL_ACTOR_RUNTIME_JUNIT_EXECUTED; NOT_GUEST_NOT_DURABILITY\n'
