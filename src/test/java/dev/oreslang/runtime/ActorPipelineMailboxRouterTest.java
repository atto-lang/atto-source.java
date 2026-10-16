package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorRuntime mailbox execution, not only a route-selector unit test.
 * Every sender operation occurs on the authenticated single-flight actor lane.
 */
@Timeout(20)
final class ActorPipelineMailboxRouterTest {
    private static ActorPipelineRoutePlan graph() {
        return new ActorPipelineRoutePlan(
                List.of(
                        new ActorPipelineRoutePlan.Stage(
                                "producer", "Producer", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage(
                                "worker-a", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                        new ActorPipelineRoutePlan.Stage(
                                "worker-b", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                        new ActorPipelineRoutePlan.Edge("producer", "worker-a", "Event"),
                        new ActorPipelineRoutePlan.Edge("producer", "worker-b", "Event")));
    }

    @Test
    void actualActorMessagesFanoutRoundRobinDirectAndKeyedDelivery() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(3, 3, 16, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicInteger observedA = new AtomicInteger();
            AtomicInteger observedB = new AtomicInteger();
            AtomicInteger invalidSingle = new AtomicInteger();
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            AtomicReference<List<ActorPipelineMailboxRouter.Admission>> fanout =
                    new AtomicReference<>();
            AtomicReference<ActorPipelineMailboxRouter<String>> binding =
                    new AtomicReference<>();
            CountDownLatch handled = new CountDownLatch(8);
            var a = runtime.<String>spawnShared(() -> (message, actor) -> {
                observedA.incrementAndGet();
                handled.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (message, actor) -> {
                observedB.incrementAndGet();
                handled.countDown();
            });
            var producer = runtime.<String>spawnShared(() -> (message, actor) -> {
                int active = inFlight.incrementAndGet();
                peak.accumulateAndGet(active, Math::max);
                try {
                    switch (message) {
                        case "all" -> fanout.set(binding.get().forwardAll("copy"));
                        case "rr" -> binding.get().forwardRoundRobin("one");
                        case "direct" -> binding.get().forwardTo("worker-b", "direct");
                        case "key" -> binding.get().forwardByKey("customer-7", "partition");
                        case "wrong-single" -> {
                            try {
                                binding.get().forward("invalid");
                            } catch (IllegalArgumentException ambiguous) {
                                invalidSingle.incrementAndGet();
                            }
                        }
                        default -> throw new AssertionError("unexpected producer command");
                    }
                } finally {
                    inFlight.decrementAndGet();
                }
            });
            binding.set(new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", producer, "worker-a", a, "worker-b", b)));

            // The host cannot spoof the currently executing actor's port.
            assertThrows(SecurityException.class,
                    () -> binding.get().forwardTo("worker-b", "unauthorized"));

            producer.send("all");
            for (int i = 0; i < 4; i++) producer.send("rr");
            producer.send("direct");
            producer.send("key");
            producer.send("wrong-single");
            producer.drain().get(5, TimeUnit.SECONDS);
            assertTrue(handled.await(5, TimeUnit.SECONDS));
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);

            var receipts = fanout.get();
            assertNotNull(receipts);
            assertEquals(2, receipts.size());
            assertEquals("worker-a", receipts.get(0).target());
            assertEquals("worker-b", receipts.get(1).target());
            assertTrue(receipts.stream().allMatch(ActorPipelineMailboxRouter.Admission::admitted));
            assertEquals(8, observedA.get() + observedB.get());
            assertEquals(1, peak.get(), "actor cannot execute two physical turns");
            assertEquals(1, invalidSingle.get());
            assertTrue(observedA.get() >= 3);
            assertTrue(observedB.get() >= 4);
            producer.stop();
            a.stop();
            b.stop();
            producer.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stoppedRecipientFailsIndependentlyOfHealthyFanoutBranch() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<List<ActorPipelineMailboxRouter.Admission>> fanout =
                    new AtomicReference<>();
            AtomicReference<ActorPipelineMailboxRouter<String>> bound =
                    new AtomicReference<>();
            CountDownLatch healthy = new CountDownLatch(1);
            var a = runtime.<String>spawnShared(() -> (message, actor) ->
                    healthy.countDown());
            var b = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var producer = runtime.<String>spawnShared(() -> (message, actor) ->
                    fanout.set(bound.get().forwardAll(message)));
            bound.set(new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", producer, "worker-a", a, "worker-b", b)));
            b.stop();
            b.getDone().get(5, TimeUnit.SECONDS);

            producer.send("one");
            producer.drain().get(5, TimeUnit.SECONDS);
            assertTrue(healthy.await(5, TimeUnit.SECONDS));
            a.drain().get(5, TimeUnit.SECONDS);
            List<ActorPipelineMailboxRouter.Admission> receipts = fanout.get();
            assertEquals(2, receipts.size());
            assertTrue(receipts.get(0).admitted());
            assertFalse(receipts.get(1).admitted());
            assertNotNull(receipts.get(1).failure());
            assertEquals("worker-b", receipts.get(1).target());
            producer.stop();
            a.stop();
            producer.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorTurnsCannotInstallTheirOwnGraphOrSpoofAnotherPipelineSource()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorPipelineMailboxRouter<String>> attached =
                    new AtomicReference<>();
            AtomicInteger registrationDenied = new AtomicInteger();
            AtomicInteger spoofDenied = new AtomicInteger();
            CountDownLatch checked = new CountDownLatch(1);
            var a = runtime.<String>spawnShared(() -> (message, actor) -> {
                try {
                    attached.get().forwardTo("worker-b", "spoofed");
                } catch (IllegalArgumentException denied) {
                    spoofDenied.incrementAndGet();
                }
                checked.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (message, actor) -> { });
            AtomicReference<ActorRuntime.ActorRef<String>> producerRef =
                    new AtomicReference<>();
            var producer = runtime.<String>spawnShared(() -> (message, actor) -> {
                try {
                    new ActorPipelineMailboxRouter<>(runtime, graph(),
                            Map.of("producer", producerRef.get(),
                                    "worker-a", a, "worker-b", b));
                } catch (SecurityException denied) {
                    registrationDenied.incrementAndGet();
                }
                attached.get().forwardTo("worker-a", "check");
            });
            producerRef.set(producer);
            attached.set(new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", producer, "worker-a", a, "worker-b", b)));
            producer.send("start");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertEquals(1, registrationDenied.get());
            assertEquals(1, spoofDenied.get());
            producer.stop();
            a.stop();
            b.stop();
            producer.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void bindingRejectsMissingDuplicateAndForeignActorIdentities() {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime other = new ActorRuntime()) {
            var a = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var b = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var p = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var foreign = other.<String>spawnShared(() -> (message, actor) -> { });

            assertThrows(IllegalArgumentException.class,
                    () -> new ActorPipelineMailboxRouter<>(runtime, graph(),
                            Map.of("producer", p, "worker-a", a)));
            assertThrows(IllegalArgumentException.class,
                    () -> new ActorPipelineMailboxRouter<>(runtime, graph(),
                            Map.of("producer", p, "worker-a", a, "worker-b", a)));
            assertThrows(IllegalArgumentException.class,
                    () -> new ActorPipelineMailboxRouter<>(runtime, graph(),
                            Map.of("producer", p, "worker-a", a, "worker-b", foreign)));
            new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", p, "worker-a", a, "worker-b", b));
        }
    }
}
