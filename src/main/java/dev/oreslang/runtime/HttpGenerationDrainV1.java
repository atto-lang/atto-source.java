package dev.oreslang.runtime;

import java.util.EnumMap;
import java.util.Map;

/**
 * Trusted-host accounting for HTTP exchanges pinned to one immutable actor
 * generation. This is NOT a router, socket adapter, or source-level hot reload.
 *
 * The stable listener/router must select and pin the generation in the SAME
 * admission linearization point. This ledger tracks the selected generation's
 * lifetime; it must never independently publish a competing route pointer.
 *
 * Completion requires both physically quiescent handler work and final
 * transport cleanup. An HTTP response object, Future settlement, or
 * cancellation request alone is not sufficient.
 */
final class HttpGenerationDrainV1 {
    enum ResourceKind { SUSPENDED_TASK, HTTP_STREAM, FFI_CALL, CALLBACK, SHARED_REGION }

    private final long generation;
    private final int maxExchanges;
    private final int maxResourcePins;
    private int exchanges;
    private int resourcePins;
    private final int[] pinsByKind = new int[ResourceKind.values().length];
    private boolean draining;

    HttpGenerationDrainV1(long generation, int maxExchanges, int maxResourcePins) {
        if (generation <= 0) throw new IllegalArgumentException("invalid generation");
        if (maxExchanges <= 0) throw new IllegalArgumentException("invalid exchange capacity");
        if (maxResourcePins <= 0) throw new IllegalArgumentException("invalid physical pin capacity");
        this.generation = generation;
        this.maxExchanges = maxExchanges;
        this.maxResourcePins = maxResourcePins;
    }

    synchronized long generation() { return generation; }
    synchronized int exchanges() { return exchanges; }
    synchronized int resourcePins() { return resourcePins; }
    synchronized boolean draining() { return draining; }

    /**
     * Immutable diagnostics for old-code retention; this HTTP-side snapshot
     * does NOT independently authorize unloading. The code-image gate must
     * also prove all actor/FFI/continuation pins have physically retired.
     */
    record DrainStatus(long generation, boolean draining, int exchanges,
                       int resourcePins, Map<ResourceKind, Integer> resourcesByKind,
                       boolean readyToRetire) { }

    synchronized DrainStatus status() {
        EnumMap<ResourceKind, Integer> counts = new EnumMap<>(ResourceKind.class);
        for (ResourceKind kind : ResourceKind.values()) {
            int count = pinsByKind[kind.ordinal()];
            if (count != 0) counts.put(kind, count);
        }
        return new DrainStatus(generation, draining, exchanges, resourcePins,
                Map.copyOf(counts), readyToRetire());
    }

    /** The caller must hold the trusted routing admission lock while calling this. */
    synchronized Exchange admit() {
        if (draining) throw new IllegalStateException("old HTTP generation is draining");
        if (exchanges == maxExchanges)
            throw new IllegalStateException("HTTP generation admission capacity exhausted");
        Exchange exchange = new Exchange(this);
        ++exchanges;
        return exchange;
    }

    /**
     * Forbid new exchange admissions; existing exchanges and their physical
     * operations may continue. Never hold this monitor during guest callbacks.
     */
    synchronized void beginDrain() {
        draining = true;
    }

    /** False while any admitted request or real resource pin remains live. */
    synchronized boolean readyToRetire() {
        return draining && exchanges == 0 && resourcePins == 0;
    }

    final class Exchange {
        private final HttpGenerationDrainV1 owner;
        private boolean handlerQuiescent;
        private boolean transportQuiescent;
        private boolean finished;
        private boolean cancelRequested;

        private Exchange(HttpGenerationDrainV1 owner) { this.owner = owner; }

        long generation() { return owner.generation; }

        /** Observation only: request cancellation does not release anything. */
        void requestCancellation() {
            synchronized (owner) { cancelRequested = true; }
        }

        boolean cancellationRequested() {
            synchronized (owner) { return cancelRequested; }
        }

        /**
         * Call only after handler execution, suspended continuations and its
         * cleanup have physically finished, not when a Future is settled.
         */
        void handlerQuiescent() {
            synchronized (owner) {
                handlerQuiescent = true;
                finishIfPossible();
            }
        }

        /**
         * Call only after the entire response body/trailers are written or
         * aborted AND the transport-owned response resources are retired.
         */
        void transportQuiescent() {
            synchronized (owner) {
                transportQuiescent = true;
                finishIfPossible();
            }
        }

        /**
         * A separate physical resource pin; must be acquired before exposing
         * a callback, native buffer, suspended frame or external operation.
         * The close side runs AFTER physical completion, even on cancellation.
         */
        ResourcePin pin(ResourceKind kind) {
            if (kind == null) throw new NullPointerException("resource kind");
            synchronized (owner) {
                if (finished) throw new IllegalStateException("HTTP exchange has retired");
                if (resourcePins == maxResourcePins)
                    throw new IllegalStateException("HTTP resource pin limit exhausted");
                ResourcePin pin = new ResourcePin(this, kind);
                ++resourcePins;
                ++pinsByKind[kind.ordinal()];
                return pin;
            }
        }

        boolean finished() {
            synchronized (owner) { return finished; }
        }

        /**
         * Roll back an admission that never became attached to a transport or
         * handler. This is the only path allowed to retire an exchange before
         * handler/transport quiescence: no resource pins or completion signal
         * may have occurred yet.
         */
        void abandonBeforeUse() {
            synchronized (owner) {
                if (finished) return;
                if (handlerQuiescent || transportQuiescent || cancelRequested
                        || resourcePins != 0)
                    throw new IllegalStateException("HTTP generation admission already in use");
                if (exchanges <= 0)
                    throw new IllegalStateException("exchange count underflow");
                --exchanges;
                finished = true;
            }
        }

        private void finishIfPossible() {
            if (!finished && handlerQuiescent && transportQuiescent) {
                if (exchanges <= 0) throw new IllegalStateException("exchange count underflow");
                --exchanges;
                finished = true;
            }
        }
    }

    final class ResourcePin implements AutoCloseable {
        private final Exchange exchange;
        private final ResourceKind kind;
        private boolean closed;

        private ResourcePin(Exchange exchange, ResourceKind kind) {
            this.exchange = exchange;
            this.kind = kind;
        }

        ResourceKind kind() { return kind; }
        long generation() { return exchange.generation(); }

        @Override public void close() {
            synchronized (HttpGenerationDrainV1.this) {
                if (closed) return;
                if (resourcePins <= 0)
                    throw new IllegalStateException("HTTP resource pin accounting underflow");
                int kindPins = pinsByKind[kind.ordinal()];
                if (kindPins <= 0)
                    throw new IllegalStateException("HTTP kind pin accounting underflow");
                --resourcePins;
                --pinsByKind[kind.ordinal()];
                closed = true;
            }
        }
    }
}
