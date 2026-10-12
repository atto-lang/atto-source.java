package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Physical actor DONE is fenced by every registered Arena, not merely by
 * the aggregate quota of the last reservation currently releasing.
 */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
final class ExplicitNativePhysicalCompletionRaceTest {
    @Test
    void twoRetiredArenasRequireBothPhysicalReleaseCallbacks() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK 25 FFM required");
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch created = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region> first = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region> second = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> firstLoan = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> secondLoan = new AtomicReference<>();

        try {
            var actor = runtime.<Integer>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var slice = ctx.actorOwnedMemory().orElseThrow();
                    var a = slice.newExplicitNativeI64Region(1);
                    var b = slice.newExplicitNativeI64Region(1);
                    first.set(a);
                    second.set(b);
                    firstLoan.set(a.borrow(a.allocate(11)));
                    secondLoan.set(b.borrow(b.allocate(22)));
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    created.countDown();
                }
            });
            actor.send(1);
            assertTrue(created.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            CompletableFuture<Void> stopping = CompletableFuture.runAsync(actor::stop);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!first.get().closed() || !second.get().closed())
                    && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(first.get().closed());
            assertTrue(second.get().closed());
            assertFalse(actor.get_done().isDone());
            assertEquals(16L, runtime.privateMemoryBytes(),
                    "both native regions remain physically charged");

            firstLoan.get().close();
            assertTrue(first.get().physicallyClosed());
            assertFalse(second.get().physicallyClosed());
            assertFalse(actor.get_done().isDone(),
                    "one retired Arena cannot release another Arena's physical done fence");
            assertEquals(8L, runtime.privateMemoryBytes());

            secondLoan.get().close();
            stopping.get(5, TimeUnit.SECONDS);
            actor.get_done().get(5, TimeUnit.SECONDS);
            actor.get_shutdown_result().get(5, TimeUnit.SECONDS);
            assertTrue(second.get().physicallyClosed());
            assertEquals(0L, runtime.privateMemoryBytes(),
                    "all native bytes must be refunded before physical completion");
        } finally {
            // In a failing test, expose incomplete cleanup rather than
            // silently allowing a physically retained region to escape.
            runtime.close();
        }
    }
}
