package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone JDK smoke for host correlation semantics, NOT Oreslang guest execution. */
public final class GenServerReplyLedgerSmoke {
    private static void yes(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var actor = new GenServerReplyLedger.Target(UUID.randomUUID(), UUID.randomUUID(), 1);
        var next = new GenServerReplyLedger.Target(actor.runtimeId(), actor.actorId(), 2);
        var foreign = new GenServerReplyLedger.Target(UUID.randomUUID(), actor.actorId(), 1);
        var ledger = new GenServerReplyLedger(4);
        var a = ledger.register(actor);
        yes(!ledger.tryReply(a, next));
        yes(!ledger.tryReply(a, foreign));
        yes(ledger.tryReply(a, actor));
        yes(!ledger.tryReply(a, actor));
        var b = ledger.register(actor);
        yes(ledger.retire(b));
        yes(!ledger.tryReply(b, actor));
        yes(!ledger.retire(b));
        var another = new GenServerReplyLedger(4);
        var foreignLedgerTicket = ledger.register(actor);
        yes(!another.tryReply(foreignLedgerTicket, actor));
        yes(ledger.retire(foreignLedgerTicket));
        var c = ledger.register(actor);
        var d = ledger.register(actor);
        var e = ledger.register(actor);
        var f = ledger.register(actor);
        try { ledger.register(actor); throw new AssertionError("quota"); }
        catch (IllegalStateException expected) { /* correct */ }
        yes(ledger.pendingCount() == 4);
        yes(ledger.close() == 4);
        yes(ledger.close() == 0);
        yes(!ledger.tryReply(c, actor) && !ledger.retire(d));
        try { ledger.register(actor); throw new AssertionError("closed"); }
        catch (IllegalStateException expected) { /* correct */ }
        for (int iteration = 0; iteration < 250; iteration++) {
            var concurrent = new GenServerReplyLedger(1);
            var ticket = concurrent.register(actor);
            var ready = new CountDownLatch(1);
            var wins = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var x = executor.submit(() -> { ready.await(); if (concurrent.tryReply(ticket, actor)) wins.incrementAndGet(); return null; });
                var y = executor.submit(() -> { ready.await(); if (concurrent.retire(ticket)) wins.incrementAndGet(); return null; });
                ready.countDown(); x.get(); y.get();
            }
            yes(wins.get() == 1 && concurrent.pendingCount() == 0);
        }
        System.out.println("GENSERVER_LEDGER_250_RACES_PASS");
    }
}
