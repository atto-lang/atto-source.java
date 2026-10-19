package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Replay fencing and exact count/byte release for peer-chosen request IDs. */
public final class UnaryInboundRequestRegistryCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void basic() {
        var ledger = new UnaryInboundRequestRegistry(101, 7, 2, 5);
        check(ledger.admit(101, 7, 10, 3), "first peer request admitted");
        check(ledger.admit(101, 7, 12, 2), "monotonic gap is allowed");
        check(ledger.active() == 2 && ledger.activePayloadBytes() == 5,
                "count and byte credits charged");

        try { ledger.admit(101, 7, 12, 0);
              throw new AssertionError("duplicate peer request accepted"); }
        catch (SecurityException expected) { }
        try { ledger.admit(101, 7, 11, 0);
              throw new AssertionError("out-of-order replay accepted"); }
        catch (SecurityException expected) { }
        try { ledger.admit(102, 7, 13, 0);
              throw new AssertionError("foreign session accepted"); }
        catch (SecurityException expected) { }
        try { ledger.admit(101, 8, 13, 0);
              throw new AssertionError("stale generation accepted"); }
        catch (SecurityException expected) { }

        check(ledger.complete(101, 7, 10), "completion releases first request");
        check(!ledger.complete(101, 7, 10), "duplicate completion is idempotent");
        check(ledger.activePayloadBytes() == 2, "completion releases original request bytes");
        check(ledger.admit(101, 7, 13, 3), "released bytes can be reused");
        check(ledger.cancel(101, 7, 12), "cancel releases pending request");
        check(ledger.complete(101, 7, 13), "remaining request completes");
        check(ledger.active() == 0 && ledger.activePayloadBytes() == 0,
                "all inbound credits reclaimed");

        try { ledger.complete(101, 7, 14);
              throw new AssertionError("future unissued peer ID accepted"); }
        catch (SecurityException expected) { }

        ledger.close();
        ledger.close();
        check(ledger.isClosed() && ledger.active() == 0
                && ledger.availablePayloadBytes() == 5, "close is idempotent");
        try { ledger.admit(101, 7, 14, 0);
              throw new AssertionError("closed ledger admitted request"); }
        catch (IllegalStateException expected) { }
    }

    private static void capacityRejectionConsumesId() {
        var ledger = new UnaryInboundRequestRegistry(201, 1, 1, 2);
        check(ledger.admit(201, 1, 1, 2), "fills admission");
        check(!ledger.admit(201, 1, 2, 0), "count capacity rejects without admission");
        check(ledger.highWaterRequestId() == 2,
                "terminal capacity rejection must consume replay high-water mark");
        check(ledger.complete(201, 1, 1), "release first");
        try { ledger.admit(201, 1, 2, 0);
              throw new AssertionError("capacity-rejected peer ID was replayed"); }
        catch (SecurityException expected) { }
        check(ledger.admit(201, 1, 3, 0), "retry must use a fresh peer request ID");
        ledger.close();

        var bytes = new UnaryInboundRequestRegistry(202, 1, 2, 2);
        check(bytes.admit(202, 1, 1, 2), "fills byte budget");
        check(!bytes.admit(202, 1, 2, 1), "byte capacity rejects");
        check(bytes.highWaterRequestId() == 2,
                "byte-rejected request must consume replay high-water mark");
        check(bytes.complete(202, 1, 1), "release byte budget");
        try { bytes.admit(202, 1, 2, 1);
              throw new AssertionError("byte-rejected peer ID was replayed"); }
        catch (SecurityException expected) { }
        check(bytes.admit(202, 1, 3, 1), "fresh retry succeeds");
        bytes.close();
    }

    private static void terminalRaces() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                var ledger = new UnaryInboundRequestRegistry(301, 9, 1, 7);
                check(ledger.admit(301, 9, 1, 7), "race admission");
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger winners = new AtomicInteger();
                var complete = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    if (ledger.complete(301, 9, 1)) winners.incrementAndGet();
                });
                var cancel = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    if (ledger.cancel(301, 9, 1)) winners.incrementAndGet();
                });
                var close = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    ledger.close();
                });
                go.countDown();
                complete.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);
                close.get(2, TimeUnit.SECONDS);
                check(winners.get() <= 1, "complete and cancel cannot both win");
                check(ledger.active() == 0 && ledger.activePayloadBytes() == 0,
                        "close/terminal race reclaims inbound credits");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        basic();
        capacityRejectionConsumesId();
        terminalRaces();
        System.out.println("PASS: inbound monotonic replay fencing and 1000 close/complete/cancel races");
    }
}
