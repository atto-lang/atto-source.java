package dev.oreslang.runtime;

final class SupervisorEscrowReclamationV1Standalone {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("escrow reclamation invariant");
    }

    private static boolean reclaim(SupervisorFailureEscrowV1 l, long id) {
        return l.reclaimRetiredLeaf(id, id, true, true, true, true);
    }

    public static void main(String[] args) {
        var ledger = new SupervisorFailureEscrowV1(true);
        ledger.registerRoot(1, 1);
        ledger.registerChild(2, 1, 2);
        ledger.registerChild(3, 2, 3);
        var io = ledger.beginDataAdmission(2, 2);
        ledger.fail(2);
        var old = ledger.deliver(2);
        check(!reclaim(ledger, 2));
        check(ledger.acknowledge(old));
        check(!reclaim(ledger, 2));
        ledger.finishDataAdmission(io, true);
        check(!reclaim(ledger, 2));
        ledger.completeNormally(3);
        check(reclaim(ledger, 3));
        check(!ledger.reclaimRetiredLeaf(2, 2, false, true, true, true));
        check(!ledger.reclaimRetiredLeaf(2, 2, true, false, true, true));
        check(!ledger.reclaimRetiredLeaf(2, 2, true, true, false, true));
        check(!ledger.reclaimRetiredLeaf(2, 2, true, true, true, false));
        check(!ledger.reclaimRetiredLeaf(2, 1, true, true, true, true));
        check(reclaim(ledger, 2));
        check(!ledger.currentDelivery(old));
        check(!ledger.acknowledge(old));
        check(!ledger.mayAdmit(2, 2));
        check(!reclaim(ledger, 2));
        try { ledger.registerChild(2, 1, 4); throw new AssertionError("reused identity"); }
        catch (IllegalStateException expected) { }
        try { ledger.finishDataAdmission(io, true); throw new AssertionError("replayed DATA"); }
        catch (IllegalStateException expected) { }
        for (long id = 4; id < 100004; id++) {
            ledger.registerChild(id, 1, id);
            ledger.fail(id);
            check(ledger.acknowledge(ledger.deliver(id)));
            check(reclaim(ledger, id));
        }
        check(ledger.snapshot().size() == 1);
        check(!reclaim(ledger, 1));
        var legacy = new SupervisorFailureEscrowV1();
        legacy.registerRoot(1, 1);
        legacy.registerChild(2, 1, 2);
        legacy.completeNormally(2);
        check(!reclaim(legacy, 2));
        System.out.println("PASS ESCROW_RECLAMATION_CYCLES=100000 RETAINED_NODES=1");
    }
}
