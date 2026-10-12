package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native regions must outlive pending async/FFI-style host leases. */
final class ExplicitNativeI64LoanTest {
    @Test
    void actorShutdownRevokesReadsButFinalLoanDefersPhysicalFree() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> outstanding = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<ActorRuntime> retainedRuntime = new AtomicReference<>();

        ActorRuntime runtime = new ActorRuntime();
        try {
            retainedRuntime.set(runtime);
            var actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var memory = ctx.actorOwnedMemory().orElseThrow();
                    var nativeRegion = memory.newExplicitNativeI64Region(2);
                    var first = nativeRegion.allocate(42);
                    var loan = nativeRegion.borrow(first);
                    assertEquals(42, loan.read());
                    assertThrows(IllegalStateException.class, () -> nativeRegion.drop(first));
                    region.set(nativeRegion);
                    outstanding.set(loan);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    ready.countDown();
                }
            });
            actor.send("allocate");
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            var stopping = java.util.concurrent.CompletableFuture.runAsync(actor::stop);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!region.get().closed() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(region.get().closed(), "STOP must retire FFM regions");
            assertFalse(actor.get_done().isDone(),
                    "pinned FFM loans forbid physical actor completion");

        assertNotNull(region.get());
        assertEquals(16, retainedRuntime.get().privateMemoryBytes(),
                "physically pinned native bytes must survive actor quota teardown");
        assertTrue(region.get().closed(), "actor termination fences native reads");
        assertFalse(region.get().physicallyClosed(),
                "FFM scope must not close before outstanding loan is released");
        assertEquals(1, region.get().activeLoans());
        assertThrows(IllegalStateException.class, () -> outstanding.get().read());

        Thread completionThread = new Thread(outstanding.get()::close, "native-loan-complete");
        completionThread.start();
        completionThread.join(5000);
        assertFalse(completionThread.isAlive());
        outstanding.get().close(); // double-close safe
        assertEquals(0, region.get().activeLoans());
        assertTrue(region.get().physicallyClosed());
        assertEquals(0, retainedRuntime.get().privateMemoryBytes(),
                "last native loan completion refunds the aggregate runtime budget");
            stopping.get(5, TimeUnit.SECONDS);
            actor.get_done().get(5, TimeUnit.SECONDS);
        } finally {
            runtime.close();
        }
    }

    @Test
    void explicitCloseWithMultipleLoansWaitsForBothAndReturnsQuota() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var heap = ctx.actorOwnedMemory().orElseThrow();
                    long baseline = heap.usedBytes();
                    var region = heap.newExplicitNativeI64Region(1);
                    var slot = region.allocate(5);
                    var first = region.borrow(slot);
                    var second = region.borrow(slot);
                    region.close();
                    assertTrue(region.closed());
                    assertFalse(region.physicallyClosed());
                    assertEquals(2, region.activeLoans());
                    assertEquals(baseline + 8, heap.usedBytes(),
                            "native quota retained while pending loans hold physical memory");
                    assertThrows(IllegalStateException.class, () -> first.read());
                    first.close();
                    assertFalse(region.physicallyClosed());
                    second.close();
                    assertTrue(region.physicallyClosed());
                    assertEquals(baseline, heap.usedBytes());
                    assertEquals(baseline, ctx.runtime().privateMemoryBytes());
                } catch (Throwable error) {
                    failure.set(error);
                } finally { done.countDown(); }
            });
            actor.send("run");
            assertTrue(done.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
    }

    @Test
    void anotherActorCannotDereferenceAnOutstandingLoan() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<ExplicitNativeI64Region.Loan> borrowed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var arena = ctx.actorOwnedMemory().orElseThrow().newExplicitNativeI64Region(1);
                    borrowed.set(arena.borrow(arena.allocate(21)));
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally { ready.countDown(); }
            });
            var second = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    assertThrows(IllegalStateException.class, () -> borrowed.get().read());
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally { checked.countDown(); }
            });
            first.send("alloc");
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            second.send("inspect");
            assertTrue(checked.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            // Physical shutdown must not finish with this actor-owned loan
            // still retained; release it before runtime close.
            borrowed.get().close();
        }
    }
}
