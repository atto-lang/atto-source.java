package dev.oreslang.runtime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SettlementProbe {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static void rejected(Runnable r) {
        try { r.run(); } catch (IllegalStateException e) { return; }
        throw new AssertionError("admission or replay accepted");
    }
    static final class Owner {
        final ActorContinuationLedger ledger = new ActorContinuationLedger(2);
        final CountDownLatch entered = new CountDownLatch(1), settle = new CountDownLatch(1);
        final AtomicInteger releases = new AtomicInteger(), calls = new AtomicInteger();
        final AtomicReference<Throwable> observed = new AtomicReference<>();
        final IllegalStateException failure = new IllegalStateException("release failed");
        final IllegalArgumentException guest = new IllegalArgumentException("guest failed");
        final boolean quarantine;
        final ActorContinuationLedger.Admission receipt, pending;
        final Thread worker;
        Owner(boolean quarantine) {
            this.quarantine = quarantine;
            receipt = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> { throw guest; }, () -> {
                releases.incrementAndGet(); entered.countDown();
                if (!settle.await(10, TimeUnit.SECONDS)) throw new AssertionError("stranded");
                if (quarantine) throw failure;
            });
            pending = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, calls::incrementAndGet);
            worker = Thread.ofPlatform().start(() -> {
                try { receipt.run(); } catch (Throwable e) { observed.set(e); }
            });
        }
    }
    public static void main(String[] args) throws Exception {
        var empty = new ActorContinuationLedger(1);
        check(!empty.retirementReady());
        empty.sealOrdinary();
        check(!empty.retirementReady());
        var cleanup = empty.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {});
        empty.sealAll();
        check(!empty.retirementReady());
        cleanup.close();
        check(empty.retirementReady());
        rejected(() -> empty.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}));
        for (int round = 0; round < 10; round++) {
            Owner[] owners = new Owner[64];
            try {
                for (int i = 0; i < owners.length; i++) owners[i] = new Owner(i % 2 == 0);
                for (Owner o : owners) {
                    check(o.entered.await(10, TimeUnit.SECONDS));
                    check(o.ledger.snapshot().running() == 1 && o.ledger.snapshot().queued() == 1);
                    rejected(() -> o.ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}));
                    o.ledger.sealOrdinary();
                    o.pending.run(); o.receipt.close();
                    check(!o.ledger.retirementReady());
                    check(o.calls.get() == 0 && o.releases.get() == 1);
                    rejected(o.receipt::run);
                    check(o.ledger.snapshot().running() == 1);
                }
                for (int i = owners.length - 1; i >= 0; i--) {
                    Owner o = owners[i]; o.settle.countDown(); o.worker.join(10000);
                    check(!o.worker.isAlive() && o.observed.get() == o.guest);
                    check(o.ledger.hasReleaseFailure() == o.quarantine);
                    check(o.ledger.snapshot().running() == (o.quarantine ? 1 : 0));
                    check(o.ledger.snapshot().queued() == 0);
                    check(o.guest.getSuppressed().length == (o.quarantine ? 1 : 0));
                    if (o.quarantine) {
                        check(o.guest.getSuppressed()[0] == o.failure);
                        rejected(() -> o.ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}));
                    } else { o.ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}).close(); }
                    o.receipt.close(); check(o.releases.get() == 1);
                    check(!o.ledger.retirementReady());
                    o.ledger.sealAll();
                    check(o.ledger.retirementReady() == !o.quarantine);
                    rejected(() -> o.ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}));
                    if (i > 0) check(owners[i-1].ledger.snapshot().running() == 1 && !owners[i-1].ledger.hasReleaseFailure() && !owners[i-1].ledger.retirementReady());
                }
            } finally {
                for (Owner o : owners) if (o != null) o.settle.countDown();
                for (Owner o : owners) if (o != null) o.worker.join(10000);
            }
        }
        System.out.println("PASS 640 independent Java owner settlements: 320 successful release, 320 quarantined release; reverse order, no retry, no premature credit refund");
    }
}
