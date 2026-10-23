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
