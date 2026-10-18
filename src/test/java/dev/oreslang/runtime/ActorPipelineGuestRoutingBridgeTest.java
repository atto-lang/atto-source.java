package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-facing sealed routing bridge against real SHARED ActorRuntime mailbox
 * turns. These tests exercise VM-owned envelopes, not synthetic route IDs.
 */
@Timeout(25)
final class ActorPipelineGuestRoutingBridgeTest {
    private static ActorPipelineRoutePlan fanout() {
        return new ActorPipelineRoutePlan(
                List.of(
                        new ActorPipelineRoutePlan.Stage("source", "Producer",
                                ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage("worker-a", "Worker",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                        new ActorPipelineRoutePlan.Stage("worker-b", "Worker",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                        new ActorPipelineRoutePlan.Edge("source", "worker-a", "Event"),
                        new ActorPipelineRoutePlan.Edge("source", "worker-b", "Event")));
    }

    @Test
    void guestFanoutReportsImmutablePerEdgeAdmissionAndNoAuthorityLeak()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new CountDownLatch(2);
            var reports = new AtomicReference<List<Map<String, Object>>>();
            var observed = Collections.synchronizedList(new ArrayList<String>());
            var a = runtime.<String>spawnShared(() -> (value, actor) -> {
                observed.add("a:" + value);
                delivered.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (value, actor) -> {
                observed.add("b:" + value);
                delivered.countDown();
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"begin".equals(value)) throw new AssertionError("unexpected source DATA");
                reports.set(runtime.routeCurrentPipelineForwardAll("payload"));
            });
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanout(),
                    Map.of("source", source, "worker-a", a, "worker-b", b),
                    41, 8);
            assertThrows(SecurityException.class,
                    () -> runtime.routeCurrentPipelineForwardAll("forged"));
            router.ingress("source", "begin");
            assertTrue(delivered.await(8, TimeUnit.SECONDS));
            source.drain().get(5, TimeUnit.SECONDS);
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);

            List<Map<String, Object>> batch = reports.get();
            assertNotNull(batch);
            assertEquals(2, batch.size());
            assertEquals("worker-a", batch.get(0).get("target"));
            assertEquals("worker-b", batch.get(1).get("target"));
            assertEquals(0L, batch.get(0).get("edge_index"));
            assertEquals(1L, batch.get(1).get("edge_index"));
            assertEquals(Boolean.TRUE, batch.get(0).get("admitted"));
            assertEquals(Boolean.TRUE, batch.get(1).get("admitted"));
            assertEquals("admitted", batch.get(0).get("status"));
            assertEquals(batch.get(0).get("flow_id"), batch.get(1).get("flow_id"));
            assertNotEquals(batch.get(0).get("route_id"), batch.get(1).get("route_id"));
            assertEquals(2, observed.size());
            assertTrue(observed.contains("a:payload"));
            assertTrue(observed.contains("b:payload"));
            assertEquals(0, router.outstanding());
            assertThrows(UnsupportedOperationException.class, batch::clear);
            assertThrows(UnsupportedOperationException.class,
                    () -> batch.get(0).put("target", "forged"));
            router.close();
            source.stop();
            a.stop();
            b.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void partialFanoutRejectionDoesNotUndoEarlierAdmissionOrLeakFrames()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var admitted = new CountDownLatch(1);
            var reports = new AtomicReference<List<Map<String, Object>>>();
            var a = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"payload".equals(value)) throw new AssertionError("wrong DATA");
                admitted.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (value, actor) -> {
                throw new AssertionError("retired sink received DATA");
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"begin".equals(value)) throw new AssertionError("wrong root DATA");
                reports.set(runtime.routeCurrentPipelineForwardAll("payload"));
            });
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanout(),
                    Map.of("source", source, "worker-a", a, "worker-b", b),
                    42, 8);
            b.stop();
            b.getDone().get(5, TimeUnit.SECONDS);
            router.ingress("source", "begin");
            assertTrue(admitted.await(8, TimeUnit.SECONDS));
            source.drain().get(5, TimeUnit.SECONDS);
            a.drain().get(5, TimeUnit.SECONDS);
            var batch = reports.get();
            assertNotNull(batch);
            assertEquals(2, batch.size());
            assertEquals(Boolean.TRUE, batch.get(0).get("admitted"));
            assertEquals(Boolean.FALSE, batch.get(1).get("admitted"));
            assertEquals("admitted", batch.get(0).get("status"));
            assertEquals("rejected", batch.get(1).get("status"));
            assertEquals(0, router.outstanding());
            router.close();
            source.stop();
            a.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void terminalReplyUsesOriginalAncestryAndSkipsUnrequestedReverseHandlers()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var answered = new CountDownLatch(1);
            var unexpectedReverse = new AtomicInteger();
            var replyCorrelation = new AtomicReference<ActorPipelineCorrelatedMailboxRouter.Correlation>();
            var routerRef = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                if ("begin".equals(value)) {
                    runtime.routeCurrentPipelineForward("work");
                } else if ("ack".equals(value)) {
                    replyCorrelation.set(routerRef.get().currentCorrelation());
                    assertThrows(IllegalStateException.class,
                            () -> runtime.routeCurrentPipelineReply("replay"));
                    answered.countDown();
                } else {
                    throw new AssertionError("unexpected origin DATA " + value);
                }
            });
            var middle = runtime.<String>spawnShared(() -> (value, actor) -> {
                if ("work".equals(value)) runtime.routeCurrentPipelineForward("finish");
                else unexpectedReverse.incrementAndGet();
            });
            var terminal = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"finish".equals(value)) throw new AssertionError("wrong terminal DATA");
                runtime.routeCurrentPipelineReply("ack");
            });
            var graph = new ActorPipelineRoutePlan(
                    List.of(
                            new ActorPipelineRoutePlan.Stage("source", "Source",
                                    ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                            new ActorPipelineRoutePlan.Stage("middle", "Middle",
                                    ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event"),
                            new ActorPipelineRoutePlan.Stage("terminal", "Terminal",
                                    ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                    List.of(
                            new ActorPipelineRoutePlan.Edge("source", "middle", "Event"),
                            new ActorPipelineRoutePlan.Edge("middle", "terminal", "Event")));
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, graph,
                    Map.of("source", source, "middle", middle, "terminal", terminal),
                    43, 8);
            routerRef.set(router);
            router.ingress("source", "begin");
            assertTrue(answered.await(8, TimeUnit.SECONDS));
            source.drain().get(5, TimeUnit.SECONDS);
            middle.drain().get(5, TimeUnit.SECONDS);
            terminal.drain().get(5, TimeUnit.SECONDS);
            assertNotNull(replyCorrelation.get());
            assertEquals("source", replyCorrelation.get().receiver());
            assertEquals(43, replyCorrelation.get().generation());
            assertEquals(0, unexpectedReverse.get());
            assertEquals(0, router.outstanding());
            router.close();
            source.stop();
            middle.stop();
            terminal.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            middle.getDone().get(5, TimeUnit.SECONDS);
            terminal.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}
