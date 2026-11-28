#!/usr/bin/env bash
# Exact-source Java 21 accelerator security smoke; not a substitute for Maven.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
javac -d "$work" \
    src/main/java/dev/oreslang/runtime/accel/QuantumCircuitIR.java \
    src/main/java/dev/oreslang/runtime/accel/GpuBufferFence.java \
    scripts/AcceleratorSafetySmoke.java
java -cp "$work" dev.oreslang.runtime.accel.AcceleratorSafetySmoke

# Do not trust reflection alone: prove javac itself forbids each backend
# control-plane transition to consumers outside the runtime package.
for call in 'beginCommand(id)' 'complete(t, true)' 'cancelPending(t)' \
            'acknowledgeCancelledDeviceEvent(t, id)' \
            'retireAfterBackendRelease(id)' 'claimPair(fence, fence, 1024)' \
            'markPairEnqueued(fence, fence, null)' \
            'abortPairBeforeEnqueue(fence, fence, null)' \
            'completePair(fence, fence, null, true)' \
            'cancelPair(fence, fence, null)' \
            'acknowledgeCancelledPair(fence, fence, null)'; do
    printf '%s\n' \
        'import dev.oreslang.runtime.accel.GpuBufferFence;' \
        'final class OutsideRuntimePackage {' \
        '  static void invoke(GpuBufferFence fence, GpuBufferFence.Ticket t,' \
        '                     GpuBufferFence.Identity id) {' \
        "    fence.$call;" \
        '  }' '}' > "$work/OutsideRuntimePackage.java"
    if javac -cp "$work" -d "$work" "$work/OutsideRuntimePackage.java" \
            > "$work/outside.stdout" 2> "$work/outside.stderr"; then
        echo "FAIL: public guest call unexpectedly compiled: $call" >&2
        exit 1
    fi
    if ! grep -q 'cannot be accessed from outside package' "$work/outside.stderr"; then
        echo "FAIL: unexpected compilation failure for: $call" >&2
        cat "$work/outside.stderr" >&2
        exit 1
    fi
    printf 'PASS: outside-package compiler rejection: %s\n' "$call"
done
