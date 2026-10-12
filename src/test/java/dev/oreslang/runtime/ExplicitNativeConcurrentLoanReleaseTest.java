package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A retired actor must physically release an FFM region exactly after its final loan. */
final class ExplicitNativeConcurrentLoanReleaseTest {
    @Test
    void simultaneousCompletionAfterActorShutdownDoesNotRefundQuotaPrematurely() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan[]> loans = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.ActorRef<String> actor;
        java.util.concurrent.CompletableFuture<Void> stopping;
        try {
            actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var nativeRegion = ctx.actorOwnedMemory().orElseThrow().newExplicitNativeI64Region(1);
                    var slot = nativeRegion.allocate(99);
                    var held = new ExplicitNativeI64Region.Loan[16];
                    for (int i = 0; i < held.length; ++i) held[i] = nativeRegion.borrow(slot);
                    region.set(nativeRegion);
                    loans.set(held);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    created.countDown();
                }
            });
            actor.send("allocate");
            assertTrue(created.await(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            stopping = java.util.concurrent.CompletableFuture.runAsync(actor::stop);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!region.get().closed() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(region.get().closed(), "STOP must fence FFM reads");
            assertFalse(actor.get_done().isDone(),
                    "physical done must wait for all native loans");

        assertNotNull(region.get());
        assertTrue(region.get().closed());
        assertFalse(region.get().physicallyClosed());
        assertEquals(16, region.get().activeLoans());
        assertEquals(8L, runtime.privateMemoryBytes(), "retired actor still owns pinned native bytes");

        CountDownLatch releaseTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(loans.get().length);
        for (var loan : loans.get()) {
            new Thread(() -> {
                try {
                    releaseTogether.await();
                    loan.close();
                    loan.close();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    finished.countDown();
                }
            }, "oreslang-ffm-loan-finish").start();
        }
        releaseTogether.countDown();
        assertTrue(finished.await(20, TimeUnit.SECONDS));
        if (error.get() != null) fail(error.get());
        assertEquals(0, region.get().activeLoans());
        assertTrue(region.get().physicallyClosed());
        assertEquals(0L, runtime.privateMemoryBytes(),
                "native bytes must be refunded exactly once, after the last loan");
            stopping.get(5, TimeUnit.SECONDS);
            actor.get_done().get(5, TimeUnit.SECONDS);
        } finally {
            try { runtime.close(); } catch (IllegalStateException incomplete) {
                if (error.get() == null) throw incomplete;
            }
        }
    }
}
