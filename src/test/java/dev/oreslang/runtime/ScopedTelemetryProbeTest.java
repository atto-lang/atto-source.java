package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only lifecycle behavior. Does NOT claim integration with the AttoLang
 * parser, actor scheduler, guest "using" scope or OTLP exporter.
 */
final class ScopedTelemetryProbeTest {
    @Test
    void disabledScopeIsSingletonAndNeverRecords() {
        ScopedTelemetryProbe a = ScopedTelemetryProbe.enter(false, -1, -1, -1);
        ScopedTelemetryProbe b = ScopedTelemetryProbe.enter(false, 999, 999, 999);
        assertSame(a, b);
        assertFalse(a.isRecording());
        assertFalse(a.suspend(1, 1));
        assertFalse(a.resume(1, 1));
        assertNull(a.finish(1, 1, ScopedTelemetryProbe.Outcome.OK));
    }

    @Test
    void carrierMigrationDoesNotCreateSecondLogicalSpan() throws Exception {
        ScopedTelemetryProbe scope = ScopedTelemetryProbe.enter(true, 3, 7, 19);
        assertTrue(scope.isRecording());
        assertTrue(scope.suspend(3, 7));
        assertFalse(scope.suspend(3, 7));
        // A JUnit assertion thrown on a raw Thread would be lost to the
        // parent test; Future.get() propagates failures from that carrier.
        try (ExecutorService carrier = Executors.newSingleThreadExecutor()) {
            Future<Boolean> resumed = carrier.submit(() -> scope.resume(3, 7));
            assertTrue(resumed.get(5, TimeUnit.SECONDS));
        }
        assertTrue(scope.isRecording());
        ScopedTelemetryProbe.Completion done =
                scope.finish(3, 7, ScopedTelemetryProbe.Outcome.OK);
        assertNotNull(done);
        assertEquals(3, done.executionId());
        assertEquals(7, done.actorGeneration());
        assertEquals(19, done.probeId());
        assertTrue(done.elapsedNs() >= 0);
        assertNull(scope.finish(3, 7, ScopedTelemetryProbe.Outcome.ERROR));
        assertFalse(scope.resume(3, 7));
        assertFalse(scope.isRecording());
    }

    @Test
    void differentActorOrGenerationCannotControlScope() {
        ScopedTelemetryProbe scope = ScopedTelemetryProbe.enter(true, 20, 9, 4);
        assertFalse(scope.suspend(21, 9));
        assertFalse(scope.suspend(20, 10));
        assertNull(scope.finish(21, 9, ScopedTelemetryProbe.Outcome.ACTOR_STOPPED));
        assertNull(scope.finish(20, 10, ScopedTelemetryProbe.Outcome.ACTOR_STOPPED));
        assertTrue(scope.isRecording());
        assertNotNull(scope.finish(20, 9, ScopedTelemetryProbe.Outcome.ACTOR_STOPPED));
        assertNull(scope.finish(20, 9, ScopedTelemetryProbe.Outcome.OK));
    }

    @Test
    void cancelAndReturnRaceFinishesAtMostOnce() throws Exception {
        ScopedTelemetryProbe scope = ScopedTelemetryProbe.enter(true, 1, 8, 3);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ScopedTelemetryProbe.Completion> completed = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return scope.finish(1, 8, ScopedTelemetryProbe.Outcome.OK);
            });
            Future<ScopedTelemetryProbe.Completion> cancelled = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return scope.finish(1, 8, ScopedTelemetryProbe.Outcome.CANCELLED);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var first = completed.get(5, TimeUnit.SECONDS);
            var second = cancelled.get(5, TimeUnit.SECONDS);
            assertEquals(1, (first == null ? 0 : 1) + (second == null ? 0 : 1));
            assertFalse(scope.isRecording());
        }
    }

    @Test
    void invalidEnabledScopeIsRejectedBeforeClockOrSpanCreation() {
        assertThrows(IllegalArgumentException.class,
                () -> ScopedTelemetryProbe.enter(true, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ScopedTelemetryProbe.enter(true, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ScopedTelemetryProbe.enter(true, 1, 1, 0));
    }
}
