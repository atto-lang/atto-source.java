package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Cross-context SHARED source-actor DATA bridge for a stable logical route.
 *
 * <p>Payload bytes are immutable logical-mail data. The codec is trusted,
 * deterministic compiler/runtime infrastructure and must reject malformed
 * bytes before returning a guest value. Physical completion advances the
 * logical watermark only after ActorRuntime's exact accepted-message receipt.</p>
 */
final class SharedSourceActorHotReloadTransportV2 {
    @FunctionalInterface
    interface MessageCodec {
        Object decode(byte[] immutablePayload);
    }

    record PhysicalRoute(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<Object> ref) {
        PhysicalRoute {
            Objects.requireNonNull(runtime, "actor runtime");
            Objects.requireNonNull(ref, "actor ref");
            if (ref.kind() != ActorRuntime.ActorKind.SHARED)
                throw new SecurityException(
                        "source hot-reload v2 route requires a SHARED actor");
        }
    }

    private final Executor completionExecutor;
    private final MessageCodec codec;
    private final ActorRoutePublicationV1<PhysicalRoute> authority;

    SharedSourceActorHotReloadTransportV2(
            Executor completionExecutor,
            MessageCodec codec,
            ActorRoutePublicationV1<PhysicalRoute> authority) {
        this.completionExecutor =
                Objects.requireNonNull(completionExecutor, "completion executor");
        this.codec = Objects.requireNonNull(codec, "message codec");
        this.authority = Objects.requireNonNull(authority, "route authority");
    }

    LogicalActorProxyMailboxV1.Envelope admit(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        // Fail before logical admission for malformed messages. The codec must
        // be deterministic; pumpOne decodes the immutable snapshot again.
        codec.decode(payload.clone());
        return authority.admit(payload);
    }

    boolean pumpOne() {
        return authority.dispatchNext((physical, envelope, completion) -> {
            Object message = codec.decode(envelope.payload());
            OresFuture<Void> processed =
                    physical.runtime().sendWithCompletion(physical.ref(), message);
            processed.whenCompleteRuntime((ignored, failure) -> {
                try {
                    completionExecutor.execute(() ->
                            settle(completion, failure));
                } catch (RuntimeException | Error coordinatorFailure) {
                    authority.quarantine(coordinatorFailure);
                }
            });
        }) != null;
    }

    private void settle(
            ActorRoutePublicationV1.CompletionReceipt<PhysicalRoute> completion,
            Throwable failure) {
        if (ActorRuntime.isActorCarrierThread()) {
            authority.quarantine(new IllegalStateException(
                    "source logical completion attempted on actor carrier"));
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
}
