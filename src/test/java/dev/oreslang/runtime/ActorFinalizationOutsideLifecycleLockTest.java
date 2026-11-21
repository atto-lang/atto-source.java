package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorFinalizationOutsideLifecycleLockTest {
    @Test
    void blockingExitHookDoesNotRetainLifecycleMonitor() throws Exception {
        CountDownLatch exitEntered = new CountDownLatch(1);
        CountDownLatch releaseExit = new CountDownLatch(1);
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        AtomicReference<Throwable> probeFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 8))) {
            runtime.setActorExitHook(ignored -> {
                exitEntered.countDown();
                awaitUninterruptibly(releaseExit);
            });

            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(2, TimeUnit.SECONDS);

            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });

            assertTrue(exitEntered.await(2, TimeUnit.SECONDS));

            Thread probe = Thread.ofPlatform().start(() -> {
                try {
                    // This runtime wake-up acquires the actor's lifecycleLock.
                    // Finalization is already scheduled, so it is otherwise a
                    // no-op; completion proves the blocking hook is outside the
                    // monitor rather than merely bypassed by DONE observation.
                    runtime.onNativeIoLoanRetired(actor.id());
                } catch (Throwable failure) {
                    probeFailure.set(failure);
                }
            });
            probe.join(1000);

            assertFalse(probe.isAlive(),
                    "exit hook must not retain actor lifecycleLock");
            assertNull(probeFailure.get());
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "DONE must remain pending until physical retirement completes");

            releaseExit.countDown();
            stopper.join(2000);

            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get());
            assertTrue(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
            assertDoesNotThrow(() -> actor.getDone().get(2, TimeUnit.SECONDS));
        } finally {
            releaseExit.countDown();
        }
    }

    @Test
    void lateCancellationCannotRewriteWinningGracefulStop() throws Exception {
        CountDownLatch exitEntered = new CountDownLatch(1);
        CountDownLatch releaseExit = new CountDownLatch(1);
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 8))) {
            runtime.setActorExitHook(ignored -> {
                exitEntered.countDown();
                awaitUninterruptibly(releaseExit);
            });

            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(2, TimeUnit.SECONDS);

            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });

            assertTrue(exitEntered.await(2, TimeUnit.SECONDS));

            // STOP already owns terminal admission. A later cancellation may
            // observe the actor mid-retirement, but it must not replace the
            // winning graceful terminal cause.
            actor.cancel();
            assertTrue(actor.failure().isEmpty());

            releaseExit.countDown();
            stopper.join(2000);

            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get());
            assertTrue(actor.failure().isEmpty());
            assertDoesNotThrow(() -> actor.getDone().get(2, TimeUnit.SECONDS));
        } finally {
            releaseExit.countDown();
        }
    }

    @Test
    void blockingDoneSubscriberDoesNotBlockHostStopOrTerminationObservation() throws Exception {
        CountDownLatch doneCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseDoneCallback = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 8))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            actor.getReady().get(2, TimeUnit.SECONDS);

            actor.getDone().whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) callbackFailure.compareAndSet(null, failure);
                doneCallbackEntered.countDown();
                awaitUninterruptibly(releaseDoneCallback);
            });

            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });

            assertTrue(doneCallbackEntered.await(2, TimeUnit.SECONDS));

            // OresFuture terminal state is committed before synchronous runtime
            // subscribers drain. Host STOP and awaitTermination observe that
            // actor-owned receipt rather than waiting on subscriber behavior.
            stopper.join(1000);
            assertFalse(stopper.isAlive(),
                    "blocking DONE subscriber must not block host STOP");
            assertNull(stopFailure.get());
            assertTrue(actor.awaitTermination(0, TimeUnit.NANOSECONDS));

            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    runtime.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            });
            closer.join(300);
            assertTrue(closer.isAlive(),
                    "runtime close must wait for the full retirement callback drain");

            releaseDoneCallback.countDown();
            closer.join(2000);

            assertFalse(closer.isAlive());
            assertNull(closeFailure.get());
            assertDoesNotThrow(() -> actor.getDone().get(2, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
        } finally {
            releaseDoneCallback.countDown();
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
