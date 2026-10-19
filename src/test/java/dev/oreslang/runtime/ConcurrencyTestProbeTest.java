package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ConcurrencyTestProbeTest {
    @TempDir Path tmp;

    @Test
    void disabledProbeHasNoSideEffects() {
        ConcurrencyTestProbe.checkpoint("task-turn");
        ConcurrencyTestProbe.deadlock("future-cycle");
    }

    @Test
    void replayRoundTripAndDetectedCycle() throws Exception {
        Path trace = tmp.resolve("run.trace");
        List<String> recorded;
        try (var session = ConcurrencyTestProbe.start(91, true, null)) {
            ConcurrencyTestProbe.checkpoint("task-turn");
            ConcurrencyTestProbe.deadlock("future-cycle");
            ConcurrencyTestProbe.checkpoint("task-turn");
            assertEquals(1, session.detectedDeadlocks());
            assertEquals(3, session.eventCount());
            session.writeTrace(trace);
            recorded = session.trace();
        }
        assertEquals(recorded, ConcurrencyTestProbe.readTrace(trace));
        try (var session = ConcurrencyTestProbe.start(91, true, ConcurrencyTestProbe.readTrace(trace))) {
            ConcurrencyTestProbe.checkpoint("task-turn");
            ConcurrencyTestProbe.deadlock("future-cycle");
            ConcurrencyTestProbe.checkpoint("task-turn");
            session.verifyReplayComplete();
            assertEquals(recorded, session.trace());
        }
    }

    @Test
    void v2CheckpointRoundTripChecksLiteralPipeAndRejectsCorruption() throws Exception {
        Path trace = tmp.resolve("v2.trace");
        String digest = "a".repeat(64);
        try (var session = ConcurrencyTestProbe.start(18, true, null,
                digest, "jit-server")) {
            ConcurrencyTestProbe.checkpoint("task-turn");
            ConcurrencyTestProbe.deadlock("future-cycle");
            session.writeTrace(trace);
        }
        var loaded = ConcurrencyTestProbe.readTraceData(trace);
        assertEquals(18, loaded.seed());
        assertEquals(digest, loaded.entrySha256());
        assertEquals("jit-server", loaded.backend());
        assertEquals(2, loaded.events().size());
        assertTrue(loaded.events().get(0).startsWith("task-turn|"));

        var original = java.nio.file.Files.readString(trace);
        java.nio.file.Files.writeString(trace,
                original.replace("deadlock-future-cycle|0",
                        "deadlock-future-cycle:0"));
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencyTestProbe.readTraceData(trace));
        java.nio.file.Files.writeString(trace,
                original.replace("|probe=1", "|probe=2"));
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencyTestProbe.readTraceData(trace));
    }

    @Test
    void v3WorkspaceBoundTraceRejectsTamperedSourceDigest() throws Exception {
        Path trace = tmp.resolve("v3.trace");
        String entry = "a".repeat(64);
        String workspace = "b".repeat(64);
        try (var session = ConcurrencyTestProbe.start(21, false, null,
                entry, "jit-server", workspace)) {
            ConcurrencyTestProbe.checkpoint("task-turn");
            session.writeTrace(trace);
        }
        var receipt = ConcurrencyTestProbe.readTraceData(trace);
        assertEquals(21, receipt.seed());
        assertEquals(entry, receipt.entrySha256());
        assertEquals(workspace, receipt.workspaceSha256());
        assertEquals("jit-server", receipt.backend());
        assertEquals(List.of("task-turn|0"), receipt.events());
        String original = java.nio.file.Files.readString(trace);
        java.nio.file.Files.writeString(trace,
                original.replace("workspace-sha256=" + workspace,
                        "workspace-sha256=not-a-hash"));
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencyTestProbe.readTraceData(trace));
        java.nio.file.Files.writeString(trace,
                original.replace("|probe=3", "|probe=4"));
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencyTestProbe.readTraceData(trace));
    }

    @Test
    void traceCreationCannotOverwriteEvidenceOrFollowOutputSymlink() throws Exception {
        Path trace = tmp.resolve("collision.trace");
        try (var session = ConcurrencyTestProbe.start(41, false, null)) {
            ConcurrencyTestProbe.checkpoint("task-turn");
            session.writeTrace(trace);
            byte[] original = java.nio.file.Files.readAllBytes(trace);
            assertThrows(java.nio.file.FileAlreadyExistsException.class,
                    () -> session.writeTrace(trace));
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(trace));
            Path alias = tmp.resolve("link.trace");
            try {
                java.nio.file.Files.createSymbolicLink(alias, trace.getFileName());
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                return;
            }
            assertThrows(java.nio.file.FileAlreadyExistsException.class,
                    () -> session.writeTrace(alias));
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(trace));
            assertThrows(IllegalArgumentException.class,
                    () -> ConcurrencyTestProbe.readTraceData(alias));
        }
    }

    @Test
    void replayMismatchFailsClosedAndSessionRetires() {
        try (var session = ConcurrencyTestProbe.start(1, true, List.of("future-await|1"))) {
            assertThrows(IllegalStateException.class,
                    () -> ConcurrencyTestProbe.checkpoint("task-turn"));
            assertEquals(0, session.eventCount());
        }
        try (var session = ConcurrencyTestProbe.start(2, false, null)) {
            assertEquals(0, session.eventCount());
        }
    }

    @Test
    void replayTruncationIsNotSuccessful() {
        try (var session = ConcurrencyTestProbe.start(1, false,
                List.of("task-turn|0", "task-turn|0"))) {
            ConcurrencyTestProbe.checkpoint("task-turn");
            assertThrows(IllegalStateException.class, session::verifyReplayComplete);
        }
    }

    @Test
    void nestedProbeCannotReplaceActiveSession() {
        try (var ignored = ConcurrencyTestProbe.start(7, false, null)) {
            assertThrows(IllegalStateException.class,
                    () -> ConcurrencyTestProbe.start(8, true, null));
        }
    }

    @Test
    void concurrentEventRecordingIsBoundedAndAtomic() throws Exception {
        try (var session = ConcurrencyTestProbe.start(5, false, null)) {
            CountDownLatch done = new CountDownLatch(2);
            for (int i = 0; i < 2; i++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        for (int j = 0; j < 100; j++) {
                            ConcurrencyTestProbe.checkpoint("task-turn");
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(200, session.eventCount());
        }
    }
}
