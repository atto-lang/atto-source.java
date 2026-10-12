package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Force retirement interleavings rather than relying on stress timing. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class ExplicitNativeRetirementFaultInjectionTest {
    @Test
    void afterArenaCloseCannotPublishDoneBeforeQuotaRefund() throws Exception {
        checkPausedRetirement(true);
    }

    @Test
    void afterQuotaRefundCannotPublishDoneBeforeRegionUnregister() throws Exception {
        checkPausedRetirement(false);
    }

    @Test
    void postRefundFailureReportsShutdownErrorWithoutPublishingPhysicalDone() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        ActorRuntime runtime = new ActorRuntime();
        AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<Throwable> actorError = new AtomicReference<>();
        CountDownLatch allocated = new CountDownLatch(1);
        var actor = runtime.<Integer>spawnPrivate(() -> (message, ctx) -> {
            try {
                var nativeRegion = ctx.actorOwnedMemory().orElseThrow()
                        .newExplicitNativeI64Region(1);
                nativeRegion.installRetirementProbesForTesting(
                        () -> { },
                        () -> { throw new IllegalStateException("injected post-refund failure"); });
                region.set(nativeRegion);
                loan.set(nativeRegion.borrow(nativeRegion.allocate(7)));
            } catch (Throwable error) {
                actorError.compareAndSet(null, error);
            } finally {
                allocated.countDown();
            }
        });
        actor.send(1);
        assertTrue(allocated.await(5, TimeUnit.SECONDS));
        if (actorError.get() != null) fail(actorError.get());
        CompletableFuture<Void> stop = CompletableFuture.runAsync(actor::stop);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!region.get().closed() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(region.get().closed());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> loan.get().close());
        assertTrue(failure.getMessage().contains("injected post-refund failure"));
        var failedShutdown = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> actor.get_shutdown_result().get(5, TimeUnit.SECONDS));
        assertTrue(String.valueOf(failedShutdown.getCause())
                        .contains("injected post-refund failure"));
        assertFalse(actor.get_done().isDone(),
                "failed post-refund registration leaves physical done pending");
        assertEquals(0L, runtime.privateMemoryBytes(),
                "native Arena has physically released quota before injected callback failure");
        assertTrue(region.get().physicallyClosed());
        // This fault deliberately leaves a registered actor-owned arena. Fail
        // closed instead of pretending that logical STOP implies physical DONE.
        var rejectedStop = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stop.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, rejectedStop.getCause());
        assertTrue(String.valueOf(rejectedStop.getCause())
                .contains("failed physical native retirement"));
        assertSame(failedShutdown.getCause(), rejectedStop.getCause().getCause(),
                "STOP must preserve the exact native fault reported to shutdown observers");
        assertThrows(IllegalStateException.class, runtime::close);
    }

    @Test
    void rejectedLoanAccountingCheckDoesNotConsumeTheOnlyCloseAuthority() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
            AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
            AtomicReference<ExplicitNativeI64Region.Slot> slot = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var nativeRegion = ctx.actorOwnedMemory().orElseThrow()
                            .newExplicitNativeI64Region(1);
                    region.set(nativeRegion);
                    slot.set(nativeRegion.allocate(99));
                    loan.set(nativeRegion.borrow(slot.get()));
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                } finally {
                    created.countDown();
                }
            });
            actor.send(1);
            assertTrue(created.await(5, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            assertEquals(1, region.get().activeLoans());
            var field = ExplicitNativeI64Region.class.getDeclaredField("slotLoans");
            field.setAccessible(true);
            int[] counters = (int[]) field.get(region.get());

            // Fault injection: corrupt a precondition without consuming a
            // valid loan. Only this test manipulates private accounting.
            counters[slot.get().index()] = 0;
            var rejected = assertThrows(IllegalStateException.class,
                    () -> loan.get().close());
            assertTrue(rejected.getMessage().contains("accounting underflow"));
            assertEquals(1, region.get().activeLoans());
            counters[slot.get().index()] = 1;
            loan.get().close();
            loan.get().close(); // exactly-once and retry after rejected precondition
            assertEquals(0, region.get().activeLoans());
            actor.stop();
            actor.get_done().get(5, TimeUnit.SECONDS);
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    @Test
    void aggregateRefundUnderflowCannotDecrementActorOwnedNativeBytes() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        ActorRuntime runtime = new ActorRuntime();
        AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorMemorySlice> slice = new AtomicReference<>();
        AtomicReference<Throwable> actorError = new AtomicReference<>();
        CountDownLatch allocated = new CountDownLatch(1);
        var actor = runtime.<Integer>spawnPrivate(() -> (message, ctx) -> {
            try {
                var owner = ctx.actorOwnedMemory().orElseThrow();
                slice.set(owner);
                var nativeRegion = owner.newExplicitNativeI64Region(1);
                region.set(nativeRegion);
                loan.set(nativeRegion.borrow(nativeRegion.allocate(81)));
            } catch (Throwable error) {
                actorError.compareAndSet(null, error);
            } finally {
                allocated.countDown();
            }
        });
        actor.send(1);
        assertTrue(allocated.await(5, TimeUnit.SECONDS));
        if (actorError.get() != null) fail(actorError.get());
        CompletableFuture<Void> stop = CompletableFuture.runAsync(actor::stop);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!region.get().closed() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(region.get().closed());
        assertEquals(8L, slice.get().usedBytes());

        // Test-only corruption of the private aggregate counter to force
        // the normally unreachable aggregate-underflow branch. No production
        // hook or guest authority is added by this regression.
        var field = ActorRuntime.class.getDeclaredField("privateMemoryBytes");
        field.setAccessible(true);
        var aggregate = (java.util.concurrent.atomic.AtomicLong) field.get(runtime);
        assertEquals(8L, aggregate.get());
        aggregate.set(0L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> loan.get().close());
        assertTrue(failure.getMessage().contains("actor native budget accounting underflow"));
        assertEquals(8L, slice.get().usedBytes(),
                "failed aggregate refund must not partially decrement actor-owned bytes");
        assertEquals(0L, runtime.privateMemoryBytes(),
                "failed aggregate refund must not publish a second global decrement");
        assertFalse(actor.get_done().isDone());
        var failedShutdown = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> actor.get_shutdown_result().get(5, TimeUnit.SECONDS));
        assertTrue(String.valueOf(failedShutdown.getCause())
                .contains("actor native budget accounting underflow"));
        var stopped = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> stop.get(5, TimeUnit.SECONDS));
        assertTrue(String.valueOf(stopped.getCause())
                .contains("failed physical native retirement"));
        assertSame(failedShutdown.getCause(), stopped.getCause().getCause(),
                "aggregate-accounting failure must be returned directly to STOP caller");
        assertThrows(IllegalStateException.class, runtime::close);
    }

    private void checkPausedRetirement(boolean pauseBeforeRefund) throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        ActorRuntime runtime = new ActorRuntime();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        Thread completion = null;
        try {
            var actor = runtime.<Integer>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var nativeRegion = ctx.actorOwnedMemory().orElseThrow()
                            .newExplicitNativeI64Region(1);
                    nativeRegion.installRetirementProbesForTesting(
                            pauseBeforeRefund ? () -> park(paused, releaseProbe) : () -> { },
                            pauseBeforeRefund ? () -> { } : () -> park(paused, releaseProbe));
                    region.set(nativeRegion);
                    loan.set(nativeRegion.borrow(nativeRegion.allocate(123)));
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                } finally {
                    allocated.countDown();
                }
            });
            actor.send(1);
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            CompletableFuture<Void> stop = CompletableFuture.runAsync(actor::stop);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!region.get().closed() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(region.get().closed(), "STOP must fence native access");
            assertFalse(actor.get_done().isDone());

            completion = new Thread(() -> {
                try {
                    loan.get().close();
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                }
            }, "native-retirement-probe-completion");
            completion.start();
            assertTrue(paused.await(5, TimeUnit.SECONDS),
                    "last loan must reach the selected physical retirement seam");
            assertFalse(actor.get_done().isDone(),
                    "physical done must never publish at an intermediate retirement phase");
            assertFalse(actor.get_shutdown_result().isDone(),
                    "successful shutdown result must wait for full ownership retirement");
            assertEquals(pauseBeforeRefund ? 8L : 0L, runtime.privateMemoryBytes(),
                    "physical budget must reflect precisely the injected retirement phase");

            releaseProbe.countDown();
            completion.join(5000);
            assertFalse(completion.isAlive());
            if (error.get() != null) fail(error.get());
            stop.get(5, TimeUnit.SECONDS);
            actor.get_done().get(5, TimeUnit.SECONDS);
            actor.get_shutdown_result().get(5, TimeUnit.SECONDS);
            assertEquals(0L, runtime.privateMemoryBytes());
            assertTrue(region.get().physicallyClosed());
        } finally {
            releaseProbe.countDown();
            if (completion != null) completion.join(5000);
            runtime.close();
        }
    }

    private static void park(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("retirement fault gate was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retirement fault gate interrupted", interrupted);
        }
    }
}
