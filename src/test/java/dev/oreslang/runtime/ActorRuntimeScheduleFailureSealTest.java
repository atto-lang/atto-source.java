package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deliberate TRUSTED-HOST reflection fault injection on the physical actor
 * dispatcher and receipt ledger. This is not a guest-accessible capability.
 */
@Timeout(30)
final class ActorRuntimeScheduleFailureSealTest {
    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    @Test
    void teardownSnapshotOomeAfterAcceptedDispatchFailureRetainsSealedReceipt()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var executed = new AtomicInteger();
            var actor = runtime.<Integer>spawnShared(
                    () -> (mail, context) -> executed.incrementAndGet());
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actors = (Map<?, ?>) field(ActorRuntime.class, "actors")
                    .get(runtime);
            Object cell = actors.get(actor.id());
            assertNotNull(cell);
            Field receipts = field(cell.getClass(), "pendingPhysicalReceipts");
            var original = (HashMap<Long, OresFuture<Void>>) receipts.get(cell);
            var snapshotFailure = new OutOfMemoryError(
                    "injected receipt-ledger snapshot allocation error");
            var faulted = new HashMap<Long, OresFuture<Void>>() {
                @Override public Collection<OresFuture<Void>> values() {
                    throw snapshotFailure;
                }
            };

            Field dispatcher = field(ActorRuntime.class, "sharedDispatcher");
            var previous = (ExecutorService) dispatcher.get(runtime);
            var dispatchFailure = new IllegalStateException(
                    "injected physical dispatch submission error");
            var rejecting = new AbstractExecutorService() {
                private volatile boolean shutdown;
                @Override public void execute(Runnable task) {
                    throw dispatchFailure;
                }
                @Override public void shutdown() { shutdown = true; }
                @Override public List<Runnable> shutdownNow() {
                    shutdown = true;
                    return List.of();
                }
                @Override public boolean isShutdown() { return shutdown; }
                @Override public boolean isTerminated() { return shutdown; }
                @Override public boolean awaitTermination(
                        long timeout, TimeUnit unit) { return shutdown; }
            };

            // Save exact receipt ownership even when both dispatcher and
            // teardown fail. Restoring the original ledger models a trusted
            // supervisor retry once the faulted allocator is repaired.
            OresFuture<Void> acceptedReceipt;
            try {
                receipts.set(cell, faulted);
                dispatcher.set(runtime, rejecting);
                Throwable reported = assertThrows(Throwable.class,
                        () -> runtime.sendWithPhysicalReceipt(actor, 1));
                assertSame(dispatchFailure, reported);
                assertEquals(1, reported.getSuppressed().length);
                assertSame(snapshotFailure, reported.getSuppressed()[0]);

                acceptedReceipt = faulted.get(0L);
                assertNotNull(acceptedReceipt,
                        "physical receipt was admitted before dispatch failure");
                assertFalse(acceptedReceipt.isDone(),
                        "failed teardown cannot forge terminal acknowledgment");
                assertThrows(ActorRuntime.ActorSealedException.class,
                        () -> actor.send(2),
                        "failed emergency retirement must permanently seal ingress");
                assertEquals(0, executed.get());
            } finally {
                dispatcher.set(runtime, previous);
                original.putAll(faulted);
                receipts.set(cell, original);
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> acceptedReceipt.get(5, TimeUnit.SECONDS),
                    "repaired supervisor teardown must fail the accepted receipt");
            assertEquals(0, executed.get());
        }
    }
}
