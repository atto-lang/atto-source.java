package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorAsyncSendAdmissionRaceTest {
    private static IsolatePolicy oneMessagePolicy() {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                1,
                base.maxWallTime(),
                base.adversarial());
    }

    @Test
    void wholeCohortQuotaRejectsBeforeTraversingAnotherPayload() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Object>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (Integer.valueOf(1).equals(message)) {
                            firstEntered.countDown();
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("held actor turn timed out");
                            }
                        }
                    });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);

            List<OresFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                OresFuture<Void> admitted = ref.sendAsync(1000 + i);
                assertFalse(admitted.isDone());
                pending.add(admitted);
            }

            // A plain mutable host object is not Sendable. At saturation we
            // must reject the 257th producer at the admission boundary before
            // walking/validating that payload graph.
            OresFuture<Void> overflow = ref.sendAsync(new Object());
            assertTrue(overflow.isDone());
            Exception failure = assertThrows(Exception.class,
                    () -> overflow.get(1, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorSendBackpressureException.class,
                    failure.getCause());

            for (OresFuture<Void> waiter : pending) {
                assertTrue(waiter.cancel(false));
            }
            releaseFirst.countDown();
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void concurrentCancellationHasExactlyOneWithdrawalWinner() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch cancelStart = new CountDownLatch(1);
        AtomicReference<Boolean> firstCancel = new AtomicReference<>();
        AtomicReference<Boolean> secondCancel = new AtomicReference<>();
        AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("held actor turn timed out");
                            }
                        }
                    });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> pending = ref.sendAsync(3);
            assertFalse(pending.isDone());

            Thread one = new Thread(() -> {
                try {
                    if (!cancelStart.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancel start timed out");
                    }
                    firstCancel.set(pending.cancel(false));
                } catch (Throwable failure) {
                    cancellationFailure.compareAndSet(null, failure);
                }
            }, "async-send-cancel-one");
            Thread two = new Thread(() -> {
                try {
                    if (!cancelStart.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancel start timed out");
                    }
                    secondCancel.set(pending.cancel(false));
                } catch (Throwable failure) {
                    cancellationFailure.compareAndSet(null, failure);
                }
            }, "async-send-cancel-two");

            one.start();
            two.start();
            cancelStart.countDown();
            one.join(5_000);
            two.join(5_000);

            assertFalse(one.isAlive());
            assertFalse(two.isAlive());
            assertNull(cancellationFailure.get());
            assertNotNull(firstCancel.get());
            assertNotNull(secondCancel.get());
            int winners = (firstCancel.get() ? 1 : 0)
                    + (secondCancel.get() ? 1 : 0);
            assertEquals(1, winners);
            assertTrue(pending.isCancelled());

            releaseFirst.countDown();
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }
}
