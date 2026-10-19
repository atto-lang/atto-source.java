package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Guards against parent cancellation hooks reentering an unsealed child. */
@Timeout(15)
final class OresTaskScopeCancellationOrderingTest {
    @SuppressWarnings("unchecked")
    private static void ownSyntheticTask(
            OresTaskScope scope, OresFuture<Integer> completion)
            throws Exception {
        Field tasks = OresTaskScope.class.getDeclaredField("tasks");
        tasks.setAccessible(true);
        ((Set<OresTask<?>>) tasks.get(scope)).add(
                new OresTask<>(completion, OresFuture.completed(null)));
    }

    @Test
    void parentCancellationHookCannotCreateTaskInGrandchild() throws Exception {
        try (var runtime = new AsyncRuntime();
             var root = new OresTaskScope(runtime)) {
            var child = root.childScope();
            var grandchild = child.childScope();
            var sealed = new AtomicBoolean();
            var future = new OresFuture<Integer>(() -> {
                assertTrue(child.isCancelRequested());
                assertTrue(grandchild.isCancelRequested());
                assertThrows(RejectedExecutionException.class,
                        () -> grandchild.submit(() -> 1));
                sealed.set(true);
            });
            ownSyntheticTask(root, future);
            assertTrue(root.cancel());
            assertTrue(future.isCancelled());
            assertTrue(sealed.get(), "descendant admission must close before parent hook");
            assertFalse(root.cancel());
        }
    }

    @Test
    void parentFutureListenersSeeAllDescendantProducersRevoked()
            throws Exception {
        try (var runtime = new AsyncRuntime();
             var root = new OresTaskScope(runtime)) {
            var child = root.childScope();
            var descendant = child.childScope();
            var parentFuture = new OresFuture<Integer>();
            var childFuture = new OresFuture<Integer>();
            var observer = new AtomicBoolean();
            ownSyntheticTask(root, parentFuture);
            ownSyntheticTask(descendant, childFuture);
            parentFuture.whenCompleteRuntime((ignored, failure) -> {
                assertTrue(descendant.isCancelRequested());
                assertTrue(childFuture.isCancelled(),
                        "descendant revocation must precede parent subscriber");
                observer.set(true);
            });
            assertTrue(root.cancel());
            assertTrue(childFuture.isCancelled());
            assertTrue(observer.get());
        }
    }
}
