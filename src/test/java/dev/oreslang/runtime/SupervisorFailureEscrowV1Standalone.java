package dev.oreslang.runtime;

final class SupervisorFailureEscrowV1Standalone {
    static int assertions;
    static void ok(boolean b, String message) {
        assertions++;
        if (!b) throw new AssertionError(message);
    }
    static void rejected(Runnable action, String message) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1, 1);
        ledger.registerChild(2, 1, 1); // supervisor S
        ledger.registerChild(3, 2, 1); // supervisor T
        ledger.registerChild(4, 3, 1); // actor A
        ledger.registerChild(5, 1, 1); // unrelated sibling B
        rejected(() -> ledger.registerChild(2, 1, 2), "reused physical identity");
        rejected(() -> ledger.registerChild(6, 77, 1), "missing parent");
        ok(ledger.mayAdmit(4, 1), "child admitted before failure");
        var tNotice = ledger.fail(3);
        var deliveredToS = ledger.deliver(3);
        ok(deliveredToS.controllerId() == 2, "T initially reports to S");
        ok(tNotice.equals(deliveredToS.notice()), "typed notice identity");
        ok(ledger.fail(3) == tNotice, "duplicate terminal is inert");
        ledger.fail(2);
        ok(!ledger.mayAdmit(3, 1) && !ledger.mayAdmit(4, 1), "descendants fenced");
        ok(ledger.mayAdmit(5, 1), "sibling remains running");
        ok(!ledger.acknowledge(deliveredToS), "dead parent cannot acknowledge stale delivery");
        ok(ledger.pendingCount() == 2, "both failures remain in escrow");
        var deliveredToR = ledger.deliver(3);
        ok(deliveredToR.controllerId() == 1, "guardian assumes CONTROL authority");
        ok(deliveredToR != deliveredToS, "old permission revoked on reassignment");
        ok(!ledger.acknowledge(deliveredToS), "stale token remains rejected");
        ok(ledger.acknowledge(deliveredToR), "guardian acknowledgement succeeds");
        ok(!ledger.acknowledge(deliveredToR), "duplicate acknowledgement is inert");
        ok(ledger.fail(4).failedId() == 4, "in-flight child can fail after subtree fencing");
        ok(ledger.pendingCount() == 2, "failed parent and descendant remain pending");
        ok(ledger.deliver(4).controllerId() == 1, "new nested failure escalates to guardian");
        ok(ledger.acknowledge(ledger.deliver(4)), "nested descendant acknowledgement");
        ok(ledger.acknowledge(ledger.deliver(2)), "failed supervisor acknowledgement");
        ok(ledger.pendingCount() == 0, "all terminal records eventually acknowledged");
        ok(ledger.deliver(3) == null, "acknowledged event not re-delivered");
        var view = ledger.snapshot();
        ok(view.get(4L).fenced() && !view.get(5L).fenced(), "immutable detached status");
        rejected(() -> view.clear(), "snapshot mutability");
        rejected(() -> ledger.fail(1), "guardian cannot self-recover");
        rejected(() -> ledger.registerChild(7, 2, 1), "failed parent denied admission");
        // Real multithreaded host race: 64 independent terminal receipts,
        // eight rounds, with no dropped or duplicate acknowledgments.
        for (int round = 0; round < 8; round++) {
            var concurrent = new SupervisorFailureEscrowV1();
            concurrent.registerRoot(1, 1);
            concurrent.registerChild(2, 1, 1);
            for (int actor = 3; actor < 67; actor++) {
                concurrent.registerChild(actor, 2, 1);
            }
            var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var begin = new java.util.concurrent.CountDownLatch(1);
                var results = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
                for (int actor = 3; actor < 67; actor++) {
                    final int actorId = actor;
                    results.add(workers.submit(() -> {
                        begin.await();
                        concurrent.fail(actorId);
                        return null;
                    }));
                }
                begin.countDown();
                for (var result : results) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
                ok(concurrent.pendingCount() == 64, "64 concurrent failure notices retained");
                for (int actor = 3; actor < 67; actor++) {
                    ok(concurrent.acknowledge(concurrent.deliver(actor)),
                            "exact once CONTROL acknowledgement");
                }
                ok(concurrent.pendingCount() == 0, "no unacknowledged concurrent failure");
            } finally {
                workers.shutdownNow();
            }
        }
        var clean = new SupervisorFailureEscrowV1();
        clean.registerRoot(1, 1);
        clean.registerChild(2, 1, 1);
        clean.completeNormally(2);
        ok(!clean.snapshot().get(2L).alive(), "clean termination retires the actor");
        ok(clean.pendingCount() == 0, "clean terminal creates no failure notice");
        rejected(() -> clean.fail(2), "late failure cannot forge a previously clean terminal");
        System.out.println("PASS SupervisorFailureEscrowV1Standalone assertions=" + assertions);
    }
}
