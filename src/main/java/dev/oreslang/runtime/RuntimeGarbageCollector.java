package dev.oreslang.runtime;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secondary runtime reclamation for host/interop resources that are not
 * governed solely by Oreslang ownership.
 *
 * Oreslang values remain ownership-managed. This collector never invalidates a
 * live guest reference. It only runs cleanup hooks after their weak owner has
 * become unreachable, plus explicit process/actor sweeps. The JVM remains
 * responsible for ordinary Java heap tracing.
 */
public final class RuntimeGarbageCollector implements AutoCloseable {
    private static final Duration DEFAULT_PERIOD = Duration.ofSeconds(30);
    private static final Object PROCESS_DOMAIN = new Object();

    public record CollectionReport(
            String scope,
            long collection,
            int trackedBefore,
            int cleaned,
            int trackedAfter,
            int cleanupFailures,
            boolean jvmGcRequested) {
        public Map<String,Object> asMap() {
            return Map.of(
                    "scope", scope,
                    "collection", collection,
                    "tracked_before", trackedBefore,
                    "cleaned", cleaned,
                    "tracked_after", trackedAfter,
                    "cleanup_failures", cleanupFailures,
                    "jvm_gc_requested", jvmGcRequested);
        }
    }

    private static final class TrackedCleanup {
        private final WeakReference<Object> owner;
        private final Object domain;
        private final Runnable cleanup;
        private final AtomicBoolean cleaned = new AtomicBoolean();

        private TrackedCleanup(Object owner, Object domain, Runnable cleanup) {
            this.owner = new WeakReference<>(Objects.requireNonNull(owner));
            this.domain = Objects.requireNonNull(domain);
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        private boolean eligible(Object requestedDomain) {
            return owner.get() == null && (requestedDomain == null || domain.equals(requestedDomain));
        }

        private boolean clean() {
            if (!cleaned.compareAndSet(false, true)) return false;
            cleanup.run();
            return true;
        }
    }

    private final CopyOnWriteArrayList<TrackedCleanup> tracked = new CopyOnWriteArrayList<>();
    private final AtomicLong collections = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable jvmGcRequest;
    private final Thread sweeper;

    public RuntimeGarbageCollector() {
        this(System::gc, DEFAULT_PERIOD);
    }

    RuntimeGarbageCollector(Runnable jvmGcRequest, Duration period) {
        this.jvmGcRequest = Objects.requireNonNull(jvmGcRequest);
        Objects.requireNonNull(period);
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("GC sweep period must be positive");
        }
        this.sweeper = Thread.ofVirtual().name("ores-gc-sweeper").start(() -> sweepLoop(period));
    }

    /**
     * Registers a host/interop cleanup hook. The owner is weakly referenced, so
     * registration itself cannot extend its lifetime.
     */
    public void track(Object owner, Runnable cleanup) {
        ensureOpen();
        Object actorDomain = ActorRuntime.currentActorExecutionDomain();
        tracked.add(new TrackedCleanup(owner, actorDomain == null ? PROCESS_DOMAIN : actorDomain, cleanup));
    }

    /**
     * Explicit process collection. This sweeps Oreslang-owned cleanup hooks and
     * then asks the JVM for a best-effort heap collection. It is never a
     * correctness boundary and callers must not rely on immediate reclamation.
     */
    public CollectionReport collectProcess() {
        ensureOpen();
        CollectionReport report = sweep(null, true);
        jvmGcRequest.run();
        return report;
    }

    /**
     * Explicit actor-local collection. It only touches cleanup hooks belonging
     * to the current semantic actor domain; it never requests global JVM GC.
     */
    public CollectionReport collectCurrentActor() {
        ensureOpen();
        Object actorDomain = ActorRuntime.currentActorExecutionDomain();
        if (actorDomain == null) {
            throw new IllegalStateException("actor.gc() requires execution inside an actor");
        }
        return sweep(actorDomain, false);
    }

    /**
     * Periodic VM housekeeping. No global JVM GC request is made here; normal
     * heap collection stays under the JVM's adaptive collector.
     */
    public CollectionReport collectPeriodic() {
        ensureOpen();
        return sweep(null, false);
    }

    private CollectionReport sweep(Object requestedDomain, boolean requestJvmGc) {
        int before = tracked.size();
        int cleanedCount = 0;
        int cleanupFailures = 0;
        List<TrackedCleanup> remove = new ArrayList<>();
        for (TrackedCleanup entry : tracked) {
            if (!entry.eligible(requestedDomain)) continue;
            try {
                if (entry.clean()) cleanedCount++;
            } catch (RuntimeException cleanupFailure) {
                cleanupFailures++;
            } finally {
                remove.add(entry);
            }
        }
        tracked.removeAll(remove);
        return new CollectionReport(
                requestedDomain == null ? "process" : "actor",
                collections.incrementAndGet(),
                before,
                cleanedCount,
                tracked.size(),
                cleanupFailures,
                requestJvmGc);
    }

    private void sweepLoop(Duration period) {
        while (!closed.get()) {
            try {
                Thread.sleep(period);
            } catch (InterruptedException interrupted) {
                if (closed.get()) return;
                Thread.currentThread().interrupt();
                return;
            }
            if (!closed.get()) {
                try {
                    collectPeriodic();
                } catch (RuntimeException ignored) {
                    // Cleanup failures are isolated to the sweep; explicit calls
                    // still surface failures to their caller.
                }
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("garbage collector is closed");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sweeper.interrupt();
        for (TrackedCleanup entry : tracked) {
            try {
                entry.clean();
            } catch (RuntimeException ignored) {
                // Context shutdown is best-effort for host cleanup hooks.
            }
        }
        tracked.clear();
    }
}
