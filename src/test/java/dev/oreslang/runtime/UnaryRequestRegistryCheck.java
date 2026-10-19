package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded admission, exact credit release and session-close race conformance. */
public final class UnaryRequestRegistryCheck {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static UnaryTransportFrame.Envelope reply(long session, long epoch, long id) {
        return new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REPLY, session, epoch, id, new byte[0]);
    }

    public static void main(String[] unused) throws Exception {
        var registry = new UnaryRequestRegistry(UnaryTransportFrame.Transport.TCP, 55, 3, 2);
        long first = registry.admit(), second = registry.admit();
        check(first == 1 && second == 2 && registry.active() == 2 && registry.available() == 0,
                "capacity must bound inflight requests");
        try { registry.admit(); throw new AssertionError("admitted beyond capacity"); }
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
        check(registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, second)),
                "out-of-order response accepted");
        check(!registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, second)),
                "duplicate response rejected");
        check(registry.cancel(first), "first cancellation wins");
        check(!registry.cancel(first), "repeat cancellation ignored");
        check(registry.active() == 0 && registry.available() == 2, "credits fully released");
        check(registry.admit() == 3, "new request ID is monotonic, never reused");
        registry.close(); registry.close();
        check(registry.active() == 0 && registry.available() == 2, "close releases all pending requests once");
        try { registry.admit(); throw new AssertionError("closed registry admitted new request"); }
        catch (IllegalStateException expected) { }
        check(!registry.complete(UnaryTransportFrame.Transport.TCP, reply(55, 3, 3)),
                "late terminal reply after session close ignored");

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                var raced = new UnaryRequestRegistry(UnaryTransportFrame.Transport.WEBSOCKET, 88, 17, 1);
                long id = raced.admit();
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger winners = new AtomicInteger();
                var responded = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    if (raced.complete(UnaryTransportFrame.Transport.WEBSOCKET, reply(88, 17, id)))
                        winners.incrementAndGet();
                });
                var cancelled = pool.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    if (raced.cancel(id)) winners.incrementAndGet();
                });
                go.countDown();
                responded.get(2, TimeUnit.SECONDS); cancelled.get(2, TimeUnit.SECONDS);
                check(winners.get() == 1 && raced.active() == 0 && raced.available() == 1,
                        "exactly one terminal and exactly one credit release");
                raced.close();
            }
        }
        System.out.println("PASS: bounded admission and 1000 reply/cancel races with exact credit release");
    }
}
