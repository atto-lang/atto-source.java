package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ReactiveReaderLeaseTest {
    private static <T> T read(OresFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }

    @Test
    void streamReleaseTransfersTheSameCursorWithoutResubscribing() throws Exception {
        OresStream<Integer> stream = OresStream.fromValues(List.of(10, 20));
        OresSubscription.Reader<Integer> first = stream.getReader();
        assertEquals(10, read(first.next()).value());
        assertThrows(IllegalStateException.class, stream::getReader);

        first.releaseLock();
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> read(first.next())).getCause());

        OresSubscription.Reader<Integer> second = stream.getReader();
        assertNotSame(first, second);
        assertEquals(20, read(second.next()).value());
        assertTrue(read(second.next()).isComplete());
        second.releaseLock();
        assertThrows(IllegalStateException.class, stream::subscribe,
                "release is reader handoff, not an additional Stream subscription");
    }

    @Test
    void anObservableHasIndependentSubscriptionsButOneReaderPerSubscription() throws Exception {
        OresObservable<Integer> observable = OresObservable.fromValues(List.of(1, 2));
        OresSubscription<Integer> a = observable.subscribe();
        OresSubscription<Integer> b = observable.subscribe();
        var aReader = a.getReader();
        var bReader = b.getReader();

        assertTrue(a.isLocked());
        assertTrue(b.isLocked());
        assertThrows(IllegalStateException.class, a::getReader);
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> read(a.next())).getCause());

        assertEquals(1, read(aReader.next()).value());
        assertEquals(1, read(bReader.next()).value());
        aReader.releaseLock();
        assertFalse(a.isLocked());
        assertEquals(2, read(a.next()).value(), "direct pulls resume after releasing the lease");
        assertEquals(2, read(bReader.next()).value());
        bReader.releaseLock();
    }

    @Test
    void cannotReleaseOrAcquireReaderWhilePullIsOutstanding() throws Exception {
        OresFuture<OresNotification<Integer>> pending = new OresFuture<>();
        OresSubscription<Integer> sub = new OresSubscription<>() {
            @Override protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                return pending;
            }
        };
        var reader = sub.getReader();
        var item = reader.next();
        assertFalse(item.isDone());
        assertThrows(IllegalStateException.class, reader::releaseLock);
        assertThrows(IllegalStateException.class, sub::getReader);
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> read(reader.next())).getCause());

        pending.completeFromRuntime(OresNotification.next(42));
        assertEquals(42, read(item).value());
        reader.releaseLock();
        assertFalse(sub.isLocked());
    }

    @Test
    void cancelSettlesPendingPullAndAllowsLeaseCleanup() throws Exception {
        OresFuture<OresNotification<Integer>> pending = new OresFuture<>();
        AtomicInteger cleanup = new AtomicInteger();
        OresSubscription<Integer> sub = new OresSubscription<>() {
            @Override protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                return pending;
            }
            @Override protected void cancelFromRuntime() { cleanup.incrementAndGet(); }
        };
        var reader = sub.getReader();
        var inFlight = reader.next();
        assertTrue(reader.cancel());
        assertTrue(inFlight.isCancelled());
        reader.releaseLock();
        assertTrue(sub.isCancelled());
        assertEquals(1, cleanup.get());
        pending.completeFromRuntime(OresNotification.next(99));
        assertTrue(read(sub.next()).isComplete());
    }

    @Test
    void synchronousSourceFailureRunsCleanupExactlyOnce() throws Exception {
        AtomicInteger cleanup = new AtomicInteger();
        OresSubscription<Integer> sub = new OresSubscription<>() {
            @Override protected OresFuture<OresNotification<Integer>> nextFromRuntime() {
                throw new IllegalStateException("producer threw");
            }
            @Override protected void cancelFromRuntime() { cleanup.incrementAndGet(); }
        };
        var reader = sub.getReader();
        var failure = assertThrows(ExecutionException.class, () -> read(reader.next()));
        assertEquals("producer threw", failure.getCause().getMessage());
        assertEquals(1, cleanup.get());
        assertTrue(read(reader.next()).isComplete());
        reader.releaseLock();
        sub.cancel();
        assertEquals(1, cleanup.get());
    }

    @Test
    void actorOwnedStreamSurvivesReaderHandoffUntilTerminalPull() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var launched = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.SHARED, 0,
                    (message, context) -> OresStream.fromValues(List.of(10, 20)));
            OresStream<Integer> stream = read(launched.result());
            var first = stream.getReader();
            assertEquals(10, read(first.next()).value());
            first.releaseLock();
            assertFalse(launched.done().isDone(),
                    "releasing a reader lease must not kill its producer actor");

            var second = stream.getReader();
            assertEquals(20, read(second.next()).value());
            assertTrue(read(second.next()).isComplete());
            second.releaseLock();
            assertTrue(read(launched.done()));
            assertEquals(0, runtime.actorCount());
        }
    }
}
