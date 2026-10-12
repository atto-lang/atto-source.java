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
 * The children have capture-free, stateless BehaviorFactory implementations.
 * Test-only static coordination NEVER becomes a guest actor capability.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class ActorTreePhysicalShutdownBarrierTest {
    private static final AtomicReference<TreeState> ACTIVE = new AtomicReference<>();

    private static final class TreeState {
        final CountDownLatch spawned = new CountDownLatch(1);
        final CountDownLatch loanAllocated = new CountDownLatch(1);
        final CountDownLatch sourceArmed = new CountDownLatch(1);
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final AtomicInteger cleanupRuns = new AtomicInteger();
        final AtomicBoolean cleaning = new AtomicBoolean();
        final OresFuture<Integer> upstream = new OresFuture<>();
        final OresFuture<Integer> cleanup = new OresFuture<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        final AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        final AtomicReference<ActorRuntime.ActorRef<Integer>> nativeChild = new AtomicReference<>();
        final AtomicReference<ActorRuntime.ActorRef<Integer>> cleanupChild = new AtomicReference<>();
    }

    @Test
    void parentDoneWaitsForSiblingCleanupAndNativeLoanInEitherReleaseOrder() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");
        for (boolean cleanupFirst : new boolean[] {true, false}) {
            TreeState state = new TreeState();
            ACTIVE.set(state);
            try (ActorRuntime runtime = new ActorRuntime()) {
                var parent = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                    try {
                        var nativeRef = context.runtime().<Integer>spawnChildPrivate(
                                ignored -> (childMail, childContext) -> {
                                    TreeState s = ACTIVE.get();
                                    try {
                                        var heap = childContext.actorOwnedMemory().orElseThrow();
                                        var region = heap.newExplicitNativeI64Region(1);
                                        s.loan.set(region.borrow(region.allocate(55)));
                                        s.region.set(region);
                                    } catch (Throwable error) {
                                        s.failure.compareAndSet(null, error);
                                    } finally {
                                        s.loanAllocated.countDown();
                                    }
                                });
                        var sourceRef = context.runtime().<Integer>spawnChildPrivate(
                                ignored -> (childMail, childContext) -> {
                                    TreeState s = ACTIVE.get();
                                    childContext.runtime().startActorTask(
                                        new OresScheduler.CancellationAwareTask<Integer>() {
                                            @Override public boolean cleanupInProgress() {
                                                return s.cleaning.get();
                                            }

                                            @Override public void cancellationRequested(
                                                    OresScheduler.TaskCancelledException reason) { }

                                            @Override public OresScheduler.Step<Integer> resume(
                                                    OresScheduler.Resume resumed) {
                                                if (resumed.initial()) {
                                                    s.sourceArmed.countDown();
                                                    return OresScheduler.await(s.upstream);
                                                }
                                                if (s.cleaning.compareAndSet(false, true)) {
                                                    assertInstanceOf(
                                                        OresScheduler.TaskCancelledException.class,
                                                        resumed.failure());
                                                    s.cleanupRuns.incrementAndGet();
                                                    s.cleanupEntered.countDown();
                                                    return OresScheduler.await(s.cleanup);
                                                }
                                                assertNull(resumed.failure());
                                                return OresScheduler.done(73);
                                            }
                                        });
                                });
                        state.nativeChild.set(nativeRef.ref());
                        state.cleanupChild.set(sourceRef.ref());
                        nativeRef.send(1);
                        sourceRef.send(2);
                    } catch (Throwable error) {
                        state.failure.compareAndSet(null, error);
                    } finally {
                        state.spawned.countDown();
                    }
                });

                parent.send(1);
                assertTrue(state.spawned.await(5, TimeUnit.SECONDS));
                if (state.failure.get() != null) fail(state.failure.get());
                assertTrue(state.loanAllocated.await(5, TimeUnit.SECONDS));
                assertTrue(state.sourceArmed.await(5, TimeUnit.SECONDS));
                if (state.failure.get() != null) fail(state.failure.get());
                assertEquals(parent.id(), state.nativeChild.get().parentId().orElseThrow());
                assertEquals(parent.id(), state.cleanupChild.get().parentId().orElseThrow());

                CompletableFuture<Void> stopping = CompletableFuture.runAsync(parent::stop);
                assertTrue(state.cleanupEntered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!state.region.get().closed() && System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
                assertTrue(state.region.get().closed(), "native child must retire on parent STOP");
                assertEquals(1, state.cleanupRuns.get());
                assertTrue(runtime.privateMemoryBytes() >= Long.BYTES);
                assertFalse(parent.get_done().isDone());
                assertFalse(state.nativeChild.get().get_done().isDone());
                assertFalse(state.cleanupChild.get().get_done().isDone());

                if (cleanupFirst) {
                    assertTrue(state.cleanup.completeFromRuntime(7));
                    state.cleanupChild.get().get_done().get(5, TimeUnit.SECONDS);
                    assertFalse(parent.get_done().isDone(),
                            "parent cannot finalize with a pinned native sibling");
                    assertFalse(state.nativeChild.get().get_done().isDone());
                    state.loan.get().close();
                } else {
                    state.loan.get().close();
                    state.nativeChild.get().get_done().get(5, TimeUnit.SECONDS);
                    assertFalse(parent.get_done().isDone(),
                            "parent cannot finalize while sibling cleanup is pending");
                    assertFalse(state.cleanupChild.get().get_done().isDone());
                    assertTrue(state.cleanup.completeFromRuntime(7));
                }

                stopping.get(5, TimeUnit.SECONDS);
                parent.get_done().get(5, TimeUnit.SECONDS);
                state.nativeChild.get().get_done().get(5, TimeUnit.SECONDS);
                state.cleanupChild.get().get_done().get(5, TimeUnit.SECONDS);
                assertEquals(1, state.cleanupRuns.get());
                assertEquals(0L, runtime.privateMemoryBytes());
            } finally {
                ACTIVE.compareAndSet(state, null);
            }
        }
    }
}
