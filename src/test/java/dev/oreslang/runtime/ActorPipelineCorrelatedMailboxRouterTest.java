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

    private static ActorPipelineRoutePlan linearGraph() {
        return new ActorPipelineRoutePlan(
                List.of(
                        new ActorPipelineRoutePlan.Stage("source", "Producer",
                                ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage("worker", "Worker",
                                ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event"),
                        new ActorPipelineRoutePlan.Stage("sink", "Sink",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                        new ActorPipelineRoutePlan.Edge("source", "worker", "Event"),
                        new ActorPipelineRoutePlan.Edge("worker", "sink", "Event")));
    }

    @Test
    void compilerBridgeUsesOnlyEnvelopeBoundTurnAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var finished = new CountDownLatch(1);
            var denied = new CountDownLatch(1);
            var response = new AtomicReference<String>();
            var ordinaryFailure = new AtomicReference<Throwable>();

            var ordinary = runtime.<String>spawnShared(() -> (value, actor) -> {
                try {
                    runtime.routeCurrentPipelineForward("forged");
                } catch (Throwable failure) {
                    ordinaryFailure.set(failure);
                    denied.countDown();
                }
            });
            runtime.send(ordinary, "ordinary-mail");
            assertTrue(denied.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, ordinaryFailure.get(),
                    "ordinary ActorMail must not acquire pipeline authority");

            var sink = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("work:sink", value);
                runtime.routeCurrentPipelineBackward("ack:sink");
            });
            var worker = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("work")) {
                    runtime.routeCurrentPipelineForward(value + ":sink");
                } else if (value.equals("ack:sink")) {
                    runtime.routeCurrentPipelineBackward(value + ":worker");
                } else {
                    throw new AssertionError("worker unexpected payload " + value);
                }
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("begin")) {
                    runtime.routeCurrentPipelineForward("work");
                } else if (value.equals("ack:sink:worker")) {
                    response.set(value);
                    finished.countDown();
                } else {
                    throw new AssertionError("source unexpected payload " + value);
                }
            });

            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, linearGraph(),
                    Map.of("source", source, "worker", worker, "sink", sink),
                    23, 12);

            assertThrows(SecurityException.class,
                    () -> runtime.routeCurrentPipelineForward("host-spoof"));
            router.ingress("source", "begin");
            assertTrue(finished.await(8, TimeUnit.SECONDS),
                    "authenticated routed source did not complete reverse path");
            assertEquals("ack:sink:worker", response.get());

            source.drain().get(5, TimeUnit.SECONDS);
            worker.drain().get(5, TimeUnit.SECONDS);
            sink.drain().get(5, TimeUnit.SECONDS);
            ordinary.drain().get(5, TimeUnit.SECONDS);
            assertEquals(0, router.outstanding(),
                    "compiler bridge must not leak route frames");

            router.close();
            source.stop();
            worker.stop();
            sink.stop();
            ordinary.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            worker.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
            ordinary.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void mailboxRejectsMismatchedFrameAuthorityPairBeforePublication() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var deliveries = new AtomicInteger();
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                deliveries.incrementAndGet();
            });
            var worker = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> { });

            var graph = linearGraph();
            var authority = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, graph,
                    Map.of("source", source, "worker", worker, "sink", sink),
                    37, 8);
            var foreignSession = new ActorPipelineRouteSession(graph, 37, 8);
            var foreignFrame = foreignSession.ingress("source");
            try {
                assertThrows(SecurityException.class,
                        () -> runtime.sendWithPipelineFrame(
                                source, "forged", foreignFrame, authority),
                        "mailbox admission must reject a frame from another route session");
                source.drain().get(5, TimeUnit.SECONDS);
                assertEquals(0, deliveries.get(),
                        "mismatched route metadata must fail before DATA publication");
            } finally {
                ActorPipelineRouteSession.retireDelivered(foreignFrame);
            }

            authority.close();
            source.stop();
            worker.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            worker.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void successfulSingleRouteConsumesFrameAndCannotEmulateFanout() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var routerRef = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var finished = new CountDownLatch(1);
            var firstDelivered = new CountDownLatch(1);
            var unexpectedSecond = new AtomicInteger();
            var failedSelection = new AtomicReference<Throwable>();
            var replayDenied = new AtomicReference<Throwable>();

            var workerA = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("first", value);
                firstDelivered.countDown();
            });
            var workerB = runtime.<String>spawnShared(() -> (value, actor) -> {
                unexpectedSecond.incrementAndGet();
            });
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                try {
                    routerRef.get().forwardTo("missing", "not-published");
                } catch (Throwable failure) {
                    failedSelection.set(failure);
                }
                routerRef.get().forwardTo("worker-a", "first");
                try {
                    // A second single-route publication from the same inbound
                    // frame would be hidden fan-out and must be rejected.
                    routerRef.get().forwardTo("worker-b", "second");
                } catch (Throwable failure) {
                    replayDenied.set(failure);
                } finally {
                    finished.countDown();
                }
            });

            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", workerA,
                           "worker-b", workerB, "sink", sink), 31, 12);
            routerRef.set(router);
            router.ingress("source", "begin");

            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failedSelection.get(),
                    "a failed selector must not consume the authenticated source frame");
            assertInstanceOf(IllegalStateException.class, replayDenied.get(),
                    "a successful single-route publication must consume the source frame");

            source.drain().get(5, TimeUnit.SECONDS);
            workerA.drain().get(5, TimeUnit.SECONDS);
            workerB.drain().get(5, TimeUnit.SECONDS);
            assertEquals(0, unexpectedSecond.get(),
                    "reusing one inbound frame must not emulate forward_all");
            assertEquals(0, router.outstanding(),
                    "one-shot source and delivered child frames must retire");

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
    void sealedReplyReturnsDirectlyToAuthenticatedOrigin() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var done = new CountDownLatch(1);
            var workerDeliveries = new AtomicInteger();
            var response = new AtomicReference<String>();

            var sink = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("request:worker", value);
                runtime.routeCurrentPipelineReply("direct-reply");
            });
            var worker = runtime.<String>spawnShared(() -> (value, actor) -> {
                workerDeliveries.incrementAndGet();
                assertEquals("request", value);
                runtime.routeCurrentPipelineForward(value + ":worker");
            });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (value.equals("begin")) {
                    runtime.routeCurrentPipelineForward("request");
                } else if (value.equals("direct-reply")) {
                    response.set(value);
                    done.countDown();
                } else {
                    throw new AssertionError("source unexpected payload " + value);
                }
            });

            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, linearGraph(),
                    Map.of("source", source, "worker", worker, "sink", sink),
                    29, 12);

            assertThrows(SecurityException.class,
                    () -> runtime.routeCurrentPipelineReply("host-spoof"));
            router.ingress("source", "begin");
            assertTrue(done.await(8, TimeUnit.SECONDS),
                    "sealed reply did not reach authenticated flow origin");

            source.drain().get(5, TimeUnit.SECONDS);
            worker.drain().get(5, TimeUnit.SECONDS);
            sink.drain().get(5, TimeUnit.SECONDS);
            assertEquals("direct-reply", response.get());
            assertEquals(1, workerDeliveries.get(),
                    "direct reply must not execute the intermediate worker again");
            assertEquals(0, router.outstanding(),
                    "direct reply must retire all route frames");

            router.close();
            source.stop();
            worker.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            worker.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
        }
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
    void compilerForwardAllBridgeReturnsPreallocatedTypedBranchReceipts()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var report = new AtomicReference<List<ActorRuntime.ActorRouteReceipt>>();
            var reportReady = new CountDownLatch(1);
            var delivered = new CountDownLatch(1);

            var workerA = runtime.<String>spawnShared(() -> (value, actor) -> {
                assertEquals("payload", value);
                delivered.countDown();
            });
            var workerB = runtime.<String>spawnShared(() -> (value, actor) -> {
                throw new AssertionError("stopped worker must never receive routed DATA");
            });
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                report.set(runtime.routeCurrentPipelineForwardAll("payload"));
                reportReady.countDown();
            });

            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", workerA,
                           "worker-b", workerB, "sink", sink), 43, 16);

            workerB.stop();
            workerB.getDone().get(5, TimeUnit.SECONDS);

            assertThrows(SecurityException.class,
                    () -> runtime.routeCurrentPipelineForwardAll("host-spoof"));
            router.ingress("source", "start");

            assertTrue(reportReady.await(5, TimeUnit.SECONDS));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            var receipts = report.get();
            assertNotNull(receipts);
            assertEquals(2, receipts.size());
            assertThrows(UnsupportedOperationException.class, receipts::clear);

            var first = receipts.get(0);
            var second = receipts.get(1);
            assertEquals(0, first.edgeIndex());
            assertEquals(1, second.edgeIndex());
            assertEquals(first.flowId(), second.flowId());
            assertNotEquals(first.routeId(), second.routeId());
            assertTrue(first.admitted());
            assertEquals("", first.error());
            assertFalse(second.admitted());
            assertEquals("worker-b", second.target());
            assertTrue(second.error().contains("mailbox admission rejected"));

            source.drain().get(5, TimeUnit.SECONDS);
            workerA.drain().get(5, TimeUnit.SECONDS);
            assertEquals(0, router.outstanding(),
                    "source fanout bridge must retire accepted, rejected and source frames");

            router.close();
            source.stop();
            workerA.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            workerA.getDone().get(5, TimeUnit.SECONDS);
            sink.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void partialMailboxRejectionRetainsEarlierAdmissionAndOriginalFailure()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var routerRef = new AtomicReference<ActorPipelineCorrelatedMailboxRouter<String>>();
            var report = new AtomicReference<List<ActorPipelineCorrelatedMailboxRouter.Admission>>();
            var reportReady = new CountDownLatch(1);
            var delivered = new CountDownLatch(1);

            var workerA = runtime.<String>spawnShared(() -> (value, actor) -> {
                if (!"payload".equals(value))
                    throw new AssertionError("unexpected first branch payload");
                delivered.countDown();
            });
            var workerB = runtime.<String>spawnShared(() -> (value, actor) -> {
                throw new AssertionError("stopped worker must never receive routed DATA");
            });
            var sink = runtime.<String>spawnShared(() -> (value, actor) -> { });
            var source = runtime.<String>spawnShared(() -> (value, actor) -> {
                report.set(routerRef.get().forwardAll("payload"));
                reportReady.countDown();
            });

            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, fanInGraph(),
                    Map.of("source", source, "worker-a", workerA,
                           "worker-b", workerB, "sink", sink), 41, 16);
            routerRef.set(router);
            // Binding is valid when the host installs the graph. Make only
            // the SECOND recipient reject admission during the routed turn.
            workerB.stop();
            workerB.getDone().get(5, TimeUnit.SECONDS);

            router.ingress("source", "start");
            assertTrue(reportReady.await(5, TimeUnit.SECONDS),
                    "first branch success must remain reportable");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            var receipts = report.get();
            assertNotNull(receipts);
            assertEquals(2, receipts.size());
            assertTrue(receipts.get(0).admitted());
            assertFalse(receipts.get(1).admitted());
            assertNull(receipts.get(0).failure());
            assertNotNull(receipts.get(1).failure());
            assertNotNull(receipts.get(1).failure().getCause(),
                    "preallocated failure receipt must retain actual exception");
            assertEquals(receipts.get(0).flowId(), receipts.get(1).flowId());
            assertNotEquals(receipts.get(0).routeId(), receipts.get(1).routeId());
            assertThrows(UnsupportedOperationException.class, receipts::clear);

            source.drain().get(5, TimeUnit.SECONDS);
            workerA.drain().get(5, TimeUnit.SECONDS);
            assertEquals(0, router.outstanding(),
                    "accepted and rejected branch frame credits must retire");
            router.close();
            source.stop();
            workerA.stop();
            sink.stop();
            source.getDone().get(5, TimeUnit.SECONDS);
            workerA.getDone().get(5, TimeUnit.SECONDS);
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
