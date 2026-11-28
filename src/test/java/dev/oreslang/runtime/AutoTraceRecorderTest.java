package dev.oreslang.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Internal policy and bounded numeric recorder tests, not OTLP evidence. */
final class AutoTraceRecorderTest {
    @Test
    void startsOffAndCapturesNoEventsUntilExplicitlyEnabled() {
        AutoTraceRecorder trace = new AutoTraceRecorder(4, false);
        assertFalse(trace.enabled());
        assertEquals(0L, trace.enterCall());
        trace.exitCall(0L, 42, false);
        assertEquals(0, trace.count());
        assertTrue(trace.reconfigureCalls(true));
        long started = trace.enterCall();
        assertNotEquals(0L, started);
        trace.exitCall(started, 42, false);
        assertEquals(1, trace.count());
        assertEquals(0, trace.droppedCount());
        assertTrue(trace.reconfigureCalls(false));
        assertEquals(0L, trace.enterCall());
        assertEquals(1, trace.count());
    }

    @Test
    void saturatesAndNeverWrapsOrExpandsTheFixedBuffer() {
        AutoTraceRecorder trace = new AutoTraceRecorder(1, false);
        assertTrue(trace.reconfigureCalls(true));
        for (int i = 0; i < 100; i++) {
            long started = trace.enterCall();
            trace.exitCall(started, 100 + i, i > 0);
        }
        assertEquals(1, trace.count());
        assertEquals(99, trace.droppedCount());
        StringWriter writer = new StringWriter();
        trace.dumpNumericReceipt(new PrintWriter(writer));
        String jsonl = writer.toString();
        assertTrue(jsonl.contains("\"recorded\":1"), jsonl);
        assertTrue(jsonl.contains("\"dropped\":99"), jsonl);
        assertTrue(jsonl.contains("\"phase_id\":100"), jsonl);
    }

    @Test
    void sealedContextsCannotTurnTracingOn() {
        AutoTraceRecorder trace = new AutoTraceRecorder(2, true);
        assertFalse(trace.reconfigureCalls(true));
        assertEquals(0L, trace.enterCall());
    }

    @Test
    void callableSiteTagsAreDeterministicAndKeepKindSeparate() {
        int first = AutoTraceRecorder.internalSiteId(42, "checkout", 1);
        assertEquals(first, AutoTraceRecorder.internalSiteId(42, "checkout", 1));
        assertNotEquals(first, AutoTraceRecorder.internalSiteId(42, "checkout", 2));
        assertNotEquals(first, AutoTraceRecorder.internalSiteId(42, "payment", 1));
        assertNotEquals(first, AutoTraceRecorder.internalSiteId(43, "checkout", 1));
    }

    @Test
    void twoContextsDoNotLeakEventsOrPolicy() {
        AutoTraceRecorder a = new AutoTraceRecorder(2, false);
        AutoTraceRecorder b = new AutoTraceRecorder(2, false);
        assertTrue(a.reconfigureCalls(true));
        a.exitCall(a.enterCall(), 15, true);
        assertEquals(1, a.count());
        assertEquals(0, b.count());
        assertFalse(b.enabled());
    }

    @Test
    void concurrentPublishersNeverOverwriteAndRespectCapacity() throws Exception {
        AutoTraceRecorder trace = new AutoTraceRecorder(64, false);
        assertTrue(trace.reconfigureCalls(true));
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch started = new CountDownLatch(1);
        try {
            var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                final int lane = i;
                work.add(pool.submit(() -> {
                    started.await();
                    for (int n = 0; n < 100; n++) {
                        long token = trace.enterCall();
                        trace.exitCall(token, 1000 + lane, false);
                    }
                    return null;
                }));
            }
            started.countDown();
            for (var future : work) future.get(10, TimeUnit.SECONDS);
            assertEquals(64, trace.count());
            assertEquals(800 - 64, trace.droppedCount());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
