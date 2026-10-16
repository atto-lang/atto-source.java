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

/** Real ActorRuntime mailbox turns; correlation is not serialized guest data. */
@Timeout(25)
final class ActorPipelineCorrelatedMailboxRouterTest {
    private static ActorPipelineRoutePlan fanInGraph() {
        return new ActorPipelineRoutePlan(
                List.of(
                        new ActorPipelineRoutePlan.Stage("source", "Producer",
                                ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage("worker-a", "Worker",
                                ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event"),
                        new ActorPipelineRoutePlan.Stage("worker-b", "Worker",
                                ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event"),
                        new ActorPipelineRoutePlan.Stage("sink", "Sink",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                        new ActorPipelineRoutePlan.Edge("source", "worker-a", "Event"),
                        new ActorPipelineRoutePlan.Edge("source", "worker-b", "Event"),
                        new ActorPipelineRoutePlan.Edge("worker-a", "sink", "Event"),
                        new ActorPipelineRoutePlan.Edge("worker-b", "sink", "Event")));
    }

    @Test
    void realMailboxFanoutAndFanInPreserveOneFlowAndIndependentBranchIdentities()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var binding = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var receipts = new AtomicReference<List<ActorPipelineCorrelatedMailboxRouter.Admission>>();
            var delivered = new CountDownLatch(2);
            var observed = Collections.synchronizedList(
                    new ArrayList<ActorPipelineCorrelatedMailboxRouter.Correlation>());
            var unexpectedPayload = new AtomicInteger();

            var sink = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"fan-in".equals(value)) unexpectedPayload.incrementAndGet();
                observed.add(binding.get().currentCorrelation());
                delivered.countDown();
            });
            var a = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("fanout", value);
                binding.get().forward("fan-in");
            });
            var b = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("fanout", value);
                binding.get().forward("fan-in");
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("begin", value);
                receipts.set(binding.get().forwardAll("fanout"));
            });

            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", a, "worker-b", b,
                           "sink", sink), 17, 12);
            binding.set(router);
            // Caller-origin DATA cannot masquerade as a routed receive.
            assertThrows(SecurityException.class, () -> router.forwardAll("spoof"));
            router.ingress("source", "begin");
            assertTrue(delivered.await(8, TimeUnit.SECONDS));
            source.drain().get(5, TimeUnit.SECONDS);
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);
            sink.drain().get(5, TimeUnit.SECONDS);
            assertEquals(0, router.outstanding(), "all delivered frames must retire");
            assertEquals(0, unexpectedPayload.get());
            var batch = receipts.get();
            assertNotNull(batch);
            assertEquals(2, batch.size());
            assertTrue(batch.stream().allMatch(
                    ActorPipelineCorrelatedMailboxRouter.Admission::admitted));
            assertEquals(0, batch.get(0).edgeIndex());
            assertEquals(1, batch.get(1).edgeIndex());
            assertEquals(batch.get(0).flowId(), batch.get(1).flowId());
            assertNotEquals(batch.get(0).routeId(), batch.get(1).routeId());
            assertEquals(2, observed.size());
            assertEquals(17, observed.get(0).generation());
            assertEquals("sink", observed.get(0).receiver());
            assertEquals("sink", observed.get(1).receiver());
            assertEquals(observed.get(0).flowId(), observed.get(1).flowId());
            assertNotEquals(observed.get(0).routeId(), observed.get(1).routeId());
            assertEquals(batch.get(0).flowId(), observed.get(0).flowId());

            router.close();
            assertThrows(IllegalStateException.class,
                    () -> router.ingress("source", "after-retirement"));
            source.stop();
            a.stop();
            b.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void ordinaryActorMailCannotBorrowAnEarlierOrDifferentRouteFrame()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var reference = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var denied = new AtomicInteger();
            var delivered = new CountDownLatch(1);
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("ordinary")) {
                    try {
                        reference.get().forward("spoof");
                    } catch (SecurityException expected) {
                        denied.incrementAndGet();
                    }
                }
                delivered.countDown();
            });
            var workerA = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var workerB = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", workerA,
                           "worker-b", workerB, "sink", sink), 3, 8);
            reference.set(router);
            sink.send("ordinary");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            sink.drain().get(5, TimeUnit.SECONDS);
            assertEquals(1, denied.get());
            assertEquals(0, router.outstanding());
            router.close();
            source.stop();
            workerA.stop();
            workerB.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            workerA.getDone().get(5, TimeUnit.SECONDS);
            workerB.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}
