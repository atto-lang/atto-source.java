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
    private static void saturatedFailurePublication() {
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1000L, 1);
        ledger.registerChild(1001L, 1000L, 1);
        ledger.registerChild(1002L, 1001L, 2);
        ledger.registerChild(1003L, 1000L, 1);
        for (int i = 4; i < SupervisorFailureEscrowV1.MAX_NODES; i++) {
            ledger.registerChild(1000L + i, 1001L, i);
        }
        rejected(() -> ledger.registerChild(99999L, 1000L, 1), "full CONTROL ledger accepted child");
        var permits = new SupervisorFailureEscrowV1.DataAdmission[
                SupervisorFailureEscrowV1.MAX_DATA_ADMISSIONS_IN_FLIGHT];
        for (int i = 0; i < permits.length; i++) {
            permits[i] = ledger.beginDataAdmission(1002L, 2);
            ok(permits[i] != null, "DATA capacity unexpectedly unavailable");
        }
        rejected(() -> ledger.beginDataAdmission(1003L, 1), "full DATA ledger accepted permit");

        // HotSpot current-thread allocation counter, measured only around valid
        // first-failure publication, not diagnostic inspection or policy delivery.
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation)
                || !allocation.isThreadAllocatedMemorySupported()) {
            throw new AssertionError("allocation instrumentation unavailable");
        }
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        // Warm the exact code path including IDs outside Long's cache.
        var warm = new SupervisorFailureEscrowV1();
        warm.registerRoot(20000L, 1);
        for (int i = 1; i <= 512; i++) warm.registerChild(20000L + i, 20000L, i);
        for (int i = 1; i <= 512; i++) warm.fail(20000L + i);
        allocation.getThreadAllocatedBytes(thread);
        long before = allocation.getThreadAllocatedBytes(thread);
        var original = ledger.fail(1001L);
        long after = allocation.getThreadAllocatedBytes(thread);
        ok(before >= 0 && after == before, "terminal publication allocated " + (after - before) + " bytes");
        ok(ledger.fail(1001L) == original, "failure replay replaced notice");
        ok(original.failedId() == 1001L && original.failedGeneration() == 1,
                "wrong reserved notice identity");
        ok(ledger.pendingCount() == 1, "failure lost under saturation");
        ok(!ledger.mayAdmit(1002L, 2), "descendant not fenced");
        ok(ledger.mayAdmit(1003L, 1), "unrelated sibling fenced");
        ok(ledger.dataInFlightInSubtree(1001L) == permits.length,
                "failure erased outstanding DATA permits");
        for (var permit : permits) ledger.finishDataAdmission(permit, false);
        ok(ledger.dataInFlightInSubtree(1001L) == 0, "outstanding DATA not drained");
        ok(ledger.acknowledge(ledger.deliver(1001L)), "reserved failure not delivered");
        var sibling = ledger.beginDataAdmission(1003L, 1);
        ok(sibling != null, "healthy sibling cannot make progress");
        ledger.finishDataAdmission(sibling, true);
        System.out.println("SATURATED_CONTROL_NODES=" + SupervisorFailureEscrowV1.MAX_NODES
                + " DATA_PERMITS=" + permits.length + " FAILURE_PUBLICATION_ALLOCATED_BYTES="
                + (after - before));
    }

    private static void rollbackIndexDoesNotRetainUnpublishedChild() {
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1000L, 1);
        ledger.registerChild(1001L, 1000L, 1);
        for (int i = 0; i < SupervisorFailureEscrowV1.MAX_NODES * 2; i++) {
            rejected(() -> ledger.registerChildAtomically(1002L, 1001L, 2,
                    () -> { throw new IllegalStateException("rollback"); }), "rollback must throw");
        }
        rejected(() -> ledger.fail(1002L), "unpublished child survived in terminal index");
        ledger.registerChild(1002L, 1001L, 3);
        ledger.fail(1001L);
        ok(!ledger.mayAdmit(1002L, 3), "replacement child not fenced");
        ok(ledger.fail(1002L).failedGeneration() == 3, "stale notice survived rollback");
    }

    private static void reservedDeliveryTakeover() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation)
                || !allocation.isThreadAllocatedMemorySupported()) {
            throw new AssertionError("allocation instrumentation unavailable");
        }
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        // Warm all relevant paths (including a stale acknowledgement).
        for (int i = 0; i < 1024; i++) {
            var warm = new SupervisorFailureEscrowV1();
            warm.registerRoot(1000L, 1);
            warm.registerChild(1001L, 1000L, 2);
            warm.registerChild(1002L, 1001L, 3);
            warm.fail(1002L);
            var old = warm.deliver(1002L);
            warm.fail(1001L);
            warm.currentDelivery(old);
            warm.acknowledge(old);
            warm.acknowledge(warm.deliver(1002L));
        }
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(2000L, 1);
        for (int i = 1; i < SupervisorFailureEscrowV1.MAX_DEPTH; i++) {
            ledger.registerChild(2000L + i, 1999L + i, i + 1);
        }
        long failed = 2000L + SupervisorFailureEscrowV1.MAX_DEPTH - 1;
        ledger.fail(failed);
        var first = ledger.deliver(failed);
        ok(first.controllerId() == failed - 1, "first controller wrong");
        // Measure all 62 possible upward takeovers and final ACK, without
        // allocating assertion text, snapshots or diagnostic objects in region.
        long before = allocation.getThreadAllocatedBytes(thread);
        var previous = first;
        boolean valid = true;
        for (long parent = failed - 1; parent > 2000L; parent--) {
            ledger.fail(parent);
            valid &= !ledger.currentDelivery(previous);
            valid &= !ledger.acknowledge(previous);
            var next = ledger.deliver(failed);
            valid &= next != previous && next.controllerId() == parent - 1;
            valid &= ledger.currentDelivery(next);
            previous = next;
        }
        valid &= ledger.acknowledge(previous);
        valid &= !ledger.acknowledge(previous);
        long after = allocation.getThreadAllocatedBytes(thread);
        ok(valid, "takeover/ACK authority violated");
        ok(before >= 0 && after == before, "delivery/ACK allocated " + (after - before) + " bytes");
        ok(ledger.deliver(failed) == null, "acknowledged notice replayed");
        System.out.println("CONTROL_TAKEOVERS=62 DELIVERY_ACK_ALLOCATED_BYTES=" + (after - before));
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
        rollbackIndexDoesNotRetainUnpublishedChild();
        saturatedFailurePublication();
        reservedDeliveryTakeover();
        System.out.println("PASS SupervisorFailureEscrowV1Standalone assertions=" + assertions);
    }
}
