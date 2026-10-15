#!/usr/bin/env bash
# Focused JUnit reproduction for Java/GraalVM actor Error and CONTROL behavior.
# A passing run does not prove Native Image or LLVM guest parity.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

# Print the actual checkout identity alongside fixture output. CI can set
# ORESLANG_EXPECTED_SOURCE_SHA to a full immutable source commit for a strict
# exact-head gate. This detects accidental branch drift, not authorization
# for copying source into another organization.
checkout_sha=""
if command -v git >/dev/null 2>&1; then
    checkout_sha="$(git -C "$root" rev-parse --verify 'HEAD^{commit}' 2>/dev/null || true)"
fi
printf 'Oreslang fixture checkout SHA: %s\n' "${checkout_sha:-unavailable}" >&2
if [[ -n "${ORESLANG_EXPECTED_SOURCE_SHA:-}" ]]; then
    if [[ ! "${ORESLANG_EXPECTED_SOURCE_SHA}" =~ ^[0-9a-f]{40}$ ]] \
            || [[ "$checkout_sha" != "${ORESLANG_EXPECTED_SOURCE_SHA}" ]]; then
        printf 'Exact-head verification refused: expected %s, actual %s\n' \
            "$ORESLANG_EXPECTED_SOURCE_SHA" "${checkout_sha:-unavailable}" >&2
        exit 2
    fi
    # A Git HEAD from a parent (or unrelated) worktree cannot prove this
    # script's source tree. Ignored nested directories may be completely
    # absent from that parent's tracked Git objects while status is clean.
    checkout_top=""
    if ! checkout_top="$(git -C "$root" rev-parse --show-toplevel 2>/dev/null)" \
            || [[ "$(cd -- "$checkout_top" && pwd -P)" != "$root" ]]; then
        printf 'Exact-head verification refused: script directory is not the Git worktree root\n' >&2
        exit 2
    fi
    # A matching HEAD hash is not a source-integrity proof when tracked files
    # or untracked compiler inputs were changed after checkout. Verify the
    # entire worktree before invoking any compiler or test process.
    checkout_status=""
    if ! checkout_status="$(git -C "$root" status --porcelain --untracked-files=all 2>/dev/null)"; then
        printf 'Exact-head verification refused: unable to inspect checkout cleanliness\n' >&2
        exit 2
    fi
    if [[ -n "$checkout_status" ]]; then
        printf 'Exact-head verification refused: checkout contains modified or untracked files\n' >&2
        exit 2
    fi
fi

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
    '-Dores.runtime.carriers=native' \
    '-Dtest=ActorTypedEffectControlTest,SourceActorTypedEffectExecutionTest,SourceActorOnThrowExecutionTest,TypedExceptionalEffectAdmissionTest,GuestThrowSuppressionAuditTest,ReservedControlDispatcherTest,ControlWakeRetryQueueTest,ActorIncarnationIdentityTest,ActorCrossRuntimeRestartAuthorityTest,NativeGuestErrorRootTest,TrapFailurePolicyTest' \
    test
