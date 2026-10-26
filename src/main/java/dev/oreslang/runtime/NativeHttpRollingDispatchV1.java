package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Narrow trusted-host adapter from NativeHttp to the rolling generation
 * coordinator. V1 deliberately supports fresh SHARED/PRIVATE handlers only:
 * pooled workers need an authenticated per-request turn-completion receipt
 * before their handler side can be proven quiescent.
 */
final class NativeHttpRollingDispatchV1 {
    private NativeHttpRollingDispatchV1() { }

    static void dispatch(
            HttpActorGenerationCoordinatorV1<NativeHttp.Handler> coordinator,
            NativeHttp.Exchange exchange) {
        Objects.requireNonNull(coordinator, "generation coordinator");
        Objects.requireNonNull(exchange, "HTTP exchange");

        var admission = coordinator.admit();
        NativeHttp.Handler route = admission.physicalRoute();
        try {
            if (route.pooledGenerationMode())
                throw new IllegalStateException(
                        "pooled HTTP hot generations require per-request turn receipts");
            exchange.bindHotGeneration(admission.lifetime());
        } catch (RuntimeException | Error failure) {
            // No transport or actor has received this lifetime yet.
            admission.lifetime().abandonBeforeUse();
            throw failure;
        }

        // Handler.dispatch owns all post-bind failure cleanup. Fresh actors
        // mark handler quiescence only when ActorRef.done physically completes;
        // NativeHttp marks transport quiescence only after raw transport close.
        route.dispatch(exchange);
    }
}
