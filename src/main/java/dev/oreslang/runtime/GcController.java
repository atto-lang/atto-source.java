package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual collection control exposed by process.gc().
 *
 * Oreslang's semantic lifetime model is ownership/drop first, collector second.
 * The current Java reference runtime still uses the JVM heap as its backing
 * store, so the manual operation is a rate-limited host-GC hint rather than a
 * promise of a physically actor-local collection pause.
 */
public final class GcController {
    private static final long MIN_HOST_GC_INTERVAL_NANOS = 250_000_000L;

    private final ActorRuntime actors;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong executedHostHints = new AtomicLong();
    private final AtomicLong coalescedRequests = new AtomicLong();
    private final AtomicLong lastHostGcNanos = new AtomicLong(Long.MIN_VALUE);

    public GcController(ActorRuntime actors) {
        this.actors = java.util.Objects.requireNonNull(actors);
    }

    public Map<String, Object> collect() {
        long request = requests.incrementAndGet();
        actors.noteManualGcRequest();

        long before = usedHeapBytes();
        boolean hostHintEnabled = Boolean.parseBoolean(System.getProperty("oreslang.gc.host-hint", "true"));
        boolean executed = hostHintEnabled && reserveHostGcSlot(System.nanoTime());

        long started = System.nanoTime();
        if (executed) {
            System.gc();
            executedHostHints.incrementAndGet();
        } else {
            coalescedRequests.incrementAndGet();
        }
        long elapsed = System.nanoTime() - started;
        long after = usedHeapBytes();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", request);
        result.put("scope", actors.currentActorId().map(id -> "actor:" + id).orElse("context"));
        result.put("semantic_policy", "ownership_drop_then_gc");
        result.put("backing_collector", "jvm");
        result.put("host_gc_hint_enabled", hostHintEnabled);
        result.put("host_gc_hint_executed", executed);
        result.put("used_bytes_before", before);
        result.put("used_bytes_after", after);
        result.put("elapsed_nanos", elapsed);
        result.put("executed_host_hints", executedHostHints.get());
        result.put("coalesced_requests", coalescedRequests.get());
        return Map.copyOf(result);
    }

    public long requests() {
        return requests.get();
    }

    private boolean reserveHostGcSlot(long now) {
        while (true) {
            long previous = lastHostGcNanos.get();
            if (previous != Long.MIN_VALUE && now - previous < MIN_HOST_GC_INTERVAL_NANOS) return false;
            if (lastHostGcNanos.compareAndSet(previous, now)) return true;
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }
}
