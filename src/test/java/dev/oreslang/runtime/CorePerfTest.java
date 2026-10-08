package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CorePerfTest {
    @Test void boundedRecorderRetainsColdEventsAndExportsNoPayloads() {
        var recorder = new CorePerf.Recorder(2);
        long first = System.nanoTime();
        recorder.record(CorePerf.HTTP_RECEIVE, first, first + 123);
        recorder.record(CorePerf.ACTOR_READY, first + 456, first + 789);
        recorder.record(CorePerf.HTTP_CLAIM, first + 900, first + 950);
        var bytes = new ByteArrayOutputStream();
        recorder.dump(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"http.receive\""));
        assertTrue(text.contains("\"actor.ready\""));
        assertFalse(text.contains("\"http.claim\""));
        assertTrue(text.contains("\"duration_ns\":123"));
        assertTrue(text.contains("\"saturated\":true"));
        assertEquals(2, text.lines().filter(line -> line.contains("\"kind\":\"phase\"")).count());
    }

    @Test void multipleThreadsReserveDistinctSlots() throws Exception {
        var recorder = new CorePerf.Recorder(128);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    gate.await();
                    for (int j = 0; j < 16; j++) {
                        long start = System.nanoTime();
                        recorder.record(CorePerf.HTTP_IO_WORK, start, System.nanoTime());
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        var bytes = new ByteArrayOutputStream();
        recorder.dump(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(128, text.lines().filter(line -> line.contains("\"kind\":\"phase\"")).count());
        assertTrue(text.contains("\"recorded\":128"));
    }

    @Test void concurrentSaturationNeverOverflowsCounter() throws Exception {
        var recorder = new CorePerf.Recorder(3);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < 1000; j++) {
                        recorder.record(CorePerf.HTTP_RECEIVE, 100L, 101L);
                    }
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        var bytes = new ByteArrayOutputStream();
        recorder.dump(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(3, output.lines().filter(line -> line.contains("\"kind\":\"phase\"")).count());
        assertTrue(output.contains("\"recorded\":3,\"reserved\":3,\"saturated\":true"));
    }

    @Test void incompleteReservationsAreNotReportedAsRecorded() {
        var recorder = new CorePerf.Recorder(2);
        int slot = recorder.reserve();
        assertEquals(0, slot);
        var bytes = new ByteArrayOutputStream();
        recorder.dump(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"recorded\":0,\"reserved\":1"));
        recorder.record(CorePerf.ACTOR_READY, 10L, 12L);
        assertEquals(1, recorder.reserve() + 1); // capacity reached; no further slots.
    }

    @Test void debugEventsContainOnlyFixedNamesAndNumericValues() {
        var recorder = new CorePerf.Recorder(1);
        recorder.recordEvent(CoreDebug.ACTOR_POOLS_CREATED, 8L);
        recorder.recordEvent(CoreDebug.HTTP_LISTENER_READY, 8080L);
        var bytes = new ByteArrayOutputStream();
        recorder.dumpDebug(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"event\":\"actor.pools.created\""));
        assertTrue(output.contains("\"value\":8"));
        assertFalse(output.contains("http.listener.ready"));
        assertTrue(output.contains("\"recorded\":1,\"reserved\":1"));
        assertThrows(IllegalArgumentException.class, () -> recorder.recordEvent(0, 0L));
    }

    @Test void invalidSamplesFailClosed() {
        var recorder = new CorePerf.Recorder(1);
        assertThrows(IllegalArgumentException.class, () -> recorder.record(0, 1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> recorder.record(CorePerf.HTTP_RECEIVE, 2L, 1L));
    }
}
