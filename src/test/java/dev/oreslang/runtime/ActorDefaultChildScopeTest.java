package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The implicit child lifetime scope is not a new supervisor or event-bus
 * group. The parent joins descendant finalization, not merely a STOP receipt.
 */
@Timeout(15)
final class ActorDefaultChildScopeTest {
    private record Gate(CountDownLatch entered, CountDownLatch release) {
        static Gate create() {
            return new Gate(new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    // Actor factories admitted from guest turns must remain stateless. This
    // host-only test rendezvous is intentionally not a guest shared pointer.
    private static final AtomicReference<Gate> CHILD_GATE = new AtomicReference<>();

    private record FenceCase(CountDownLatch entered, CountDownLatch release,
                             CountDownLatch checked, AtomicReference<Throwable> rejected) {
        static FenceCase create() {
            return new FenceCase(new CountDownLatch(1), new CountDownLatch(1),
                    new CountDownLatch(1), new AtomicReference<>());
        }
    }
    private static final AtomicReference<FenceCase> FENCE_CASE = new AtomicReference<>();

    @Test
    void inheritedManagedGroupSpawnMustHonorShutdownFence() throws Exception {
        FenceCase gate = FenceCase.create();
        FENCE_CASE.set(gate);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> parent = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    factory -> (message, turn) -> {
                        if (!"nested".equals(message)) return;
                        FenceCase active = FENCE_CASE.get();
                        active.entered().countDown();
                        try {
                            if (!active.release().await(5, TimeUnit.SECONDS)) return;
                            turn.runtime().spawnPrivate(
                                    ignored -> (childMessage, childContext) -> { });
                        } catch (IllegalStateException expected) {
                            active.rejected().set(expected);
                        } finally {
                            active.checked().countDown();
                        }
                    });
            parent.getReady().get(5, TimeUnit.SECONDS);
            try {
                parent.send("nested");
                assertTrue(gate.entered().await(5, TimeUnit.SECONDS));
                // The actor is still executing. A previously admitted actor
                // turn must not bypass a newly committed supervisor fence.
                group.fenceWorkerAdmissions();
                gate.release().countDown();
                assertTrue(gate.checked().await(5, TimeUnit.SECONDS));
                assertNotNull(gate.rejected().get(),
                        "nested spawn ignored inherited supervisor admission fence");
                assertEquals(1, group.workerCount(),
                        "fenced nested spawn must not leak an owned worker");
                assertEquals(0, group.memberCount(),
                        "implicit lifetime ownership is not event-bus membership");
            } finally {
                gate.release().countDown();
                group.close();
            }
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            gate.release().countDown();
            FENCE_CASE.compareAndSet(gate, null);
        }
    }

    @Test
    void parentDoneWaitsForChildCarrierExitWithoutCreatingSupervisorGroup() throws Exception {
        Gate gate = Gate.create();
        CHILD_GATE.set(gate);
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(
                    () -> (message, context) -> {
                        if (!"spawn".equals(message)) return;
                        ActorRuntime.ActorRef<String> child = context.runtime().spawnPrivate(
                                ignored -> (childMessage, childContext) -> {
                                    if (!"hold".equals(childMessage)) return;
                                    Gate active = CHILD_GATE.get();
                                    active.entered().countDown();
                                    assertTrue(active.release().await(5, TimeUnit.SECONDS),
                                            "test child release never arrived");
                                });
                        childRef.set(child);
                        child.send("hold");
                    });
            try {
                parent.send("spawn");
                assertTrue(gate.entered().await(5, TimeUnit.SECONDS),
                        "child never obtained its carrier");
                ActorRuntime.ActorRef<String> child = childRef.get();
                assertNotNull(child);
                assertEquals(parent.id(), child.parentId().orElseThrow());
                assertEquals(0, runtime.actorGroupCount(),
                        "ordinary spawn must not create an event-bus ActorGroup");
                parent.stop();
                assertFalse(parent.done().isDone(),
                        "parent DONE must join the child while its carrier is still executing");
                assertFalse(child.done().isDone(),
                        "requesting child cancellation must not fabricate physical DONE");
            } finally {
                gate.release().countDown();
            }
            ActorRuntime.ActorRef<String> child = childRef.get();
            assertNotNull(child);
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    child.failure().orElseThrow());
            assertThrows(ExecutionException.class,
                    () -> child.done().get(5, TimeUnit.SECONDS));
            parent.done().get(5, TimeUnit.SECONDS);
            assertEquals(0, runtime.actorGroupCount());
        } finally {
            gate.release().countDown();
            CHILD_GATE.compareAndSet(gate, null);
        }
    }

    @Test
    void failedOrdinaryChildReportsDoneFailureWithoutImplicitRestart() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            CountDownLatch published = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(
                    () -> (message, context) -> {
                        if (!"spawn".equals(message)) return;
                        ActorRuntime.ActorRef<String> child = context.runtime().spawnPrivate(
                                ignored -> (childMessage, childContext) -> {
                                    throw new IllegalStateException("child failure marker");
                                });
                        childRef.set(child);
                        published.countDown();
                        child.send("fail");
                    });
            parent.send("spawn");
            assertTrue(published.await(5, TimeUnit.SECONDS));
            ActorRuntime.ActorRef<String> child = childRef.get();
            assertNotNull(child);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> child.done().get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(child.failure().orElseThrow(), failure.getCause());
            assertTrue(parent.isAlive(), "default child failure must not kill parent");
            assertTrue(parent.childIds().isEmpty(),
                    "failed child must retire its lifetime edge without re-spawn");
            assertEquals(0, runtime.actorGroupCount());
            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            parent.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopWinsAgainstInFlightSpawnWithoutCreatingOrphan() throws Exception {
        Gate gate = Gate.create();
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(
                    () -> (message, context) -> {
                        gate.entered().countDown();
                        if (!gate.release().await(5, TimeUnit.SECONDS)) return;
                        try {
                            context.runtime().spawnPrivate(
                                    ignored -> (value, childContext) -> { });
                        } catch (CancellationException rejected) {
                            admissionFailure.set(rejected);
                        }
                    });
            try {
                parent.send("attempt");
                assertTrue(gate.entered().await(5, TimeUnit.SECONDS));
                parent.stop();
                gate.release().countDown();
                assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
                assertNotNull(admissionFailure.get(),
                        "spawn after parent STOP must fail closed");
                assertTrue(parent.failure().isEmpty(),
                        "caught child admission rejection must not poison parent");
                assertEquals(0, runtime.actorGroupCount());
            } finally {
                gate.release().countDown();
            }
        }
    }
}
