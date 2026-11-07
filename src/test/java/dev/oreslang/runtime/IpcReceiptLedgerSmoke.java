package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class IpcReceiptLedgerSmoke {
    private static IpcReceiptLedger.Envelope env(long seq) {
        return new IpcReceiptLedger.Envelope("tenant", "caller", 4, "worker", 7, 3,
                "transport", seq, 1, 2, "a".repeat(64));
    }
    private static void deny(Runnable r) {
        boolean failed = false;
        try { r.run(); } catch (IllegalArgumentException | IllegalStateException exc) { failed = true; }
        if (!failed) throw new AssertionError("expected rejection");
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("IPC invariant"); }
    public static void main(String[] args) throws InterruptedException {
        var ledger = new IpcReceiptLedger(2);
        var e = env(1);
        ledger.admit(e);
        deny(() -> ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true));
        deny(() -> ledger.advance(e, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, false));
        ledger.advance(e, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, true);
        check(ledger.budgets().transportCredits() == 0);
        deny(() -> ledger.advance(e, IpcReceiptLedger.Stage.REJECTED_BEFORE_ADMISSION, true));
        ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true);
        deny(() -> ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true));
        var two = new IpcReceiptLedger.Envelope("tenant", "caller", 4, "worker", 7, 4,
                "transport", 2, 1, 3, "a".repeat(64));
        ledger.admit(two);
        deny(() -> ledger.admit(env(3)));
        ledger.advance(two, IpcReceiptLedger.Stage.REJECTED_BEFORE_ADMISSION, true);
        var three = new IpcReceiptLedger.Envelope("tenant", "caller", 4, "worker", 7, 4,
                "transport", 3, 1, 3, "a".repeat(64));
        ledger.admit(three);
        check(ledger.markTransportLost() == 1);
        deny(() -> ledger.advance(three, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true));
        check(ledger.invariant());
        check(!IpcReceiptLedger.canReplayAutomatically());
        deny(() -> new IpcReceiptLedger.Envelope("bad!", "caller", 4, "worker", 7, 3,
                "transport", 1, 1, 2, "a".repeat(64)));
        deny(() -> new IpcReceiptLedger(65537));

        var concurrent = new IpcReceiptLedger(8);
        var starting = new CountDownLatch(1);
        var finishing = new CountDownLatch(8);
        var unexpected = new AtomicInteger();
        for (int worker = 0; worker < 8; worker++) {
            final int index = worker;
            var thread = new Thread(() -> {
                try {
                    starting.await();
                    for (int i = 1; i <= 1000; i++) {
                        var message = env((long)index * 1000 + i);
                        try {
                            concurrent.admit(message);
                            concurrent.advance(message, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, true);
                            concurrent.advance(message, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true);
                        } catch (IllegalStateException expected) { }
                    }
                } catch (Exception ex) { unexpected.incrementAndGet(); }
                finally { finishing.countDown(); }
            });
            thread.start();
        }
        starting.countDown();
        finishing.await();
        check(unexpected.get() == 0);
        check(concurrent.invariant());
        check(concurrent.budgets().inFlight() <= 8);
        concurrent.markTransportLost();
        check(concurrent.budgets().inFlight() == 0);
        System.out.println("PASS Java IPC receipt ledger smoke and concurrent invariant");
    }
}
