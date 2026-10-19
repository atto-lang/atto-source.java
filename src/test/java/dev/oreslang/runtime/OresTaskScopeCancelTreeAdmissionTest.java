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
}
