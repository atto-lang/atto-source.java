package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for cancellation-hook reentrancy across a structured scope tree.
 *
 * <p>The test installs host-only synthetic owned Task shells directly into
 * a compiler scope. This avoids scheduling nondeterminism: the real
 * OresFuture.cancelDeferredNotification path calls its registered revoker
 * synchronously, so the child-admission gate must already be closed.</p>
 */
@Timeout(15)
final class OresTaskScopeCancelTreeAdmissionTest {
    @SuppressWarnings("unchecked")
    private static void ownSyntheticTask(
            OresTaskScope scope, OresFuture<Integer> completion)
            throws Exception {
        Class<?> slotType = Class.forName(
                "dev.oreslang.runtime.OresTaskScope$OwnershipSlot");
        Constructor<?> constructor = slotType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object slot = constructor.newInstance();
        Field taskField = slotType.getDeclaredField("task");
        taskField.setAccessible(true);
        taskField.set(slot, new OresTask<>(
                completion, OresFuture.completed(null)));
        Field slotsField = OresTaskScope.class.getDeclaredField("tasks");
        slotsField.setAccessible(true);
        ((Set<Object>) slotsField.get(scope)).add(slot);
    }

    @Test
    void parentCancellationHookCannotAdmitIntoPreviouslyOpenGrandchild()
            throws Exception {
        try (var root = OresTaskScope.compilerOwned()) {
            var child = root.childScope();
            var grandchild = child.childScope();
            var rejectedChild = new AtomicBoolean();
            var rejectedGrandchild = new AtomicBoolean();
            var parent = new OresFuture<Integer>(() -> {
                assertTrue(child.isCancelRequested(),
                        "child must seal BEFORE any parent revocation hook");
                assertTrue(grandchild.isCancelRequested(),
                        "grandchild must seal BEFORE any parent revocation hook");
                assertThrows(RejectedExecutionException.class,
                        child::childScope);
                rejectedChild.set(true);
                assertThrows(RejectedExecutionException.class,
                        grandchild::childScope);
                rejectedGrandchild.set(true);
            });
            ownSyntheticTask(root, parent);
            assertTrue(root.cancel());
            assertTrue(parent.isCancelled());
            assertTrue(rejectedChild.get());
            assertTrue(rejectedGrandchild.get());
            assertTrue(grandchild.isCancelRequested());
            assertFalse(root.cancel(), "a second cancellation cannot reclaim ownership");
        }
    }

    @Test
    void synchronousParentSubscriberRunsAfterAllDescendantTaskRevocations()
            throws Exception {
        try (var root = OresTaskScope.compilerOwned()) {
            var child = root.childScope();
            var grandchild = child.childScope();
            var parent = new OresFuture<Integer>();
            var descendant = new OresFuture<Integer>();
            var listenerObservedFullRevocation = new AtomicBoolean();
            ownSyntheticTask(root, parent);
            ownSyntheticTask(grandchild, descendant);

            parent.whenCompleteRuntime((ignored, failure) -> {
                assertTrue(child.isCancelRequested());
                assertTrue(grandchild.isCancelRequested());
                assertTrue(descendant.isCancelled(),
                        "all descendant producers must revoke before subscriber delivery");
                listenerObservedFullRevocation.set(true);
            });

            assertTrue(root.cancel());
            assertTrue(parent.isCancelled());
            assertTrue(descendant.isCancelled());
            assertTrue(listenerObservedFullRevocation.get());
        }
    }

    @Test
    void preflightOomeLeavesAllDescendantAdmissionOpenAndRetryable()
            throws Exception {
        try (var root = OresTaskScope.compilerOwned()) {
            var child = root.childScope();
            var grandchild = child.childScope();
            Field tasksField = OresTaskScope.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            Object original = tasksField.get(grandchild);
            tasksField.set(grandchild, new java.util.HashSet<Object>() {
                @Override public java.util.Iterator<Object> iterator() {
                    throw new OutOfMemoryError("injected third-level cancellation snapshot");
                }
            });
            try {
                assertThrows(OutOfMemoryError.class, root::cancel);
                assertFalse(root.isCancelRequested(),
                        "preflight allocation failure cannot publish a partial root seal");
                assertFalse(child.isCancelRequested());
                assertFalse(grandchild.isCancelRequested());
                assertNotNull(grandchild.childScope(),
                        "a failed cancellation plan must preserve admission for retry");
            } finally {
                tasksField.set(grandchild, original);
            }
            assertTrue(root.cancel(), "a repaired snapshot can retry cancellation");
            assertTrue(root.isCancelRequested());
            assertTrue(child.isCancelRequested());
            assertTrue(grandchild.isCancelRequested());
            assertThrows(RejectedExecutionException.class, grandchild::childScope);
        }
    }

