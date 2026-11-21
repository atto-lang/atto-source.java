package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-control regression; cancelled Future != physical task quiescence.
 * The executable source guest/compiler integration remains a separate gate.
 */
final class TaskScopeCancellationAdmissionHardeningTest {
    @Test
    void brokenCancellationAdmissionCannotSkipHealthySiblingOrForgeDone() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration faultyTicket = scope.reserve();
        TaskScope.Registration healthyTicket = scope.reserve();
        OresFuture<Void> broken = new OresFuture<>(
                () -> { throw new IllegalStateException("injected admission error"); },
                () -> { });
        OresFuture<Void> healthy = new OresFuture<>();
        faultyTicket.attach(broken);
        healthyTicket.attach(healthy);

        OresFuture<Void> joined = assertDoesNotThrow(scope::cancelAndDrain);
        assertTrue(healthy.isCancelled(), "other siblings must be cancelled");
        assertFalse(broken.isDone(), "failure to claim does not create terminal authority");
        assertFalse(joined.isDone(), "failed cancellation cannot fake physical DONE");
        assertThrows(IllegalStateException.class, scope::reserve);

        assertTrue(broken.failFromRuntime(new IllegalStateException("real producer failed")));
        faultyTicket.quiesced();
        assertFalse(joined.isDone());
        healthyTicket.quiesced();
        assertThrows(ExecutionException.class, () -> joined.get(2, TimeUnit.SECONDS));
        assertSame(joined, scope.cancelAndDrain());
    }

    @Test
    void failedVirtualPublisherAdmissionNeverRunsBlockedListenerInline() throws Exception {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch allowListenerExit = new CountDownLatch(1);
        // An injected failure emulates Thread.ofVirtual().start failure without
        // disrupting the JVM and proves the emergency path stays off carrier.
        TaskScope scope = new TaskScope(task -> {
            throw new IllegalStateException("virtual publisher unavailable");
        });
        TaskScope.Registration slowTicket = scope.reserve();
        TaskScope.Registration fastTicket = scope.reserve();
        OresFuture<Void> slow = new OresFuture<>(
                () -> true, () -> { }, () -> {
                    listenerEntered.countDown();
                    try {
                        if (!allowListenerExit.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("blocked callback timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        OresFuture<Void> fast = new OresFuture<>();
        slowTicket.attach(slow);
        fastTicket.attach(fast);

        try {
            // Previously the unavailable-virtual-thread fallback invoked
            // publish() on this exact thread and blocked here indefinitely.
            OresFuture<Void> joined = assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2), scope::cancelAndDrain);
            assertTrue(slow.isCancelled());
            assertTrue(fast.isCancelled());
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            assertFalse(joined.isDone());
            fastTicket.quiesced();
            assertFalse(joined.isDone(), "slow carrier still owns a ticket");
            slowTicket.quiesced();
            assertThrows(ExecutionException.class,
                    () -> joined.get(2, TimeUnit.SECONDS));
        } finally {
            allowListenerExit.countDown();
        }
    }
}
