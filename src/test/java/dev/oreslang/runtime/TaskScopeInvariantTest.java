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

    @Test
    void closedScopeRejectsRootAndWrongScopePermits() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope other = new TaskScope();
        TaskScope.Registration owner = scope.reserve();
        owner.beganExecution();
        TaskScope.Registration wrong = other.reserve();
        OresFuture<Void> joined = scope.close();
        assertThrows(IllegalStateException.class, scope::reserve);
        assertThrows(IllegalStateException.class, () -> scope.reserve(wrong));
        TaskScope.Registration child = scope.reserve(owner);
        owner.attach(OresFuture.completed(null));
        owner.quiesced();
        assertThrows(IllegalStateException.class, () -> scope.reserve(owner));
        child.attach(OresFuture.completed(null));
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
        wrong.failedAdmission(new IllegalStateException("cleanup"));
        assertThrows(IllegalStateException.class, () -> scope.reserve(child));
    }

    @Test
    void cancelledParentCannotAdmitDescendantBeforeCarrierQuiescence() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        OresFuture<Integer> running = new OresFuture<>();
        parent.attach(running);
        OresFuture<Void> joined = scope.close();
        assertTrue(running.cancel(false));
        assertFalse(joined.isDone(), "carrier has not retired");
        assertThrows(IllegalStateException.class, () -> scope.reserve(parent));
        parent.quiesced();
        assertTrue(joined.isDone());
        assertTrue(joined.isCompletedExceptionally());
    }

    @Test
    void closingScopeAdmissionRacesLastChildRetirement() throws Exception {
        for (int i = 0; i < 128; i++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration parent = scope.reserve();
            parent.beganExecution();
            parent.attach(OresFuture.completed(null));
            OresFuture<Void> joined = scope.close();
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<TaskScope.Registration> admitted =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread entrant = Thread.ofVirtual().start(() -> {
                awaitGate(gate);
                try {
                    admitted.set(scope.reserve(parent));
                } catch (IllegalStateException expected) {
                    // Parent has already physically retired; no admission.
                }
            });
            Thread retire = Thread.ofVirtual().start(() -> {
                awaitGate(gate);
                parent.quiesced();
            });
            gate.countDown();
            entrant.join();
            retire.join();
            TaskScope.Registration child = admitted.get();
            if (child != null) {
                assertFalse(joined.isDone());
                child.attach(OresFuture.completed(null));
                child.quiesced();
            }
            assertNull(joined.get(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, scope::reserve);
        }
    }

    @Test
    void cancelledParentDoesNotReleaseStillRunningDescendant() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        TaskScope.Registration descendant = scope.reserve(parent);
        OresFuture<Void> parentFuture = new OresFuture<>();
        OresFuture<Void> descendantFuture = new OresFuture<>();
        parent.attach(parentFuture);
        descendant.attach(descendantFuture);
        OresFuture<Void> joined = scope.close();

        assertTrue(parentFuture.cancel(false));
        parent.quiesced();
        assertFalse(joined.isDone(), "descendant still holds a scope ticket");
        assertThrows(IllegalStateException.class, () -> scope.reserve(parent));

        descendantFuture.completeFromRuntime(null);
        assertFalse(joined.isDone(), "terminal descendant has not quiesced");
        descendant.quiesced();
        assertTrue(joined.isCompletedExceptionally());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> joined.get(2, TimeUnit.SECONDS));
    }

    @Test
    void reservedButNeverScheduledParentCannotSpawnDuringClose() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration unstarted = scope.reserve();
        OresFuture<Void> joined = scope.close();
        assertThrows(IllegalStateException.class, () -> scope.reserve(unstarted));
        assertFalse(joined.isDone());
        unstarted.failedAdmission(new java.util.concurrent.RejectedExecutionException(
                "scheduler refused admission"));
        assertTrue(joined.isCompletedExceptionally());
        assertThrows(IllegalStateException.class, unstarted::beganExecution);
    }

    @Test
    void dispatchedParentMayAdmitBeforeItsFutureAttachment() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        parent.beganExecution();
        OresFuture<Void> joined = scope.close();
        TaskScope.Registration grandchild = scope.reserve(parent);
        parent.attach(OresFuture.completed(null));
        parent.quiesced();
        assertFalse(joined.isDone());
        grandchild.beganExecution();
        grandchild.attach(OresFuture.completed(null));
        grandchild.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }


    @Test
    void cancellationRevokesEveryChildBeforeBlockingSubscriberPublication()
            throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration first = scope.reserve();
        TaskScope.Registration second = scope.reserve();
        java.util.concurrent.atomic.AtomicBoolean firstRevoked =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean secondRevoked =
                new java.util.concurrent.atomic.AtomicBoolean();
        OresFuture<Void> firstFuture = new OresFuture<>(
                () -> firstRevoked.set(true));
        OresFuture<Void> secondFuture = new OresFuture<>(
                () -> secondRevoked.set(true));
        first.attach(firstFuture);
        second.attach(secondFuture);

        java.util.concurrent.CountDownLatch subscriberEntered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSubscriber =
                new java.util.concurrent.CountDownLatch(1);
        firstFuture.whenCompleteRuntime((value, failure) -> {
            subscriberEntered.countDown();
            awaitGate(releaseSubscriber);
        });

        OresFuture<Void> joined = scope.cancelAndDrain();
        assertTrue(firstRevoked.get(), "first child must be revoked before publication");
        assertTrue(secondRevoked.get(), "sibling revocation cannot wait for first subscriber");
        assertTrue(subscriberEntered.await(2, TimeUnit.SECONDS));

        first.quiesced();
        second.quiesced();
        assertTrue(joined.isDone(),
                "scope lifetime must not wait for ordinary Future subscribers");
        assertTrue(joined.isCompletedExceptionally());
        releaseSubscriber.countDown();
    }

    @Test
    void terminalAccountingPrecedesLaterBlockingSubscriber() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        OresFuture<Void> future = new OresFuture<>();
        child.attach(future);
        OresFuture<Void> joined = scope.close();

        java.util.concurrent.CountDownLatch subscriberEntered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSubscriber =
                new java.util.concurrent.CountDownLatch(1);
        future.whenCompleteRuntime((value, failure) -> {
            subscriberEntered.countDown();
            awaitGate(releaseSubscriber);
        });

        Thread completion = Thread.ofVirtual().start(
                () -> future.completeFromRuntime(null));
        assertTrue(subscriberEntered.await(2, TimeUnit.SECONDS));
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS),
                "structured lifetime accounting must precede later subscribers");
        releaseSubscriber.countDown();
        completion.join();
    }


}
