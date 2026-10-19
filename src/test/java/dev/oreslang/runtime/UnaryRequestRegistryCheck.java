package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded count/byte admission, exact credit release and session-close race conformance. */
public final class UnaryRequestRegistryCheck {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static UnaryTransportFrame.Envelope reply(long session, long epoch, long id) {
        return reply(session, epoch, id, 0);
    }

    private static UnaryTransportFrame.Envelope reply(long session, long epoch, long id, int payloadBytes) {
        return new UnaryTransportFrame.Envelope(
                UnaryTransportFrame.Kind.REPLY, session, epoch, id, new byte[payloadBytes]);
    }

    private static void basicAdmission() {
        var registry = new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 55, 3, 3, 5);
        long first = registry.admit(3), second = registry.admit(2);
        check(first == 1 && second == 2 && registry.active() == 2 && registry.available() == 1,
                "count credits must track inflight requests");
        check(registry.activePayloadBytes() == 5 && registry.availablePayloadBytes() == 0,
                "byte credits must track admitted request payloads");

        try { registry.admit(1); throw new AssertionError("admitted beyond byte capacity"); }
        catch (IllegalStateException expected) { }
        try { registry.complete(UnaryTransportFrame.Transport.TCP, reply(56, 3, first));
              throw new AssertionError("cross-session reply accepted"); }
        catch (SecurityException expected) { }
        try { registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 4, first));
              throw new AssertionError("stale/incorrect generation accepted"); }
        catch (SecurityException expected) { }
        try { registry.complete(UnaryTransportFrame.Transport.WEBSOCKET, reply(55, 3, first));
              throw new AssertionError("wrong transport accepted"); }
        catch (SecurityException expected) { }
        try { registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, 999));
              throw new AssertionError("unissued reply accepted"); }
        catch (SecurityException expected) { }

        check(registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, second, 100)),
                "out-of-order response accepted");
        check(registry.activePayloadBytes() == 3 && registry.availablePayloadBytes() == 2,
                "completion must release original request bytes, not reply bytes");
        check(!registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, second)),
                "duplicate response rejected");

        long third = registry.admit(1);
        check(third == 3 && registry.activePayloadBytes() == 4,
                "released byte credit must become available without reusing request IDs");
        check(registry.cancel(first), "first cancellation wins");
        check(!registry.cancel(first), "repeat cancellation ignored");
        check(registry.activePayloadBytes() == 1, "cancellation releases exactly its request bytes");
        check(registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, third)),
                "third request completes");
        check(registry.active() == 0 && registry.available() == 3
                && registry.activePayloadBytes() == 0 && registry.availablePayloadBytes() == 5,
                "all count and byte credits fully released");

        long fourth = registry.admit(5);
        check(fourth == 4 && registry.activePayloadBytes() == 5, "full byte budget admission");
        registry.close(); registry.close();
        check(registry.active() == 0 && registry.available() == 3
                && registry.activePayloadBytes() == 0 && registry.availablePayloadBytes() == 5,
                "close releases all pending count and byte credits once");
        try { registry.admit(0); throw new AssertionError("closed registry admitted new request"); }
        catch (IllegalStateException expected) { }
        check(!registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, fourth)),
                "late terminal reply after session close ignored");
    }

    private static void configurationGuards() {
        try { new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 1, 1, 1, -1);
              throw new AssertionError("negative byte capacity accepted"); }
        catch (IllegalArgumentException expected) { }
        try { new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 1, 1, 1,
                    (long) UnaryTransportFrame.MAX_PAYLOAD_BYTES + 1);
              throw new AssertionError("useless oversized byte capacity accepted"); }
        catch (IllegalArgumentException expected) { }

        var zero = new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 2, 1, 1, 0);
        check(zero.admit(0) == 1, "zero byte capacity must still allow empty request payloads");
        zero.close();

        var invalid = new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 3, 1, 1,
                UnaryTransportFrame.MAX_PAYLOAD_BYTES);
        try { invalid.admit(-1); throw new AssertionError("negative request payload size accepted"); }
        catch (IllegalArgumentException expected) { }
        try { invalid.admit(UnaryTransportFrame.MAX_PAYLOAD_BYTES + 1);
              throw new AssertionError("request payload above global maximum accepted"); }
        catch (IllegalArgumentException expected) { }
        invalid.close();

        var count = new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 4, 1, 2,
                2L * UnaryTransportFrame.MAX_PAYLOAD_BYTES);
        count.admit(0); count.admit(0);
        try { count.admit(0); throw new AssertionError("admitted beyond count capacity"); }
        catch (IllegalStateException expected) { }
        count.close();
    }

    private static void replyCancelRaces() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                var raced = new UnaryRequestRegistry(UnaryTransportFrame.Transport.WEBSOCKET, 88, 17, 1, 7);
                long id = raced.admit(7);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger winners = new AtomicInteger();
                var responded = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    if (raced.complete(UnaryTransportFrame.Transport.WEBSOCKET, reply(88, 17, id)))
                        winners.incrementAndGet();
                });
                var cancelled = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    if (raced.cancel(id)) winners.incrementAndGet();
                });
                go.countDown();
                responded.get(2, TimeUnit.SECONDS); cancelled.get(2, TimeUnit.SECONDS);
                check(winners.get() == 1 && raced.active() == 0 && raced.available() == 1
                        && raced.activePayloadBytes() == 0 && raced.availablePayloadBytes() == 7,
                        "exactly one terminal and exactly one count/byte credit release");
                raced.close();
            }
        }
    }

    private static void closeReplyCancelRaces() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                var raced = new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 89, 18, 1, 9);
                long id = raced.admit(9);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger explicitTerminalWinners = new AtomicInteger();
                var responded = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    if (raced.complete(UnaryTransportFrame.Transport.TCP, reply(89, 18, id)))
                        explicitTerminalWinners.incrementAndGet();
                });
                var cancelled = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    if (raced.cancel(id)) explicitTerminalWinners.incrementAndGet();
                });
                var closed = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new AssertionError(e);
                    }
                    raced.close();
                });

                go.countDown();
                responded.get(2, TimeUnit.SECONDS);
                cancelled.get(2, TimeUnit.SECONDS);
                closed.get(2, TimeUnit.SECONDS);

                check(explicitTerminalWinners.get() <= 1, "reply and cancel cannot both win around close");
                check(raced.active() == 0 && raced.available() == 1
                        && raced.activePayloadBytes() == 0 && raced.availablePayloadBytes() == 9,
                        "session close race must reclaim all count and byte credits");
                check(!raced.complete(UnaryTransportFrame.Transport.TCP, reply(89, 18, id)),
                        "late reply after close race must remain terminal");
            }
        }
    }

    public static void main(String[] unused) throws Exception {
        basicAdmission();
        configurationGuards();
        replyCancelRaces();
        closeReplyCancelRaces();
        System.out.println("PASS: bounded count/byte admission and 1000 reply/cancel + 1000 close/reply/cancel races");
    }
}
