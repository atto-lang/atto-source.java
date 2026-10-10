package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class ChannelRuntimeTest {
    @Test
    void mixedPriorityPrefersReadyChannelToAlreadyCompletedFuture() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(7));
        OresFuture<String> observed = OresFuture.completed("future");
        var result = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(observed))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();
        assertEquals(ChannelRuntime.SelectOperation.READ, result.operation());
        assertEquals(7, result.value());
        assertEquals(0, result.index());
        assertEquals("future", observed.join());
    }

    @Test
    void pendingFutureCompletesMixedSelectAndDetachesLosingChannel() {
        var producer = new OresFuture<Integer>();
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(producer))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(selected.isDone());
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(producer.completeFromRuntime(42));

        var winner = selected.join();
        assertEquals(ChannelRuntime.SelectOperation.AWAIT, winner.operation());
        assertEquals(1, winner.index());
        assertEquals(42, winner.value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite(13));
        assertEquals(13, channel.tryRead().orElseThrow());
    }

    @Test
    void cancellingMixedSelectionPreservesSharedFutureAndChannelValue() {
        var producer = new OresFuture<Integer>();
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(producer))
                .selectAsync();
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(selected.cancel(false));
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isCancelled());
        assertTrue(channel.tryWrite(19));
        assertEquals(19, channel.tryRead().orElseThrow());
        assertTrue(producer.completeFromRuntime(23));
        assertThrows(CancellationException.class, selected::join);
    }

    @Test
    void failedAwaitedFuturePreservesOriginalErrorAndDoesNotConsumeChannel() {
        var cause = new IllegalStateException("upstream failed");
        OresFuture<Integer> failed = OresFuture.failed(cause);
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(failed), ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var terminal = assertThrows(java.util.concurrent.CompletionException.class,
                selected::join);
        org.junit.jupiter.api.Assertions.assertSame(cause, terminal.getCause());
        assertTrue(channel.tryWrite(72));
        assertEquals(72, channel.tryRead().orElseThrow());
    }

    @Test
    void cancellationSignalIsAnExplicitCaseNotAuthorityToCancelProducer() {
        var signal = new OresFuture<Void>();
        var producer = new OresFuture<Integer>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer), ChannelRuntime.cancelled(signal))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertTrue(signal.cancel(false));
        var result = selected.join();
        assertEquals(ChannelRuntime.SelectOperation.CANCELLED, result.operation());
        assertEquals(1, result.index());
        assertFalse(producer.isDone());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void timeoutWinsWithoutLaterConsumingChannel() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel),
                ChannelRuntime.timeout(2, java.util.concurrent.TimeUnit.MILLISECONDS))
                .selectAsync();
        var result = selected.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT, result.operation());
        assertEquals(1, result.index());
        assertTrue(channel.tryWrite(37));
        assertEquals(37, channel.tryRead().orElseThrow());
    }

    @Test
    void immediateTimeoutIsReadyButFutureDeadlineDoesNotBlockTrySelect() {
        var unready = new OresFuture<Integer>();
        var nonBlocking = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(unready),
                ChannelRuntime.timeout(1, java.util.concurrent.TimeUnit.HOURS));
        assertTrue(nonBlocking.trySelect().isEmpty());
        assertEquals(0, unready.pendingRuntimeWaiterCount());
        var immediate = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(unready),
                ChannelRuntime.timeout(0, java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT,
                immediate.trySelect().orElseThrow().operation());
        assertFalse(unready.isCancelled());
    }

    @Test
    void mixedSelectionFairCursorRotatesAcrossReadyFutureAndChannel() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
        OresFuture<Integer> producer = OresFuture.completed(50);
        ChannelRuntime.SelectSet reusable = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer), ChannelRuntime.read(channel));
        assertTrue(channel.tryWrite(10));
        assertEquals(ChannelRuntime.SelectOperation.AWAIT,
                reusable.selectAsync().join().operation());
        assertEquals(ChannelRuntime.SelectOperation.READ,
                reusable.selectAsync().join().operation());
        assertEquals(0, channel.size());
    }

    @Test
    void observableTakeOneUnsubscribesAfterFirstEmission() throws Exception {
        var requested = new java.util.concurrent.atomic.AtomicInteger();
        var unsubscribed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Flow.Publisher<Integer> source = subscriber ->
                subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    @Override public void request(long n) {
                        requested.incrementAndGet();
                        subscriber.onNext(31);
                        subscriber.onNext(99); // Ignore any unexpected second emission.
                        subscriber.onComplete();
                    }
                    @Override public void cancel() { unsubscribed.countDown(); }
                });
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.observeOnce(source))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var winner = selected.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(ChannelRuntime.SelectOperation.OBSERVE, winner.operation());
        assertEquals(31, winner.value());
        assertEquals(1, requested.get());
        assertTrue(unsubscribed.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void losingObservableSubscriptionIsCancelledWithoutStealingChannelValue()
            throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var unsubscribed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Flow.Publisher<Integer> never = subscriber -> {
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                @Override public void request(long n) { subscribed.countDown(); }
                @Override public void cancel() { unsubscribed.countDown(); }
            });
        };
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.observeOnce(never), ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertTrue(subscribed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(channel.tryWrite(12));
        var winner = selected.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(ChannelRuntime.SelectOperation.READ, winner.operation());
        assertEquals(12, winner.value());
        assertTrue(unsubscribed.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "losing reactive case must unsubscribe");
    }

    @Test
    void cancellingSelectUnsubscribesObservableWithoutRunningAnyGuestCase()
            throws Exception {
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var unsubscribed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Flow.Publisher<Integer> never = subscriber -> {
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                @Override public void request(long n) { subscribed.countDown(); }
                @Override public void cancel() { unsubscribed.countDown(); }
            });
        };
        var sharedFuture = new OresFuture<Integer>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.observeOnce(never), ChannelRuntime.await(sharedFuture))
                .selectAsync();
        assertTrue(subscribed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(selected.cancel(false));
        assertThrows(CancellationException.class, selected::join);
        assertTrue(unsubscribed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, sharedFuture.pendingRuntimeWaiterCount());
        assertFalse(sharedFuture.isCancelled());
    }

    @Test
    void readyChannelPreventsUnnecessaryObservableSubscription() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(5));
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Flow.Publisher<Integer> publisher =
                subscriber -> starts.incrementAndGet();
        var winner = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.observeOnce(publisher))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(ChannelRuntime.SelectOperation.READ, winner.operation());
        assertEquals(0, starts.get(),
                "already decided select must not subscribe to the losing Publisher");
    }

    @Test
    void throwingHostSubscriptionCancelDoesNotBreakWinnerOrSubsequentSelections()
            throws Exception {
        var cancellationAttempted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Flow.Publisher<Integer> malicious = subscriber ->
                subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    @Override public void request(long n) { subscriber.onNext(21); }
                    @Override public void cancel() {
                        cancellationAttempted.countDown();
                        throw new IllegalStateException("foreign cancel hook threw");
                    }
                });
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.observeOnce(malicious))
                .selectAsync();
        assertEquals(21, selected.get(5, java.util.concurrent.TimeUnit.SECONDS).value());
        assertTrue(cancellationAttempted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        ChannelRuntime.Channel<Integer> next = new ChannelRuntime.Channel<>(1);
        assertTrue(next.tryWrite(42));
        assertEquals(42, ChannelRuntime.SelectSet.of(ChannelRuntime.read(next))
                .selectAsync().join().value());
    }

    @Test
    void duplicateOnSubscribeNeverReplacesOriginalSubscription() throws Exception {
        var firstCancelled = new java.util.concurrent.CountDownLatch(1);
        var extraCancelled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Flow.Publisher<Integer> malformed = subscriber -> {
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { firstCancelled.countDown(); }
            });
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                @Override public void request(long n) { throw new AssertionError("must not request"); }
                @Override public void cancel() { extraCancelled.countDown(); }
            });
        };
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.observeOnce(malformed))
                .selectAsync();
        assertTrue(extraCancelled.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(selected.cancel(false));
        assertTrue(firstCancelled.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void observableEmptyCompletionFailsSelectRatherThanHanging() throws Exception {
        java.util.concurrent.Flow.Publisher<Integer> empty = subscriber ->
                subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    @Override public void request(long n) { subscriber.onComplete(); }
                    @Override public void cancel() { }
                });
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.observeOnce(empty))
                .selectAsync();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> selected.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof java.util.NoSuchElementException);
    }

    @Test
    void mixedFutureAndChannelRaceNeverConsumesLosingChannelValue() throws Exception {
        try (var producers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 200; attempt++) {
                ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
                OresFuture<Integer> future = new OresFuture<>();
                var selected = ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(channel), ChannelRuntime.await(future),
                        ChannelRuntime.timeout(2, java.util.concurrent.TimeUnit.SECONDS))
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
                var start = new java.util.concurrent.CountDownLatch(1);
                var produceChannel = producers.submit(() -> {
                    start.await();
                    channel.writeAsync(11).join();
                    return true;
                });
                var produceFuture = producers.submit(() -> {
                    start.await();
                    future.completeFromRuntime(22);
                    return true;
                });
                start.countDown();
                var winner = selected.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(produceChannel.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(produceFuture.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, future.pendingRuntimeWaiterCount());
                assertEquals(22, future.join());
                if (winner.operation() == ChannelRuntime.SelectOperation.READ) {
                    assertEquals(11, winner.value());
                    assertTrue(channel.tryRead().isEmpty());
                } else if (winner.operation() == ChannelRuntime.SelectOperation.AWAIT) {
                    assertEquals(22, winner.value());
                    assertEquals(11, channel.tryRead().orElseThrow());
                } else {
                    throw new AssertionError("unexpected winner " + winner.operation());
                }
            }
        }
    }

    @Test
    void cancelBeforeDeadlineRemovesFutureObserverWithoutCancellingProducer() {
        OresFuture<Integer> producer = new OresFuture<>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer),
                ChannelRuntime.timeout(30, java.util.concurrent.TimeUnit.SECONDS))
                .selectAsync();
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(selected.cancel(false));
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isCancelled());
        assertThrows(CancellationException.class, selected::join);
    }

    @Test
    void affineWriteDescriptorCannotPublishTheSameMutablePayloadTwice() {
        ChannelRuntime.Channel<List<Integer>> channel = new ChannelRuntime.Channel<>(2);
        List<Integer> owned = new java.util.ArrayList<>(List.of(1, 2));
        ChannelRuntime.WriteCase<List<Integer>> oneShot =
                ChannelRuntime.write(channel, owned);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(oneShot);

        assertEquals(ChannelRuntime.SelectOperation.WRITE,
                plan.selectAsync().join().operation());
        var duplicate = plan.selectAsync();
        var failed = assertThrows(java.util.concurrent.CompletionException.class,
                duplicate::join);
        assertTrue(failed.getCause().getMessage().contains("already transferred"));
        assertEquals(1, channel.size());
        org.junit.jupiter.api.Assertions.assertSame(owned, channel.tryRead().orElseThrow());

        // Publishing a new descriptor revision must not reset the already
        // transferred token on the old WriteCase instance.
        plan.replaceCases(List.of(oneShot));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> plan.selectAsync().join());
        plan.replaceCase(0, ChannelRuntime.write(channel,
                new java.util.ArrayList<>(List.of(3))));
        assertEquals(ChannelRuntime.SelectOperation.WRITE,
                plan.selectAsync().join().operation());
        assertEquals(List.of(3), channel.tryRead().orElseThrow());
    }

    @Test
    void concurrentSelectionsCannotCommitOneAffineWriteCaseTwice() throws Exception {
        ChannelRuntime.Channel<java.util.List<Integer>> channel =
                new ChannelRuntime.Channel<>(2);
        java.util.List<Integer> payload = new java.util.ArrayList<>(List.of(1));
        ChannelRuntime.WriteCase<java.util.List<Integer>> shared =
                ChannelRuntime.write(channel, payload);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(shared);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                try {
                    plan.selectAsync().join();
                    return true;
                } catch (java.util.concurrent.CompletionException rejected) {
                    return false;
                }
            });
            var second = pool.submit(() -> {
                start.await();
                try {
                    plan.selectAsync().join();
                    return true;
                } catch (java.util.concurrent.CompletionException rejected) {
                    return false;
                }
            });
            start.countDown();
            boolean a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            boolean b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a ^ b, "one and only one registration may commit the payload");
        }
        assertEquals(1, channel.size());
        org.junit.jupiter.api.Assertions.assertSame(payload, channel.tryRead().orElseThrow());
    }

    @Test
    void scalarWriteCaseCanBeReusedBecauseItsPayloadIsCopySafe() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.write(channel, 17));
        assertEquals(ChannelRuntime.SelectOperation.WRITE,
                plan.selectAsync().join().operation());
        assertEquals(ChannelRuntime.SelectOperation.WRITE,
                plan.selectAsync().join().operation());
        assertEquals(17, channel.tryRead().orElseThrow());
        assertEquals(17, channel.tryRead().orElseThrow());
    }

    @Test
    void losingAffineWriteCaseCanStillWinASeparateLaterSelection() {
        ChannelRuntime.Channel<List<Integer>> channel = new ChannelRuntime.Channel<>(0);
        var owned = new java.util.ArrayList<>(List.of(7));
        var descriptor = ChannelRuntime.write(channel, owned);
        var set = ChannelRuntime.SelectSet.of(descriptor,
                ChannelRuntime.timeout(0, java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT, set.selectAsync().join().operation());

        OresFuture<List<Integer>> receiver = channel.readAsync();
        assertEquals(ChannelRuntime.SelectOperation.WRITE, set.selectAsync().join().operation());
        org.junit.jupiter.api.Assertions.assertSame(owned, receiver.join());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join());
    }

    @Test
    void selectPlanMutationDoesNotRetargetAlreadyRegisteredReaders() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(first));
        OresFuture<ChannelRuntime.SelectResult> older = plan.selectAsync();
        assertEquals(0L, plan.revision());
        assertFalse(older.isDone());

        plan.replaceCase(0, ChannelRuntime.read(second));
        assertEquals(1L, plan.revision());
        assertEquals(1, plan.cases().size());
        OresFuture<ChannelRuntime.SelectResult> newer = plan.selectAsync();
        assertFalse(newer.isDone());

        assertTrue(second.tryWrite(2));
        assertEquals(2, newer.join().value());
        assertFalse(older.isDone(), "old selection must still watch the original channel");
        assertTrue(first.tryWrite(1));
        assertEquals(1, older.join().value());
    }

    @Test
    void selectPlanCanAddRemoveAndReplaceCasesWithoutLeakingPreviousGenerations() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(first));
        plan.addCase(ChannelRuntime.read(second));
        assertEquals(1L, plan.revision());
        assertEquals(2, plan.cases().size());
        assertTrue(second.tryWrite(9));
        assertEquals(1, plan.trySelect().orElseThrow().index());

        plan.removeCase(0);
        assertEquals(2L, plan.revision());
        assertEquals(1, plan.cases().size());
        plan.replaceCases(List.of(ChannelRuntime.timeout(
                0, java.util.concurrent.TimeUnit.NANOSECONDS)));
        assertEquals(3L, plan.revision());
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT,
                plan.trySelect().orElseThrow().operation());

        assertThrows(IllegalArgumentException.class, () -> plan.removeCase(0));
        assertEquals(3L, plan.revision(),
                "rejected empty-plan change must leave the original snapshot intact");
    }

    @Test
    void invalidDuplicateDefaultPlanRevisionRollsBackAtomically() {
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.defaultCase());
        assertThrows(IllegalArgumentException.class,
                () -> plan.addCase(ChannelRuntime.defaultCase()));
        assertEquals(0L, plan.revision());
        assertEquals(1, plan.cases().size());
        assertEquals(ChannelRuntime.SelectOperation.DEFAULT,
                plan.trySelect().orElseThrow().operation());
    }

    @Test
    void concurrentPlanUpdatesAreLosslessAndGenerationMonotonic() throws Exception {
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.timeout(0, java.util.concurrent.TimeUnit.NANOSECONDS));
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            java.util.List<java.util.concurrent.Future<?>> edits =
                    new java.util.ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                edits.add(workers.submit(() -> {
                    for (int n = 0; n < 40; n++) {
                        plan.addCase(ChannelRuntime.timeout(
                                0, java.util.concurrent.TimeUnit.NANOSECONDS));
                    }
                }));
            }
            for (var edit : edits) edit.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(160L, plan.revision());
        assertEquals(161, plan.cases().size());
        assertEquals(ChannelRuntime.SelectOperation.TIMEOUT,
                plan.trySelect().orElseThrow().operation());
    }

    @Test
    void pendingWriterCannotOvertakeBufferedValue() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(1));
        OresFuture<Void> pending = channel.writeAsync(2);
        assertFalse(pending.isDone());
        assertEquals(1, channel.tryRead().orElseThrow());
        assertTrue(pending.isDone());
        assertEquals(2, channel.tryRead().orElseThrow());
    }

    @Test
    void shutdownDrainReleasesBufferedValuesAndStopsAtClosedEmptyChannel() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(2);
        assertTrue(channel.tryWrite("one"));
        assertTrue(channel.tryWrite("two"));
        channel.close();
        java.util.List<String> drained = new java.util.ArrayList<>();
        assertTrue(channel.drainOne(drained::add));
        assertTrue(channel.drainOne(drained::add));
        assertFalse(channel.drainOne(drained::add));
        assertEquals(java.util.List.of("one", "two"), drained);
    }

    @Test
    void bufferedChannelIsFifo() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(2);

        assertTrue(channel.tryWrite("a"));
        assertTrue(channel.tryWrite("b"));
        assertFalse(channel.tryWrite("c"));

        assertEquals("a", channel.tryRead().orElseThrow());
        assertEquals("b", channel.tryRead().orElseThrow());
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test
    void cancelledReadDoesNotConsumeLaterValue() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<String> pending = channel.readAsync();

        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertThrows(CancellationException.class, pending::join);

        assertTrue(channel.tryWrite("kept"));
        assertEquals("kept", channel.tryRead().orElseThrow());
    }

    @Test
    void cancelledWriteDoesNotDeliverLater() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<Void> pending = channel.writeAsync("discarded");

        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);

        OresFuture<String> read = channel.readAsync();
        assertFalse(read.isDone());
        read.cancel(false);
    }

    @Test
    void prioritySelectAlwaysUsesLexicalOrderWhenMultipleCasesAreReady() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);
        first.tryWrite("one");
        second.tryWrite("two");

        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second));

        ChannelRuntime.SelectResult result =
                set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();

        assertEquals(0, result.index());
        assertEquals(ChannelRuntime.SelectOperation.READ, result.operation());
        assertEquals("one", result.value());
        assertEquals("two", second.tryRead().orElseThrow());
    }

    @Test
    void fairSelectRotatesOnAStableSelectSetWithoutRandomness() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);
        first.tryWrite("a1");
        second.tryWrite("b1");

        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second));

        ChannelRuntime.SelectResult firstResult =
                set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(0, firstResult.index());

        first.tryWrite("a2");
        ChannelRuntime.SelectResult secondResult =
                set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, secondResult.index());
        assertEquals("b1", secondResult.value());
    }

    @Test
    void nonBlockingSelectRegistrationReturnsPendingFutureAndCompletesLater() {
        ChannelRuntime.Channel<String> incoming = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(incoming));

        OresFuture<ChannelRuntime.SelectResult> pending = set.selectAsync();
        assertFalse(pending.isDone());

        incoming.writeAsync("later").join();

        assertTrue(pending.isDone());
        assertEquals("later", pending.join().value());
    }

    @Test
    void cancellingSelectCannotConsumeAfterCancellationWins() {
        ChannelRuntime.Channel<String> incoming = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(incoming));

        OresFuture<ChannelRuntime.SelectResult> pending = set.selectAsync();
        assertTrue(pending.cancel(false));

        incoming.writeAsync("still-there").join();

        assertThrows(CancellationException.class, pending::join);
        assertEquals("still-there", incoming.tryRead().orElseThrow());
    }

    @Test
    void rendezvousChannelPairsSelectReadWithPendingWrite() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel));

        OresFuture<ChannelRuntime.SelectResult> selected = set.selectAsync();
        OresFuture<Void> write = channel.writeAsync("hand-off");

        assertEquals("hand-off", selected.join().value());
        write.join();
    }

    @Test
    void twoPendingSelectsCanRendezvousOnUnbufferedChannel() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(0);

        OresFuture<ChannelRuntime.SelectResult> writer =
                ChannelRuntime.SelectSet.of(
                                ChannelRuntime.write(channel, 123))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(writer.isDone());

        OresFuture<ChannelRuntime.SelectResult> reader =
                ChannelRuntime.SelectSet.of(
                                ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertEquals(123, reader.join().value());
        assertEquals(
                ChannelRuntime.SelectOperation.WRITE,
                writer.join().operation());
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test
    void selectToSelectRendezvousCommitsExactlyOneArmPerSelection() {
        ChannelRuntime.Channel<Integer> rendezvous =
                new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<Integer> alternate =
                new ChannelRuntime.Channel<>(1);
        alternate.tryWrite(77);

        ChannelRuntime.SelectSet readerSet =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(rendezvous),
                        ChannelRuntime.read(alternate));
        OresFuture<ChannelRuntime.SelectResult> reader =
                readerSet.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        OresFuture<ChannelRuntime.SelectResult> writer =
                ChannelRuntime.SelectSet.of(
                                ChannelRuntime.write(rendezvous, 99))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        ChannelRuntime.SelectResult result = reader.join();
        if (result.index() == 0) {
            assertEquals(99, result.value());
            assertEquals(77, alternate.tryRead().orElseThrow());
            assertEquals(
                    ChannelRuntime.SelectOperation.WRITE,
                    writer.join().operation());
        } else {
            assertEquals(77, result.value());
            assertFalse(writer.isDone());
            assertTrue(writer.cancel(false));
            assertTrue(rendezvous.tryRead().isEmpty());
        }
    }

    @Test
    void dynamicSelectCanBeBuiltFromListsAndMaps() {
        ChannelRuntime.Channel<String> one = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> two = new ChannelRuntime.Channel<>(1);
        two.tryWrite("two");

        ChannelRuntime.SelectSet listSet = ChannelRuntime.SelectSet.from(List.of(
                ChannelRuntime.read(one),
                ChannelRuntime.read(two)));
        assertEquals(1,
                listSet.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join().index());

        one.tryWrite("one");
        LinkedHashMap<String, ChannelRuntime.SelectCase> cases = new LinkedHashMap<>();
        cases.put("one", ChannelRuntime.read(one));
        cases.put("two", ChannelRuntime.read(two));
        ChannelRuntime.SelectSet mapSet = ChannelRuntime.SelectSet.fromMap(cases);

        assertEquals(0,
                mapSet.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join().index());
    }


    @Test
    void selectPlanReusesDescriptorsButCreatesIndependentOperations() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(channel));

        channel.tryWrite("first");
        ChannelRuntime.SelectResult first = plan.selectAsync().join();
        assertEquals("first", first.value());

        OresFuture<ChannelRuntime.SelectResult> second = plan.selectAsync();
        assertFalse(second.isDone());
        assertTrue(second.cancel(false));

        channel.tryWrite("second");
        assertThrows(CancellationException.class, second::join);
        assertEquals("second", channel.tryRead().orElseThrow());
    }

    @Test
    void selectPlanPreservesFairRotationAcrossRepeatedExecutions() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second));

        first.tryWrite("a1");
        second.tryWrite("b1");
        assertEquals(0, plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());

        first.tryWrite("a2");
        assertEquals(1, plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());
    }

    @Test
    void selectPlanCanBeAssembledDynamicallyWithoutChangingSelectSetPath() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);
        second.tryWrite("ready");

        java.util.ArrayList<ChannelRuntime.SelectCase> dynamicCases =
                new java.util.ArrayList<>();
        dynamicCases.add(ChannelRuntime.read(first));
        dynamicCases.add(ChannelRuntime.read(second));

        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.from(dynamicCases);
        assertEquals(
                1,
                plan.trySelect(ChannelRuntime.SelectPolicy.PRIORITY)
                        .orElseThrow()
                        .index());

        // The original SelectSet API remains independently usable.
        first.tryWrite("legacy");
        ChannelRuntime.SelectSet original = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first));
        assertEquals("legacy", original.selectAsync().join().value());
    }

    @Test
    void immediateWriteRendezvousWithPendingSelectRead() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertTrue(channel.tryWrite("direct"));
        assertEquals("direct", selected.join().value());
    }

    @Test
    void immediateReadRendezvousWithPendingSelectWrite() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.write(channel, "direct"))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertEquals("direct", channel.tryRead().orElseThrow());
        assertEquals(
                ChannelRuntime.SelectOperation.WRITE,
                selected.join().operation());
    }

    @Test
    void fairSelectRotatesPastClosedArmAfterTerminalFailure() {
        ChannelRuntime.Channel<String> closed =
                new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> ready =
                new ChannelRuntime.Channel<>(1);
        closed.close();
        ready.tryWrite("value");

        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(closed),
                ChannelRuntime.read(ready));

        assertThrows(
                RuntimeException.class,
                () -> set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());

        ChannelRuntime.SelectResult next =
                set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, next.index());
        assertEquals("value", next.value());
    }

    @Test
    void closeFailsPendingWaitersAndSelects() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<String> read = channel.readAsync();
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();

        channel.close();

        assertThrows(RuntimeException.class, read::join);
        assertThrows(RuntimeException.class, selected::join);
    }
}
