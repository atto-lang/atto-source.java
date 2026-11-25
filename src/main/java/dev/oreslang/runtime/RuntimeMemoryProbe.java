package dev.oreslang.runtime;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.OptionalLong;

/**
 * Explicit host-boundary allocation/GC sampling outside a measured guest loop.
 *
 * These counters are NOT ownership proof, per-guest attribution, per-actor GC,
 * GC pause quantiles or zero-allocation certification. The JVM collector is
 * allowed to run even if Oreslang guest resources use deterministic cleanup.
 *
 * No System.gc(), GC scheduling, or sampling is introduced into actor turns.
 * This is opt-in diagnostic code; callers sample before/after a batch.
 */
public final class RuntimeMemoryProbe {
    private RuntimeMemoryProbe() {}

    public record Snapshot(
            OptionalLong currentThreadAllocatedBytes,
            OptionalLong globalCollectionCount,
            OptionalLong globalCollectionTimeMillis,
            int unobservableCollectors) {}

    public record Delta(
            OptionalLong currentThreadAllocatedBytes,
            OptionalLong globalCollectionCount,
            OptionalLong globalCollectionTimeMillis) {}

    public static Snapshot sample() {
        OptionalLong bytes = OptionalLong.empty();
        try {
            var threadBean = ManagementFactory.getThreadMXBean();
            if (threadBean instanceof com.sun.management.ThreadMXBean jdk
                    && jdk.isThreadAllocatedMemorySupported()
                    && jdk.isThreadAllocatedMemoryEnabled()) {
                long observed = jdk.getThreadAllocatedBytes(Thread.currentThread().threadId());
                if (observed >= 0L) bytes = OptionalLong.of(observed);
            }
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            // Native Image/host monitor APIs may be absent or restricted.
            // Unavailable is not zero allocated bytes.
        }
        long count = 0L;
        long time = 0L;
        int unknown = 0;
        List<GarbageCollectorMXBean> collectors;
        try {
            collectors = ManagementFactory.getGarbageCollectorMXBeans();
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            return new Snapshot(bytes, OptionalLong.empty(), OptionalLong.empty(), 1);
        }
        if (collectors.isEmpty()) {
            // No observable collector does not prove a collector-free runtime.
            return new Snapshot(bytes, OptionalLong.empty(), OptionalLong.empty(), 1);
        }
        for (GarbageCollectorMXBean collector : collectors) {
            try {
                long observedCount = collector.getCollectionCount();
                long observedMillis = collector.getCollectionTime();
                if (observedCount < 0L || observedMillis < 0L) {
                    unknown++;
                    continue;
                }
                // Fail closed on overflow rather than publishing wrapped metrics.
                count = Math.addExact(count, observedCount);
                time = Math.addExact(time, observedMillis);
            } catch (ArithmeticException | UnsupportedOperationException | SecurityException unsupported) {
                unknown++;
            }
        }
        OptionalLong collections = unknown == 0
                ? OptionalLong.of(count) : OptionalLong.empty();
        OptionalLong elapsed = unknown == 0
                ? OptionalLong.of(time) : OptionalLong.empty();
        return new Snapshot(bytes, collections, elapsed, unknown);
    }

    private static OptionalLong difference(OptionalLong before, OptionalLong after) {
        if (before.isEmpty() || after.isEmpty()) return OptionalLong.empty();
        long b = before.getAsLong(), a = after.getAsLong();
        if (b < 0L || a < b) return OptionalLong.empty();
        return OptionalLong.of(a - b);
    }

    public static Delta difference(Snapshot before, Snapshot after) {
        if (before == null || after == null) throw new IllegalArgumentException(
                "two measured snapshots are required");
        return new Delta(
                difference(before.currentThreadAllocatedBytes(), after.currentThreadAllocatedBytes()),
                difference(before.globalCollectionCount(), after.globalCollectionCount()),
                difference(before.globalCollectionTimeMillis(), after.globalCollectionTimeMillis()));
    }
}
