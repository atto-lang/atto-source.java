package dev.oreslang;

import dev.oreslang.runtime.RaceTraceRecorder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated host runtime contract tests; complete actor instrumentation is not implied.
 */
class RaceTraceRecorderTest {
    @TempDir Path temporary;

    @Test void emitsVersionedBoundedTraceWithCountedEnd() throws Exception {
        Path file = temporary.resolve("events.jsonl");
        try (RaceTraceRecorder out = RaceTraceRecorder.open(file, "ci-run", "graal")) {
            out.task("a");
            out.task("b");
            out.access("a", "heap:cell-value", true);
            out.send("a", "msg:1");
            out.recv("b", "msg:1");
            out.access("b", "heap:cell-value", false);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(8, lines.size());
        assertEquals("{\"op\":\"begin\",\"version\":1,\"run_id\":\"ci-run\",\"backend\":\"graal\"}", lines.get(0));
        assertTrue(lines.get(3).contains("\"mode\":\"W\""));
        assertTrue(lines.get(6).contains("\"mode\":\"R\""));
        assertEquals("{\"op\":\"end\",\"run_id\":\"ci-run\",\"events\":6}", lines.get(7));
    }

    @Test void refusesOverwritingAndLabelInjection() throws Exception {
        Path file = temporary.resolve("events.jsonl");
        try (RaceTraceRecorder out = RaceTraceRecorder.open(file, "r1", "graal")) {
            assertThrows(IllegalArgumentException.class,
                    () -> out.task("injected\"}\n{\"op\":\"end"));
            assertThrows(IOException.class, () -> out.task("ok"));

        }
        assertFalse(Files.readString(file).contains("\\\"op\\\":\\\"end\\\""));
        assertThrows(IOException.class, () -> RaceTraceRecorder.open(file, "r2", "graal"));
        assertThrows(IllegalArgumentException.class,
                () -> RaceTraceRecorder.open(temporary.resolve("other.jsonl"), "invalid run\n", "graal"));
    }

    @Test void serializedMultithreadedEmissionMaintainsCount() throws Exception {
        Path file = temporary.resolve("concurrent.jsonl");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (RaceTraceRecorder out = RaceTraceRecorder.open(file, "threads", "graal")) {
            out.task("a");
            out.task("b");
            CountDownLatch go = new CountDownLatch(1);
            Thread first = new Thread(() -> record(out, "a", go, failure));
            Thread second = new Thread(() -> record(out, "b", go, failure));
            first.start();
            second.start();
            go.countDown();
            first.join();
            second.join();
        }
        assertNull(failure.get());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(24, lines.size()); // begin + 2 tasks + 20 accesses + end
        assertTrue(lines.get(lines.size() - 1).endsWith("\"events\":22}"));
        assertTrue(lines.stream().skip(1).limit(22).allMatch(x -> x.startsWith("{\"op\":")));
    }

    private static void record(RaceTraceRecorder out, String task, CountDownLatch go,
                               AtomicReference<Throwable> failed) {
        try {
            go.await();
            for (int i = 0; i < 10; i++) out.access(task, "heap:shared", true);
        } catch (Exception failure) {
            failed.compareAndSet(null, failure);
        }
    }

    @Test void cannotWriteAfterClosingTrace() throws Exception {
        Path file = temporary.resolve("closed.jsonl");
        RaceTraceRecorder out = RaceTraceRecorder.open(file, "closed", "graal");
        out.task("a");
        out.close();
        out.close();
        assertThrows(IOException.class, () -> out.access("a", "heap:test", true));
        assertEquals(3, Files.readAllLines(file).size());
    }
}
