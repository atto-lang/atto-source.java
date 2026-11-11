package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.Function;

/**
 * Trusted CONTROL bridge from the logical route to REAL SHARED ActorRuntime
 * mailboxes with one-way physical ActorRef send sealing.
 *
 * <p>A runtime-minted HotRoutePhysical permit is the only way to deliver a
 * coordinator PhysicalCompletion to the underlying actor. The original raw
 * ActorRef.send()/sendAsync() rejects once the actor is claimed, and even a
 * captured permit cannot be replayed with a duplicate/foreign route receipt.
 *
 * <p>Future completion covers processed mail, NOT Task, FFI, timer, borrowed
 * state or physical native-code quiescence. This class does not validate guest
 * checkpoint migration or implement canonical guest ActorRef handover.</p>
 */
final class ActorRuntimeHotRouteBridgeV1 {
    private ActorRuntimeHotRouteBridgeV1() { }

    static <M> ActorRouteCoordinatorV1.TrackedTransport<
            ActorRuntime.HotRoutePhysical<M>> processedReceiptTransport(
            Function<byte[], M> decode) {
        Objects.requireNonNull(decode, "trusted canonical message decoder");
        return (route, envelope, completion) -> {
            Objects.requireNonNull(route, "runtime-minted physical route");
            M message = Objects.requireNonNull(
                    decode.apply(envelope.payload()), "decoded actor message");

            // This consumes the unique coordinator receipt BEFORE send and
            // verifies the exact owner physical route. Returning from send
            // only means admitted, never physically processed.
            route.sendTracked(message, completion);
            OresFuture<Void> drain = Objects.requireNonNull(
                    route.processedDrain(), "physical actor processing barrier");
            drain.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) {
                    completion.quarantine(failure);
                    return;
                }
                try {
                    completion.acknowledge();
                } catch (RuntimeException | Error badReceipt) {
                    completion.quarantine(badReceipt);
                }
            });
        };
    }
}
