package dev.oreslang.runtime;

import java.util.List;
import java.util.Map;
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
        check(ActorPipelineRouteSession.ReturnPath.class.getConstructors().length == 0,
                "host caller could construct a forged reply path");
        var plan = graph();
        // A selector-only ledger cannot attach even a legitimate root frame
        // to a real mailbox. A sealed host binding must match the *actual*
        // recipient identity, not merely a graph actor-instance name.
        var bound = new ActorPipelineRouteSession(plan, 19, 4,
                Map.of("source", "actor-0",
                        "worker-a", "actor-1", "worker-b", "actor-2",
                        "sink", "actor-3"));
        var permitted = bound.ingress("source");
        ActorPipelineRouteSession.verifyMailboxRecipient(permitted, "actor-0");
        try {
            ActorPipelineRouteSession.verifyMailboxRecipient(permitted, "actor-1");
            throw new AssertionError("bound frame reached wrong recipient");
        } catch (SecurityException expected) { }
        check(bound.outstanding() == 1,
                "wrong-recipient preflight consumed legitimate frame");
        bound.complete(permitted);
        var selectorOnly = new ActorPipelineRouteSession(plan, 19, 4);
        var bareFrame = selectorOnly.ingress("source");
        try {
            ActorPipelineRouteSession.verifyMailboxRecipient(bareFrame, "actor-0");
            throw new AssertionError("unbound selector frame reached mailbox");
        } catch (SecurityException expected) { }
        selectorOnly.complete(bareFrame);

        var ledger = new ActorPipelineRouteSession(plan, 7, 8);
        reject(() -> ledger.ingress("worker-a"), "internal worker must not originate flow");
        reject(() -> ledger.ingress("worker-b"), "second internal worker must not originate flow");
        reject(() -> ledger.ingress("sink"), "terminal sink must not originate flow");
        check(ledger.outstanding() == 0, "invalid ingress allocated a frame");
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

        // Exercise ACTUAL reverse hop frame transitions, not just
        // informational ReturnPath lists. Both fan-in branches keep the same
        // flow but travel back through their own immutable worker path.
        var returns = new ActorPipelineRouteSession(plan, 11, 5);
        var start = returns.ingress("source");
        var fork = returns.forwardAll(start);
        var sinkA = returns.forward(fork.get(0));
        var sinkB = returns.forward(fork.get(1));
        check(returns.outstanding() == 5, "fan-in route quota incorrect");
        var workerA = returns.returnOneHop(sinkA);
        var workerB = returns.returnOneHop(sinkB);
        check(workerA.receiver().equals("worker-a")
                        && workerB.receiver().equals("worker-b"),
                "reverse fan-in guessed the wrong worker branch");
        check(workerA.flowId() == workerB.flowId()
                        && workerA.routeId() != workerB.routeId(),
                "reverse fan-in corrupted correlation identities");
        reject(() -> returns.returnOneHop(sinkA),
                "one-shot reverse route frame was replayed");
        reject(() -> returns.forward(workerA),
                "reverse mail re-entered forward DAG selection");
        var responseA = returns.returnOneHop(workerA);
        var responseB = returns.returnOneHop(workerB);
        check(responseA.receiver().equals("source")
                        && responseB.receiver().equals("source"),
                "reverse branch did not reach authenticated origin");
        reject(() -> returns.returnOneHop(responseA),
                "pipeline origin manufactured an upstream return hop");
        ActorPipelineRouteSession.retireDelivered(responseA);
        ActorPipelineRouteSession.retireDelivered(responseA);
        ActorPipelineRouteSession.retireDelivered(responseB);
        returns.complete(fork.get(0));
        returns.complete(fork.get(1));
        returns.complete(start);
        check(returns.outstanding() == 0,
                "reverse delivery leaked quota at capacity boundary");
        returns.close();

        // A direct reply bypasses intermediate handler turns but MUST keep
        // the exact ingress actor and each fork's immutable branch lineage.
        var direct = new ActorPipelineRouteSession(plan, 12, 5);
        var directRoot = direct.ingress("source");
        reject(() -> direct.returnToOrigin(directRoot),
                "source was allowed to reply to itself");
        var directFork = direct.forwardAll(directRoot);
        var directSinkA = direct.forward(directFork.get(0));
        var directSinkB = direct.forward(directFork.get(1));
        var directReplyA = direct.returnToOrigin(directSinkA);
        var directReplyB = direct.returnToOrigin(directSinkB);
        check(directReplyA.receiver().equals("source")
                        && directReplyB.receiver().equals("source"),
                "direct reply did not reach the authenticated ingress");
        check(directReplyA.flowId() == directReplyB.flowId()
                        && directReplyA.routeId() != directReplyB.routeId(),
                "direct fan-in collapsed distinct reply correlations");
        reject(() -> direct.returnToOrigin(directSinkA),
                "one-shot direct reply frame replay accepted");
        reject(() -> direct.forward(directReplyA),
                "reply DATA incorrectly entered forward selection");
        reject(() -> direct.returnToOrigin(directReplyB),
                "returning DATA fabricated another full-path reply");
        check(direct.outstanding() == 5,
                "direct reply duplicated or silently retired live frames");
        direct.complete(directReplyA);
        direct.complete(directReplyB);
        direct.complete(directFork.get(0));
        direct.complete(directFork.get(1));
        direct.complete(directRoot);
        check(direct.outstanding() == 0, "direct reply leaked frame credits");
        direct.close();

        var old = ledger.ingress("source");
        reject(() -> ledger.advanceGeneration(8),
                "clean generation change lost an in-flight message");
        check(ledger.generation() == 7 && ledger.outstanding() == 1,
                "rejected reconfiguration mutated graph state");
        check(ledger.abortAndAdvanceGeneration(8) == 1,
                "supervisor abort did not account for cancelled frames");
        reject(() -> ledger.forwardRoundRobin(old), "stale generation frame accepted");
        reject(() -> ledger.advanceGeneration(8), "same generation accepted");
        var refreshed = ledger.ingress("source");
        check(refreshed.generation() == 8, "new epoch not propagated");
        var resetCursor = ledger.forwardRoundRobin(refreshed);
        check(resetCursor.receiver().equals("worker-a"), "RR cursor did not reset at generation fence");
        ledger.complete(resetCursor);
        ledger.complete(refreshed);
        check(ledger.outstanding() == 0, "clean generation barrier retained frames");
        ledger.advanceGeneration(9);
        check(ledger.generation() == 9 && ledger.outstanding() == 0,
                "quiescent generation change failed");
        var epochNine = ledger.ingress("source");
        reject(() -> ledger.abortAndAdvanceGeneration(9),
                "abort accepted a non-increasing generation");
        check(ledger.generation() == 9 && ledger.outstanding() == 1,
                "rejected abort mutated the graph epoch");
        check(ledger.abortAndAdvanceGeneration(10) == 1,
                "abort did not retire the single outstanding route");
        reject(() -> ledger.complete(epochNine),
                "aborted message remained valid after epoch change");
        ledger.close();
        reject(() -> ledger.ingress("source"), "STOP accepted data");

        // Host handles belong to exactly one graph session, even when
        // topology, generation and actor instance names are identical.
        var foreign = new ActorPipelineRouteSession(plan, 10, 3);
        var foreignFrame = foreign.ingress("source");
        var otherSession = new ActorPipelineRouteSession(plan, 10, 3);
        reject(() -> otherSession.forwardAll(foreignFrame),
                "foreign session message frame crossed graph authority");
        check(otherSession.outstanding() == 0,
                "foreign frame created local graph credits");
        foreign.complete(foreignFrame);
        foreign.close();
        reject(() -> foreign.ingress("source"), "closed graph admitted new ingress");

        var bounded = new ActorPipelineRouteSession(plan, 9, 2);
        var root = bounded.ingress("source");
        reject(() -> bounded.forwardAll(root), "partial fan-out frame reservation permitted");
        check(bounded.outstanding() == 1, "all-or-none frame budget violated");
        bounded.complete(root);

        // Repeat fan-out under tight credits: no unpublished identity or
        // stale route ID may remain after child completion.
        var reusable = new ActorPipelineRouteSession(plan, 10, 3);
        var producer = reusable.ingress("source");
        long previousRoute = -1;
        for (int i = 0; i < 10000; i++) {
            var fan = reusable.forwardAll(producer);
            check(fan.size() == 2 && fan.get(0).routeId() > previousRoute,
                    "fanout identities duplicated or disappeared");
            previousRoute = fan.get(1).routeId();
            check(reusable.outstanding() == 3, "fan-out allocated wrong batch size");
            reject(() -> reusable.forwardRoundRobin(producer),
                    "additional branch bypassed active-frame budget");
            reusable.complete(fan.get(0));
            reusable.complete(fan.get(1));
            check(reusable.outstanding() == 1, "fan-out leaked a completed child");
        }
        reusable.complete(producer);
        check(reusable.outstanding() == 0, "fan-out producer not released");

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
        System.out.println("ORES_ACTOR_REPLY_CORRELATION_OK cases=18 concurrent_branches=2000");
    }
}
