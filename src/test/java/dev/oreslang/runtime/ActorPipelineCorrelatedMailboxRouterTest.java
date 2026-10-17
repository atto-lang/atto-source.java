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
            var replied = new CountDownLatch(2);
            var observed = Collections.synchronizedList(
                    new ArrayList<ActorPipelineCorrelatedMailboxRouter.Correlation>());
            var originReplies = Collections.synchronizedList(
                    new ArrayList<ActorPipelineCorrelatedMailboxRouter.Correlation>());
            var unexpectedPayload = new AtomicInteger();

            var sink = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"fan-in".equals(value)) unexpectedPayload.incrementAndGet();
                observed.add(binding.get().currentCorrelation());
                binding.get().backward("ack");
                delivered.countDown();
            });
            var a = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("fanout")) binding.get().forward("fan-in");
                else if (value.equals("ack")) binding.get().backward("ack");
                else throw new AssertionError("worker-a unexpected payload");
            });
            var b = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("fanout")) binding.get().forward("fan-in");
                else if (value.equals("ack")) binding.get().backward("ack");
                else throw new AssertionError("worker-b unexpected payload");
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("begin")) receipts.set(binding.get().forwardAll("fanout"));
                else if (value.equals("ack")) {
                    originReplies.add(binding.get().currentCorrelation());
                    replied.countDown();
                } else throw new AssertionError("source unexpected payload");
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
            assertTrue(replied.await(8, TimeUnit.SECONDS),
                    "two reverse branches must reach the actual origin");
            source.drain().get(5, TimeUnit.SECONDS);
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);
            sink.drain().get(5, TimeUnit.SECONDS);
            assertEquals(0, router.outstanding(), "all delivered frames must retire");
            assertEquals(0, unexpectedPayload.get());
            var batch = receipts.get();
            assertNotNull(batch);
            assertEquals(2, batch.size());
            assertThrows(UnsupportedOperationException.class,
                    () -> batch.clear(),
                    "published fanout receipts must retain immutable branch identity");
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
            assertEquals(2, originReplies.size());
            assertEquals("source", originReplies.get(0).receiver());
            assertEquals("source", originReplies.get(1).receiver());
            assertEquals(observed.get(0).flowId(), originReplies.get(0).flowId());
            assertEquals(observed.get(1).flowId(), originReplies.get(1).flowId());
            assertNotEquals(originReplies.get(0).routeId(), originReplies.get(1).routeId(),
                    "two fan-in returns cannot collapse into one global previous actor");

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
    void directReplyBypassesIntermediateActorsButPreservesFanInBranches()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bound = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var replies = new CountDownLatch(2);
            var sourceReplyFrames = Collections.synchronizedList(
                    new ArrayList<ActorPipelineCorrelatedMailboxRouter.Correlation>());
            var sinkFrames = Collections.synchronizedList(
                    new ArrayList<ActorPipelineCorrelatedMailboxRouter.Correlation>());
            var deniedAtIngress = new AtomicInteger();
            var deniedReplay = new AtomicInteger();
            var unexpected = new AtomicReference<Throwable>();
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> {
                try {
                    if (!value.equals("fan-in")) {
                        throw new AssertionError("unexpected sink mail");
                    }
                    sinkFrames.add(bound.get().currentCorrelation());
                    bound.get().reply("origin-ack");
                    try {
                        bound.get().reply("replayed-ack");
                        throw new AssertionError("completed reply frame reused");
                    } catch (IllegalStateException expected) {
                        deniedReplay.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            var workerA = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!value.equals("broadcast")) throw new AssertionError();
                bound.get().forward("fan-in");
            });
            var workerB = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!value.equals("broadcast")) throw new AssertionError();
                bound.get().forward("fan-in");
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                try {
                    if (value.equals("begin")) {
                        try {
                            bound.get().reply("forged");
                            throw new AssertionError("ingress replied to itself");
                        } catch (IllegalStateException expected) {
                            deniedAtIngress.incrementAndGet();
                        }
                        var batch = bound.get().forwardAll("broadcast");
                        if (batch.size() != 2 ||
                                batch.stream().anyMatch(a -> !a.admitted())) {
                            throw new AssertionError("fan-out branch admission failed");
                        }
                    } else if (value.equals("origin-ack")) {
                        sourceReplyFrames.add(bound.get().currentCorrelation());
                        replies.countDown();
                    } else {
                        throw new AssertionError("unexpected source mail");
                    }
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", workerA,
                           "worker-b", workerB, "sink", sink), 31, 12);
            bound.set(router);
            router.ingress("source", "begin");
            assertTrue(replies.await(8, TimeUnit.SECONDS),
                    "two direct origin replies did not arrive");
            source.drain().get(5, TimeUnit.SECONDS);
            workerA.drain().get(5, TimeUnit.SECONDS);
            workerB.drain().get(5, TimeUnit.SECONDS);
            sink.drain().get(5, TimeUnit.SECONDS);
            assertNull(unexpected.get(), "actor routing callback failed");
            assertEquals(1, deniedAtIngress.get());
            assertEquals(2, deniedReplay.get());
            assertEquals(2, sinkFrames.size());
            assertEquals(2, sourceReplyFrames.size());
            assertEquals(sinkFrames.get(0).flowId(), sinkFrames.get(1).flowId());
            assertNotEquals(sinkFrames.get(0).routeId(), sinkFrames.get(1).routeId());
            assertEquals(sinkFrames.get(0).flowId(), sourceReplyFrames.get(0).flowId());
            assertEquals(sinkFrames.get(1).flowId(), sourceReplyFrames.get(1).flowId());
            assertNotEquals(sourceReplyFrames.get(0).routeId(),
                            sourceReplyFrames.get(1).routeId());
            assertTrue(sourceReplyFrames.stream().allMatch(c ->
                            c.receiver().equals("source") && c.generation() == 31));
            assertEquals(0, router.outstanding(), "origin-directed replies leaked frames");
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
            var privateWorker = runtime.<String>spawnPrivateTrusted(
                    context -> (value, actor) -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> new ActorPipelineCorrelatedMailboxRouter<>(
                            runtime, fanInGraph(),
                            Map.of("source", source, "worker-a", workerA,
                                   "worker-b", privateWorker, "sink", sink),
                            3, 8),
                    "private actor must not be given VM-owned shared route frames");
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
            privateWorker.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            workerA.getDone().get(5, TimeUnit.SECONDS);
            workerB.getDone().get(5, TimeUnit.SECONDS);
            privateWorker.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
        }
    }
    @Test
    void supervisorRetirementFencesAlreadyExecutingRoutedActorBeforePublication()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var routerRef = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var completed = new CountDownLatch(1);
            var denied = new AtomicInteger();
            var unexpected = new AtomicReference<Throwable>();
            var workerA = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var workerB = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                entered.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("retirement race release never arrived");
                    // This is a legitimate routed receive with a valid frame,
                    // but the host already sealed the graph. It must not
                    // publish to a mailbox using a stale route generation.
                    try {
                        routerRef.get().forwardTo("worker-a", "late");
                        unexpected.compareAndSet(null,
                                new AssertionError("post-retirement DATA was admitted"));
                    } catch (IllegalStateException expected) {
                        denied.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                } finally {
                    completed.countDown();
                }
            });
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", workerA,
                            "worker-b", workerB, "sink", sink), 23, 16);
            routerRef.set(router);
            router.ingress("source", "begin");
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "source actor never began its routed receive");
                // Closing here must not wait for the actor's physical carrier:
                // it is executing user code but has no routing read lease yet.
                Thread hostClose = Thread.ofPlatform()
                        .name("route-retirement-host-test").start(router::close);
                hostClose.join(3000);
                assertFalse(hostClose.isAlive(),
                        "host retirement blocked on an actor carrier");
            } finally {
                release.countDown();
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "source actor failed: " + unexpected.get());
            assertEquals(1, denied.get());
            assertEquals(0, router.outstanding(),
                    "retirement must cancel all outstanding route credits");
            assertThrows(IllegalStateException.class,
                    () -> router.ingress("source", "new-flow-after-close"));
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
