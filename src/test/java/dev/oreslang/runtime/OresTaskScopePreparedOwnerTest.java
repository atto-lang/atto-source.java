package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pre-reserved ownership shell may be snapshotted and logically revoked
 * before AsyncRuntime/OresScheduler has attached its completion Future.
 * The scope must replay cancellation after successful admission without
 * claiming a physical join before the task actually exits.
 */
@Timeout(30)
final class OresTaskScopePreparedOwnerTest {
    @Test
    void unboundShellDefersCancellationUntilItsFutureIsAttached() {
        AtomicInteger published = new AtomicInteger();
        // Bounded host-only stress of the exact prebind/attach sequence.
        for (int trial = 0; trial < 10_000; trial++) {
            OresFuture<Void> physicalExit = new OresFuture<>();
            OresTask<Integer> shell = OresTask.<Integer>preAdmitted(physicalExit);
            assertNull(shell.cancelDeferredNotification(),
                    "prebound cancellation must not dereference a missing Future");
            assertFalse(shell.isQuiescent());

            OresFuture<Integer> completion = new OresFuture<>();
            shell.attachCompletion(completion);
            Runnable notify = shell.cancelDeferredNotification();
            assertNotNull(notify, "revocation must be replayable after binding");
            assertTrue(shell.isCancelled());
            notify.run();
            assertTrue(completion.isCancelled());
            assertFalse(shell.isQuiescent(),
                    "logical cancellation is not physical guest exit");

            physicalExit.completeFromRuntime(null);
            assertTrue(shell.isQuiescent());
            published.incrementAndGet();
        }
        assertEquals(10_000, published.get());
    }

    @Test
    void unusedPreparedHostTaskCanRetireWithoutEnteringExecutor() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            AtomicInteger invoked = new AtomicInteger();
            AsyncRuntime.PreparedTask<Integer> prepared =
                    runtime.prepareTask(invoked::incrementAndGet);
            OresTask<Integer> owner = prepared.owner();
            assertFalse(owner.isQuiescent());
            assertNull(owner.cancelDeferredNotification());
            prepared.rejectUnadmitted();
            assertTrue(owner.isQuiescent());
            assertEquals(0, invoked.get());
            assertThrows(IllegalStateException.class, prepared::start);
        }
    }

    @Test
    void simultaneousPrepareStartAndRejectHaveExactlyOneWinner() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            for (int trial = 0; trial < 128; trial++) {
                AsyncRuntime.PreparedTask<Integer> prepared =
                        runtime.prepareTask(() -> 23);
                java.util.concurrent.CountDownLatch begin =
                        new java.util.concurrent.CountDownLatch(1);
                AtomicInteger succeeded = new AtomicInteger();
                AtomicInteger rejected = new AtomicInteger();
                java.util.concurrent.atomic.AtomicReference<Throwable> unexpected =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Thread starter = Thread.ofVirtual().start(() -> {
                    try {
                        begin.await();
                        prepared.start();
                        succeeded.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                Thread canceller = Thread.ofVirtual().start(() -> {
                    try {
                        begin.await();
                        prepared.rejectUnadmitted();
                        succeeded.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                begin.countDown();
                starter.join(5_000);
                canceller.join(5_000);
                assertFalse(starter.isAlive());
                assertFalse(canceller.isAlive());
                assertNull(unexpected.get());
                assertEquals(1, succeeded.get(), "one first claim on trial " + trial);
                assertEquals(1, rejected.get(), "one losing claim on trial " + trial);
                assertNull(AsyncRuntime.await(prepared.owner().getDone()));
                assertTrue(prepared.owner().isQuiescent());
            }
        }
    }
}
