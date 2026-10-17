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
    void fanoutFaninReplyGenerationAndConcurrentRoundRobin() throws Exception {
        ActorPipelineRouteSessionSmoke.main(new String[0]);
    }
}
