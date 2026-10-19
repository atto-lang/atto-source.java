package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An inline Executor may rethrow a fatal guest Error after its afterTurn
 * callback has retired the same dispatch. That must not decrement twice.
 */
final class SchedulerInlineRetirementTest {
    @Test
    void fatalGuestTurnCannotRetireOneInlineDispatchTwice() throws Exception {
        AtomicReference<OresScheduler> owningScheduler = new AtomicReference<>();
        AtomicReference<Object> observedRunner = new AtomicReference<>();
        AtomicInteger cleanups = new AtomicInteger();

        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "test-inline-retirement", 1, command -> {
                    try {
                        Field field = OresScheduler.class.getDeclaredField("tasks");
                        field.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        Set<Object> tasks = (Set<Object>) field.get(owningScheduler.get());
                        observedRunner.set(tasks.iterator().next());
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    command.run();
                });
        owningScheduler.set(scheduler);

        try {
            OresFuture<Void> outcome = scheduler.startWithPhysicalRetirement(
                    resume -> {
                        throw new LinkageError("test-only fatal guest turn");
                    }, cleanups::incrementAndGet);

            assertNotNull(observedRunner.get());
            assertEquals(1, cleanups.get(), "cleanup must run exactly once");
            assertTrue(outcome.isCompletedExceptionally());
            Field field = observedRunner.get().getClass()
                    .getDeclaredField("inFlightDispatches");
            field.setAccessible(true);
            AtomicInteger inFlight = (AtomicInteger) field.get(observedRunner.get());
            assertEquals(0, inFlight.get(),
                    "inline error propagated after physical exit must not double-retire");
        } finally {
            scheduler.close();
        }
    }

    @Test
    void rejectedDispatchRetiresReservationExactlyOnce() throws Exception {
        AtomicReference<OresScheduler> schedulerRef = new AtomicReference<>();
        AtomicReference<Object> runnerRef = new AtomicReference<>();
        AtomicInteger cleanups = new AtomicInteger();

        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "test-rejected-retirement", 1, command -> {
                    try {
                        Field field = OresScheduler.class.getDeclaredField("tasks");
                        field.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        Set<Object> tasks = (Set<Object>) field.get(schedulerRef.get());
                        runnerRef.set(tasks.iterator().next());
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    throw new java.util.concurrent.RejectedExecutionException("test rejection");
                });
        schedulerRef.set(scheduler);

        try {
            OresFuture<Void> outcome = scheduler.startWithPhysicalRetirement(
                    resume -> OresScheduler.done(null), cleanups::incrementAndGet);
            assertNotNull(runnerRef.get());
            assertEquals(1, cleanups.get());
            assertTrue(outcome.isCompletedExceptionally());
            Field counter = runnerRef.get().getClass()
                    .getDeclaredField("inFlightDispatches");
            counter.setAccessible(true);
            assertEquals(0, ((AtomicInteger) counter.get(runnerRef.get())).get());
        } finally {
            scheduler.close();
        }
    }
}
