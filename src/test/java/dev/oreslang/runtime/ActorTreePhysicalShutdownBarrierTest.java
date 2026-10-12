package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A parent is not physically complete until *all* structured children
 * have drained source cleanup and returned their last native FFM loan.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class ActorTreePhysicalShutdownBarrierTest {
    @Test
    void parentDoneWaitsForSiblingCleanupAndNativeLoanInEitherReleaseOrder() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");
        for (boolean cleanupFirst : new boolean[] {true, false}) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                CountDownLatch spawned = new CountDownLatch(1);
                CountDownLatch loanAllocated = new CountDownLatch(1);
                CountDownLatch sourceArmed = new CountDownLatch(1);
                CountDownLatch cleanupEntered = new CountDownLatch(1);
                AtomicInteger cleanupRuns = new AtomicInteger();
                AtomicBoolean cleaning = new AtomicBoolean();
                OresFuture<Integer> upstream = new OresFuture<>();
                OresFuture<Integer> cleanup = new OresFuture<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
                AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
                AtomicReference<ActorRuntime.ActorRef<Integer>> nativeChild = new AtomicReference<>();
                AtomicReference<ActorRuntime.ActorRef<Integer>> cleanupChild = new AtomicReference<>();

                var parent = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                    try {
                        var nativeRef = context.runtime().<Integer>spawnChildPrivate(
                                factoryContext -> (childMail, childContext) -> {
                                    try {
                                        var heap = childContext.actorOwnedMemory().orElseThrow();
                                        var nativeRegion = heap.newExplicitNativeI64Region(1);
                                        loan.set(nativeRegion.borrow(nativeRegion.allocate(55)));
                                        region.set(nativeRegion);
                                    } catch (Throwable error) {
                                        failure.compareAndSet(null, error);
                                    } finally {
                                        loanAllocated.countDown();
                                    }
                                });
                        var sourceRef = context.runtime().<Integer>spawnChildPrivate(
                                factoryContext -> (childMail, childContext) -> {
                                    childContext.runtime().startActorTask(
                                            new OresScheduler.CancellationAwareTask<Integer>() {
                                                @Override public boolean cleanupInProgress() {
                                                    return cleaning.get();
                                                }

                                                @Override public void cancellationRequested(
                                                        OresScheduler.TaskCancelledException reason) { }

                                                @Override public OresScheduler.Step<Integer> resume(
                                                        OresScheduler.Resume resumed) {
                                                    if (resumed.initial()) {
                                                        sourceArmed.countDown();
                                                        return OresScheduler.await(upstream);
                                                    }
                                                    if (cleaning.compareAndSet(false, true)) {
                                                        assertInstanceOf(
                                                                OresScheduler.TaskCancelledException.class,
                                                                resumed.failure());
                                                        cleanupRuns.incrementAndGet();
                                                        cleanupEntered.countDown();
                                                        return OresScheduler.await(cleanup);
                                                    }
                                                    assertNull(resumed.failure());
                                                    return OresScheduler.done(73);
                                                }
                                            });
                                });
                        nativeChild.set(nativeRef.ref());
                        cleanupChild.set(sourceRef.ref());
                        nativeRef.send(1);
                        sourceRef.send(2);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        spawned.countDown();
                    }
                });

                parent.send(1);
                assertTrue(spawned.await(5, TimeUnit.SECONDS));
                if (failure.get() != null) fail(failure.get());
                assertTrue(loanAllocated.await(5, TimeUnit.SECONDS));
                assertTrue(sourceArmed.await(5, TimeUnit.SECONDS));
                if (failure.get() != null) fail(failure.get());
                assertEquals(parent.id(), nativeChild.get().parentId().orElseThrow());
                assertEquals(parent.id(), cleanupChild.get().parentId().orElseThrow());

                CompletableFuture<Void> stopping = CompletableFuture.runAsync(parent::stop);
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS),
                        "parent STOP must cascade to suspended child cleanup");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!region.get().closed() && System.nanoTime() < until) {
                    Thread.sleep(2);
                }
                assertTrue(region.get().closed(), "native sibling must retire on parent STOP");
                assertEquals(1, cleanupRuns.get());
                assertEquals(Long.BYTES, runtime.privateMemoryBytes());
                assertFalse(parent.get_done().isDone());
                assertFalse(nativeChild.get().get_done().isDone());
                assertFalse(cleanupChild.get().get_done().isDone());

                if (cleanupFirst) {
                    assertTrue(cleanup.completeFromRuntime(7));
                    cleanupChild.get().get_done().get(5, TimeUnit.SECONDS);
                    assertFalse(parent.get_done().isDone(),
                            "parent cannot finalize while native sibling still has a loan");
                    assertFalse(nativeChild.get().get_done().isDone());
                    loan.get().close();
                } else {
                    loan.get().close();
                    nativeChild.get().get_done().get(5, TimeUnit.SECONDS);
                    assertFalse(parent.get_done().isDone(),
                            "parent cannot finalize while source sibling cleanup is pending");
                    assertFalse(cleanupChild.get().get_done().isDone());
                    assertTrue(cleanup.completeFromRuntime(7));
                }

                stopping.get(5, TimeUnit.SECONDS);
                parent.get_done().get(5, TimeUnit.SECONDS);
                nativeChild.get().get_done().get(5, TimeUnit.SECONDS);
                cleanupChild.get().get_done().get(5, TimeUnit.SECONDS);
                assertEquals(1, cleanupRuns.get());
                assertEquals(0L, runtime.privateMemoryBytes());
            }
        }
    }
}
