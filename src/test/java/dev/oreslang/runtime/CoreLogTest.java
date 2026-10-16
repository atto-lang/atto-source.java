package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CoreLogTest {
    @Test void strictSeverityAdmissionAndFiltering() {
        assertEquals(CoreLog.Level.OFF, CoreLog.Level.parse("OFF"));
        assertEquals(CoreLog.Level.ERROR, CoreLog.Level.parse("error"));
        assertEquals(CoreLog.Level.WARN, CoreLog.Level.parse("Warn"));
        assertEquals(CoreLog.Level.INFO, CoreLog.Level.parse("info"));
        assertEquals(CoreLog.Level.DEBUG, CoreLog.Level.parse("debug"));
        assertEquals(CoreLog.Level.TRACE, CoreLog.Level.parse("trace"));
        for (String invalid : new String[] {"", "notice", "all", "fatal", "info ", "none"}) {
            assertThrows(IllegalArgumentException.class, () -> CoreLog.Level.parse(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> CoreLog.Level.parse(null));
        CoreLog.configure(CoreLog.Level.OFF);
        assertFalse(CoreLog.enabled(CoreLog.Level.ERROR));
        assertFalse(CoreLog.enabled(CoreLog.Level.TRACE));
        CoreLog.configure(CoreLog.Level.WARN);
        try {
            assertTrue(CoreLog.enabled(CoreLog.Level.ERROR));
            assertTrue(CoreLog.enabled(CoreLog.Level.WARN));
            assertFalse(CoreLog.enabled(CoreLog.Level.INFO));
            assertFalse(CoreLog.enabled(CoreLog.Level.DEBUG));
            assertFalse(CoreLog.enabled(CoreLog.Level.TRACE));
            assertFalse(CoreLog.enabled(CoreLog.Level.OFF));
            assertFalse(CoreLog.enabled(null));
            assertEquals(CoreLog.Level.WARN, CoreLog.currentLevel());
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
        assertEquals(CoreLog.Level.OFF, CoreLog.currentLevel());
    }

    @Test void boundedEventsHaveOnlyFixedNamesAndNumericValues() {
        var recorder = new CoreLog.Recorder(2);
        recorder.record(CoreLog.Level.DEBUG, CoreLog.ASYNC_IO_REJECTED, 17L);
        recorder.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, Long.MIN_VALUE);
        recorder.record(CoreLog.Level.INFO, CoreLog.ACTOR_DISPATCHER_FAILED, 999L);
        String output = dump(recorder);
        assertTrue(output.contains("\"event\":\"async.io.rejected\""));
        assertTrue(output.contains("\"severity\":\"debug\""));
        assertTrue(output.contains("\"value\":17"));
        assertTrue(output.contains("\"event\":\"async.io.failed\""));
        assertTrue(output.contains("\"severity\":\"error\""));
        assertTrue(output.contains("\"value\":" + Long.MIN_VALUE));
        assertFalse(output.contains("actor.dispatcher.failed"));
        assertTrue(output.contains("\"recorded\":2,\"reserved\":2,\"saturated\":true"));
        assertEquals(2, output.lines().filter(s -> s.contains("\"kind\":\"event\"")).count());
    }

    @Test void rejectedEventsDoNotConsumeSlots() {
        var recorder = new CoreLog.Recorder(1);
        recorder.record(CoreLog.Level.OFF, CoreLog.ASYNC_IO_FAILED, 100);
        recorder.record(null, CoreLog.ASYNC_IO_FAILED, 100);
        recorder.record(CoreLog.Level.ERROR, 0, 100);
        recorder.record(CoreLog.Level.ERROR, Integer.MAX_VALUE, 100);
        assertTrue(dump(recorder).contains("\"reserved\":0"));
        recorder.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 7);
        assertTrue(dump(recorder).contains("\"recorded\":1,\"reserved\":1"));
    }

    @Test void concurrentPublishAndSaturationNeverExposeIncompleteRows() throws Exception {
        var recorder = new CoreLog.Recorder(96);
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 6; t++) {
                futures.add(workers.submit(() -> {
                    gate.await();
                    for (int i = 0; i < 128; i++) {
                        recorder.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 11L);
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        String output = dump(recorder);
        assertEquals(96, output.lines().filter(s -> s.contains("\"kind\":\"event\"")).count());
        assertTrue(output.contains("\"recorded\":96,\"reserved\":96,\"saturated\":true"));
        assertTrue(output.lines().filter(s -> s.contains("\"kind\":\"event\""))
                .allMatch(s -> s.contains("\"value\":11") && s.contains("\"thread_id\":")
                        && s.contains("\"severity\":\"warn\"")));
    }

    @Test void filteredAndInvalidPublicCallsCannotThrow() {
        CoreLog.configure(CoreLog.Level.ERROR);
        try {
            assertDoesNotThrow(() -> CoreLog.record(CoreLog.Level.DEBUG, CoreLog.ASYNC_IO_FAILED, 1));
            assertDoesNotThrow(() -> CoreLog.record(CoreLog.Level.ERROR, Integer.MIN_VALUE, 1));
            assertDoesNotThrow(() -> CoreLog.record(null, CoreLog.ASYNC_IO_FAILED, 1));
            assertDoesNotThrow(() -> CoreLog.record(CoreLog.Level.OFF, CoreLog.ASYNC_IO_FAILED, 1));
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test void disablingDropsAllRetainedEventsAndSuppressesShutdownExport() {
        CoreLog.configure(CoreLog.Level.OFF);
        try {
            CoreLog.configure(CoreLog.Level.ERROR);
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 77L);
            assertTrue(dumpRetained().contains("\"value\":77"));
            CoreLog.configure(CoreLog.Level.OFF);
            assertEquals("", dumpRetained(), "no diagnostic output once disabled");
            assertFalse(CoreLog.enabled(CoreLog.Level.ERROR));

            // A fresh session must never replay data recorded before disable.
            CoreLog.configure(CoreLog.Level.INFO);
            String fresh = dumpRetained();
            assertTrue(fresh.contains("\"reserved\":0"));
            assertFalse(fresh.contains("\"value\":77"));
            CoreLog.record(CoreLog.Level.INFO, CoreLog.ASYNC_IO_REJECTED, 88L);
            String after = dumpRetained();
            assertTrue(after.contains("\"value\":88"));
            assertFalse(after.contains("\"value\":77"));
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
        assertEquals("", dumpRetained());
    }

    @Test void severityChangeKeepsCurrentSessionUntilExplicitOff() {
        CoreLog.configure(CoreLog.Level.OFF);
        try {
            CoreLog.configure(CoreLog.Level.WARN);
            CoreLog.record(CoreLog.Level.INFO, CoreLog.ASYNC_IO_FAILED, 901L);
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 902L);
            CoreLog.configure(CoreLog.Level.ERROR);
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 903L);
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 904L);
            String snapshot = dumpRetained();
            assertFalse(snapshot.contains("\"value\":901"));
            assertTrue(snapshot.contains("\"value\":902"));
            assertFalse(snapshot.contains("\"value\":903"));
            assertTrue(snapshot.contains("\"value\":904"));
            assertTrue(snapshot.contains("\"recorded\":2,\"reserved\":2"));
        } finally {
            CoreLog.configure(CoreLog.Level.OFF);
        }
    }

    @Test void disabledLoggerDoesNotAllocateOnHotPath() {
        CoreLog.configure(CoreLog.Level.OFF);
        var rawBean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                rawBean instanceof com.sun.management.ThreadMXBean,
                "per-thread allocation counters unavailable");
        var bean = (com.sun.management.ThreadMXBean) rawBean;
        org.junit.jupiter.api.Assumptions.assumeTrue(
                bean.isThreadAllocatedMemorySupported(),
                "per-thread allocation counters unavailable");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        long tid = Thread.currentThread().threadId();
        for (int i = 0; i < 100_000; i++) {
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
            CoreLog.enabled(CoreLog.Level.WARN);
        }
        // ThreadMXBean reads themselves can incur fixed lazy/JVM bookkeeping
        // allocations, which must not be confused with per-event allocations.
        long baselineStart = bean.getThreadAllocatedBytes(tid);
        long baselineEnd = bean.getThreadAllocatedBytes(tid);
        long baseline = baselineEnd - baselineStart;
        long before = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 300_000; i++) {
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, i);
            CoreLog.enabled(CoreLog.Level.WARN);
        }
        long after = bean.getThreadAllocatedBytes(tid);
        assertTrue(baseline >= 0 && before >= 0 && after >= 0,
                "thread allocation counters must be readable");
        assertTrue(after - before <= baseline + 512L,
                "disabled hot path must not allocate per event (measured " + (after - before)
                        + " bytes across 300000 calls; counter baseline " + baseline + ")");
    }

    private static String dumpRetained() {
        var bytes = new ByteArrayOutputStream();
        CoreLog.dumpRetained(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String dump(CoreLog.Recorder recorder) {
        var bytes = new ByteArrayOutputStream();
        recorder.dump(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
