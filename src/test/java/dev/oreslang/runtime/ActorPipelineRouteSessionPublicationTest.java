package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Host-only control-plane publication tests; no guest source execution. */
final class ActorPipelineRouteSessionPublicationTest {
    private static ActorPipelineRouteSession newSession() {
        var graph = new ActorPipelineRoutePlan(
                List.of(
                    new ActorPipelineRoutePlan.Stage("source", "Source",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                    new ActorPipelineRoutePlan.Stage("left", "Sink",
                        ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                    new ActorPipelineRoutePlan.Stage("right", "Sink",
                        ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                    new ActorPipelineRoutePlan.Edge("source", "left", "Event"),
                    new ActorPipelineRoutePlan.Edge("source", "right", "Event")));
        return new ActorPipelineRouteSession(graph, 7, 4);
    }

    @Test void rejectedChildMayRetireButSourceCannotBeLostDuringBatch() {
        var session = newSession();
        var source = session.ingress("source");
        var children = new AtomicReference<List<ActorPipelineRouteSession.MailFrame>>();
        session.withAdmissionBarrier(source, () -> {
            ActorPipelineRouteSession.retireDelivered(source);
            assertEquals(1, session.outstanding(),
                    "source must survive synchronous callback reentrancy");
            assertThrows(IllegalStateException.class, () -> session.complete(source));
            children.set(session.forwardAll(source));
            ActorPipelineRouteSession.retireDelivered(children.get().get(0));
            assertEquals(2, session.outstanding(),
                    "unpublished child cleanup remains authorized");
            return null;
        });
        session.complete(children.get().get(1));
        session.complete(source);
        assertEquals(0, session.outstanding());
        session.advanceGeneration(8);
    }

    @Test void rejectedOrThrowingPublicationReleasesBarrierWithoutReleasingSource() {
        var session = newSession();
        var source = session.ingress("source");
        var failure = new AssertionError("test injected host publication failure");
        assertSame(failure, assertThrows(AssertionError.class,
                () -> session.withAdmissionBarrier(source, () -> { throw failure; })));
        assertEquals(1, session.outstanding());
        // A later trusted dispatch can still bind this source; exception
        // unwinding must never strand publicationInProgress=true.
        var successors = session.withAdmissionBarrier(source,
                () -> session.forwardAll(source));
        for (var child : successors) session.complete(child);
        session.complete(source);
        assertEquals(0, session.outstanding());
    }

    @Test void reverseAndReplyCannotConsumeActiveSourcePublication() {
        var session = newSession();
        var source = session.ingress("source");
        var child = session.forwardTo(source, "left");
        session.withAdmissionBarrier(child, () -> {
            assertThrows(IllegalStateException.class, () -> session.returnOneHop(child));
            assertThrows(IllegalStateException.class, () -> session.returnToOrigin(child));
            assertThrows(IllegalStateException.class, () -> session.backward(child));
            assertThrows(IllegalStateException.class, () -> session.reply(child));
            return null;
        });
        var returned = session.returnOneHop(child);
        session.complete(returned);
        session.complete(source);
    }
}
