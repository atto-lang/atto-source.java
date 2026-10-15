package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorUnaryReplySlotTest {
    @Test
    void acceptsExactlyOneCorrelatedReplyAndRejectsWrongGeneration() {
        var owner = ActorRuntime.ActorId.create();
        var id = UUID.randomUUID();
        var slot = new ActorUnaryReplySlot<Integer>(owner, 10, id);
        assertThrows(SecurityException.class,
                () -> slot.reply(owner, 9, id, 1));
        assertThrows(SecurityException.class,
                () -> slot.reply(ActorRuntime.ActorId.create(), 10, id, 1));
        assertThrows(SecurityException.class,
                () -> slot.reply(owner, 10, UUID.randomUUID(), 1));
        assertEquals(ActorUnaryReplySlot.State.PENDING, slot.state());
        assertTrue(slot.reply(owner, 10, id, 42));
        assertFalse(slot.reply(owner, 10, id, 43));
        assertFalse(slot.cancel());
        assertEquals(42, slot.result().toCompletableFuture().join());
        assertEquals(ActorUnaryReplySlot.State.REPLIED, slot.state());
    }

    @Test
    void cancellationFencesLateReplyAndReject() {
        var owner = ActorRuntime.ActorId.create();
        var id = UUID.randomUUID();
        var slot = new ActorUnaryReplySlot<String>(owner, 1, id);
        assertTrue(slot.cancel());
        assertFalse(slot.cancel());
        assertFalse(slot.reply(owner, 1, id, "late"));
        assertFalse(slot.reject(owner, 1, id, new IllegalStateException("late")));
        assertEquals(ActorUnaryReplySlot.State.CANCELLED, slot.state());
        assertThrows(CompletionException.class,
                () -> slot.result().toCompletableFuture().join());
    }

    @Test
    void rejectionIsSingleTerminalEvent() {
        var owner = ActorRuntime.ActorId.create();
        var id = UUID.randomUUID();
        var slot = new ActorUnaryReplySlot<Integer>(owner, 7, id);
        assertTrue(slot.reject(owner, 7, id, new IllegalStateException("denied")));
        assertFalse(slot.reply(owner, 7, id, 3));
        assertEquals(ActorUnaryReplySlot.State.REJECTED, slot.state());
        assertThrows(CompletionException.class,
                () -> slot.result().toCompletableFuture().join());
    }

    @Test
    void simultaneousReplyAndCancellationNeverDoubleComplete() throws Exception {
        for (int round = 0; round < 64; ++round) {
            var owner = ActorRuntime.ActorId.create();
            var id = UUID.randomUUID();
            var slot = new ActorUnaryReplySlot<Integer>(owner, 0, id);
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var completed = new AtomicInteger();
            Thread actor = new Thread(() -> {
                ready.countDown();
                try {
                    if (!go.await(2, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                    if (slot.reply(owner, 0, id, 1)) completed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread supervisor = new Thread(() -> {
                ready.countDown();
                try {
                    if (!go.await(2, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                    if (slot.cancel()) completed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            actor.start();
            supervisor.start();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            actor.join(2000);
            supervisor.join(2000);
            assertFalse(actor.isAlive());
            assertFalse(supervisor.isAlive());
            assertEquals(1, completed.get());
            assertNotEquals(ActorUnaryReplySlot.State.PENDING, slot.state());
        }
    }
}
