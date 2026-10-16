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
            assertEquals(0, receipts.get(0).edgeIndex());
            assertEquals(1, receipts.get(1).edgeIndex());
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
    void actorStartupCannotPublishDataOrAdvanceRoundRobinCursor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorPipelineMailboxRouter<String>> bound =
                    new AtomicReference<>();
            AtomicInteger denied = new AtomicInteger();
            CountDownLatch startup = new CountDownLatch(1);
            CountDownLatch received = new CountDownLatch(1);
            AtomicInteger aCount = new AtomicInteger();
            AtomicInteger bCount = new AtomicInteger();
            var a = runtime.<String>spawnShared(() -> (message, actor) -> {
                aCount.incrementAndGet();
                received.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (message, actor) ->
                    bCount.incrementAndGet());
            var producer = runtime.<String>spawnShared(() -> {
                try {
                    bound.get().forwardRoundRobin("forbidden-startup");
                } catch (SecurityException rejected) {
                    denied.incrementAndGet();
                } finally {
                    startup.countDown();
                }
                return (message, actor) -> bound.get().forwardRoundRobin(message);
            });
            bound.set(new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", producer, "worker-a", a, "worker-b", b)));

            producer.ready().get(5, TimeUnit.SECONDS);
            assertTrue(startup.await(5, TimeUnit.SECONDS));
            assertEquals(1, denied.get(), "startup should lack DATA receive authority");
            producer.send("valid-mail");
            producer.drain().get(5, TimeUnit.SECONDS);
            assertTrue(received.await(5, TimeUnit.SECONDS),
                    "rejected startup must not advance RR selection");
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);
            assertEquals(1, aCount.get());
            assertEquals(0, bCount.get());
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
            assertEquals(0, receipts.get(0).edgeIndex());
            assertEquals(1, receipts.get(1).edgeIndex());
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
    void selfStopFencesAdditionalRoutingInSameActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorPipelineMailboxRouter<String>> router =
                    new AtomicReference<>();
            AtomicInteger correctlyDenied = new AtomicInteger();
            CountDownLatch completed = new CountDownLatch(1);
            var a = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var b = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var source = runtime.<String>spawnShared(() -> (message, actor) -> {
                actor.self().stop();
                try {
                    router.get().forwardAll(message);
                } catch (IllegalStateException stopped) {
                    correctlyDenied.incrementAndGet();
                } finally {
                    completed.countDown();
                }
            });
            router.set(new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", source, "worker-a", a, "worker-b", b)));
            source.send("cannot-route-after-stop");
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            source.getDone().get(5, TimeUnit.SECONDS);
            assertEquals(1, correctlyDenied.get());
            a.stop();
            b.stop();
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void privateActorCannotUseCapturedMutableHostRoundRobinRouter() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorPipelineMailboxRouter<String>> routing =
                    new AtomicReference<>();
            AtomicInteger denied = new AtomicInteger();
            CountDownLatch checked = new CountDownLatch(1);
            CountDownLatch unexpectedDelivery = new CountDownLatch(1);
            var a = runtime.<String>spawnShared(() -> (message, actor) ->
                    unexpectedDelivery.countDown());
            var b = runtime.<String>spawnShared(() -> (message, actor) ->
                    unexpectedDelivery.countDown());
            // The trusted private factory may capture a Java value, but
            // the captured object must not turn into mutable shared state.
            var privateSource = runtime.<String>spawnPrivateTrusted(
                    context -> (message, actor) -> {
                        try {
                            routing.get().forwardRoundRobin(message);
                        } catch (SecurityException blocked) {
                            denied.incrementAndGet();
                        } finally {
                            checked.countDown();
                        }
                    });
            routing.set(new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", privateSource, "worker-a", a, "worker-b", b)));
            privateSource.send("must-not-route");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertEquals(1, denied.get(), "private execution cannot own shared host cursor");
            assertEquals(1L, unexpectedDelivery.getCount(), "private route leaked a delivery");
            privateSource.stop();
            a.stop();
            b.stop();
            privateSource.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void retiredRoutingGenerationRejectsReplaysAndRebindingResetsRoundRobin()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorPipelineMailboxRouter<String>> active =
                    new AtomicReference<>();
            AtomicReference<ActorPipelineMailboxRouter<String>> old =
                    new AtomicReference<>();
            AtomicInteger denied = new AtomicInteger();
            AtomicInteger aCount = new AtomicInteger();
            AtomicInteger bCount = new AtomicInteger();
            var a = runtime.<String>spawnShared(() -> (message, actor) ->
                    aCount.incrementAndGet());
            var b = runtime.<String>spawnShared(() -> (message, actor) ->
                    bCount.incrementAndGet());
            var producer = runtime.<String>spawnShared(() -> (message, actor) -> {
                if (message.equals("old-route")) {
                    try {
                        old.get().forwardRoundRobin("replay");
                    } catch (IllegalStateException retired) {
                        denied.incrementAndGet();
                    }
                } else if (message.equals("try-close")) {
                    try {
                        active.get().close();
                    } catch (SecurityException deniedClose) {
                        denied.incrementAndGet();
                    }
                } else {
                    active.get().forwardRoundRobin("delivered");
                }
            });
            var plan = graph();
            Map<String, ActorRuntime.ActorRef<String>> bindings =
                    Map.of("producer", producer, "worker-a", a, "worker-b", b);
            var first = new ActorPipelineMailboxRouter<>(runtime, plan, bindings);
            old.set(first);
            active.set(first);
            producer.send("first");
            producer.drain().get(5, TimeUnit.SECONDS);
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);
            assertEquals(1, aCount.get());
            assertEquals(0, bCount.get());

            first.close();  // supervisor retires the first routing generation
            var second = new ActorPipelineMailboxRouter<>(runtime, plan, bindings);
            active.set(second);  // same immutable plan must NOT inherit cursor
            producer.send("old-route");
            producer.send("try-close");
            producer.send("new-generation");
            producer.drain().get(5, TimeUnit.SECONDS);
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, denied.get(), "retired route and actor close both denied");
            assertEquals(2, aCount.get(), "new generation must restart RR at first edge");
            assertEquals(0, bCount.get());
            second.close();
            producer.stop();
            a.stop();
            b.stop();
            producer.getDone().get(5, TimeUnit.SECONDS);
            a.getDone().get(5, TimeUnit.SECONDS);
            b.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorCarrierNeverWaitsForHostRetirementGate() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorPipelineMailboxRouter<String>> router =
                    new AtomicReference<>();
            AtomicInteger denied = new AtomicInteger();
            CountDownLatch refused = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            var a = runtime.<String>spawnShared(() -> (message, actor) ->
                    delivered.countDown());
            var b = runtime.<String>spawnShared(() -> (message, actor) -> { });
            var producer = runtime.<String>spawnShared(() -> (message, actor) -> {
                if (message.equals("while-retiring")) {
                    try {
                        router.get().forwardRoundRobin("must-not-block");
                    } catch (IllegalStateException unavailable) {
                        if (unavailable.getMessage().contains("retirement")) {
                            denied.incrementAndGet();
                        }
                    } finally {
                        refused.countDown();
                    }
                } else {
                    router.get().forwardRoundRobin("valid");
                }
            });
            var attached = new ActorPipelineMailboxRouter<>(runtime, graph(),
                    Map.of("producer", producer, "worker-a", a, "worker-b", b));
            router.set(attached);

            // White-box hold models a supervisor reconfiguration lease. The
            // actor must FAIL immediately instead of blocking a carrier.
            var field = ActorPipelineMailboxRouter.class
                    .getDeclaredField("retirementGate");
            field.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantReadWriteLock)
                    field.get(attached);
            lock.writeLock().lock();
            try {
                producer.send("while-retiring");
                assertTrue(refused.await(5, TimeUnit.SECONDS),
                        "actor carrier blocked behind host retirement lock");
                assertEquals(1, denied.get());
            } finally {
                lock.writeLock().unlock();
            }
            producer.send("normal");
            producer.drain().get(5, TimeUnit.SECONDS);
            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "rejected admission must not consume the RR cursor");
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);
            attached.close();
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
