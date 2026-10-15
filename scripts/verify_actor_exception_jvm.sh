#!/usr/bin/env bash
# Focused JUnit reproduction for Java/GraalVM actor Error and CONTROL behavior.
# A passing run does not prove Native Image or LLVM guest parity.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root"

if ! command -v mvn >/dev/null 2>&1; then
    printf 'Maven is required for the Java actor error fixtures.\n' >&2
    exit 2
fi
if ! command -v java >/dev/null 2>&1; then
    printf 'A supported Java toolchain is required.\n' >&2
    exit 2
fi

# Use --full for the entire JUnit suite; the default is the actor exception
# slice that CI needs to validate at the exact PR head.
if [[ "${1:-}" == "--full" && "$#" == 1 ]]; then
    exec mvn -B -ntp test
fi
if (($# != 0)); then
    printf 'usage: bash scripts/verify_actor_exception_jvm.sh [--full]\n' >&2
    exit 2
fi

exec mvn -B -ntp \
    '-Dtest=ActorTypedEffectControlTest,SourceActorTypedEffectExecutionTest,SourceActorOnThrowExecutionTest,TypedExceptionalEffectAdmissionTest,GuestThrowSuppressionAuditTest,ReservedControlDispatcherTest,ControlWakeRetryQueueTest,ActorIncarnationIdentityTest,NativeGuestErrorRootTest,TrapFailurePolicyTest' \
    test
