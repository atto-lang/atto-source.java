package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Host runtime strong/weak lifetime mechanism for a future compiler-owned
 * Arc<T> / Weak<T> lowering. This package-private type is deliberately NOT a
 * guest-visible class or permission to share mutable Java objects/actor state.
 * The compiler must prove deep immutability, domain provenance and one-owner
 * per handle. Java owns control-block memory and may GC it; only payload
 * cleanup and logical count retirement are deterministic here.
 *
 * A callback is attempted exactly once on final release. If it throws, the
 * implicit weak sentinel is still retired and the payload detached, although
 * user-defined destructor completion cannot then be claimed successful.
 */
final class AtomicSharedCellV1 {
    static final int MAX_OWNERS = 1_048_576;

    private AtomicSharedCellV1() { }

    static <T> Strong<T> new_owner(T payload, long domain, long generation,
                                   Consumer<? super T> destroy) {
        if (domain <= 0 || generation <= 0) {
            throw new IllegalArgumentException("valid shared allocation domain required");
        }
        Objects.requireNonNull(payload, "owned payload");
        Objects.requireNonNull(destroy, "payload destructor");
        return new Strong<>(new Control<>(payload, domain, generation, destroy));
    }

    private static boolean incrementIfLive(AtomicInteger count) {
        for (;;) {
            int prior = count.get();
            if (prior <= 0 || prior >= MAX_OWNERS) return false;
            if (count.compareAndSet(prior, prior + 1)) return true;
        }
    }

    private static int decrementIfLive(AtomicInteger count) {
        for (;;) {
            int prior = count.get();
            if (prior <= 0) throw new IllegalStateException("shared ownership underflow");
            if (count.compareAndSet(prior, prior - 1)) return prior;
        }
    }

    private static final class Control<T> {
        final long domain;
        final long generation;
        final AtomicInteger strong = new AtomicInteger(1);
        final AtomicInteger weak = new AtomicInteger(1); // implicit sentinel
        final AtomicReference<T> payload;
        final Consumer<? super T> destroy;
        final AtomicBoolean payloadDetached = new AtomicBoolean();
        final AtomicBoolean controlRetired = new AtomicBoolean();

        Control(T initial, long domain, long generation, Consumer<? super T> destroy) {
            this.domain = domain;
            this.generation = generation;
            this.payload = new AtomicReference<>(initial);
            this.destroy = destroy;
        }

        void releaseWeak() {
            if (decrementIfLive(weak) == 1) {
                if (!payloadDetached.get()) {
                    throw new IllegalStateException("implicit weak sentinel retired early");
                }
                if (!controlRetired.compareAndSet(false, true)) {
                    throw new IllegalStateException("control block double retirement");
                }
            }
        }

        void releaseStrong() {
            if (decrementIfLive(strong) == 1) {
                T value = payload.getAndSet(null);
                try {
                    destroy.accept(value);
                } finally {
                    payloadDetached.set(true);
                    releaseWeak(); // implicit sentinel released AFTER destructor
                }
            }
        }
    }

    /** Each instance is a separate, linear strong owner. No exposed mutable T. */
    static final class Strong<T> implements AutoCloseable {
        private final Control<T> control;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Strong(Control<T> control) { this.control = control; }

        synchronized Strong<T> clone_owner() {
            ensureOpen();
            // Construct an unpublished owner before the reference increment:
            // allocation failure must not strand a strong count.
            Strong<T> candidate = new Strong<>(control);
            if (!incrementIfLive(control.strong)) {
                throw new IllegalStateException("strong owner exhausted or dead");
            }
            return candidate;
        }

        synchronized Weak<T> downgrade() {
            ensureOpen();
            // Construct before reference reservation; OOME is failure-atomic.
            Weak<T> candidate = new Weak<>(control);
            if (!incrementIfLive(control.weak)) {
                throw new IllegalStateException("weak owner exhausted or dead");
            }
            return candidate;
        }

        private void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("strong owner already retired");
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("double strong owner retirement");
            }
            control.releaseStrong();
        }

        // Host-only observation for tests; not a guest pointer/access API.
        boolean is_payload_detached() { return control.payloadDetached.get(); }
        boolean is_control_retired() { return control.controlRetired.get(); }
    }

    /** Weak owner holds no strong payload ownership and never publishes raw T. */
    static final class Weak<T> implements AutoCloseable {
        private final Control<T> control;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Weak(Control<T> control) { this.control = control; }

        synchronized Weak<T> clone_owner() {
            ensureOpen();
            // Construct before reference reservation; OOME is failure-atomic.
            Weak<T> candidate = new Weak<>(control);
            if (!incrementIfLive(control.weak)) {
                throw new IllegalStateException("weak owner exhausted or dead");
            }
            return candidate;
        }

        synchronized Strong<T> upgrade(long domain, long generation) {
            ensureOpen();
            if (domain != control.domain || generation != control.generation) return null;
            // Failed handle allocation must leave the strong count unchanged.
            Strong<T> candidate = new Strong<>(control);
            if (!incrementIfLive(control.strong)) return null;
            return candidate;
        }

        private void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("weak owner already retired");
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("double weak owner retirement");
            }
            control.releaseWeak();
        }

        boolean is_control_retired() { return control.controlRetired.get(); }
    }
}
