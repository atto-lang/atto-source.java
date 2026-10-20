package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A failed completion-drain hook cannot leave sibling Tasks running. */
final class TaskScopeCancellationNotifierFailureTest {
    @Test
    void failingNotificationDrainDoesNotSkipAnySibling() throws Exception {
        for (int repetition = 0; repetition < 64; repetition++) {
            TaskScope scope = new TaskScope();
            List<TaskScope.Registration> registrations = new ArrayList<>();
            List<OresFuture<Void>> futures = new ArrayList<>();

            for (int child = 0; child < 4; child++) {
                TaskScope.Registration ticket = scope.reserve();
                OresFuture<Void> future = new OresFuture<>(
                        () -> true,
                        () -> { },
                        () -> { throw new IllegalStateException("injected drain error"); });
                ticket.attach(future);
                registrations.add(ticket);
                futures.add(future);
            }

            OresFuture<Void> barrier = assertDoesNotThrow(scope::cancelAndDrain);
            for (OresFuture<Void> future : futures) {
                assertTrue(future.isCancelled(),
                        "a failing notification hook must not skip unrelated children");
            }
            assertFalse(barrier.isDone(),
                    "cancellation is not physical carrier quiescence");
            assertFalse(barrier.cancel(true), "barrier is non-cancellable");

            for (TaskScope.Registration ticket : registrations) ticket.quiesced();
            assertTrue(barrier.isCompletedExceptionally());
            assertThrows(ExecutionException.class,
                    () -> barrier.get(2, TimeUnit.SECONDS));
            assertSame(barrier, scope.cancelAndDrain());
            assertThrows(IllegalStateException.class, scope::reserve);
        }
    }

    @Test
    void successfulSiblingStillGetsCancelledAfterFaultySibling() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration bad = scope.reserve();
        TaskScope.Registration good = scope.reserve();
        OresFuture<Void> broken = new OresFuture<>(
                () -> true, () -> { },
                () -> { throw new IllegalStateException("injected"); });
        OresFuture<Void> ordinary = new OresFuture<>();
        bad.attach(broken);
        good.attach(ordinary);
        OresFuture<Void> joined = assertDoesNotThrow(scope::cancelAndDrain);
        assertTrue(broken.isCancelled());
        assertTrue(ordinary.isCancelled());
        assertFalse(joined.isDone());
        bad.quiesced();
        assertFalse(joined.isDone());
        good.quiesced();
        assertTrue(joined.isCompletedExceptionally());
    }
}
