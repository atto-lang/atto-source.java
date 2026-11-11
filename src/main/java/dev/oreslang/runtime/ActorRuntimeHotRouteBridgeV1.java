package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.Function;

/**
 * Narrow trusted-host bridge from the logical mailbox coordinator to real
 * SHARED ActorRuntime ActorRefs.
 *
 * <p>ActorRef.send() is only mailbox ADMISSION. The returned drain Future
 * covers processing of already admitted messages, so logical completion is
 * acknowledged only when the runtime's processing barrier succeeds.
 *
 * <p>WARNING: drain() does NOT prove physical Task, FFI, timer or borrowed
 * memory quiescence. Upgrade publication must separately retain old-generation
 * pins and validate the successor's restored state/ready barrier. The raw
 * physical ActorRefs must remain inaccessible to guest DATA senders, or they
 * could bypass the stable logical route. PRIVATE/UNTRUSTED migration and
 * physical image retirement are not implemented by this bridge.</p>
 */
final class ActorRuntimeHotRouteBridgeV1 {
    private ActorRuntimeHotRouteBridgeV1() { }

    static <M> ActorRouteCoordinatorV1.TrackedTransport<ActorRuntime.ActorRef<M>>
            processedReceiptTransport(Function<byte[], M> decode) {
        Objects.requireNonNull(decode, "trusted canonical message decoder");
        return (route, envelope, completion) -> {
            Objects.requireNonNull(route, "physical actor route");
            if (route.kind() != ActorRuntime.ActorKind.SHARED) {
                throw new SecurityException(
                        "v1 host route bridge is SHARED-only; physical protected actors need explicit attestation");
            }
            M decoded = Objects.requireNonNull(
                    decode.apply(envelope.payload()), "decoded actor mail");
            // No completion is minted by ActorRef.send(): enqueue is not an ACK.
            route.send(decoded);
            OresFuture<Void> processing = Objects.requireNonNull(
                    route.drain(), "actor message processing barrier");
            processing.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) {
                    completion.quarantine(failure);
                } else {
                    try {
                        completion.acknowledge();
                    } catch (RuntimeException | Error inconsistentReceipt) {
                        completion.quarantine(inconsistentReceipt);
                    }
                }
            });
        };
    }
}
