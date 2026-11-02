package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secondary runtime reclamation for host/interop resources that are not governed
 * solely by Oreslang ownership. Guest memory safety remains ownership/borrow
 * based; this collector cannot make an invalid ownership program valid.
 */
public final class RuntimeGarbageCollector implements AutoCloseable {
    private static final Duration DEFAULT_PERIOD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_MIN_PROCESS_GC_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_TRACKED = 100_000;
    private static final int DEFAULT_MAX_TRACKED_PER_ACTOR = 4_096;
    private static final int DEFAULT_MAX_ACTOR_SWEEP_ENTRIES = 256;
    private static final int DEFAULT_MAX_ACTOR_EXIT_SWEEP_ENTRIES = 32;
    private static final int DEFAULT_RETIRED_DOMAIN_SWEEP_QUANTUM = 32;
    private static final int DEFAULT_MAX_PERIODIC_SWEEP_ENTRIES = 256;
    private static final Object PROCESS_DOMAIN = new Object();
    private static final ScheduledThreadPoolExecutor SWEEP_TIMER = createSweepTimer();

    private static ScheduledThreadPoolExecutor createSweepTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "ores-gc-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    public record CollectionReport(
            String scope,
            long collection,
            int trackedBefore,
            int inspected,
            int cleaned,
            int trackedAfter,
            int cleanupFailures,
            boolean jvmGcRequested) {
        public Map<String,Object> asMap() {
            return Map.of(
                    "scope", scope,
                    "collection", collection,
                    "tracked_before", trackedBefore,
                    "inspected", inspected,
                    "cleaned", cleaned,
                    "tracked_after", trackedAfter,
                    "cleanup_failures", cleanupFailures,
                    "jvm_gc_requested", jvmGcRequested);
        }
    }

    private static final class TrackedCleanup extends WeakReference<Object> {
        // A completed weak-reference entry may remain in ReferenceQueue after
        // its registry slot is gone. Sever its actor-domain link on completion
        // so queued stale notifications cannot pin retired actor heaps.
        private volatile Object domain;
        private Runnable cleanup;
        private final AtomicBoolean cleaning = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();

        private TrackedCleanup(
                Object owner,
                Object domain,
                Runnable cleanup,
                ReferenceQueue<Object> queue) {
            super(Objects.requireNonNull(owner), queue);
            this.domain = Objects.requireNonNull(domain);
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        private boolean eligible(Set<Object> retiredDomains) {
            Object currentDomain = domain;
            return get() == null
                    || (currentDomain != null && retiredDomains.contains(currentDomain));
        }

        private boolean tryClean() {
            if (cleaned.get() || !cleaning.compareAndSet(false, true)) return false;
            try {
                // Another caller may have completed between the initial check
                // and our acquisition of the cleanup slot.
                if (cleaned.get()) return false;
                cleanup.run();
                cleanup = null; // A retained closed handle must not retain captured resources.
                cleaned.set(true);
                return true;
            } finally {
                cleaning.set(false);
            }
        }
    }

    public final class CleanupHandle implements AutoCloseable {
        private final TrackedCleanup entry;
        private CleanupHandle(TrackedCleanup entry) { this.entry = entry; }

        @Override
        public void close() {
            if (entry.cleaned.get()) {
                removeTracked(entry);
                return;
            }
            try {
                boolean cleanedNow = entry.tryClean();
                if (cleanedNow) {
                    removeTracked(entry);
                } else if (!entry.cleaned.get()) {
                    // A concurrent cleanup owns the slot. If it later fails,
                    // retain an independent retry path even without a queue event.
                    retainRetry(entry);
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (RuntimeException | Error cleanupFailure) {
                retainRetry(entry);
                throw cleanupFailure;
            }
        }
    }

    private final Set<TrackedCleanup> tracked = ConcurrentHashMap.newKeySet();
    private final Set<TrackedCleanup> retryableFailures = ConcurrentHashMap.newKeySet();
    private final Map<Object, Set<TrackedCleanup>> trackedByDomain = new ConcurrentHashMap<>();
    private final Set<Object> retiredDomains = ConcurrentHashMap.newKeySet();
    // Every retired actor domain owns at most one round-robin maintenance ticket.
    private final ConcurrentLinkedQueue<Object> retiredDomainQueue = new ConcurrentLinkedQueue<>();
    private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private final AtomicLong collections = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastJvmGcNanos = new AtomicLong(Long.MIN_VALUE);
    private final Object lifecycleLock = new Object();
    private final Runnable jvmGcRequest;
    private final Duration minProcessGcInterval;
    private final int maxTracked;
    private final int maxTrackedPerActor;
    private final int maxActorSweepEntries;
    // No periodic task exists for the common case: wholly ownership-managed values.
    // Protected by lifecycleLock; cancellation removes this task from the shared timer.
    private ScheduledFuture<?> periodicSweep;
    private final long periodNanos;

    public RuntimeGarbageCollector() {
        this(
                System::gc,
                DEFAULT_PERIOD,
                DEFAULT_MIN_PROCESS_GC_INTERVAL,
                DEFAULT_MAX_TRACKED,
                DEFAULT_MAX_TRACKED_PER_ACTOR,
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(Runnable jvmGcRequest, Duration period) {
        this(
                jvmGcRequest,
                period,
                DEFAULT_MIN_PROCESS_GC_INTERVAL,
                DEFAULT_MAX_TRACKED,
                DEFAULT_MAX_TRACKED_PER_ACTOR,
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(
            Runnable jvmGcRequest,
            Duration period,
            Duration minProcessGcInterval,
            int maxTracked) {
        this(
                jvmGcRequest,
                period,
                minProcessGcInterval,
                maxTracked,
                Math.min(maxTracked, DEFAULT_MAX_TRACKED_PER_ACTOR),
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(
            Runnable jvmGcRequest,
            Duration period,
            Duration minProcessGcInterval,
            int maxTracked,
            int maxTrackedPerActor,
            int maxActorSweepEntries) {
        this.jvmGcRequest = Objects.requireNonNull(jvmGcRequest);
        this.minProcessGcInterval = requirePositive(minProcessGcInterval, "minimum process GC interval");
        requirePositive(period, "GC sweep period");
        if (maxTracked <= 0) throw new IllegalArgumentException("maxTracked must be positive");
        if (maxTrackedPerActor <= 0) throw new IllegalArgumentException("maxTrackedPerActor must be positive");
        if (maxTrackedPerActor > maxTracked) throw new IllegalArgumentException("maxTrackedPerActor cannot exceed maxTracked");
        if (maxActorSweepEntries <= 0) throw new IllegalArgumentException("maxActorSweepEntries must be positive");
        this.maxTracked = maxTracked;
        this.maxTrackedPerActor = maxTrackedPerActor;
        this.maxActorSweepEntries = maxActorSweepEntries;
        this.periodNanos = period.toNanos();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /**
     * Registers an idempotent cleanup hook. The owner is weakly referenced;
     * callers must not capture the owner strongly from the cleanup closure.
     */
    public CleanupHandle track(Object owner, Runnable cleanup) {
        synchronized (lifecycleLock) {
            ensureOpen();
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(cleanup, "cleanup");
            if (tracked.size() >= maxTracked) {
                throw new IllegalStateException("runtime cleanup registry limit exceeded: " + maxTracked);
            }
            Object actorDomain = ActorRuntime.currentActorExecutionDomain();
            Object domain = actorDomain == null ? PROCESS_DOMAIN : actorDomain;
            Set<TrackedCleanup> domainEntries =
                    trackedByDomain.computeIfAbsent(domain, ignored -> ConcurrentHashMap.newKeySet());
            if (actorDomain != null && domainEntries.size() >= maxTrackedPerActor) {
                throw new IllegalStateException(
                        "actor cleanup registry limit exceeded: " + maxTrackedPerActor);
            }
            // Delay housekeeping until the first host/interop resource actually
            // needs fallback cleanup. Ordinary owned values never register here.
            if (periodicSweep == null) {
                periodicSweep = SWEEP_TIMER.scheduleWithFixedDelay(
                        this::safePeriodicSweep,
                        periodNanos,
                        periodNanos,
                        TimeUnit.NANOSECONDS);
            }
            TrackedCleanup entry = new TrackedCleanup(owner, domain, cleanup, referenceQueue);
            tracked.add(entry);
            domainEntries.add(entry);
            return new CleanupHandle(entry);
        }
    }

    public CollectionReport collectProcess() {
        ensureOpen();
        boolean requested = requestJvmGcIfAllowed();
        return sweep(null, requested, Integer.MAX_VALUE);
    }

    public CollectionReport collectCurrentActor() {
        ensureOpen();
        Object actorDomain = ActorRuntime.currentActorExecutionDomain();
        if (actorDomain == null) throw new IllegalStateException("actor.gc() requires execution inside an actor");
        return sweep(actorDomain, false, maxActorSweepEntries);
    }

    /**
     * Perform at most one bounded fallback-cleanup quantum. This method never
     * requests JVM GC; process.gc() is the explicit full-sweep entry point.
     * A cleanup callback itself may take arbitrary time, so callers must not
     * treat this budget as a real-time deadline.
     */
    public CollectionReport collectPeriodic() {
        ensureOpen();
        return sweep(null, false, DEFAULT_MAX_PERIODIC_SWEEP_ENTRIES);
    }

    /**
     * Retires one semantic actor domain. Actor-local runtime resources are
     * deterministic actor-lifetime resources: actor termination makes them
     * cleanup-eligible even if a stale host reference still exists.
     *
     * Failed cleanup hooks remain registered and are retried by later process
     * or periodic sweeps. The per-actor registry cap bounds exit work.
     */
    public CollectionReport retireActorDomain(Object actorDomain) {
        Objects.requireNonNull(actorDomain, "actorDomain");
        synchronized (lifecycleLock) {
            ensureOpen();
            // Actors without resources are the common case. Do not retain their
            // domain (and potentially their heap) indefinitely in a tombstone set.
            if (trackedByDomain.containsKey(actorDomain) && retiredDomains.add(actorDomain)) {
                retiredDomainQueue.offer(actorDomain);
            }
        }
        // Actor termination runs on a latency-sensitive path. Defer remaining
        // cleanup instead of visiting thousands of hooks synchronously.
        return sweep(actorDomain, false,
                Math.min(maxActorSweepEntries, DEFAULT_MAX_ACTOR_EXIT_SWEEP_ENTRIES));
    }

    private boolean requestJvmGcIfAllowed() {
        long now = System.nanoTime();
        long previous = lastJvmGcNanos.get();
        long minGap = minProcessGcInterval.toNanos();
        if (previous != Long.MIN_VALUE && now - previous < minGap) return false;
        if (!lastJvmGcNanos.compareAndSet(previous, now)) return false;
        try {
            jvmGcRequest.run();
            return true;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private CollectionReport sweep(
            Object requestedDomain,
            boolean jvmGcRequested,
            int maxEntriesToInspect) {
        int before = requestedDomain == null
                ? tracked.size()
                : trackedByDomain.getOrDefault(requestedDomain, Set.of()).size();
        int inspected = 0;
        int cleanedCount = 0;
        int cleanupFailures = 0;

        if (requestedDomain == null) {
            // Reserve a bounded maintenance slice for retired actor heaps:
            // retry storms and dead-owner queue bursts cannot starve them.
            boolean pendingRetirements = !retiredDomainQueue.isEmpty();
            int retryLimit = pendingRetirements && maxEntriesToInspect != Integer.MAX_VALUE
                    ? Math.max(1, maxEntriesToInspect / 4) : maxEntriesToInspect;
            int weakQueueLimit = pendingRetirements && maxEntriesToInspect != Integer.MAX_VALUE
                    ? maxEntriesToInspect - Math.max(1, maxEntriesToInspect / 4)
                    : maxEntriesToInspect;
            // Weakly-consistent concurrent iterators avoid allocating a full
            // snapshot of potentially thousands of registrations per tick.
            for (TrackedCleanup entry : retryableFailures) {
                if (inspected >= retryLimit) break;
                // Stale retry notifications still cost time to scan. Charge
                // them against the same quantum as live registrations.
                inspected++;
                if (!tracked.contains(entry)) {
                    retryableFailures.remove(entry);
                    continue;
                }
                try {
                    if (entry.tryClean()) {
                        cleanedCount++;
                        removeTracked(entry);
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable cleanupFailure) {
                    cleanupFailures++;
                }
            }

            while (inspected < weakQueueLimit) {
                TrackedCleanup entry = (TrackedCleanup) referenceQueue.poll();
                if (entry == null) break;
                // A closed handle may enqueue later; stale reference-queue
                // notifications must not bypass the background work budget.
                inspected++;
                if (!tracked.contains(entry)) continue;
                try {
                    if (entry.tryClean()) {
                        cleanedCount++;
                        removeTracked(entry);
                    } else if (!entry.cleaned.get()) {
                        // Polling consumes the only queue notification. A
                        // concurrent explicit release can still fail, so keep
                        // this entry eligible for a later sweep.
                        retainRetry(entry);
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable cleanupFailure) {
                    retainRetry(entry);
                    cleanupFailures++;
                }
            }

            // Rotate each retired domain after a small quantum. Stale queue
            // tickets cost one unit, and failed cleanup cannot spin forever
            // inside process.gc(), even if all hooks continually throw.
            int retiredWorkLimit = maxEntriesToInspect == Integer.MAX_VALUE
                    ? maxTracked : maxEntriesToInspect;
            while (inspected < retiredWorkLimit) {
                Object retired = retiredDomainQueue.poll();
                if (retired == null) break;
                if (!retiredDomains.contains(retired)) {
                    inspected++;
                    continue;
                }
                Set<TrackedCleanup> entries = trackedByDomain.getOrDefault(retired, Set.of());
                if (entries.isEmpty()) {
                    retiredDomains.remove(retired);
                    inspected++;
                    continue;
                }
                int domainBudget = Math.min(
                        DEFAULT_RETIRED_DOMAIN_SWEEP_QUANTUM, retiredWorkLimit - inspected);
                int domainInspected = 0;
                for (TrackedCleanup entry : entries) {
                    if (domainInspected >= domainBudget) break;
                    inspected++;
                    domainInspected++;
                    try {
                        if (entry.tryClean()) {
                            cleanedCount++;
                            removeTracked(entry);
                        } else if (!entry.cleaned.get()) {
                            retainRetry(entry);
                        }
                    } catch (VirtualMachineError | ThreadDeath fatal) {
                        throw fatal;
                    } catch (Throwable cleanupFailure) {
                        retainRetry(entry);
                        cleanupFailures++;
                    }
                }
                if (retiredDomains.contains(retired)) {
                    retiredDomainQueue.offer(retired);
                }
            }
        } else {
            Set<TrackedCleanup> candidates =
                    trackedByDomain.getOrDefault(requestedDomain, Set.of());
            for (TrackedCleanup entry : candidates) {
                if (inspected >= maxEntriesToInspect) break;
                inspected++;
                if (!entry.eligible(retiredDomains)) continue;
                try {
                    if (entry.tryClean()) {
                        cleanedCount++;
                        removeTracked(entry);
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable cleanupFailure) {
                    retainRetry(entry);
                    cleanupFailures++;
                }
            }
        }

        int after = requestedDomain == null
                ? tracked.size()
                : trackedByDomain.getOrDefault(requestedDomain, Set.of()).size();
        return new CollectionReport(
                requestedDomain == null ? "process" : "actor",
                collections.incrementAndGet(),
                before,
                inspected,
                cleanedCount,
                after,
                cleanupFailures,
                jvmGcRequested);
    }

    private void retainRetry(TrackedCleanup entry) {
        synchronized (lifecycleLock) {
            if (!closed.get() && tracked.contains(entry) && !entry.cleaned.get()) {
                retryableFailures.add(entry);
            }
        }
    }

    private void removeTracked(TrackedCleanup entry) {
        synchronized (lifecycleLock) {
            tracked.remove(entry);
            retryableFailures.remove(entry);
            // Once the cleanup is complete, the GC no longer needs to watch
            // this owner. Explicitly disarm the weak reference so it cannot
            // become another queued notification after deterministic drop.
            entry.clear();
            Object domain = entry.domain;
            if (domain != null) {
                Set<TrackedCleanup> domainEntries = trackedByDomain.get(domain);
                if (domainEntries != null) {
                    domainEntries.remove(entry);
                    if (domainEntries.isEmpty()) {
                        trackedByDomain.remove(domain, domainEntries);
                        retiredDomains.remove(domain);
                    }
                }
                // Clear even when a concurrent release already removed the map
                // entry; the queue can retain this weak-reference object.
                entry.domain = null;
            }
            // Deterministic final release is the normal path. Stop even the
            // bounded fallback ticker when no unmanaged registrations remain.
            if (tracked.isEmpty() && periodicSweep != null) {
                periodicSweep.cancel(false);
                periodicSweep = null;
            }
        }
    }

    private void safePeriodicSweep() {
        if (closed.get() || tracked.isEmpty()) return;
        try {
            collectPeriodic();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Periodic housekeeping is best-effort. Cleanup hook failures are
            // retained for retry and must not kill the shared timer thread.
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("garbage collector is closed");
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
        }
        synchronized (lifecycleLock) {
            if (periodicSweep != null) {
                periodicSweep.cancel(false);
                periodicSweep = null;
            }
        }
        for (TrackedCleanup entry : tracked) {
            try {
                if (entry.tryClean()) removeTracked(entry);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Context shutdown is best-effort for host cleanup hooks.
            }
        }
        synchronized (lifecycleLock) {
            tracked.clear();
            retryableFailures.clear();
            trackedByDomain.clear();
            retiredDomains.clear();
            retiredDomainQueue.clear();
            while (referenceQueue.poll() != null) { /* drain */ }
        }
    }
}
