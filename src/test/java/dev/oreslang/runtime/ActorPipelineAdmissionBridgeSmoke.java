package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        // An adapter must not be able to invalidate the epoch while a
        // partial fan-out is publishing, even by reentering the Java monitor.
        var reentrant = new ActorPipelineRouteSession(graph, 40, 4);
        var reentrantRoot = reentrant.ingress("source");
        var denials = new AtomicInteger();
        var rejectedBatch = ActorPipelineAdmissionBridge.forwardAll(
                reentrant, reentrantRoot, "publish", (child, value) -> {
                    try {
                        reentrant.abortAndAdvanceGeneration(41);
                        throw new AssertionError("reentrant abort accepted");
                    } catch (IllegalStateException expected) {
                        denials.incrementAndGet();
                    }
                    try {
                        reentrant.close();
                        throw new AssertionError("reentrant STOP accepted");
                    } catch (IllegalStateException expected) {
                        denials.incrementAndGet();
                    }
                    return ActorPipelineAdmissionBridge.Admission.FULL;
                });
        check(rejectedBatch.rejected() == 3 && denials.get() == 6
                        && reentrant.generation() == 40 && reentrant.outstanding() == 1,
                "reentrant epoch change broke fan-out publication");
        reentrant.complete(reentrantRoot);

        // A concurrent supervisor abort must wait until an admitted batch
        // finishes; no new-generation fence may overtake an edge publication.
        var concurrent = new ActorPipelineRouteSession(graph, 50, 4);
        var concurrentRoot = concurrent.ingress("source");
        var inAdapter = new CountDownLatch(1);
        var releaseAdapter = new CountDownLatch(1);
        var attemptAbort = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        var failures = new AtomicReference<Throwable>();
        var cancelled = new AtomicInteger(-1);
        Thread publisher = new Thread(() -> {
            try {
                ActorPipelineAdmissionBridge.forwardAll(
                        concurrent, concurrentRoot, "payload", (child, value) -> {
                            inAdapter.countDown();
                            try {
                                if (!releaseAdapter.await(5, TimeUnit.SECONDS))
                                    throw new IllegalStateException("test publication gate timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            return ActorPipelineAdmissionBridge.Admission.FULL;
                        });
            } catch (Throwable failure) {
                failures.compareAndSet(null, failure);
            }
        });
        Thread supervisor = new Thread(() -> {
            try {
                attemptAbort.countDown();
                cancelled.set(concurrent.abortAndAdvanceGeneration(51));
            } catch (Throwable failure) {
                failures.compareAndSet(null, failure);
            } finally {
                aborted.countDown();
            }
        });
        publisher.start();
        check(inAdapter.await(5, TimeUnit.SECONDS), "publisher never entered adapter");
        supervisor.start();
        check(attemptAbort.await(5, TimeUnit.SECONDS), "supervisor did not start");
        check(!aborted.await(100, TimeUnit.MILLISECONDS),
                "concurrent abort overtook an in-flight mailbox publication");
        releaseAdapter.countDown();
        publisher.join(5000);
        supervisor.join(5000);
        check(!publisher.isAlive() && !supervisor.isAlive() && failures.get() == null,
                "epoch publication race deadlocked or failed");
        check(cancelled.get() == 1 && concurrent.generation() == 51
                        && concurrent.outstanding() == 0,
                "concurrent abort did not retire exactly the uncompleted source");
        System.out.println("ORES_ACTOR_ADMISSION_BRIDGE_OK batches=10000");
    }
}
