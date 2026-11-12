package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Executable fast-path admission tests, not host-only mock TCP tests. */
@Timeout(20)
final class ActorTrySendFastPathTest {
    private static IsolatePolicy oneSlot() {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(base.capabilities(), base.maxHeapBytes(),
                1, base.maxWallTime(), base.adversarial());
    }

    @Test
    void successfulTrySendPreservesOrderingAndReturnsNoFuture() throws Exception {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneSlot(),
                    () -> (message, context) -> seen.add(message));
            assertTrue(ref.trySend(1));
            ref.drain().get(5, TimeUnit.SECONDS);
            assertTrue(ref.trySend(2));
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), seen);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void fullUserQueueReturnsFalseWithoutCreatingHiddenWaiters() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneSlot(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("stalled actor never released");
                        }
                        seen.add(message);
                    });
            assertTrue(ref.trySend(1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(ref.trySend(2));
            for (int i = 0; i < 1000; i++)
                assertFalse(ref.trySend(3), "full USER mailbox must remain fail-fast");
            OresFuture<Void> async = ref.sendAsync(3);
            assertFalse(async.isDone(), "async fallback alone may register pending producer");
            release.countDown();
            async.get(5, TimeUnit.SECONDS);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 3), seen);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidOrRetiredSendIsNotMisrepresentedAsMailboxFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Object>spawnShared(oneSlot(), () -> (msg, context) -> {
                if (Integer.valueOf(1).equals(msg)) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("stalled actor never released");
                }
            });
            assertThrows(IllegalArgumentException.class, () -> ref.trySend(new Object()),
                    "unsafe host graph may not be passed into an actor");
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            assertFalse(ref.trySend(new Object()),
                    "full mailbox should reject before traversing an unsafe payload");
            release.countDown();
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
            assertThrows(RuntimeException.class, () -> ref.trySend(4),
                    "terminated actor is an error, not a mailbox-full false");
        } finally {
            release.countDown();
        }
    }
}
