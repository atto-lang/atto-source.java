package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/** JUnit entrypoint for the exact standalone reply-correlation regression. */
final class ActorPipelineRouteSessionTest {
    private static ActorPipelineRoutePlan linearPlan() {
        return new ActorPipelineRoutePlan(
                java.util.List.of(
                        new ActorPipelineRoutePlan.Stage("source", "Source",
                                ActorPipelineRoutePlan.Role.READABLE, "", "int"),
                        new ActorPipelineRoutePlan.Stage("transform", "Transform",
                                ActorPipelineRoutePlan.Role.TRANSFORM, "int", "int"),
                        new ActorPipelineRoutePlan.Stage("sink", "Sink",
                                ActorPipelineRoutePlan.Role.WRITABLE, "int", "")),
                java.util.List.of(
                        new ActorPipelineRoutePlan.Edge("source", "transform", "int"),
                        new ActorPipelineRoutePlan.Edge("transform", "sink", "int")));
    }

    @Test
    void failedReverseMailboxPublicationRetainsAuthenticatedSourceFrame() {
        var session = new ActorPipelineRouteSession(linearPlan(), 1, 8);
        var source = session.ingress("source");
        var transform = session.forward(source);
        ActorPipelineRouteSession.retireDelivered(source);
        var sink = session.forward(transform);
        ActorPipelineRouteSession.retireDelivered(transform);
        org.junit.jupiter.api.Assertions.assertEquals(1, session.outstanding());

        var candidateHop = session.returnOneHop(sink);
        org.junit.jupiter.api.Assertions.assertEquals("transform", candidateHop.receiver());
        org.junit.jupiter.api.Assertions.assertEquals(2, session.outstanding());
        // Simulate rejection BEFORE the destination mailbox accepts the
        // candidate. sendChild retires only that failed candidate.
        ActorPipelineRouteSession.retireDelivered(candidateHop);
        session.requireLive(sink);
        org.junit.jupiter.api.Assertions.assertEquals(1, session.outstanding());

        var candidateReply = session.returnToOrigin(sink);
        org.junit.jupiter.api.Assertions.assertEquals("source", candidateReply.receiver());
        ActorPipelineRouteSession.retireDelivered(candidateReply);
        session.requireLive(sink);
        org.junit.jupiter.api.Assertions.assertEquals(1, session.outstanding());

        // After successful publication the bridge retires the source.
        var published = session.returnToOrigin(sink);
        ActorPipelineRouteSession.retireDelivered(sink);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> session.requireLive(sink));
        org.junit.jupiter.api.Assertions.assertEquals(1, session.outstanding());
        ActorPipelineRouteSession.retireDelivered(published);
        org.junit.jupiter.api.Assertions.assertEquals(0, session.outstanding());
    }

    @Test
    void exhaustedLedgerDoesNotConsumeReverseSourceAuthority() {
        var session = new ActorPipelineRouteSession(linearPlan(), 1, 2);
        var root = session.ingress("source");
        var transform = session.forward(root);
        ActorPipelineRouteSession.retireDelivered(root);
        var sink = session.forward(transform);
        ActorPipelineRouteSession.retireDelivered(transform);
        var unrelated = session.ingress("source");
        org.junit.jupiter.api.Assertions.assertEquals(2, session.outstanding());

        var rejection = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> session.returnToOrigin(sink));
        org.junit.jupiter.api.Assertions.assertTrue(
                rejection.getMessage().contains("budget"));
        session.requireLive(sink);
        ActorPipelineRouteSession.retireDelivered(unrelated);
        var child = session.returnToOrigin(sink);
        ActorPipelineRouteSession.retireDelivered(sink);
        org.junit.jupiter.api.Assertions.assertEquals(1, session.outstanding());
        ActorPipelineRouteSession.retireDelivered(child);
        org.junit.jupiter.api.Assertions.assertEquals(0, session.outstanding());
    }

    @Test
    void actualReplyMailboxRejectionKeepsAuthenticatedSourceFrameLive() throws Exception {
        var plan = new ActorPipelineRoutePlan(
                java.util.List.of(
                        new ActorPipelineRoutePlan.Stage("source", "Source",
                                ActorPipelineRoutePlan.Role.READABLE, "", "int"),
                        new ActorPipelineRoutePlan.Stage("sink", "Sink",
                                ActorPipelineRoutePlan.Role.WRITABLE, "int", "")),
                java.util.List.of(
                        new ActorPipelineRoutePlan.Edge("source", "sink", "int")));

        var routerRef = new java.util.concurrent.atomic.AtomicReference<
                ActorPipelineCorrelatedMailboxRouter<Integer>>();
        var forwarded = new java.util.concurrent.CountDownLatch(1);
        var sinkEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseSource = new java.util.concurrent.CountDownLatch(1);
        var originTerminated = new java.util.concurrent.CountDownLatch(1);
        var checked = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var source = runtime.<Integer>spawnShared(() -> (message, turn) -> {
                try {
                    routerRef.get().forward(message);
                    forwarded.countDown();
                    if (!releaseSource.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("source release timed out");
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    forwarded.countDown();
                }
            });

            var sink = runtime.<Integer>spawnShared(() -> (message, turn) -> {
                sinkEntered.countDown();
                try {
                    if (!originTerminated.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("origin termination timed out");
                    }
                    try {
                        routerRef.get().reply(message);
                        throw new AssertionError("reply to terminated origin unexpectedly succeeded");
                    } catch (RuntimeException expected) {
                        var correlation = routerRef.get().currentCorrelation();
                        org.junit.jupiter.api.Assertions.assertEquals("sink", correlation.receiver());
                        org.junit.jupiter.api.Assertions.assertEquals(1L, correlation.generation());
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    checked.countDown();
                    turn.self().stop();
                }
            });

            source.ready().get(5, java.util.concurrent.TimeUnit.SECONDS);
            sink.ready().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var router = new ActorPipelineCorrelatedMailboxRouter<>(
                    runtime, plan, java.util.Map.of("source", source, "sink", sink), 1, 8);
            routerRef.set(router);

            router.ingress("source", 7);
            org.junit.jupiter.api.Assertions.assertTrue(
                    forwarded.await(5, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(
                    sinkEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            source.stop();
            releaseSource.countDown();
            org.junit.jupiter.api.Assertions.assertTrue(
                    source.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            originTerminated.countDown();

            org.junit.jupiter.api.Assertions.assertTrue(
                    checked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertNull(
                    failure.get(), () -> "actual reverse rejection proof failed: " + failure.get());
            org.junit.jupiter.api.Assertions.assertTrue(
                    sink.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            router.close();
        }
    }

    @Test
    void fanoutFaninReplyGenerationAndConcurrentRoundRobin() throws Exception {
        ActorPipelineRouteSessionSmoke.main(new String[0]);
    }
}