    @Test
    void concurrentGrandchildCreationWaitsForWholeTreeSnapshotCommit()
            throws Exception {
        try (var root = OresTaskScope.compilerOwned()) {
            var child = root.childScope();
            var grandchild = child.childScope();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var allowSeal = new java.util.concurrent.CountDownLatch(1);
            Field field = OresTaskScope.class.getDeclaredField("tasks");
            field.setAccessible(true);
            Object original = field.get(grandchild);
            field.set(grandchild, new java.util.HashSet<Object>() {
                @Override public java.util.Iterator<Object> iterator() {
                    entered.countDown();
                    try {
                        if (!allowSeal.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("test preflight gate was not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return super.iterator();
                }
            });
            var cancelFailure =
                    new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var admissionFailure =
                    new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var admissionReturned = new java.util.concurrent.atomic.AtomicBoolean();
            Thread canceller = Thread.ofPlatform().start(() -> {
                try {
                    root.cancel();
                } catch (Throwable failure) {
                    cancelFailure.set(failure);
                }
            });
            Thread contender = null;
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                contender = Thread.ofPlatform().start(() -> {
                    try {
                        grandchild.childScope();
                        admissionReturned.set(true);
                    } catch (Throwable failure) {
                        admissionFailure.set(failure);
                    }
                });
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (contender.getState() != Thread.State.BLOCKED
                        && !admissionReturned.get()
                        && admissionFailure.get() == null
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(Thread.State.BLOCKED, contender.getState(),
                        "descendant admission must wait on the tree-wide preflight gate");
            } finally {
                allowSeal.countDown();
                canceller.join(7000);
                if (contender != null) contender.join(7000);
                field.set(grandchild, original);
            }
            assertFalse(canceller.isAlive());
            if (contender != null) assertFalse(contender.isAlive());
            assertNull(cancelFailure.get());
            assertFalse(admissionReturned.get(),
                    "a descendant must not be admitted after root seal commits");
            assertInstanceOf(RejectedExecutionException.class, admissionFailure.get());
            assertTrue(root.isCancelRequested());
            assertTrue(grandchild.isCancelRequested());
        }
    }

    @Test
    void closeSnapshotOomeCannotPublishUnjoinableJoiningState()
            throws Exception {
        var root = OresTaskScope.compilerOwned();
        Field field = OresTaskScope.class.getDeclaredField("tasks");
        field.setAccessible(true);
        Object original = field.get(root);
        var iterations = new java.util.concurrent.atomic.AtomicInteger();
        field.set(root, new java.util.HashSet<Object>() {
            @Override public java.util.Iterator<Object> iterator() {
                if (iterations.incrementAndGet() == 2)
                    throw new OutOfMemoryError("injected close-frame preparation failure");
                return super.iterator();
            }
        });
        try {
            assertThrows(OutOfMemoryError.class, root::close,
                    "the second snapshot fails before the join claim");
            assertTrue(root.isCancelRequested());
        } finally {
            field.set(root, original);
        }
        // This must not join a permanently uncounted fullyJoined latch.
        root.close();
        assertTrue(root.physicallyQuiescent());
    }


    @Test
    void closeCannotJoinUnsealedTreeWhenCancellationPreflightFails()
            throws Exception {
        var root = OresTaskScope.compilerOwned();
        var child = root.childScope();
        var grandchild = child.childScope();
        Field field = OresTaskScope.class.getDeclaredField("tasks");
        field.setAccessible(true);
        Object original = field.get(grandchild);
        field.set(grandchild, new java.util.HashSet<Object>() {
            @Override public java.util.Iterator<Object> iterator() {
                throw new OutOfMemoryError("injected preflight error during close");
            }
        });
        try {
            assertThrows(OutOfMemoryError.class, root::close);
            assertFalse(root.isCancelRequested());
            assertFalse(grandchild.isCancelRequested());
            assertNotNull(grandchild.childScope(),
                    "failed close must not claim sealed lifecycle ownership");
        } finally {
            field.set(grandchild, original);
        }
        root.close();
        assertTrue(root.isCancelRequested());
        assertTrue(grandchild.isCancelRequested());
    }

}
