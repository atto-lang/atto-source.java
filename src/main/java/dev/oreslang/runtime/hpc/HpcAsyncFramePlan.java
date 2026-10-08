package dev.oreslang.runtime.hpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inert planner for address-sensitive async state and ActorGroup lifetime.
 *
 * <p>Rust Pin is a pointer/pointee lifetime contract, not an OS thread pin, a
 * GC pause control, or a licence to expose stable addresses of Java objects.
 * This class NEVER registers a waiter, allocates a native arena, unpins an
 * object, admits relocation or recycles a running ActorGroup. Safety requires
 * compiler proofs and a generation-fenced scheduler/backend implementation.</p>
 */
public final class HpcAsyncFramePlan {

    public enum Storage {
        /** Java/Truffle-managed task state: JVM may move an object while collecting. */
        GRAAL_MANAGED,
        /** Native fixed-layout record whose references are validated region offsets. */
        NATIVE_RELATIVE_OFFSETS,
        /** Native frame with interior/FFI pointers that require a stable address. */
        NATIVE_ADDRESS_SENSITIVE
    }

    public enum Operation {
        SUSPEND,
        RELOCATE,
        RETIRE_GROUP
    }

    public record Request(Storage storage,
                          Operation operation,
                          boolean containsInteriorPointers,
                          boolean hasPinnedNativeLease,
                          boolean hasBorrowAcrossAwait,
                          boolean borrowIsProvenFrameOwned,
                          boolean referencesCrossActorBoundary,
                          boolean usesRawJvmObjectAddress,
                          boolean hasGenerationFencedWakeIds,
                          int activeTurns,
                          int suspendedFrames,
                          int registeredWaiters,
                          int externalLeases) {
        public Request {
            Objects.requireNonNull(storage, "storage");
            Objects.requireNonNull(operation, "operation");
            if (activeTurns < 0 || suspendedFrames < 0
                    || registeredWaiters < 0 || externalLeases < 0) {
                throw new IllegalArgumentException("async state/root counts cannot be negative");
            }
        }
    }

    private HpcAsyncFramePlan() {
    }

    /**
     * Return violations for a *proposed* frame operation, never an authorization.
     * Backend implementation is intentionally always unavailable in this PR.
     */
    public static List<String> blockers(Request request) {
        Objects.requireNonNull(request, "request");
        List<String> reasons = new ArrayList<>();

        if (request.usesRawJvmObjectAddress()) {
            reasons.add("raw JVM object addresses cannot be pinned with a language-level Pin");
        }
        if (request.referencesCrossActorBoundary()) {
            reasons.add("borrowed or native frame pointers cannot cross actor isolation boundaries");
        }
        if (request.hasBorrowAcrossAwait() && !request.borrowIsProvenFrameOwned()) {
            reasons.add("borrow across await requires compiler-proven frame/arena ownership");
        }
        if (request.hasBorrowAcrossAwait() && request.storage() == Storage.GRAAL_MANAGED) {
            reasons.add("Graal backend must preserve existing no-live-borrow-across-await rules");
        }
        if (request.containsInteriorPointers()
                && request.storage() == Storage.GRAAL_MANAGED) {
            reasons.add("managed Graal frames cannot expose self-referential raw pointers");
        }
        if (request.containsInteriorPointers()
                && request.storage() == Storage.NATIVE_RELATIVE_OFFSETS) {
            reasons.add("relocatable frame contains interior pointers instead of validated offsets");
        }
        if (request.storage() == Storage.NATIVE_ADDRESS_SENSITIVE
                && !request.hasPinnedNativeLease()) {
            reasons.add("address-sensitive native frame requires an owning pinned region lease");
        }

        if (request.operation() == Operation.RELOCATE) {
            if (request.storage() == Storage.NATIVE_ADDRESS_SENSITIVE
                    || request.hasPinnedNativeLease()) {
                reasons.add("a pinned frame or region may not be relocated while live");
            }
            if (request.hasBorrowAcrossAwait()
                    && request.storage() != Storage.NATIVE_RELATIVE_OFFSETS) {
                reasons.add("frame relocation cannot invalidate an active interior borrow");
            }
        }

        if (request.operation() == Operation.RETIRE_GROUP) {
            if (request.activeTurns() != 0 || request.suspendedFrames() != 0
                    || request.registeredWaiters() != 0 || request.externalLeases() != 0) {
                reasons.add("group retirement must drain turns, suspended frames, waiters and native leases");
            }
            if (!request.hasGenerationFencedWakeIds()) {
                reasons.add("late wakers require generation-fenced task/region identifiers");
            }
        }

        // Even when the abstract shape is safe, the compiler and runtime do
        // not yet prove native frame pinning, native relocation or reclamation.
        reasons.add("async native-frame relocation/pinning backend is not implemented");
        return List.copyOf(reasons);
    }
}
