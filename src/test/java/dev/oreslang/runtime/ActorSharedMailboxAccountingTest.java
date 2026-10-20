package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial accounting probe: reflection is test-only, never a guest API.
 * Proves that an over-release cannot silently debit the shared runtime budget.
 */
@Timeout(15)
final class ActorSharedMailboxAccountingTest {

    @Test
    @SuppressWarnings("unchecked")
    void teardownDrainRetainsExactDequeuedEnvelopeAcrossRefundFailure()
            throws Exception {
        var releaseTurn = new java.util.concurrent.CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var turns = new java.util.concurrent.atomic.AtomicInteger();

            ActorRuntime.ActorRef<Object> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        if (turns.incrementAndGet() == 1) {
                            entered.countDown();
                            if (!releaseTurn.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "active mailbox turn release timeout");
                            }
                        }
                    });
            actor.ready().get(5, TimeUnit.SECONDS);

            actor.send(new java.util.ArrayList<>(java.util.List.of(1, 2, 3)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send(new java.util.ArrayList<>(java.util.List.of(4, 5, 6)));

            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            Field accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            actorsField.setAccessible(true);
            accountingField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);

            Field queuedBytesField =
                    cell.getClass().getDeclaredField("sharedMailboxBytes");
            Field pendingEnvelopeField =
                    cell.getClass().getDeclaredField("drainPendingEnvelope");
            Field pendingSlotField =
                    cell.getClass().getDeclaredField("drainPendingSlotReleased");
            Field currentEnvelopeField =
                    cell.getClass().getDeclaredField("currentEnvelopeOwner");
            queuedBytesField.setAccessible(true);
            pendingEnvelopeField.setAccessible(true);
            pendingSlotField.setAccessible(true);
            currentEnvelopeField.setAccessible(true);

            AtomicLong queuedBytes = (AtomicLong) queuedBytesField.get(cell);
            AtomicLong accounting = (AtomicLong) accountingField.get(runtime);
            long reserved = accounting.get();
            assertTrue(reserved > 0L);
            assertEquals(reserved, queuedBytes.get(),
                    "active and queued user envelopes must both remain charged");

            accounting.set(0L);
            var failure = assertThrows(IllegalStateException.class, actor::stop);
            assertTrue(failure.getMessage().contains(
                    "shared actor memory accounting underflow"));

            assertNotNull(pendingEnvelopeField.get(cell),
                    "teardown must retain the exact dequeued queued envelope");
            assertTrue(pendingSlotField.getBoolean(cell),
                    "mailbox slot release completed before the refund failure");
            assertNotNull(currentEnvelopeField.get(cell),
                    "the still-running first turn keeps its own exact envelope");
            assertEquals(reserved, queuedBytes.get(),
                    "failed refund must not consume either transport debit");

            accounting.set(reserved);
            actor.stop();

            long afterQueuedRetry = accounting.get();
            assertTrue(afterQueuedRetry > 0L && afterQueuedRetry < reserved,
                    "retry must refund only the queued envelope while the active turn remains live");
            assertNull(pendingEnvelopeField.get(cell));

            releaseTurn.countDown();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, queuedBytes.get());
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertNull(currentEnvelopeField.get(cell));
        } finally {
            releaseTurn.countDown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void suspendedReceiveRetainsTransportDebitUntilCompletionContinuation()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var suspended = new java.util.concurrent.CountDownLatch(1);
            var resume = new OresFuture<Void>();

            ActorRuntime.ActorRef<Object> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        runtime.suspendCurrentUserMailboxUntil(resume);
                        suspended.countDown();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);

            actor.send(new java.util.ArrayList<>(
                    java.util.List.of(10, 20, 30, 40)));
            assertTrue(suspended.await(5, TimeUnit.SECONDS));

            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell);

            Field suspendedOwnerField =
                    cell.getClass().getDeclaredField("suspendedUserEnvelopeOwner");
            Field queuedBytesField =
                    cell.getClass().getDeclaredField("sharedMailboxBytes");
            Field activeTurnsField =
                    cell.getClass().getDeclaredField("activeTurns");
            suspendedOwnerField.setAccessible(true);
            queuedBytesField.setAccessible(true);
            activeTurnsField.setAccessible(true);

            AtomicLong queuedBytes = (AtomicLong) queuedBytesField.get(cell);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((suspendedOwnerField.get(cell) == null
                    || activeTurnsField.getInt(cell) != 0)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertNotNull(suspendedOwnerField.get(cell),
                    "suspended receive must retain the exact transport envelope");
            assertEquals(0, activeTurnsField.getInt(cell),
                    "physical turn should unwind while logical receive stays suspended");
            assertTrue(queuedBytes.get() > 0L);
            assertEquals(queuedBytes.get(), runtime.sharedMemoryBytes(),
                    "transport memory remains charged for the complete suspended lifetime");

            OresFuture<Void> drained = actor.drain();
            assertFalse(drained.isDone());

            resume.completeFromRuntime(null);
            drained.get(5, TimeUnit.SECONDS);

            assertNull(suspendedOwnerField.get(cell));
            assertEquals(0L, queuedBytes.get());
            assertEquals(0L, runtime.sharedMemoryBytes());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oversizedAndDuplicateReleaseLeaveBothAccountingLayersUnchanged() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);

            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell, "actor cell should remain alive during accounting probe");

            Field queuedBytesField = cell.getClass().getDeclaredField("sharedMailboxBytes");
            queuedBytesField.setAccessible(true);
            AtomicLong queuedBytes = (AtomicLong) queuedBytesField.get(cell);

            Method reserve = cell.getClass().getDeclaredMethod("reserveSharedMailbox", long.class);
            Method release = cell.getClass().getDeclaredMethod("releaseSharedMailbox", long.class);
            reserve.setAccessible(true);
            release.setAccessible(true);

            long baseline = runtime.sharedMemoryBytes();
            assertEquals(0L, queuedBytes.get());
            reserve.invoke(cell, 64L);
            assertEquals(64L, queuedBytes.get());
            assertEquals(baseline + 64L, runtime.sharedMemoryBytes());

            InvocationTargetException oversized = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(cell, 65L));
            assertInstanceOf(IllegalStateException.class, oversized.getCause());
            assertTrue(oversized.getCause().getMessage().contains(
                    "shared actor mailbox memory accounting underflow"));
            assertEquals(64L, queuedBytes.get());
            assertEquals(baseline + 64L, runtime.sharedMemoryBytes());

            release.invoke(cell, 64L);
            assertEquals(0L, queuedBytes.get());
            assertEquals(baseline, runtime.sharedMemoryBytes());

            InvocationTargetException duplicate = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(cell, 64L));
            assertInstanceOf(IllegalStateException.class, duplicate.getCause());
            assertEquals(0L, queuedBytes.get());
            assertEquals(baseline, runtime.sharedMemoryBytes());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
        }
    }
}
