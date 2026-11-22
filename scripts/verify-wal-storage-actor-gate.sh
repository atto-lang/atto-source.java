#!/bin/sh
# Validates a host-internal WAL gate against strict actor API shims, NOT actual Graal guest execution.
# Caller must independently pin Git commit/tree and Java/Javac binaries, as in inherited host test.
set -eu
export LC_ALL=C
sh scripts/verify-wal-storage-file-owner.sh

# Verify that the *current repository* retains the exact ActorRuntime and
# OresContext API symbols used by the gate. This is only source-shape checking:
# a full Truffle/Maven compilation remains a separate mandatory gate.
actor=src/main/java/dev/oreslang/runtime/ActorRuntime.java
context=src/main/java/dev/oreslang/runtime/OresContext.java
for signature in \
    'public static boolean inActorExecution()' \
    'public static boolean isActorCarrierThread()' \
    'public static ActorRuntime currentActorRuntime()' \
    'public static Optional<ActorId> currentActorId()' \
    'public static Object currentActorExecutionDomain()' \
    'public static ActorKind currentActorKind()' \
    'static StorageActorProof mintStorageActorProof()'; do
  grep -Fq "$signature" "$actor" || { echo "actor API absent: $signature" >&2; exit 1; }
done
for signature in 'public ActorRuntime actors()' 'public void requirePermission('; do
  grep -Fq "$signature" "$context" || { echo "context API absent: $signature" >&2; exit 1; }
done
command -v timeout >/dev/null 2>&1
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
"$DB_JAVAC_BIN" -Xlint:all -Werror -d "$scratch" \
  tests/host/wal-gate-stubs/dev/oreslang/runtime/ActorRuntime.java \
  tests/host/wal-gate-stubs/dev/oreslang/runtime/RuntimePermissions.java \
  tests/host/wal-gate-stubs/dev/oreslang/runtime/OresContext.java \
  src/main/java/dev/oreslang/runtime/WalStorageFileOwner.java \
  src/main/java/dev/oreslang/runtime/WalStorageActorGate.java \
  tests/host/WalStorageActorGateHostTest.java
# No stdout other than exact oracle, and no opaque failure swallowed by shell.
timeout 30s "$DB_JAVA_BIN" -cp "$scratch" \
  dev.oreslang.runtime.WalStorageActorGateHostTest >"$scratch/out" 2>"$scratch/err" || {
  cat "$scratch/err" >&2; exit 1;
}
printf '%s\n' 'WAL_ACTOR_GATE_SHIM_PASS checks=29; NOT ACTUAL ACTOR OR GUEST' >"$scratch/expected"
cmp -s "$scratch/out" "$scratch/expected" || { cat "$scratch/out" >&2; exit 1; }
printf 'HOST_ACTOR_SHIM_CHECKS=29 NO_TRUFFLE_BUILD NO_GUEST_EXECUTION\n'
