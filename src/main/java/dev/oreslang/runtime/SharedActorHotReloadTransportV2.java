package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * SHARED-only adapter from the stable logical hot-reload route to real
 * ActorRuntime mailbox admission.
 *
 * <p>The physical ActorRuntime returns an exact-message processed Future.
 * Its callback never mutates logical upgrade state directly on the actor
 * carrier: completion is handed to a host coordinator Executor first. A
 * failed physical message or rejected coordinator handoff quarantines the
 * logical route and leaves its mailbox completion outstanding.</p>
 *
 * <p>This is deliberately package-private infrastructure. It does not yet
 * perform source checkpoint restore, candidate compilation, or old physical
 * actor retirement; those remain CONTROL-owned upgrade operations.</p>
 */
final class SharedActorHotReloadTransportV2 {
    private final ActorRuntime runtime;
    private final Executor completionExecutor;
    private final ActorRoutePublicationV1<ActorRuntime.ActorRef<byte[]>> authority;

    SharedActorHotReloadTransportV2(
            ActorRuntime runtime,
            Executor completionExecutor,
            ActorRoutePublicationV1<ActorRuntime.ActorRef<byte[]>> authority) {
        this.runtime = Objects.requireNonNull(runtime, "actor runtime");
        this.completionExecutor =
                Objects.requireNonNull(completionExecutor, "completion executor");
        this.authority = Objects.requireNonNull(authority, "route authority");
        requireShared(authority.current().physicalRoute());
    }

    LogicalActorProxyMailboxV1.Envelope admit(byte[] payload) {
        return authority.admit(payload);
    }

    /**
     * Attempt one physical dispatch. False means the logical route is frozen
     * or has no queued DATA.
     */
    boolean pumpOne() {
        return authority.dispatchNext((physical, envelope, completion) -> {
            requireShared(physical);

            final OresFuture<Void> processed =
                    runtime.sendWithCompletion(physical, envelope.payload());

            processed.whenCompleteRuntime((ignored, failure) -> {
                try {
                    completionExecutor.execute(() ->
                            settlePhysicalCompletion(completion, failure));
                } catch (RuntimeException | Error coordinatorFailure) {
                    authority.quarantine(coordinatorFailure);
                }
            });
        }) != null;
    }

    private void settlePhysicalCompletion(
            ActorRoutePublicationV1<ActorRuntime.ActorRef<byte[]>>
                    .CompletionReceipt<byte[]> impossible) {
        // This overload must never be selected. CompletionReceipt is static
        // with R as its route type; retained only to make accidental generic
        // nesting fail loudly at compile time.
        throw new AssertionError(impossible);
    }

    private void settlePhysicalCompletion(
            ActorRoutePublicationV1.CompletionReceipt<
                    ActorRuntime.ActorRef<byte[]>> completion,
            Throwable failure) {
        if (ActorRuntime.isActorCarrierThread()) {
            authority.quarantine(new IllegalStateException(
                    "logical completion attempted on an actor carrier"));
            return;
        }
        if (failure != null) {
            authority.quarantine(OresFuture.unwrap(failure));
            return;
        }
        try {
            completion.complete();
        } catch (RuntimeException | Error completionFailure) {
            authority.quarantine(completionFailure);
        }
    }

    private static void requireShared(ActorRuntime.ActorRef<?> physical) {
        Objects.requireNonNull(physical, "physical actor");
        if (physical.kind() != ActorRuntime.ActorKind.SHARED) {
            throw new SecurityException(
                    "SHARED hot-reload transport cannot route to "
                            + physical.kind());
        }
    }
}
