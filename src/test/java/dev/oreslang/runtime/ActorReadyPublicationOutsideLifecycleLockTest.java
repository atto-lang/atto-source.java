package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorReadyPublicationOutsideLifecycleLockTest {
    @Test
    void blockingReadySubscriberCannotHoldActorLifecycleMonitor() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 8))) {
            var actor = runtime.<String>spawnShared(() -> {
                factoryEntered.countDown();
                awaitUninterruptibly(releaseFactory);
                return (message, context) -> { };
            });

            OresFuture<Void> ready = actor.getReady();
            assertTrue(factoryEntered.await(2, TimeUnit.SECONDS));

            ready.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) callbackFailure.compareAndSet(null, failure);
                callbackEntered.countDown();
                awaitUninterruptibly(releaseCallback);
            });

            releaseFactory.countDown();
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });

            // A host stop cannot physically finalize until the actor turn exits,
            // so while the READY subscriber is intentionally blocked it should
            // hit the bounded stop deadline. The important invariant is that it
            // can acquire lifecycleLock and reach that deadline instead of being
            // blocked behind the subscriber itself.
            stopper.join(1500);
            assertFalse(stopper.isAlive(),
                    "READY subscriber must not hold actor lifecycleLock");
            assertInstanceOf(IllegalStateException.class, stopFailure.get());
            assertTrue(stopFailure.get().getMessage().contains(
                    "did not finalize within the stop deadline"));
            assertFalse(actor.isAlive());

            releaseCallback.countDown();

            assertDoesNotThrow(() -> actor.getDone().get(2, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
        } finally {
            releaseFactory.countDown();
            releaseCallback.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
