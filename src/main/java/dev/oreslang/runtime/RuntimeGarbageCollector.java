package dev.oreslang.runtime;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fallback collector for runtime-owned resources that cannot be reclaimed by
 * Oreslang's deterministic ownership/drop model alone.
 *
 * <p>This collector deliberately does not call {@link System#gc()}. The JVM
 * (or a future native backend) decides when ordinary unreachable memory is
 * traced. Oreslang uses phantom reachability only to release runtime metadata,
 * quotas, direct-memory reservations, reflection/FFI proxy state, and similar
 * idempotent host resources after their guest-visible owner becomes
 * unreachable.</p>
 *
 * <p>Normal Oreslang values must never be registered here merely to manage
 * their memory. Their lifetime is governed by ownership, moves, borrows, and
 * compiler-inserted drops. This class is the narrow fallback path for runtime
 * handles whose cleanup has side effects outside the ordinary object graph.</p>
 */
public final class RuntimeGarbageCollector implements AutoCloseable {
    private static final long DEFAULT_PERIODIC_CHECKPOINTS = 256L;

    public record GcReport(
            long cleaned,
            long failed,
            long tracked,
            long collections,
            long totalCleaned,
            long totalFailures) { }

    private enum CleanResult { NONE, CLEANED, FAILED }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Set<TrackedReference> tracked = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<TrackedReference> ready = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong checkpoints = new AtomicLong();
    private final AtomicLong collections = new AtomicLong();
    private final AtomicLong totalCleaned = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final long periodicCheckpoints;

    public RuntimeGarbageCollector() {
        this(DEFAULT_PERIODIC_CHECKPOINTS);
    }

    RuntimeGarbageCollector(long periodicCheckpoints) {
        if (periodicCheckpoints <= 0) {
            throw new IllegalArgumentException("periodicCheckpoints must be positive");
        }
        this.periodicCheckpoints = periodicCheckpoints;
    }

    /**
     * Register process-scoped fallback cleanup. The cleanup action must be
     * idempotent and must not capture {@code owner}, directly or indirectly.
     */
    public Registration trackProcess(Object owner, String category, Runnable cleanup) {
        return track(owner, null, category, cleanup);
    }

    /**
     * Register actor-scoped fallback cleanup. actorKey is a stable semantic
     * actor identity, never a carrier Thread.
     */
    Registration trackActor(Object owner, Object actorKey, String category, Runnable cleanup) {
        return track(owner, Objects.requireNonNull(actorKey, "actorKey"), category, cleanup);
    }

    private Registration track(Object owner, Object actorKey, String category, Runnable cleanup) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(cleanup, "cleanup");
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("GC category cannot be blank");
        }
        if (closed.get()) throw new IllegalStateException("runtime garbage collector is closed");

        TrackedReference reference = new TrackedReference(owner, actorKey, category, cleanup);
        tracked.add(reference);
        // The caller still strongly owns owner for the duration of this method,
        // so a periodic queue drain cannot race construction of the handle.
        maybeCollect();
        return new Registration(reference);
    }

    /**
     * Drain all cleanup candidates already declared unreachable by the host
     * memory manager. This is intentionally not a request for a global JVM GC.
     */
    public GcReport collectProcess() {
        if (closed.get()) throw new IllegalStateException("runtime garbage collector is closed");
        return collect(null, true);
    }

    /**
     * Drain only candidates owned by one semantic actor. Process-scoped and
     * other actors' resources stay queued for their own sweep/process sweep.
     */
    GcReport collectActor(Object actorKey) {
        Objects.requireNonNull(actorKey, "actorKey");
        if (closed.get()) throw new IllegalStateException("runtime garbage collector is closed");
        return collect(actorKey, false);
    }

    /**
     * Cheap VM checkpoint used by loop safepoints, actor-turn boundaries, and
     * runtime handle creation. One queue drain every N checkpoints bounds
     * cleanup latency without adding a hidden collector thread.
     */
    public void maybeCollect() {
        if (closed.get()) return;
        long checkpoint = checkpoints.incrementAndGet();
        if (checkpoint % periodicCheckpoints == 0L) collect(null, true);
    }

    public GcReport stats() {
        return new GcReport(
                0L,
                0L,
                tracked.size(),
                collections.get(),
                totalCleaned.get(),
                totalFailures.get());
    }

    private GcReport collect(Object actorKey, boolean processWide) {
        drainQueue();
        collections.incrementAndGet();

        long cleaned = 0L;
        long failed = 0L;
        int candidates = ready.size();
        for (int i = 0; i < candidates; i++) {
            TrackedReference reference = ready.poll();
            if (reference == null) break;
            if (reference.cleaned.get()) continue;

            if (!processWide && !Objects.equals(reference.actorKey, actorKey)) {
                ready.offer(reference);
                continue;
            }

            CleanResult result = reference.clean();
            if (result == CleanResult.CLEANED) cleaned++;
            else if (result == CleanResult.FAILED) failed++;
        }

        return new GcReport(
                cleaned,
                failed,
                tracked.size(),
                collections.get(),
                totalCleaned.get(),
                totalFailures.get());
    }

    private void drainQueue() {
        Reference<?> reference;
        while ((reference = queue.poll()) != null) {
            if (reference instanceof RuntimeGarbageCollector.TrackedReference trackedReference) {
                ready.offer(trackedReference);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        drainQueue();
        // Runtime teardown owns every remaining fallback resource, including
        // handles that are still strongly reachable from host code.
        for (TrackedReference reference : List.copyOf(tracked)) {
            reference.clean();
        }
        tracked.clear();
        ready.clear();
    }

    public final class Registration implements AutoCloseable {
        private final TrackedReference reference;

        private Registration(TrackedReference reference) {
            this.reference = reference;
        }

        public boolean active() {
            return !reference.cleaned.get();
        }

        @Override
        public void close() {
            reference.clean();
        }

        // Deterministic queue injection for runtime-package tests only. This is
        // not part of the guest language surface.
        boolean enqueueForTesting() {
            return reference.enqueue();
        }
    }

    private final class TrackedReference extends PhantomReference<Object> {
        private final Object actorKey;
        @SuppressWarnings("unused")
        private final String category;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private volatile Runnable cleanup;

        private TrackedReference(Object owner, Object actorKey, String category, Runnable cleanup) {
            super(owner, queue);
            this.actorKey = actorKey;
            this.category = category;
            this.cleanup = cleanup;
        }

        private CleanResult clean() {
            if (!cleaned.compareAndSet(false, true)) return CleanResult.NONE;

            tracked.remove(this);
            clear();
            Runnable action = cleanup;
            cleanup = null; // release captured runtime state after cleanup

            if (action == null) return CleanResult.NONE;
            try {
                action.run();
                totalCleaned.incrementAndGet();
                return CleanResult.CLEANED;
            } catch (VirtualMachineError fatal) {
                totalFailures.incrementAndGet();
                throw fatal;
            } catch (ThreadDeath fatal) {
                totalFailures.incrementAndGet();
                throw fatal;
            } catch (LinkageError fatal) {
                totalFailures.incrementAndGet();
                throw fatal;
            } catch (Throwable cleanupFailure) {
                // Runtime cleaners are required to be idempotent and no-throw.
                // Count a violation without turning a periodic maintenance
                // sweep into an arbitrary guest-code failure.
                totalFailures.incrementAndGet();
                return CleanResult.FAILED;
            }
        }
    }
}
