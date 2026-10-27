package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASYNC-01 / ASYNC-02: closing a scope must never allow one failing
 * structured-resource hook to strand siblings or forge a successful drain.
 */
@Timeout(15)
final class TaskScopeCloseHookFailureTest {

    @Test
    void throwingCloseHookCannotPreventSiblingCloseOrPrematurelyDrain() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration failing = scope.reserve();
        TaskScope.Registration sibling = scope.reserve();
        AtomicBoolean siblingHookRan = new AtomicBoolean();
        IllegalStateException fault = new IllegalStateException("close-hook-fault");

        failing.onScopeClose(() -> { throw fault; });
        sibling.onScopeClose(() -> {
            siblingHookRan.set(true);
            sibling.retireUnstarted();
        });

        OresFuture<Void> joined = assertDoesNotThrow(scope::close);

        assertTrue(siblingHookRan.get(), "all admitted close hooks must be signalled");
        assertFalse(joined.isDone(),
                "a failed close hook does not prove the failing child's retirement");

        failing.retireUnstarted();

        assertTrue(joined.isDone());
        RuntimeException observed = assertThrows(RuntimeException.class, joined::join);
        assertTrue(observed == fault || observed.getCause() == fault
                || observed.toString().contains("close-hook-fault"));
    }

    @Test
    void lastChildRetiringInsideHookCannotPublishSuccessBeforeHookThrows() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        IllegalStateException fault = new IllegalStateException("late-close-fault");

        child.onScopeClose(() -> {
            child.retireUnstarted();
            throw fault;
        });

        OresFuture<Void> joined = assertDoesNotThrow(scope::close);

        assertTrue(joined.isDone());
        RuntimeException observed = assertThrows(RuntimeException.class, joined::join);
        assertTrue(observed == fault || observed.getCause() == fault
                || observed.toString().contains("late-close-fault"),
                "scope must not publish success before its last close hook returns");
    }

    @Test
    void throwingCloseHookDoesNotPreventCancellationOfAttachedSibling() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        OresFuture<Void> backing = new OresFuture<>();
        child.attach(backing);

        TaskScope.Registration failing = scope.reserve();
        failing.onScopeClose(() -> {
            throw new IllegalStateException("fault-on-cancel");
        });

        OresFuture<Void> joined = assertDoesNotThrow(scope::cancelAndDrain);

        assertTrue(backing.isCancelled(), "cancellation must still revoke sibling Future");
        assertFalse(joined.isDone(), "cancelled Future is not quiescence");
        child.quiesced();
        assertFalse(joined.isDone(), "failing resource must still retire");
        failing.retireUnstarted();

        assertTrue(joined.isDone());
        assertThrows(RuntimeException.class, joined::join);
    }

    @Test
    void concurrentCloseCallsMustNotPublishWhileAnyCloseHookIsRunning()
            throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        child.onScopeClose(() -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("hook timed out");
                }
                child.retireUnstarted();
                throw new IllegalStateException("concurrent-hook-fault");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("hook interrupted", interrupted);
            }
        });

        Thread closer = Thread.ofVirtual().start(() -> {
            try {
                scope.close();
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> joined = scope.close();
            assertFalse(joined.isDone(),
                    "second close cannot publish before first close hook finishes");
        } finally {
            release.countDown();
            closer.join(5000);
        }

        assertFalse(closer.isAlive());
        assertNull(unexpected.get());
        assertTrue(scope.drained().isDone());
        assertThrows(RuntimeException.class, scope.drained()::join);
    }
}
