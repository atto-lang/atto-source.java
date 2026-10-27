package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Host-only Future->Task runtime tests, NOT executable Oreslang syntax. */
@Timeout(15)
final class TaskFutureAdaptersTest {
    @Test
    void successUsesOneTrackedAdapterAndNeverReplaysProducer() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            TaskHandle<Integer> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            assertEquals(1, source.pendingRuntimeWaiterCount());
            assertFalse(adapter.result().isDone());
            assertTrue(source.completeFromRuntime(42));
            assertEquals(42, adapter.result().get(3, TimeUnit.SECONDS));
            var done = adapter.getDone().get(3, TimeUnit.SECONDS);
            assertEquals(42, assertInstanceOf(TaskHandle.Success.class, done).value());
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void alreadyCompletedSourceDoesNotNeedAnotherProducer() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<String> source = OresFuture.completed("ok");
            TaskHandle<String> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            assertEquals("ok", adapter.result().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    adapter.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void adapterCancellationDetachesOnlyItsWaiter() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            TaskHandle<Integer> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            assertTrue(adapter.cancel("observer no longer needed"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    adapter.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(source.isDone());
            assertTrue(source.completeFromRuntime(100));
            assertEquals(100, source.join());
        }
    }

    @Test
    void authenticatedSourceCancellationCancelsDependentNotProducerTwice()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            TaskHandle<Integer> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            assertTrue(source.cancel(false));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    adapter.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertTrue(source.isCancelled());
        }
    }

    @Test
    void userThrownCancellationNamedErrorRemainsFailure() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            TaskHandle<Integer> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            CancellationException fake = new CancellationException("user exception");
            assertTrue(source.failFromRuntime(fake));
            var done = adapter.getDone().get(3, TimeUnit.SECONDS);
            assertSame(fake, assertInstanceOf(TaskHandle.Failed.class, done).failure());
            assertFalse(source.isCancelled());
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void sourceFailurePreservesIdentityAndReleasesRegistration() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<String> source = new OresFuture<>();
            TaskHandle<String> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            IllegalArgumentException cause = new IllegalArgumentException("expected");
            assertTrue(source.failFromRuntime(cause));
            var done = adapter.getDone().get(3, TimeUnit.SECONDS);
            assertSame(cause, assertInstanceOf(TaskHandle.Failed.class, done).failure());
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void waitingAdapterDoesNotParkOnlySchedulerCarrier() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            TaskHandle<Integer> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
            OresFuture<Integer> independent = scheduler.start(
                    resume -> OresScheduler.done(15));
            assertEquals(15, independent.get(3, TimeUnit.SECONDS));
            assertTrue(source.completeFromRuntime(27));
            assertEquals(27, adapter.result().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    adapter.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationAndProducerSettlementDoNotLeakWaiters() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int i = 0; i < 128; i++) {
                OresFuture<Integer> source = new OresFuture<>();
                TaskHandle<Integer> adapter = TaskFutureAdapters.fromFuture(scheduler, source);
                if ((i & 1) == 0) {
                    adapter.cancel("cancel adapter");
                    source.completeFromRuntime(i);
                } else {
                    source.completeFromRuntime(i);
                    adapter.cancel("already completed or cancelling");
                }
                adapter.getDone().get(3, TimeUnit.SECONDS);
                assertEquals(0, source.pendingRuntimeWaiterCount());
                assertFalse(source.isCancelled());
            }
        }
    }
}
