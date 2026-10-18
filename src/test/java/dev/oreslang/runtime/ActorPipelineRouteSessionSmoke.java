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
        // ActorGroup membership may restrict an existing DAG, never
        // introduce a new edge or change its deterministic receipt order.
        // This standalone selector test has no ActorRuntime dependency.
        var selectedLedger = new ActorPipelineRouteSession(plan, 72, 6);
        var groupRoot = selectedLedger.ingress("source");
        var ordered = plan.forwardAll("source");
        var onlySecond = selectedLedger.forwardSelected(
                groupRoot, List.of(ordered.get(1)));
        check(onlySecond.size() == 1
                        && onlySecond.getFirst().receiver().equals("worker-b"),
                "selective ActorGroup fanout chose incorrect connected worker");
        selectedLedger.complete(onlySecond.getFirst());
        reject(() -> selectedLedger.forwardSelected(
                        groupRoot, List.of(ordered.get(0), ordered.get(0))),
                "selective fanout admitted duplicate route edges");
        try {
            selectedLedger.forwardSelected(
                    groupRoot, List.of(ordered.get(1), ordered.get(0)));
            throw new AssertionError("selective fanout reordered immutable DAG edges");
        } catch (SecurityException expected) { }
        reject(() -> selectedLedger.forwardSelected(groupRoot, List.of()),
                "empty ActorGroup selection silently discarded DATA");
        reject(() -> selectedLedger.forwardRoundRobinEligible(
                        groupRoot, java.util.Set.of()),
                "round-robin without group members silently discarded DATA");
        var firstEligible = selectedLedger.forwardRoundRobinEligible(
                groupRoot, java.util.Set.of("worker-b"));
        check(firstEligible.receiver().equals("worker-b"),
                "round-robin did not skip nonmember graph worker-a");
        selectedLedger.complete(firstEligible);
        var nextEligible = selectedLedger.forwardRoundRobinEligible(
                groupRoot, java.util.Set.of("worker-a", "worker-b"));
        check(nextEligible.receiver().equals("worker-a"),
                "round-robin cursor changed incorrectly after membership expansion");
        selectedLedger.complete(nextEligible);
        selectedLedger.complete(groupRoot);
        check(selectedLedger.outstanding() == 0,
                "selective fanout or round-robin retained phantom frames");
        selectedLedger.advanceGeneration(73);
        var restarted = selectedLedger.ingress("source");
        var restartChoice = selectedLedger.forwardRoundRobinEligible(
                restarted, java.util.Set.of("worker-a", "worker-b"));
        check(restartChoice.receiver().equals("worker-a"),
                "quiescent generation change lost or failed to reset RR cursor");
        selectedLedger.complete(restartChoice);
        selectedLedger.complete(restarted);
        var cancelling = selectedLedger.ingress("source");
        check(selectedLedger.abortAndAdvanceGeneration(74) == 1,
                "abort did not retire the last source frame");
        reject(() -> selectedLedger.forwardRoundRobin(cancelling),
                "old-generation source used live RR cursor");
        var afterAbort = selectedLedger.ingress("source");
        var abortChoice = selectedLedger.forwardRoundRobinEligible(
                afterAbort, java.util.Set.of("worker-a", "worker-b"));
        check(abortChoice.receiver().equals("worker-a"),
                "abort generation discarded preallocated RR cursor state");
        selectedLedger.complete(abortChoice);
        selectedLedger.complete(afterAbort);
        check(selectedLedger.outstanding() == 0,
                "generation reset left orphan group frames");

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

        // A real router must bind immutable actor identities before it can
        // publish any route frame into a user mailbox. The standalone
        // selector-only session deliberately has no real mailbox authority.
        var authenticated = new ActorPipelineRouteSession(plan, 18, 5,
                Map.of("source", "id-0", "worker-a", "id-1",
                        "worker-b", "id-2", "sink", "id-3"));
        var authorizedRoot = authenticated.ingress("source");
        ActorPipelineRouteSession.requireMailboxRecipient(authorizedRoot, "id-0");
        try {
            ActorPipelineRouteSession.requireMailboxRecipient(authorizedRoot, "id-1");
            throw new AssertionError("wrong-recipient DATA acquired frame authority");
        } catch (SecurityException expected) { }
        check(authenticated.outstanding() == 1,
                "wrong recipient bypassed live frame accounting");
        authenticated.complete(authorizedRoot);
        var unbound = new ActorPipelineRouteSession(plan, 18, 4);
        var unboundRoot = unbound.ingress("source");
        try {
            ActorPipelineRouteSession.requireMailboxRecipient(unboundRoot, "id-0");
            throw new AssertionError("selector-only frame published user DATA");
        } catch (SecurityException expected) { }
        unbound.complete(unboundRoot);

        // Model both failure paths: no envelope allocated, and a real
        // mailbox rejection that already called envelope.close() to retire
        // its attempted return frame. Neither may consume the source's
        // legitimate one-shot reply right or reuse a spent route ID.
        var retry = new ActorPipelineRouteSession(plan, 21, 4);
        var retryRoot = retry.ingress("source");
        var retryWorker = retry.forwardTo(retryRoot, "worker-a");
        var retrySink = retry.forward(retryWorker);
        var rejectedReturn = retry.returnOneHop(retrySink);
        retry.restoreRejectedReturn(retrySink, rejectedReturn);
        check(retry.outstanding() == 3,
                "rejected reverse selection failed to restore live source");
        reject(() -> retry.complete(rejectedReturn),
                "failed reverse route could be replayed");
        var rejectedReply = retry.returnToOrigin(retrySink);
        ActorPipelineRouteSession.retireDelivered(rejectedReply);
        retry.restoreRejectedReturn(retrySink, rejectedReply);
        check(retry.outstanding() == 3,
                "already-retired envelope prevented original frame rollback");
        // Same flow AND identical route path is insufficient for rollback:
        // only the exact predecessor frame consumed by this return may
        // be restored. A sibling can have the same path and still have a
        // different one-shot publication authority.
        var siblings = new ActorPipelineRouteSession(plan, 22, 4);
        var sharedRoot = siblings.ingress("source");
        var siblingA = siblings.forwardTo(sharedRoot, "worker-a");
        var siblingB = siblings.forwardTo(sharedRoot, "worker-a");
        // At this point both siblings have identical actor path and flow.
        var siblingBack = siblings.returnOneHop(siblingA);
        try {
            siblings.restoreRejectedReturn(siblingB, siblingBack);
            throw new AssertionError("same-path sibling forged rollback");
        } catch (IllegalStateException expected) { }
        siblings.restoreRejectedReturn(siblingA, siblingBack);
        siblings.complete(siblingA);
        try {
            siblings.restoreRejectedReturn(siblingA, siblingBack);
            throw new AssertionError("spent reverse rollback was replayed");
        } catch (IllegalStateException expected) { }
        siblings.complete(siblingB);
        siblings.complete(sharedRoot);
        check(siblings.outstanding() == 0, "sibling return rollback leaked credits");

        // Failed prepublication send attempts release the exclusive claim,
        // preserving the route for a later safe retry. Once published, the
        // same frame cannot be used for a second mailbox envelope.
        var claimLedger = new ActorPipelineRouteSession(plan, 24, 4);
        var claimFrame = claimLedger.ingress("source");
        ActorPipelineRouteSession.claimMailboxAdmission(claimFrame);
        reject(() -> ActorPipelineRouteSession.claimMailboxAdmission(claimFrame),
                "concurrent mailbox publishers claimed the same route frame");
        // In-flight mailbox admission blocks generation replacement and
        // terminal teardown; otherwise a stopped graph could enqueue stale
        // DATA before the successful tryWrite is marked irrevocable.
        reject(() -> claimLedger.abortAndAdvanceGeneration(25),
                "abort fenced epoch over in-flight mailbox publication");
        reject(() -> claimLedger.close(),
                "close retired actor route during in-flight mailbox admission");
        check(!claimLedger.closed(), "rejected close mutated live session");
        ActorPipelineRouteSession.abortMailboxAdmission(claimFrame);
        check(!ActorPipelineRouteSession.wasMailboxPublished(claimFrame),
                "failed prepublication attempt reported accepted DATA");
        ActorPipelineRouteSession.claimMailboxAdmission(claimFrame);
        ActorPipelineRouteSession.markMailboxPublished(claimFrame);
        reject(() -> ActorPipelineRouteSession.claimMailboxAdmission(claimFrame),
                "already-published route frame was reused in a second mailbox");
        ActorPipelineRouteSession.abortMailboxAdmission(claimFrame);
        check(ActorPipelineRouteSession.wasMailboxPublished(claimFrame),
                "abort of a published frame erased monotonic publication");
        claimLedger.complete(claimFrame);
        claimLedger.advanceGeneration(25);
        var newRoot = claimLedger.ingress("source");
        ActorPipelineRouteSession.claimMailboxAdmission(newRoot);
        reject(() -> claimLedger.abortAndAdvanceGeneration(26),
                "generation abort raced second claimed mailbox frame");
        ActorPipelineRouteSession.abortMailboxAdmission(newRoot);
        check(claimLedger.abortAndAdvanceGeneration(26) == 1,
                "failed claimed mailbox abort left phantom correlation frame");
        reject(() -> ActorPipelineRouteSession.claimMailboxAdmission(newRoot),
                "old generation was claimable after supervisor abort");

        // Eight concurrent racing host admission attempts MUST grant exactly
        // one winner until that winner releases its reservation. Do not
        // require a physical carrier or a dedicated actor to prove CAS.
        var race = new ActorPipelineRouteSession(plan, 25, 4);
        var raceFrame = race.ingress("source");
        var claimReady = new CountDownLatch(8);
        var claimGo = new CountDownLatch(1);
        var claimWins = new AtomicInteger();
        var claimRejected = new AtomicInteger();
        var claimThreads = new Thread[8];
        for (int i = 0; i < claimThreads.length; i++) {
            claimThreads[i] = new Thread(() -> {
                claimReady.countDown();
                try {
                    claimGo.await();
                    ActorPipelineRouteSession.claimMailboxAdmission(raceFrame);
                    claimWins.incrementAndGet();
                } catch (IllegalStateException expected) {
                    claimRejected.incrementAndGet();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            claimThreads[i].start();
        }
        claimReady.await();
        claimGo.countDown();
        for (var contender : claimThreads) contender.join();
        check(claimWins.get() == 1 && claimRejected.get() == 7,
                "route-frame exclusive CAS admitted more than one publisher");
        ActorPipelineRouteSession.abortMailboxAdmission(raceFrame);
        race.complete(raceFrame);

        // Publication is a monotonic VM fact, distinct from the current
        // active-credit state. A return already enqueued in a real mailbox
        // cannot be relabeled a rejection even after its envelope retires.
        var published = new ActorPipelineRouteSession(plan, 23, 4);
        var publishedRoot = published.ingress("source");
        var publishedWorker = published.forwardTo(publishedRoot, "worker-a");
        var publishedSink = published.forward(publishedWorker);
        var publishedReply = published.returnToOrigin(publishedSink);
        ActorPipelineRouteSession.claimMailboxAdmission(publishedReply);
        ActorPipelineRouteSession.markMailboxPublished(publishedReply);
        check(ActorPipelineRouteSession.wasMailboxPublished(publishedReply),
                "published reply lost monotonic mailbox marker");
        reject(() -> ActorPipelineRouteSession.markMailboxPublished(publishedReply),
                "one route frame published into multiple envelopes");
        reject(() -> published.restoreRejectedReturn(publishedSink, publishedReply),
                "published reply was improperly restored for retry");
        ActorPipelineRouteSession.retireDelivered(publishedReply);
        reject(() -> published.restoreRejectedReturn(publishedSink, publishedReply),
                "retired published reply was resurrected");
        published.complete(publishedRoot);
        published.complete(publishedWorker);
        check(published.outstanding() == 0,
                "published reply teardown leaked route credits");

        var successfulReply = retry.returnToOrigin(retrySink);
        check(successfulReply.receiver().equals("source")
                        && successfulReply.routeId() > rejectedReply.routeId(),
                "retry recycled an old route ID or forged origin");
        retry.complete(successfulReply);
        retry.complete(retryWorker);
        retry.complete(retryRoot);
        check(retry.outstanding() == 0,
                "reverse rejection/retry leaked VM correlation credits");
        retry.close();

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

        // 8,000 concurrent group-filtered RR selections with alternating
        // membership subsets must never choose an ineligible DAG target,
        // duplicate a correlation ID, or leak per-message frame credits.
        var groupParallel = new ActorPipelineRouteSession(plan, 76, 64);
        var groupParent = groupParallel.ingress("source");
        var groupReady = new CountDownLatch(8);
        var groupGo = new CountDownLatch(1);
        var groupErrors = new AtomicInteger();
        var groupAccepted = new AtomicInteger();
        var groupIds = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        var groupThreads = new Thread[8];
        for (int t = 0; t < groupThreads.length; t++) {
            final int shard = t;
            groupThreads[t] = new Thread(() -> {
                groupReady.countDown();
                try {
                    groupGo.await();
                    for (int i = 0; i < 1000; i++) {
                        var members = (i + shard) % 3 == 0
                                ? java.util.Set.of("worker-b")
                                : (i + shard) % 3 == 1
                                        ? java.util.Set.of("worker-a")
                                        : java.util.Set.of("worker-a", "worker-b");
                        var child = groupParallel.forwardRoundRobinEligible(
                                groupParent, members);
                        try {
                            if (!members.contains(child.receiver())
                                    || !groupIds.add(child.routeId())) {
                                throw new IllegalStateException(
                                        "RR chose ineligible actor or repeated route identity");
                            }
                            groupAccepted.incrementAndGet();
                        } finally {
                            groupParallel.complete(child);
                        }
                    }
                } catch (Throwable failure) {
                    groupErrors.incrementAndGet();
                }
            }, "group-rr-stress-" + t);
            groupThreads[t].start();
        }
        groupReady.await();
        groupGo.countDown();
        for (var thread : groupThreads) thread.join();
        check(groupErrors.get() == 0 && groupAccepted.get() == 8000
                        && groupIds.size() == 8000
                        && groupParallel.outstanding() == 1,
                "concurrent dynamic group RR selection leaked/spoofed frames");
        groupParallel.complete(groupParent);

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
        System.out.println("ORES_ACTOR_REPLY_CORRELATION_OK cases=21 concurrent_branches=10000");
    }
}
