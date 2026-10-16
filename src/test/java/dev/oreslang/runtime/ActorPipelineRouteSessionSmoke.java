package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK21 standalone host contract test, no Maven/Graal needed. */
public final class ActorPipelineRouteSessionSmoke {
    private ActorPipelineRouteSessionSmoke() { }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void reject(Runnable action, String reason) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new AssertionError(reason);
    }
    private static ActorPipelineRoutePlan graph() {
        return new ActorPipelineRoutePlan(
                List.of(
                    new ActorPipelineRoutePlan.Stage("source", "Source", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                    new ActorPipelineRoutePlan.Stage("worker-a", "Worker", ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event"),
                    new ActorPipelineRoutePlan.Stage("worker-b", "Worker", ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event"),
                    new ActorPipelineRoutePlan.Stage("sink", "Sink", ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")
                ),
                List.of(
                    new ActorPipelineRoutePlan.Edge("source", "worker-a", "Event"),
                    new ActorPipelineRoutePlan.Edge("source", "worker-b", "Event"),
                    new ActorPipelineRoutePlan.Edge("worker-a", "sink", "Event"),
                    new ActorPipelineRoutePlan.Edge("worker-b", "sink", "Event")
                ));
    }
    public static void main(String[] args) throws Exception {
        var plan = graph();
        var ledger = new ActorPipelineRouteSession(plan, 7, 8);
        var origin = ledger.ingress("source");
        reject(() -> ledger.reply(origin), "root cannot forge a return route");
        reject(() -> ledger.forward(origin), "ambiguous source forward allowed");
        var branches = ledger.forwardAll(origin);
        check(branches.size() == 2, "fan-out lost branch");
        check(branches.get(0).flowId() == branches.get(1).flowId(), "fan-out lost correlation");
        check(branches.get(0).routeId() != branches.get(1).routeId(), "branches not independently identified");
        var b0 = ledger.forward(branches.get(0));
        var b1 = ledger.forward(branches.get(1));
        check(b0.receiver().equals("sink") && b1.receiver().equals("sink"), "fan-in wrong receiver");
        var reply0 = ledger.reply(b0);
        var reply1 = ledger.reply(b1);
        check(reply0.reverseActors().equals(List.of("sink", "worker-a", "source")),
                "reply guessed the wrong fan-in origin path");
        check(reply1.reverseActors().equals(List.of("sink", "worker-b", "source")),
                "reply guessed the wrong fan-in origin path");
        check(reply0.routeId() != reply1.routeId(), "fan-in replies lacked branch identities");
        reject(() -> ledger.reply(b0), "replayed reply accepted");
        var back = ledger.backward(branches.get(0));
        check(back.reverseActors().equals(List.of("worker-a", "source")), "backward wrong edge");
        reject(() -> ledger.backward(branches.get(0)), "double backward accepted");
        var byKey = ledger.forwardByKey(origin, "device-42");
        check(byKey.receiver().equals(plan.forwardByKey("source", "device-42").target()),
                "stable key selected wrong child");
        reject(() -> ledger.forwardTo(origin, "sink"), "unauthorized direct edge accepted");
        var direct = ledger.forwardTo(origin, "worker-b");
        check(direct.receiver().equals("worker-b"), "explicit connection failed");
        ledger.complete(direct);
        reject(() -> ledger.complete(direct), "duplicate completion accepted");
        ledger.complete(byKey);
        ledger.complete(branches.get(1));
        ledger.complete(origin);
        check(ledger.outstanding() == 0, "route frames leaked");

        var old = ledger.ingress("source");
        ledger.advanceGeneration(8);
        reject(() -> ledger.forwardRoundRobin(old), "stale generation frame accepted");
        reject(() -> ledger.advanceGeneration(8), "same generation accepted");
        var refreshed = ledger.ingress("source");
        check(refreshed.generation() == 8, "new epoch not propagated");
        var resetCursor = ledger.forwardRoundRobin(refreshed);
        check(resetCursor.receiver().equals("worker-a"), "RR cursor did not reset at generation fence");
        ledger.complete(resetCursor);
        ledger.complete(refreshed);
        ledger.close();
        reject(() -> ledger.ingress("source"), "STOP accepted data");

        var bounded = new ActorPipelineRouteSession(plan, 9, 2);
        var root = bounded.ingress("source");
        reject(() -> bounded.forwardAll(root), "partial fan-out frame reservation permitted");
        check(bounded.outstanding() == 1, "all-or-none frame budget violated");
        bounded.complete(root);

        var parallel = new ActorPipelineRouteSession(plan, 10, 8192);
        var r = parallel.ingress("source");
        int threads = 4, iterations = 500;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var errors = new AtomicInteger();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < iterations; i++) {
                        var f = parallel.forwardRoundRobin(r);
                        parallel.backward(f);
                    }
                } catch (Exception error) { errors.incrementAndGet(); }
            });
            workers[t].start();
        }
        ready.await(); go.countDown();
        for (Thread worker : workers) worker.join();
        check(errors.get() == 0 && parallel.outstanding() == 1,
                "concurrent branches raced or leaked");
        parallel.complete(r);
        System.out.println("ORES_ACTOR_REPLY_CORRELATION_OK cases=16 concurrent_branches=2000");
    }
}
