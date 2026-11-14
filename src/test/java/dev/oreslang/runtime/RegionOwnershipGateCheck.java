package dev.oreslang.runtime;

/** Host-only exact class check; does not compile a guest ownership syntax. */
public final class RegionOwnershipGateCheck {
    private static int checks;
    private static void expectFailure(Runnable r) {
        try { r.run(); throw new AssertionError("expected admission failure"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("region ownership invariant violated");
        checks++;
    }
    public static void main(String[] args) {
        var root = DopRegionSchedule.Root.trustedCreate(7, 2);
        var a = new Object(); var b = new Object(); var c = new Object();
        var gate = RegionOwnershipGate.trustedCreate(root, a);
        var sender = gate.initialOwner();
        expectFailure(gate::initialOwner);
        expectFailure(() -> RegionOwnershipGate.trustedCreate(root, b));
        check(gate.rows() == 7 && gate.fields() == 2);
        try (var read1 = gate.borrowRead(sender); var read2 = gate.borrowRead(sender)) {
            read1.requireReadable(); read2.requireReadable(); checks += 2;
            expectFailure(read1::requireWritable);
            expectFailure(() -> gate.requireWrite(sender));
            expectFailure(() -> gate.borrowWrite(sender));
            expectFailure(() -> gate.prepareTransfer(sender, b));
            expectFailure(() -> gate.beginCompute(sender));
        }
        try (var write = gate.borrowWrite(sender)) {
            write.requireWritable(); checks++;
            expectFailure(() -> gate.requireRead(sender));
            expectFailure(() -> gate.borrowRead(sender));
            expectFailure(() -> gate.prepareTransfer(sender, b));
        }
        var rejected = gate.prepareTransfer(sender, b);
        expectFailure(() -> gate.borrowRead(sender));
        expectFailure(() -> gate.beginCompute(sender));
        rejected.rejected();
        expectFailure(rejected::admitted);
        gate.requireWrite(sender);
        checks++;
        var compute = gate.beginCompute(sender);
        compute.requestCancellation();
        check(compute.cancelled());
        expectFailure(() -> gate.prepareTransfer(sender, b));
        expectFailure(() -> gate.borrowRead(sender));
        compute.trustedBackendQuiescent(true);
        expectFailure(() -> compute.trustedBackendQuiescent(true));
        // A cancelled kernel could have left partial output: require fresh
        // validated allocation rather than opening old storage to actors.
        expectFailure(() -> gate.requireWrite(sender));
        expectFailure(() -> gate.prepareTransfer(sender, b));
        gate.retire(sender);
        expectFailure(() -> gate.borrowRead(sender));

        // Fresh allocation is independently minted after poisoned retirement.
        var gate2 = RegionOwnershipGate.trustedCreate(root, a);
        var sender2 = gate2.initialOwner();
        var committed = gate2.prepareTransfer(sender2, b);
        var receiver = committed.admitted();
        expectFailure(gate2::initialOwner);
        expectFailure(() -> gate2.requireWrite(sender2));
        expectFailure(() -> gate2.borrowRead(sender2));
        expectFailure(committed::admitted);
        expectFailure(committed::rejected);
        gate2.requireWrite(receiver);
        checks++;
        check(receiver.domain() == b);
        var second = gate2.prepareTransfer(receiver, c);
        expectFailure(() -> gate2.prepareTransfer(receiver, a));
        second.rejected();
        gate2.requireRead(receiver);
        checks++;
        var third = gate2.prepareTransfer(receiver, c);
        var newOwner = third.admitted();
        expectFailure(() -> gate2.requireWrite(receiver));
        try (var read = gate2.borrowRead(newOwner)) {
            read.requireReadable(); checks++;
            expectFailure(() -> gate2.retire(newOwner));
        }
        gate2.retire(newOwner);
        expectFailure(() -> gate2.borrowRead(newOwner));
        expectFailure(() -> gate2.prepareTransfer(newOwner, a));
        // A stale completion cannot release a newer region generation or a
        // subsequent compute lease. Completion is one-shot and identity-bound.
        var gate3 = RegionOwnershipGate.trustedCreate(root, a);
        var owner3 = gate3.initialOwner();
        var firstCompute = gate3.beginCompute(owner3);
        firstCompute.trustedBackendQuiescent(true);
        expectFailure(() -> firstCompute.trustedBackendQuiescent(true));
        var firstTransfer = gate3.prepareTransfer(owner3, b);
        var afterTransfer = firstTransfer.admitted();
        expectFailure(() -> gate3.beginCompute(owner3));
        var secondCompute = gate3.beginCompute(afterTransfer);
        expectFailure(() -> firstCompute.trustedBackendQuiescent(true));
        expectFailure(() -> gate3.prepareTransfer(afterTransfer, c));
        secondCompute.trustedBackendQuiescent(true);
        var nextOwner = gate3.prepareTransfer(afterTransfer, c).admitted();
        gate3.requireRead(nextOwner); checks++;
        gate3.retire(nextOwner);

        // Backend-reported failure poisons even without cancellation.
        var gate4 = RegionOwnershipGate.trustedCreate(root, a);
        var owner4 = gate4.initialOwner();
        gate4.beginCompute(owner4).trustedBackendQuiescent(false);
        expectFailure(() -> gate4.borrowRead(owner4));
        expectFailure(() -> gate4.prepareTransfer(owner4, b));
        gate4.retire(owner4);
        expectFailure(() -> gate4.requireRead(owner4));

        // Concurrent handoff admits exactly one owner; commit and rejection
        // cannot both succeed for the same ticket.
        for (int iteration = 0; iteration < 64; iteration++) {
            var g = RegionOwnershipGate.trustedCreate(root, a);
            var initial = g.initialOwner();
            var transfer = g.prepareTransfer(initial, b);
            var ready = new java.util.concurrent.CountDownLatch(1);
            var winners = new java.util.concurrent.atomic.AtomicInteger();
            var acceptedOwner = new java.util.concurrent.atomic.AtomicReference<RegionOwnershipGate.Owner>();
            Runnable commit = () -> {
                try { ready.await(); acceptedOwner.set(transfer.admitted()); winners.incrementAndGet(); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException err) { Thread.currentThread().interrupt(); throw new AssertionError(err); }
            };
            Runnable abort = () -> {
                try { ready.await(); transfer.rejected(); winners.incrementAndGet(); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException err) { Thread.currentThread().interrupt(); throw new AssertionError(err); }
            };
            var t1 = new Thread(commit);
            var t2 = new Thread(abort);
            t1.start(); t2.start(); ready.countDown();
            try { t1.join(5000); t2.join(5000); }
            catch (InterruptedException err) { Thread.currentThread().interrupt(); throw new AssertionError(err); }
            check(!t1.isAlive() && !t2.isAlive() && winners.get() == 1);
            g.retire(acceptedOwner.get() == null ? initial : acceptedOwner.get());
        }
        var reroot = RegionOwnershipGate.trustedCreate(root, a);
        var rerootOwner = reroot.initialOwner();
        reroot.retire(rerootOwner);
        System.out.println("REGION_OWNERSHIP_HOST_CHECKS=" + checks + " PASS");
    }
}
