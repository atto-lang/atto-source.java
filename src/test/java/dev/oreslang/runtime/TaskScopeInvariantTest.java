package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class TaskScopeInvariantTest {
    @Test
    void joiningCannotBeCancelledWhileChildMayStillBeExecuting() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        OresFuture<Void> joined = scope.close();
        assertFalse(joined.cancel(true));
        assertFalse(joined.isDone());
        child.attach(OresFuture.completed(null));
        assertFalse(joined.isDone());
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }

    @Test
    void duplicateAttachmentDoesNotCountTwice() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        child.attach(OresFuture.completed(null));
        assertThrows(IllegalStateException.class, () -> child.attach(OresFuture.completed(null)));
        OresFuture<Void> joined = scope.close();
        assertFalse(joined.isDone());
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }

    @Test
    void attachedChildCannotBeReportedAsFailedAdmission() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        child.attach(OresFuture.completed(null));
        OresFuture<Void> joined = scope.close();
        assertThrows(IllegalStateException.class,
                () -> child.failedAdmission(new IllegalArgumentException("invalid")));
        assertFalse(joined.isDone());
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }
    @Test
    void concurrentTerminalAndQuiescenceRetireExactlyOnce() throws Exception {
        for (int i = 0; i < 64; i++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration child = scope.reserve();
            OresFuture<Integer> future = new OresFuture<>();
            child.attach(future);
            OresFuture<Void> joined = scope.close();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Thread terminal = Thread.ofVirtual().start(() -> {
                awaitGate(start);
                future.completeFromRuntime(42);
            });
            Thread quiescence = Thread.ofVirtual().start(() -> {
                awaitGate(start);
                child.quiesced();
            });
            start.countDown();
            terminal.join();
            quiescence.join();
            assertNull(joined.get(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, scope::reserve);
            child.quiesced();
            assertFalse(joined.isCancelled());
        }
    }

    private static void awaitGate(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent retirement barrier timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void selfReferentialBarrierFutureCannotBeAttachedAsChild() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        OresFuture<Void> joined = scope.close();
        assertThrows(IllegalArgumentException.class, () -> child.attach(joined));
        assertFalse(joined.isDone());
        child.failedAdmission(new IllegalStateException("bad child dependency"));
        assertTrue(joined.isCompletedExceptionally());
    }

}
