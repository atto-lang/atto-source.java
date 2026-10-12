package dev.oreslang.runtime;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Instrumented allocation probe, not a throughput benchmark or an assertion
 * that the entire JVM has no GC. Only the admitted actor's native scalar
 * read/write hot path is measured after sufficient JIT warmup.
 */
final class ActorArenaAllocationProbeTest {
    @Test
    void reportsThreadAllocatedBytesPerFfmScalarReadWritePair() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var platform = ManagementFactory.getThreadMXBean();
        assumeTrue(platform instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platform;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        assumeTrue(bean.isThreadAllocatedMemoryEnabled());

        var allocated = new AtomicLong(-1L);
        var observed = new AtomicLong(-1L);
        var error = new AtomicReference<Throwable>();
        var ended = new CountDownLatch(1);
        final int warmups = 50_000;
        final int iterations = 250_000;

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var arena = ctx.actorOwnedMemory().orElseThrow().newActorArena(1, 1);
                    var slot = arena.allocatePersistent(0);
                    long sum = 0L;
                    for (int i = 0; i < warmups; i++) {
                        long v = arena.readPersistent(slot);
                        arena.writePersistent(slot, v + 1L);
                        sum += v;
                    }
                    long threadId = Thread.currentThread().getId();
                    long before = bean.getThreadAllocatedBytes(threadId);
                    for (int i = 0; i < iterations; i++) {
                        long v = arena.readPersistent(slot);
                        arena.writePersistent(slot, v + 1L);
                        sum += v;
                    }
                    long after = bean.getThreadAllocatedBytes(threadId);
                    observed.set(sum);
                    if (before >= 0 && after >= before) allocated.set(after - before);
                    arena.dropPersistent(slot);
                    arena.close();
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                } finally {
                    ended.countDown();
                    ctx.self().stop();
                }
            });
            actor.send("measure");
            assertTrue(ended.await(20, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
        }
        assertNotEquals(-1L, observed.get(), "JIT must retain the observable read/write loop");
        assumeTrue(allocated.get() >= 0, "allocated bytes counter unavailable on actor carrier");
        System.out.printf("Oreslang FFM steady-state scalar read+write: %.4f Java allocated bytes/pair (%d pairs, %d warmups)%n",
                (double) allocated.get() / iterations, iterations, warmups);
    }
}
