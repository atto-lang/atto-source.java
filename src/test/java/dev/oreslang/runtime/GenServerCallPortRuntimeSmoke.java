package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * No-JUnit real OresFuture/ledger/port probe. A transport or scheduler
 * must separately supply authenticated mailbox delivery. Not guest execution.
 */
public final class GenServerCallPortRuntimeSmoke {
    private static GenServerReplyLedger.Target target(long generation) {
        return new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-0000000000ab"),
                UUID.fromString("00000000-0000-0000-0000-0000000000cd"),
                generation);
    }

    private static void yes(boolean predicate, String why) {
        if (!predicate) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        var port = new GenServerCallPort<Integer>(2);
        var success = port.registerAndPublish(target(1),
                ticket -> yes(port.reply(ticket, target(1), 42), "reply"));
        yes(success.future().get() == 42, "future result");
        yes(port.pendingCount() == 0, "success releases capacity");

        var rejection = new IllegalStateException("mailbox admission denied");
        var rejected = port.registerAndPublish(target(1), ticket -> {
            throw rejection;
        });
        try {
            rejected.future().get();
            throw new AssertionError("rejection should fail the Future");
        } catch (ExecutionException expected) {
            yes(expected.getCause() == rejection, "original rejection retained");
        }
        yes(!port.reply(rejected.ticket(), target(1), 123), "rejection fences reply");

        var first = port.register(target(1));
        var other = port.register(target(1));
        yes(!port.reply(first.ticket(), target(2), 1), "incarnation mismatch");
        yes(first.future().cancel(false), "cancel accepted");
        yes(!port.reply(first.ticket(), target(1), 1), "late reply rejected");
        yes(port.reply(other.ticket(), target(1), 2), "other reply");
        yes(other.future().get() == 2, "no cross-completion");
        yes(port.pendingCount() == 0, "cancel and reply release budget");

        var fresh = port.register(target(1));
        yes(first.ticket().slotIndex() == fresh.ticket().slotIndex()
                || other.ticket().slotIndex() == fresh.ticket().slotIndex(),
                "reclaimed slot");
        yes(!port.reply(first.ticket(), target(1), -10), "ABA fenced");
        yes(port.reply(fresh.ticket(), target(1), 7), "fresh");
        yes(fresh.future().get() == 7, "fresh reply value");

        for (int i = 0; i < 128; ++i) {
            var racing = new GenServerCallPort<Integer>(1);
            var call = racing.register(target(1));
            var ready = new CountDownLatch(1);
            var wins = new AtomicInteger();
            try (var workers = Executors.newFixedThreadPool(2)) {
                var completion = workers.submit(() -> {
                    ready.await();
                    if (racing.reply(call.ticket(), target(1), 99)) wins.incrementAndGet();
                    return null;
                });
                var timeout = workers.submit(() -> {
                    ready.await();
                    if (racing.timeout(call.ticket())) wins.incrementAndGet();
                    return null;
                });
                ready.countDown();
                completion.get(10, TimeUnit.SECONDS);
                timeout.get(10, TimeUnit.SECONDS);
            }
            yes(wins.get() == 1, "single winner");
            yes(call.future().isDone(), "future terminal");
            yes(racing.pendingCount() == 0, "all credit reclaimed");
            yes(!racing.reply(call.ticket(), target(1), -1), "duplicate refused");
        }

        var old = port.register(target(1));
        var survivor = port.register(target(2));
        yes(port.failActor(target(1), new IllegalStateException("actor stopped")) == 1,
                "only matching incarnation failed");
        yes(old.future().isDone(), "failed Future terminal");
        yes(!survivor.future().isDone(), "other incarnation unaffected");
        yes(port.reply(survivor.ticket(), target(2), 66), "survivor");
        yes(survivor.future().get() == 66, "survivor completed");
        yes(port.close() == 0, "no leaked calls");
        System.out.println("GENSERVER_REAL_FUTURE_PORT_128_RACES_PASS");
    }
}
