package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Source cancellation must drain actor-owned lexical cleanup before the
 * native FFM arena is logically retired. Opaque native loans can outlive that
 * logical retirement, but never refund pinned bytes before their final close.
 */
@Timeout(30)
final class ActorNativeSourceCleanupLoanTest {
    @Test
    void cancellationCleanupAndOutstandingFfmLoanHaveSeparateRetirementBarriers()
            throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        ActorRuntime runtime = new ActorRuntime();
        OresFuture<Void> upstream = new OresFuture<>();
        OresFuture<Void> cleanup = new OresFuture<>();
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        AtomicReference<OresFuture<Integer>> sourceTask = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var arena = ctx.actorOwnedMemory().orElseThrow()
                            .newExplicitNativeI64Region(1);
                    var slot = arena.allocate(2026);
                    var hold = arena.borrow(slot);
                    region.set(arena);
                    loan.set(hold);
                    var source = ctx.runtime().startActorTask(
                            new OresScheduler.CancellationAwareTask<Integer>() {
                                private int phase;
                                private volatile boolean cancelled;
                                private volatile boolean cleaning;
                                @Override public boolean cleanupInProgress() {
                                    return cleaning;
                                }
                                @Override public void cancellationRequested(
                                        OresScheduler.TaskCancelledException reason) {
                                    cancelled = true;
                                }
                                @Override public OresScheduler.Step<Integer> resume(
                                        OresScheduler.Resume resumed) {
                                    if (phase++ == 0) {
                                        awaiting.countDown();
                                        return OresScheduler.await(upstream);
                                    }
                                    if (phase == 2) {
                                        assertTrue(cancelled);
                                        assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                                resumed.failure());
                                        cleaning = true;
                                        cleanupEntered.countDown();
                                        return OresScheduler.await(cleanup);
                                    }
                                    assertEquals(3, phase);
                                    assertTrue(cleaning);
                                    assertNull(resumed.failure());
                                    cleaning = false;
                                    return OresScheduler.done(1);
                                }
                            });
                    sourceTask.set(ctx.runtime().ownCurrentActorFuture(source));
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    awaiting.countDown();
                }
            });
            actor.send("start");
            assertTrue(awaiting.await(4, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertNotNull(region.get());
            assertNotNull(loan.get());
            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(4, TimeUnit.SECONDS));
            assertFalse(actor.get_done().isDone(),
                    "source cleanup still owns actor memory and execution domain");
            assertFalse(region.get().closed(),
                    "native actor region cannot retire during awaited lexical cleanup");
            assertEquals(1, region.get().activeLoans());
            assertTrue(runtime.privateMemoryBytes() >= 8L);

            assertTrue(cleanup.completeFromRuntime(null));
            assertTrue(actor.awaitTermination(4, TimeUnit.SECONDS));
            assertTrue(sourceTask.get().isCancelled());
            assertTrue(region.get().closed(), "physical actor retirement revokes region");
            assertFalse(region.get().physicallyClosed(),
                    "outstanding FFM loan retains the native allocation");
            assertEquals(1, region.get().activeLoans());
            assertEquals(8L, runtime.privateMemoryBytes(),
                    "pinned native bytes cannot be refunded at logical actor stop");

            loan.get().close();
            loan.get().close();
            assertTrue(region.get().physicallyClosed());
            assertEquals(0, region.get().activeLoans());
            assertEquals(0L, runtime.privateMemoryBytes());
            assertDoesNotThrow(runtime::close);
        } finally {
            cleanup.completeFromRuntime(null);
            if (loan.get() != null) loan.get().close();
            runtime.close();
        }
    }
}
