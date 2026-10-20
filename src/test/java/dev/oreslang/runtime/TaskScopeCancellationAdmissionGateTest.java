package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A cancellation snapshot must seal descendant admission before revocation. */
final class TaskScopeCancellationAdmissionGateTest {
    @Test
    void liveParentMaySpawnDuringOrdinaryCloseButNotAfterCancellation() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        parent.beganExecution();
        OresFuture<Void> parentFuture = new OresFuture<>();
        parent.attach(parentFuture);

        OresFuture<Void> joined = scope.close();
        TaskScope.Registration child = scope.reserve(parent);
        child.beganExecution();
        OresFuture<Void> childFuture = new OresFuture<>();
        child.attach(childFuture);

        assertSame(joined, scope.cancelAndDrain());
        assertThrows(IllegalStateException.class, () -> scope.reserve(parent));
        assertThrows(IllegalStateException.class, () -> scope.reserve(child));
        assertThrows(IllegalStateException.class, scope::reserve);

        assertTrue(parentFuture.isCancelled());
        assertTrue(childFuture.isCancelled());
        assertFalse(joined.isDone(), "Future cancellation is not physical quiescence");
        parent.quiesced();
        assertFalse(joined.isDone());
        child.quiesced();
        assertThrows(ExecutionException.class, () -> joined.get(5, TimeUnit.SECONDS));
    }

    @Test
    void noDescendantIsAdmittedOnceCancellationHasBeenPublished() throws Exception {
        for (int attempt = 0; attempt < 128; attempt++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration parent = scope.reserve();
            parent.beganExecution();
            OresFuture<Void> parentFuture = new OresFuture<>();
            parent.attach(parentFuture);
            OresFuture<Void> joined = scope.cancelAndDrain();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            Thread[] attempts = new Thread[8];
            for (int i = 0; i < attempts.length; i++) {
                attempts[i] = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        assertThrows(IllegalStateException.class,
                                () -> scope.reserve(parent));
                        rejected.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
            }
            start.countDown();
            for (Thread worker : attempts) worker.join();
            assertEquals(attempts.length, rejected.get());
            assertTrue(parentFuture.isCancelled());
            assertFalse(joined.isDone());
            parent.quiesced();
            assertThrows(ExecutionException.class,
                    () -> joined.get(5, TimeUnit.SECONDS));
        }
    }
}
