package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests semantic lifecycle staging ONLY; no OTLP/exporter claims. */
final class SemanticTraceBufferTest {
    private static AutoTraceRecorder gate() {
        return new AutoTraceRecorder(128, false);
    }

    @Test
    void offAndUnsampledPathsDoNotAllocateScopes() {
        AutoTraceRecorder gate = gate();
        SemanticTraceBuffer stage = new SemanticTraceBuffer(gate, 2);
        TraceParent sampled = TraceParent.newRoot(true);
        assertNull(stage.begin(sampled, "", 201, SemanticTraceBuffer.Kind.SERVER));
        assertTrue(gate.reconfigureCalls(true));
        TraceParent unsampled = TraceParent.newRoot(false);
        assertNull(stage.begin(unsampled, "", 201, SemanticTraceBuffer.Kind.SERVER));
        assertNull(stage.begin(null, "", 201, SemanticTraceBuffer.Kind.SERVER));
        assertEquals(0, stage.recordedCount());
        assertEquals(0, stage.droppedCount());
    }

    @Test
    void completeServerActorClientTreeExactlyOnceWithW3cParents() {
        AutoTraceRecorder gate = gate();
        assertTrue(gate.reconfigureCalls(true));
        SemanticTraceBuffer stage = new SemanticTraceBuffer(gate, 4);
        TraceParent remote = TraceParent.newRoot(true);
        TraceParent server = remote.newChild();
        TraceParent actor = server.newChild();
        TraceParent client = actor.newChild();

        var serverScope = stage.begin(server, remote.spanId(),
                SemanticTraceBuffer.HTTP_SERVER, SemanticTraceBuffer.Kind.SERVER);
        var actorScope = stage.begin(actor, server.spanId(),
                SemanticTraceBuffer.ACTOR_RECEIVE, SemanticTraceBuffer.Kind.INTERNAL);
        var clientScope = stage.begin(client, actor.spanId(),
                SemanticTraceBuffer.HTTP_CLIENT, SemanticTraceBuffer.Kind.CLIENT);
        assertNotNull(serverScope);
        assertNotNull(actorScope);
        assertNotNull(clientScope);

        // Async physical HTTP completion can precede enclosing parent cleanup.
        assertTrue(clientScope.finish(200, false));
        assertTrue(actorScope.finish(0, false));
        assertTrue(serverScope.finish(200, false));
        assertFalse(clientScope.finish(500, true));
        assertFalse(serverScope.finish(500, true));
        assertEquals(3, stage.recordedCount());
        var spans = stage.snapshot();
        assertEquals(3, spans.size());
        for (var s : spans) {
            assertEquals(remote.traceId(), s.context().traceId());
            assertTrue(s.startUnixNanos() > 0);
            assertTrue(s.endUnixNanos() >= s.startUnixNanos());
        }
        assertEquals(client.spanId(), spans.get(0).context().spanId());
        assertEquals(actor.spanId(), spans.get(0).parentSpanId());
        assertEquals(server.spanId(), spans.get(1).parentSpanId());
        assertEquals(remote.spanId(), spans.get(2).parentSpanId());
        assertEquals(2, spans.get(2).kind().otlpKind);
        assertEquals(3, spans.get(0).kind().otlpKind);
        assertEquals(1, spans.get(1).kind().otlpKind);
    }

    @Test
    void failsClosedOnFullBufferAndNeverBlocksOrExpandsCapacity() {
        AutoTraceRecorder gate = gate();
        assertTrue(gate.reconfigureCalls(true));
        SemanticTraceBuffer buffer = new SemanticTraceBuffer(gate, 1);
        TraceParent root = TraceParent.newRoot(true);
        for (int i = 0; i < 99; i++) {
            var scope = buffer.begin(root.newChild(), root.spanId(), 201,
                    SemanticTraceBuffer.Kind.SERVER);
            assertNotNull(scope);
            SemanticTraceBuffer.finish(scope, 500, true);
        }
        assertEquals(1, buffer.recordedCount());
        assertEquals(98, buffer.droppedCount());
        assertTrue(buffer.snapshot().getFirst().failed());
    }

    @Test
    void parallelFinishesClaimSingleSlotAndKeepTheirIndividualContext() throws Exception {
        AutoTraceRecorder gate = gate();
        assertTrue(gate.reconfigureCalls(true));
        SemanticTraceBuffer stage = new SemanticTraceBuffer(gate, 32);
        TraceParent root = TraceParent.newRoot(true);
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int lane = 0; lane < 8; lane++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int x = 0; x < 20; x++) {
                        var scope = stage.begin(root.newChild(), root.spanId(),
                                200, SemanticTraceBuffer.Kind.CLIENT);
                        SemanticTraceBuffer.finish(scope, 200, false);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(32, stage.recordedCount());
            assertEquals(128, stage.droppedCount());
            assertEquals(32, stage.snapshot().size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void inFlightScopeRetainsAdmissionAfterDisablingRecording() {
        AutoTraceRecorder gate = gate();
        assertTrue(gate.reconfigureCalls(true));
        SemanticTraceBuffer buffer = new SemanticTraceBuffer(gate, 4);
        var root = TraceParent.newRoot(true);
        var scope = buffer.begin(root, "", 201, SemanticTraceBuffer.Kind.SERVER);
        assertTrue(gate.reconfigureCalls(false));
        assertNull(buffer.begin(root, "", 201, SemanticTraceBuffer.Kind.SERVER));
        SemanticTraceBuffer.finish(scope, 200, false);
        assertEquals(1, buffer.recordedCount());
    }

    @Test
    void invalidScopeIsRejectedWithoutCorruptingStoredRecords() {
        AutoTraceRecorder gate = gate();
        assertTrue(gate.reconfigureCalls(true));
        SemanticTraceBuffer buffer = new SemanticTraceBuffer(gate, 1);
        var root = TraceParent.newRoot(true);
        assertThrows(IllegalArgumentException.class, () -> buffer.begin(root, null,
                201, SemanticTraceBuffer.Kind.SERVER));
        assertThrows(IllegalArgumentException.class, () -> buffer.begin(root, "",
                -1, SemanticTraceBuffer.Kind.SERVER));
        assertEquals(0, buffer.recordedCount());
    }
}
