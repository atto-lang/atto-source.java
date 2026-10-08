package dev.oreslang.runtime.hpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inert local-pinning/lifetime preflight for a possible native Oreslang async
 * lowering. Stack-like pinning does not mean thread-stack residence once a
 * local is captured by an enclosing async frame.
 *
 * <p>This models proposed static compiler invariants. It does not pin JVM
 * objects, create a native frame, authorize a raw pointer, or make GC pauses
 * impossible. Every result includes an implementation blocker.</p>
 */
public final class HpcLocalPinPlan {

    public enum FrameLocation {
        /** Synchronous caller stack slot; cannot survive the caller's return. */
        SYNCHRONOUS_STACK,
        /** Field of a suspended parent async state machine, in parent storage. */
        ENCLOSING_ASYNC_FRAME,
        /** Explicit stable native arena owned by an actor. */
        ACTOR_ARENA,
        /** An independently allocated native/boxed frame. */
        INDEPENDENT_FRAME
    }

    public enum Operation {
        POLL_LOCALLY,
        SUSPEND_PARENT,
        MOVE_OWNER,
        RETURN_BORROWED_PIN,
        RETIRE_OWNER
    }

    public record Request(
            FrameLocation location,
            Operation operation,
            boolean locationSensitive,
            boolean pinEstablished,
            boolean parentFramePinned,
            boolean ownerLifetimeProven,
            boolean inPlaceDropProven,
            boolean borrowEscapesScope,
            boolean crossActorOrIsolate,
            boolean childFuturePending,
            boolean waiterRegistered,
            boolean nativeLeaseOutstanding,
            boolean generationFencedWakeIds) {
        public Request {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(operation, "operation");
        }
    }

    private HpcLocalPinPlan() {
    }

    /**
     * Returns validation blockers, *never* authorization to execute.
     * The missing compiler/backend blocker must remain until full lowering,
     * lifetime tracking, and cancellation are implemented and verified.
     */
    public static List<String> blockers(Request request) {
        Objects.requireNonNull(request, "request");
        List<String> blockers = new ArrayList<>();

        if (request.locationSensitive() && !request.pinEstablished()) {
            blockers.add("address-sensitive frame requires pin before native polling");
        }
        if (request.locationSensitive() && !request.inPlaceDropProven()) {
            blockers.add("pinned frame requires compiler-proven in-place destructor/drop");
        }
        if (!request.ownerLifetimeProven()) {
            blockers.add("pinned or borrowed frame must be owned for its complete lifetime");
        }
        if (request.borrowEscapesScope()) {
            blockers.add("locally pinned borrowed reference may not escape its owning scope");
        }
        if (request.crossActorOrIsolate()) {
            blockers.add("pinned local references cannot cross actor/isolate ownership boundary");
        }
        if (request.location() == FrameLocation.ENCLOSING_ASYNC_FRAME
                && request.operation() == Operation.SUSPEND_PARENT
                && !request.parentFramePinned() && request.locationSensitive()) {
            blockers.add("self-referential child requires stable pinned parent async frame");
        }
        if (request.location() == FrameLocation.SYNCHRONOUS_STACK
                && request.operation() == Operation.SUSPEND_PARENT) {
            blockers.add("synchronous stack slot cannot survive caller stack unwinding");
        }
        if (request.operation() == Operation.MOVE_OWNER && request.locationSensitive()
                && request.pinEstablished()) {
            blockers.add("moving a pinned address-sensitive pointee violates location stability");
        }
        if (request.operation() == Operation.RETURN_BORROWED_PIN
                && (request.location() == FrameLocation.SYNCHRONOUS_STACK
                || request.location() == FrameLocation.ENCLOSING_ASYNC_FRAME)) {
            blockers.add("borrowed local Pin cannot outlive its stack or parent async frame");
        }
        if (request.operation() == Operation.RETIRE_OWNER) {
            if (request.childFuturePending() || request.waiterRegistered()
                    || request.nativeLeaseOutstanding()) {
                blockers.add("owner retirement requires settled child Futures and detached wakers/leases");
            }
            if (!request.generationFencedWakeIds()) {
                blockers.add("recycled actor-group frames require generation-fenced wake IDs");
            }
        }

        // A safe *shape* is only a compiler proposal; there is no physical
        // pinning or standalone-heap-free async native lowering yet.
        blockers.add("native local-pinning lowering and lifecycle enforcement not implemented");
        return List.copyOf(blockers);
    }
}
