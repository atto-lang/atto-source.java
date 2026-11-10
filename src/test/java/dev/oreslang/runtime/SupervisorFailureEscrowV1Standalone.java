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
    public static void main(String[] args) {
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
        System.out.println("PASS SupervisorFailureEscrowV1Standalone assertions=" + assertions);
    }
}
