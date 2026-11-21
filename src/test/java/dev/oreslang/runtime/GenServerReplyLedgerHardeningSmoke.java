package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK-only admission/correlation smoke; no guest GenServer claim. */
public final class GenServerReplyLedgerHardeningSmoke {
    private static void yes(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var target = new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                UUID.fromString("00000000-0000-0000-0000-000000000022"), 1);
        var next = new GenServerReplyLedger.Target(target.runtimeId(), target.actorId(), 2);

        var ledger = new GenServerReplyLedger(1);
        var first = ledger.register(target);
        try {
            ledger.register(target);
            throw new AssertionError("capacity admission unexpectedly succeeded");
        } catch (GenServerReplyLedger.AdmissionException expected) {
            yes(expected.reason() == GenServerReplyLedger.AdmissionException.Reason.CAPACITY,
                    "capacity reason");
        }
        yes(!ledger.tryReply(first, next), "stale incarnation must not consume ticket");
        yes(ledger.tryReply(first, target), "current incarnation reply");
        yes(!ledger.tryReply(first, target), "duplicate reply");
        ledger.close();
        try {
            ledger.register(target);
            throw new AssertionError("closed admission unexpectedly succeeded");
        } catch (GenServerReplyLedger.AdmissionException expected) {
            yes(expected.reason() == GenServerReplyLedger.AdmissionException.Reason.CLOSED,
                    "closed reason");
        }

        for (int iteration = 0; iteration < 1_000; iteration++) {
            var concurrent = new GenServerReplyLedger(1);
            var ticket = concurrent.register(target);
            var ready = new CountDownLatch(1);
            var wins = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var reply = pool.submit(() -> {
                    ready.await();
                    if (concurrent.tryReply(ticket, target)) wins.incrementAndGet();
                    return null;
                });
                var timeout = pool.submit(() -> {
                    ready.await();
                    if (concurrent.retire(ticket)) wins.incrementAndGet();
                    return null;
                });
                ready.countDown();
                reply.get();
                timeout.get();
            }
            yes(wins.get() == 1, "reply/retire must have exactly one winner");
            yes(concurrent.pendingCount() == 0, "ticket leak");
        }
        System.out.println("GENSERVER_LEDGER_HARDENING_1000_RACES_PASS");
    }
}
