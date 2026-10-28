package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Host one-shot Observable proof targets, not general guest Observable. */
@Timeout(15)
final class OneShotObservableTest {
    @Test
    void futureSingleSeesOneValueAndDoesNotRerunSource() throws Exception {
        OresFuture<String> producer = new OresFuture<>();
        OneShotObservable<String> events = OneShotObservable.fromFuture(producer);
        OresFuture<String> result = events.single();
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(producer.completeFromRuntime("value"));
        assertEquals("value", result.get(2, TimeUnit.SECONDS));
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void firstIsTypedOptionAndEmptyVoidIsNotInventedPayload() {
        OresFuture<Integer> producer = OresFuture.completed(17);
        OneShotObservable<Integer> events = OneShotObservable.fromFuture(producer);
        assertEquals(Optional.of(17), events.first().join());
        OresFuture<Void> emptySource = OresFuture.completed(null);
        assertEquals(Optional.empty(),
                OneShotObservable.fromVoidFuture(emptySource).first().join());
        var single = OneShotObservable.fromVoidFuture(emptySource).single();
        assertInstanceOf(OneShotObservable.EmptyObservableError.class,
                assertThrows(CompletionException.class, single::join).getCause());
    }

    @Test
    void producerCancelledBeforeSubscriptionRemainsAuthenticatedCancellation() {
        OresFuture<Integer> source = new OresFuture<>();
        assertTrue(source.cancel(false));
        OresFuture<Integer> observed =
                OneShotObservable.fromFuture(source).single();
        assertTrue(observed.isCancelled());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    void producerFailedBeforeSubscriptionDoesNotFabricateOptionalEmpty() {
        OresFuture<Integer> source = new OresFuture<>();
        IllegalArgumentException failure = new IllegalArgumentException("before attach");
        assertTrue(source.failFromRuntime(failure));
        OresFuture<Optional<Integer>> observed =
                OneShotObservable.fromFuture(source).first();
        assertSame(failure,
                assertThrows(CompletionException.class, observed::join).getCause());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    void rejectsNullFromNonVoidProducerRatherThanInventingEmptyStream() {
        OresFuture<Integer> producer = OresFuture.completed(null);
        OresFuture<Optional<Integer>> first =
                OneShotObservable.fromFuture(producer).first();
        assertInstanceOf(OneShotObservable.NullObservablePayloadError.class,
                assertThrows(CompletionException.class, first::join).getCause());
        OresFuture<Integer> single =
                OneShotObservable.fromFuture(producer).single();
        assertInstanceOf(OneShotObservable.NullObservablePayloadError.class,
                assertThrows(CompletionException.class, single::join).getCause());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectsValueProducedByVoidFuture() {
        OresFuture<Void> broken = (OresFuture) OresFuture.completed("not void");
        OresFuture<Optional<Void>> first =
                OneShotObservable.fromVoidFuture(broken).first();
        assertInstanceOf(OneShotObservable.InvalidVoidPayloadError.class,
                assertThrows(CompletionException.class, first::join).getCause());
        assertEquals(0, broken.pendingRuntimeWaiterCount());
    }

    @Test
    void waitsForEntireSourceNotificationDrainBeforePublishingValue()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Integer> result = OneShotObservable.fromFuture(producer).single();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        producer.whenCompleteRuntime((value, failure) -> {
            entered.countDown();
            try {
                if (!resume.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("notification-drain test timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var settlement = pool.submit(() -> producer.completeFromRuntime(23));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertFalse(result.isDone(),
                        "observable must not publish while sibling source callback is active");
            } finally {
                resume.countDown();
            }
            assertTrue(settlement.get(3, TimeUnit.SECONDS));
            assertEquals(23, result.get(3, TimeUnit.SECONDS));
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void cancellationDetachesOneShotObserverWithoutCancellingProducer() {
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Integer> result = OneShotObservable.fromFuture(producer).single();
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(result.cancel(false));
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isCancelled());
        assertTrue(producer.completeFromRuntime(9));
        assertEquals(9, producer.join());
    }

    @Test
    void terminalAndConsumerCancellationRacesDetachWithoutCancellingSource()
            throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 128; i++) {
                OresFuture<Integer> source = new OresFuture<>();
                OresFuture<Optional<Integer>> first =
                        OneShotObservable.fromFuture(source).first();
                var start = new java.util.concurrent.CountDownLatch(1);
                var cancel = pool.submit(() -> {
                    start.await();
                    first.cancel(false);
                    return null;
                });
                final int value = i;
                var complete = pool.submit(() -> {
                    start.await();
                    source.completeFromRuntime(value);
                    return null;
                });
                start.countDown();
                cancel.get(3, TimeUnit.SECONDS);
                complete.get(3, TimeUnit.SECONDS);
                assertEquals(0, source.pendingRuntimeWaiterCount());
                assertFalse(source.isCancelled());
                assertEquals(value, source.join());
                assertTrue(first.isDone());
            }
        }
    }

    @Test
    void moveOnlyUnprovenSecondSubscriberIsDeniedEvenIfFirstSettled() {
        OneShotObservable<Integer> observable =
                OneShotObservable.fromFuture(OresFuture.completed(13));
        assertEquals(13, observable.single().join());
        assertThrows(IllegalStateException.class, observable::single);
        assertThrows(IllegalStateException.class, observable::first);
    }

    @Test
    void cancellationIdentityAndOrdinaryThrownCancellationStayDifferent() {
        OresFuture<Integer> cancelledProducer = new OresFuture<>();
        OresFuture<Integer> cancelledView =
                OneShotObservable.fromFuture(cancelledProducer).single();
        assertTrue(cancelledProducer.cancel(false));
        assertTrue(cancelledView.isCancelled());

        OresFuture<Integer> failedProducer = new OresFuture<>();
        CancellationException guest = new CancellationException("user failure");
        OresFuture<Integer> failedView =
                OneShotObservable.fromFuture(failedProducer).single();
        assertTrue(failedProducer.failFromRuntime(guest));
        assertFalse(failedView.isCancelled());
        assertSame(guest, assertThrows(CompletionException.class,
                failedView::join).getCause());
    }

    @Test
    void firstAndSingleTaskConversionsOwnAdapterNotTheProducer() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            var fromOne = OneShotObservable.fromFuture(source).toTaskFirst(scheduler);
            assertFalse(source.isDone());
            assertTrue(source.completeFromRuntime(18));
            assertEquals(Optional.of(18), fromOne.result().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    fromOne.getDone().get(3, TimeUnit.SECONDS));

            OresFuture<Integer> independent = new OresFuture<>();
            var fromSingle = OneShotObservable.fromFuture(independent).toTaskSingle(scheduler);
            assertTrue(fromSingle.cancel("stop my observation"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    fromSingle.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, independent.pendingRuntimeWaiterCount());
            assertFalse(independent.isDone());
            assertTrue(independent.completeFromRuntime(7));
        }
    }

    @Test
    void rejectedSchedulerAdmissionDoesNotLeakObservableSubscription() {
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        OresFuture<Integer> source = new OresFuture<>();
        OneShotObservable<Integer> observable = OneShotObservable.fromFuture(source);
        assertThrows(IllegalStateException.class, () -> observable.toTaskSingle(scheduler));
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertFalse(source.isDone());
    }

    @Test
    void taskOriginCannotBeCancelledBySubscriber() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> upstream = new OresFuture<>();
            TaskHandle<Integer> producing = TaskFutureAdapters.fromFuture(scheduler, upstream);
            OresFuture<Integer> reduced = OneShotObservable.fromTask(producing).single();
            assertEquals(1, producing.result().pendingRuntimeWaiterCount());
            assertTrue(reduced.cancel(false));
            assertFalse(producing.result().isCancelled());
            assertEquals(0, producing.result().pendingRuntimeWaiterCount());
            assertTrue(upstream.completeFromRuntime(123));
            assertEquals(123, producing.result().get(3, TimeUnit.SECONDS));
            producing.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void alreadyCancelledUpstreamCannotCompleteReductionBeforeRevocation()
            throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        OresFuture<Integer> producer = new OresFuture<>(() -> {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("observable revocation test timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var cancellation = pool.submit(() -> producer.cancel(false));
            OresFuture<Optional<Integer>> reduced;
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                reduced = OneShotObservable.fromFuture(producer).first();
                assertFalse(reduced.isDone(),
                        "first() must wait for producer's physical cancel revoker");
                assertEquals(1, producer.pendingRuntimeWaiterCount());
            } finally {
                release.countDown();
            }
            assertTrue(cancellation.get(3, TimeUnit.SECONDS));
            assertTrue(reduced.isCancelled());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

}
