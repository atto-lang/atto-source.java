package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Diagnostic single-writer witness, independent of ActorCell's lifecycle lease.
 *
 * <p>This is deliberately NOT a mutex that blocks contenders. Blocking would
 * hide an overlap. Each ActorCell owns its own witness, so unrelated actors
 * remain free to execute in parallel. It observes Java carrier Thread identity,
 * including the Java representation of native carrier threads.</p>
 */
final class ActorTurnContentionProbe {
    private final ActorRuntime.ActorId actorId;
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicLong violationCount;

    ActorTurnContentionProbe(ActorRuntime.ActorId actorId, AtomicLong violationCount) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.violationCount = Objects.requireNonNull(violationCount, "violationCount");
    }

    Scope enter() {
        Thread contender = Thread.currentThread();
        if (!owner.compareAndSet(null, contender)) {
            violationCount.incrementAndGet();
            Thread incumbent = owner.get();
            throw new IllegalStateException(
                    "core actor contention probe violation for " + actorId
                            + ": contender=" + carrierLabel(contender)
                            + ", incumbent=" + carrierLabel(incumbent));
        }
        return new Scope(contender);
    }

    private static String carrierLabel(Thread carrier) {
        return carrier == null ? "<released>" : carrier.getName() + "#" + carrier.threadId();
    }

    final class Scope implements AutoCloseable {
        private final Thread carrier;
        private boolean released;

        private Scope(Thread carrier) {
            this.carrier = carrier;
        }

        @Override
        public void close() {
            if (released || Thread.currentThread() != carrier || !owner.compareAndSet(carrier, null)) {
                violationCount.incrementAndGet();
                throw new IllegalStateException(
                        "core actor contention probe release violation for " + actorId
                                + ": expected=" + carrierLabel(carrier)
                                + ", actual=" + carrierLabel(owner.get()));
            }
            released = true;
        }
    }
}
