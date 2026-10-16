package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;

/** Standalone JDK21 admission contract (not guest-language execution). */
public final class ActorPipelineAdmissionBridgeSmoke {
    private ActorPipelineAdmissionBridgeSmoke() { }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var graph = new ActorPipelineRoutePlan(
                List.of(new ActorPipelineRoutePlan.Stage("source", "Source",
                                ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage("a", "Worker",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                        new ActorPipelineRoutePlan.Stage("b", "Worker",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                        new ActorPipelineRoutePlan.Stage("c", "Worker",
                                ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(new ActorPipelineRoutePlan.Edge("source", "a", "Event"),
                        new ActorPipelineRoutePlan.Edge("source", "b", "Event"),
                        new ActorPipelineRoutePlan.Edge("source", "c", "Event")));
        var session = new ActorPipelineRouteSession(graph, 9, 4);
        var root = session.ingress("source");
        List<ActorPipelineRouteSession.MailFrame> accepted = new ArrayList<>();
        var result = ActorPipelineAdmissionBridge.forwardAll(session, root, "data",
                (child, payload) -> {
                    check(payload.equals("data"), "payload changed");
                    if (child.receiver().equals("b"))
                        return ActorPipelineAdmissionBridge.Admission.FULL;
                    if (child.receiver().equals("c"))
                        return ActorPipelineAdmissionBridge.Admission.CLOSED;
                    accepted.add(child);
                    return ActorPipelineAdmissionBridge.Admission.ADMITTED;
                });
        check(result.receipts().size() == 3 && result.admitted() == 1
                && result.rejected() == 2, "partial-admission receipt mismatch");
        check(session.outstanding() == 2, "rejected route frames leaked");
        try {
            result.receipts().clear();
            throw new AssertionError("mutable receipt list");
        } catch (UnsupportedOperationException expected) {
            // Immutable results.
        }
        session.complete(accepted.getFirst());
        session.complete(root);

        root = session.ingress("source");
        accepted.clear();
        try {
            ActorPipelineAdmissionBridge.forwardAll(session, root, "data",
                    (child, payload) -> {
                        if (child.receiver().equals("b"))
                            throw new IllegalStateException("unpublished edge rejected");
                        accepted.add(child);
                        return ActorPipelineAdmissionBridge.Admission.ADMITTED;
                    });
            throw new AssertionError("unexpected adapter success");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("unpublished edge"),
                    "unexpected adapter failure");
        }
        check(session.outstanding() == 2,
                "throwing or unattempted edge retained a route frame");
        session.complete(accepted.getFirst());
        session.complete(root);

        root = session.ingress("source");
        try {
            ActorPipelineAdmissionBridge.forwardAll(session, root, "data",
                    (child, payload) -> null);
            throw new AssertionError("null admission was accepted");
        } catch (NullPointerException expected) {
            check(session.outstanding() == 1, "null admission leaked children");
        }
        session.complete(root);

        root = session.ingress("source");
        for (int i = 0; i < 10000; ++i) {
            final int value = i;
            List<ActorPipelineRouteSession.MailFrame> batch = new ArrayList<>();
            var all = ActorPipelineAdmissionBridge.forwardAll(session, root, value,
                    (child, payload) -> {
                        check(payload == value, "message identity mismatch");
                        batch.add(child);
                        return ActorPipelineAdmissionBridge.Admission.ADMITTED;
                    });
            check(all.admitted() == 3 && session.outstanding() == 4,
                    "admission bypassed three-child credit bound");
            for (var child : batch) session.complete(child);
            check(session.outstanding() == 1, "admitted branch leaked credits");
        }
        session.complete(root);
        check(session.outstanding() == 0, "source retained an active frame");
        System.out.println("ORES_ACTOR_ADMISSION_BRIDGE_OK batches=10000");
    }
}
