package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in exploratory measurements; CI must report actual numbers, not assert
 * timing SLAs against shared runners. Enable with -Dores.corelog.bench=true.
 *
 * Direct recorder capacity is intentionally larger than the production 8192
 * slots to isolate per-event costs from early saturation. A separate benchmark
 * must characterize the actual 8192-entry stop-on-saturation behavior.
 */
@EnabledIfSystemProperty(named = "ores.corelog.bench", matches = "true")
final class CoreLogBenchmarkTest {
    private static final int DISABLED_EVENTS = 1_000_000;
    private static final int ENABLED_EVENTS = 150_000;
    private static final int WORKERS = 4;
    private static final int PER_WORKER = 40_000;
    private static final int ACTOR_MESSAGES = 500;

    @Test void measureRecordingThroughputAndContention() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        for (int i = 0; i < 100_000; i++)
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
        long begin = System.nanoTime();
        for (int i = 0; i < DISABLED_EVENTS; i++) {
            assertFalse(CoreLog.enabled(CoreLog.Level.ERROR));
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
        }
        report("disabled", DISABLED_EVENTS, System.nanoTime() - begin);

        var warm = new CoreLog.Recorder(20_000);
        for (int i = 0; i < 19_000; i++)
            warm.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
        var solo = new CoreLog.Recorder(ENABLED_EVENTS + 1);
        begin = System.nanoTime();
        for (int i = 0; i < ENABLED_EVENTS; i++)
            solo.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
        report("enabled_single_thread", ENABLED_EVENTS, System.nanoTime() - begin);

        var shared = new CoreLog.Recorder(WORKERS * PER_WORKER + 1);
        var ready = new CountDownLatch(WORKERS);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(WORKERS)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < WORKERS; t++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < PER_WORKER; i++)
                        shared.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            begin = System.nanoTime();
            go.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            report("enabled_4_threads", WORKERS * PER_WORKER, System.nanoTime() - begin);
        }
    }

    @Test void measureActorQueueLatencyWithCoreLogOffAndOn() throws Exception {
        for (var level : new CoreLog.Level[] {CoreLog.Level.OFF, CoreLog.Level.ERROR}) {
            CoreLog.configure(level);
            try (var runtime = new ActorRuntime()) {
                var observed = new long[ACTOR_MESSAGES];
                var sequence = new AtomicInteger();
                var received = new CountDownLatch(ACTOR_MESSAGES);
                var ref = runtime.<Long>spawn(() -> (sentAt, ctx) -> {
                    int i = sequence.getAndIncrement();
                    observed[i] = Math.max(0L, System.nanoTime() - sentAt);
                    if (level != CoreLog.Level.OFF)
                        CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
                    received.countDown();
                    if (i == ACTOR_MESSAGES - 1) ctx.self().stop();
                });
                long start = System.nanoTime();
                for (int i = 0; i < ACTOR_MESSAGES; i++) ref.send(System.nanoTime());
                assertTrue(received.await(15, TimeUnit.SECONDS));
                assertTrue(ref.awaitTermination(15, TimeUnit.SECONDS));
                long elapsed = System.nanoTime() - start;
                Arrays.sort(observed);
                System.out.println("CORE_LOG_ACTOR_BENCH level=" + level.name().toLowerCase()
                        + " events=" + ACTOR_MESSAGES
                        + " queue_latency_p50_ns=" + percentile(observed, 50)
                        + " queue_latency_p95_ns=" + percentile(observed, 95)
                        + " queue_latency_p99_ns=" + percentile(observed, 99)
                        + " duration_ns=" + elapsed);
            } finally {
                CoreLog.configure(CoreLog.Level.OFF);
            }
        }
    }

    private static long percentile(long[] sorted, int percentage) {
        return sorted[(int) Math.ceil(sorted.length * percentage / 100.0) - 1];
    }

    private static void report(String kind, int events, long elapsedNs) {
        double nanosPerEvent = (double) elapsedNs / events;
        double eventsPerSecond = events * 1_000_000_000.0 / Math.max(1L, elapsedNs);
        System.out.println("CORE_LOG_RECORDER_BENCH kind=" + kind
                + " events=" + events
                + " elapsed_ns=" + elapsedNs
                + " nanos_per_event=" + nanosPerEvent
                + " events_per_second=" + eventsPerSecond);
    }
}
