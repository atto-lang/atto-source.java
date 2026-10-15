package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Future completion and channel transfer must share one atomic select winner. */
final class FutureChannelSelectTest {
    @Test
    void concurrentCancellationAndFutureSettlementDoNotRetainLosingWaiters()
            throws Exception {
        for (int round = 0; round < 64; round++) {
            var producer = new OresFuture<Integer>();
            var channel = new ChannelRuntime.Channel<Integer>(1);
            var selected = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(producer), ChannelRuntime.read(channel))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            var gate = new CountDownLatch(1);
            var workerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    awaitStart(gate);
                    selected.cancel(false);
                } catch (Throwable error) {
                    workerFailure.compareAndSet(null, error);
                }
            });
            Thread settler = Thread.startVirtualThread(() -> {
                try {
                    awaitStart(gate);
                    assertTrue(producer.completeFromRuntime(19));
                } catch (Throwable error) {
                    workerFailure.compareAndSet(null, error);
                }
            });
            gate.countDown();
            canceller.join(5_000);
            settler.join(5_000);
            assertFalse(canceller.isAlive(), "cancel stalled in round " + round);
            assertFalse(settler.isAlive(), "settlement stalled in round " + round);
            if (workerFailure.get() != null) {
                throw new AssertionError("select race worker failed in round " + round,
                        workerFailure.get());
            }
            if (selected.isCancelled()) {
                assertThrows(CancellationException.class, selected::join);
            } else {
                var winner = selected.get(5, TimeUnit.SECONDS);
                assertEquals(ChannelRuntime.SelectOperation.FUTURE, winner.operation());
                assertEquals(19, winner.value());
            }
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertEquals(19, producer.get(5, TimeUnit.SECONDS));
            assertTrue(channel.tryWrite(73));
            assertEquals(73, channel.tryRead().orElseThrow());
        }
    }

    @Test
    void concurrentFairAdmissionsObservePreviouslyCommittedRotation() throws Exception {
        // Both callers construct their registrations while coordinator
        // admission is blocked. A cursor sampled in the constructor would
        // make both select arm zero, even though the first commit rotates FAIR.
        var first = new ChannelRuntime.Channel<Integer>(2);
        var second = new ChannelRuntime.Channel<Integer>(2);
        assertTrue(first.tryWrite(11));
        assertTrue(first.tryWrite(12));
        assertTrue(second.tryWrite(21));
        assertTrue(second.tryWrite(22));
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first), ChannelRuntime.read(second));

        var coordinatorField = ChannelRuntime.class.getDeclaredField("COORDINATOR");
        coordinatorField.setAccessible(true);
        var coordinator = (java.util.concurrent.locks.ReentrantLock) coordinatorField.get(null);
        var started = new CountDownLatch(2);
        var winnerA = new java.util.concurrent.atomic.AtomicReference<ChannelRuntime.SelectResult>();
        var winnerB = new java.util.concurrent.atomic.AtomicReference<ChannelRuntime.SelectResult>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread a;
        Thread b;
        coordinator.lock();
        try {
            a = Thread.startVirtualThread(() -> {
                started.countDown();
                try {
                    winnerA.set(set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            b = Thread.startVirtualThread(() -> {
                started.countDown();
                try {
                    winnerB.set(set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "select workers never started");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(coordinator.hasQueuedThread(a) && coordinator.hasQueuedThread(b))
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(coordinator.hasQueuedThread(a) && coordinator.hasQueuedThread(b),
                    "both selections must queue before the admission gate is released");
        } finally {
            coordinator.unlock();
        }
        a.join(5_000);
        b.join(5_000);
        assertFalse(a.isAlive(), "first FAIR admission stalled");
        assertFalse(b.isAlive(), "second FAIR admission stalled");
        if (failure.get() != null) throw new AssertionError("FAIR worker failed", failure.get());

        assertTrue(winnerA.get() != null && winnerB.get() != null);
        assertEquals(1, winnerA.get().index() + winnerB.get().index(),
                "concurrent FAIR admissions must rotate across both ready arms");
        assertEquals(0, first.pendingRegistrations());
        assertEquals(0, second.pendingRegistrations());
        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    @Test
    void maximumFanInPreservesHighIndexWinnerAndRefundsEveryLosingWaiter() {
        // Probe the largest supported arm set. The channel is deliberately
        // last, so arbitration visits every pending Future before its winner.
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var cases = new java.util.ArrayList<ChannelRuntime.SelectCase>();
        for (int i = 0; i < ChannelRuntime.MAX_SELECT_CASES - 1; i++) {
            cases.add(ChannelRuntime.await(producer));
        }
        cases.add(ChannelRuntime.read(channel));
        var selection = new ChannelRuntime.SelectSet(cases);
        var pending = selection.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertEquals(ChannelRuntime.MAX_SELECT_CASES - 1,
                producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite(42));
        var channelWinner = pending.join();
        assertEquals(ChannelRuntime.MAX_SELECT_CASES - 1, channelWinner.index());
        assertEquals(42, channelWinner.value());
        assertTrue(channel.tryRead().isEmpty());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isDone());

        // A reused select sees its first newly completed Future and never
        // consumes a channel value merely because that channel was registered.
        var waitingAgain = selection.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertTrue(producer.completeFromRuntime(99));
        var futureWinner = waitingAgain.join();
        assertEquals(0, futureWinner.index());
        assertEquals(99, futureWinner.value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite(73));
        assertEquals(73, channel.tryRead().orElseThrow());
    }

    @Test
    void closingChannelAndSettlingFutureRaceWithoutCancellingLosingSource()
            throws Exception {
        for (int round = 0; round < 64; round++) {
            var channel = new ChannelRuntime.Channel<Integer>(0);
            var producer = new OresFuture<Integer>();
            var closeCause = new IllegalStateException("channel shutdown");
            var selected = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(channel), ChannelRuntime.await(producer))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            var gate = new CountDownLatch(1);
            var workerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread closer = Thread.startVirtualThread(() -> {
                try {
                    awaitStart(gate);
                    channel.close(closeCause);
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            });
            Thread settler = Thread.startVirtualThread(() -> {
                try {
                    awaitStart(gate);
                    assertTrue(producer.completeFromRuntime(202));
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            });
            gate.countDown();
            closer.join(5_000);
            settler.join(5_000);
            assertFalse(closer.isAlive(), "channel close stalled in round " + round);
            assertFalse(settler.isAlive(), "Future settlement stalled in round " + round);
            if (workerFailure.get() != null) {
                throw new AssertionError("close/settle race failed in round " + round,
                        workerFailure.get());
            }
            try {
                var winner = selected.get(5, TimeUnit.SECONDS);
                assertEquals(ChannelRuntime.SelectOperation.FUTURE, winner.operation());
                assertEquals(1, winner.index());
                assertEquals(202, winner.value());
            } catch (java.util.concurrent.ExecutionException failed) {
                assertTrue(failed.getCause()
                        instanceof ChannelRuntime.ChannelClosedException);
                assertEquals(closeCause, failed.getCause().getCause());
            }
            assertTrue(channel.isClosed());
            assertEquals(0, channel.pendingRegistrations());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertEquals(202, producer.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void settlementDuringManyFutureListenerAttachmentsLeavesNoWaiters()
            throws Exception {
        for (int round = 0; round < 128; round++) {
            var producer = new OresFuture<Integer>();
            var cases = new java.util.ArrayList<ChannelRuntime.SelectCase>();
            for (int i = 0; i < 128; i++) {
                cases.add(ChannelRuntime.await(producer));
            }
            var gate = new CountDownLatch(1);
            var workerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread settler = Thread.startVirtualThread(() -> {
                try {
                    awaitStart(gate);
                    assertTrue(producer.completeFromRuntime(17));
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            });
            gate.countDown();
            var selected = new ChannelRuntime.SelectSet(cases)
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            settler.join(5_000);
            assertFalse(settler.isAlive(), "settlement stalled in round " + round);
            if (workerFailure.get() != null) {
                throw new AssertionError("attachment race failed in round " + round,
                        workerFailure.get());
            }
            var winner = selected.get(5, TimeUnit.SECONDS);
            assertEquals(ChannelRuntime.SelectOperation.FUTURE, winner.operation());
            assertAll("shared Future attachment race round " + round,
                    () -> assertEquals(0, winner.index(),
                            "PRIORITY must select the first alias"),
                    () -> assertEquals(17, winner.value()),
                    () -> assertEquals(0, producer.pendingRuntimeWaiterCount(),
                            "losing listeners must be detached independently of ordering"));
        }
    }

    @Test
    void winningResultIsPublishedOnlyAfterLosingWaitersAreDetached() throws Exception {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer), ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var observedAtPublication = new java.util.concurrent.atomic.AtomicInteger(-1);
        selected.whenCompleteRuntime((winner, failure) ->
                observedAtPublication.set(producer.pendingRuntimeWaiterCount()));
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite(77));
        assertEquals(77, selected.get(5, TimeUnit.SECONDS).value());
        assertEquals(0, observedAtPublication.get(),
                "select completion must not precede loser listener detachment");
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isDone(), "loser producer must remain owned by its source");
    }

    @Test
    void cancelledSelectionPublishesOnlyAfterProducerWaiterDetachment() {
        var producer = new OresFuture<Integer>();
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.await(producer))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var observedAtCancellation = new java.util.concurrent.atomic.AtomicInteger(-1);
        selected.whenCompleteRuntime((winner, failure) ->
                observedAtCancellation.set(producer.pendingRuntimeWaiterCount()));
        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(selected.cancel(false));
        assertEquals(0, observedAtCancellation.get(),
                "cancellation callback must observe retired producer listeners");
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isDone());
    }

    @Test
    void alreadySettledSharedFutureAliasesAlwaysChooseEarliestPriorityArm() {
        var producer = OresFuture.completed(71);
        var cases = new java.util.ArrayList<ChannelRuntime.SelectCase>();
        for (int i = 0; i < ChannelRuntime.MAX_SELECT_CASES; i++) {
            cases.add(ChannelRuntime.await(producer));
        }
        var selection = new ChannelRuntime.SelectSet(cases);

        for (int round = 0; round < 16; round++) {
            var winner = selection.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();
            assertAll("ready shared Future round " + round,
                    () -> assertEquals(0, winner.index()),
                    () -> assertEquals(71, winner.value()),
                    () -> assertEquals(0, producer.pendingRuntimeWaiterCount()));
        }
    }

    @Test
    void failedClosedArmAdvancesFairCursorPastPermanentFailure() {
        var closed = new ChannelRuntime.Channel<Integer>(0);
        closed.close();
        var pending = new OresFuture<Integer>();
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(closed), ChannelRuntime.await(pending));
        var failed = assertThrows(CompletionException.class,
                () -> set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());
        assertTrue(failed.getCause() instanceof ChannelRuntime.ChannelClosedException);

        assertTrue(pending.completeFromRuntime(23));
        var winner = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(ChannelRuntime.SelectOperation.FUTURE, winner.operation());
        assertEquals(1, winner.index());
        assertEquals(23, winner.value());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
    }

    @Test
    void internalBufferedDrainUnblocksPendingWriterWithoutStealingLosingFuture()
            throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertTrue(channel.tryWrite(10));
        var producer = new OresFuture<Integer>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.write(channel, 20),
                ChannelRuntime.await(producer))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(selected.isDone());
        assertEquals(1, channel.pendingRegistrations());
        assertEquals(1, producer.pendingRuntimeWaiterCount());

        assertTrue(channel.drainOne(value -> {
            assertEquals(10, value);
            // Completion callbacks are not run inside the mailbox consumer.
            assertFalse(selected.isDone());
        }));
        var winner = selected.get(5, TimeUnit.SECONDS);
        assertEquals(0, winner.index());
        assertEquals(ChannelRuntime.SelectOperation.WRITE, winner.operation());
        assertEquals(0, channel.pendingRegistrations());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isDone());
        assertEquals(20, channel.tryRead().orElseThrow());
    }

    @Test
    void throwingInternalDrainConsumerStillCompletesAlreadyAdmittedWriter()
            throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertTrue(channel.tryWrite(41));
        var admitted = channel.writeAsync(42);
        assertFalse(admitted.isDone());

        var failure = new IllegalStateException("mailbox consumer failure");
        var caught = assertThrows(IllegalStateException.class,
                () -> channel.drainOne(value -> {
                    assertEquals(41, value);
                    throw failure;
                }));
        assertEquals(failure, caught);
        assertEquals(null, admitted.get(5, TimeUnit.SECONDS));
        assertEquals(0, channel.pendingRegistrations());
        assertEquals(42, channel.tryRead().orElseThrow());
    }

    @Test
    void pendingFutureOnlyAndMixedSelectionsRespectSeparateAdmissionDomains() {
        var futureOnlySource = new OresFuture<Integer>();
        var futureOnly = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(futureOnlySource)).selectAsync();
        assertFalse(futureOnly.isDone());
        assertEquals(1, futureOnlySource.pendingRuntimeWaiterCount());
        assertTrue(futureOnly.cancel(false));
        assertEquals(0, futureOnlySource.pendingRuntimeWaiterCount());
        assertFalse(futureOnlySource.isDone());

        var channel = new ChannelRuntime.Channel<Integer>(0);
        var sharedSource = new OresFuture<Integer>();
        var mixed = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(sharedSource),
                ChannelRuntime.read(channel)).selectAsync();
        assertFalse(mixed.isDone());
        assertEquals(1, sharedSource.pendingRuntimeWaiterCount());
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(mixed.cancel(false));
        assertEquals(0, sharedSource.pendingRuntimeWaiterCount());
        assertEquals(0, channel.pendingRegistrations());
        assertFalse(sharedSource.isDone());
    }

    @Test
    void pendingFutureWakesHeterogeneousSelectWithoutConsumingLosingChannel() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var producer = new OresFuture<Integer>();
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(producer));

        var selected = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(selected.isDone());
        assertEquals(1, producer.pendingRuntimeWaiterCount());

        assertTrue(producer.completeFromRuntime(42));
        var result = selected.join();
        assertEquals(1, result.index());
        assertEquals(ChannelRuntime.SelectOperation.FUTURE, result.operation());
        assertEquals(42, result.value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());

        assertTrue(channel.tryWrite(7));
        assertEquals(7, channel.tryRead().orElseThrow());
    }

    @Test
    void channelWinnerDetachesFutureWaiterAndDoesNotCancelSharedProducer() {
        var channel = new ChannelRuntime.Channel<String>(1);
        var producer = new OresFuture<String>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer),
                ChannelRuntime.read(channel)).selectAsync();

        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite("mail"));

        assertEquals(1, selected.join().index());
        assertEquals("mail", selected.join().value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isDone());

        assertTrue(producer.completeFromRuntime("later"));
        assertEquals("mail", selected.join().value());
    }

    @Test
    void cancelledSelectionDetachesAllFutureWaitersWithoutCancellingSources() {
        var first = new OresFuture<Integer>();
        var second = new OresFuture<Integer>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(first), ChannelRuntime.await(second)).selectAsync();

        assertEquals(1, first.pendingRuntimeWaiterCount());
        assertEquals(1, second.pendingRuntimeWaiterCount());
        assertTrue(selected.cancel(false));
        assertTrue(selected.isCancelled());
        assertThrows(CancellationException.class, selected::join);
        assertEquals(0, first.pendingRuntimeWaiterCount());
        assertEquals(0, second.pendingRuntimeWaiterCount());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
    }

    @Test
    void priorityAndFairnessApplyAcrossAlreadyReadyChannelsAndFutures() {
        var channel = new ChannelRuntime.Channel<Integer>(2);
        assertTrue(channel.tryWrite(11));
        var future = OresFuture.completed(22);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(future));

        var first = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(0, first.index());
        assertEquals(11, first.value());

        assertTrue(channel.tryWrite(33));
        var second = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, second.index());
        assertEquals(22, second.value());
        assertEquals(33, channel.tryRead().orElseThrow());
    }

    @Test
    void settledFailedFutureIsSelectableButPreservesItsFailure() {
        var failure = new IllegalStateException("upstream failed");
        var future = OresFuture.<Integer>failed(failure);
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(future), ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var thrown = assertThrows(CompletionException.class, selected::join);
        assertEquals(failure, thrown.getCause());
        assertFalse(channel.tryRead().isPresent());
    }

    @Test
    void concurrentChannelWriteAndFutureSettlementChooseExactlyOneWinner() throws Exception {
        for (int round = 0; round < 64; round++) {
            var channel = new ChannelRuntime.Channel<Integer>(1);
            var producer = new OresFuture<Integer>();
            var selected = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(channel), ChannelRuntime.await(producer))
                    .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
            var go = new CountDownLatch(1);
            Thread writer = Thread.startVirtualThread(() -> {
                awaitStart(go);
                assertTrue(channel.tryWrite(71));
            });
            Thread resolver = Thread.startVirtualThread(() -> {
                awaitStart(go);
                assertTrue(producer.completeFromRuntime(82));
            });

            go.countDown();
            writer.join(5_000);
            resolver.join(5_000);
            assertFalse(writer.isAlive(), "writer stalled in round " + round);
            assertFalse(resolver.isAlive(), "resolver stalled in round " + round);
            var result = selected.get(5, TimeUnit.SECONDS);
            if (result.index() == 0) {
                assertEquals(71, result.value());
                assertTrue(channel.tryRead().isEmpty());
                assertEquals(82, producer.join());
            } else {
                assertEquals(1, result.index());
                assertEquals(82, result.value());
                assertEquals(71, channel.tryRead().orElseThrow());
            }
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    private static void awaitStart(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent select test gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent select test interrupted", interrupted);
        }
    }

    @Test
    void tooManySelectableDescriptorsFailWithoutConsumingAnyPayload() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertTrue(channel.tryWrite(91));
        var cases = new java.util.ArrayList<ChannelRuntime.SelectCase>();
        for (int i = 0; i < 257; i++) cases.add(ChannelRuntime.read(channel));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelRuntime.SelectSet(cases));
        assertEquals(91, channel.tryRead().orElseThrow());
    }

    @Test
    void perChannelWaiterQuotaStillAppliesToMixedFutureSelections() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var first = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        var sharedProducer = new OresFuture<Integer>();

        // Global pending-selection quota is not exhausted, but this channel's
        // own one-waiter budget is. Reject the entire heterogeneous selection.
        var rejected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(sharedProducer),
                ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var failure = assertThrows(CompletionException.class, rejected::join);
        assertTrue(failure.getCause()
                instanceof ChannelRuntime.ChannelBackpressureException);
        assertEquals(0, sharedProducer.pendingRuntimeWaiterCount());
        assertFalse(sharedProducer.isDone());
        assertFalse(first.isDone());

        // Failed arbitration may neither steal the queued read nor retain a
        // Future subscriber. The earlier registration is still deliverable.
        assertEquals(ChannelRuntime.SelectOperation.WRITE,
                ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, 31))
                        .selectAsync().join().operation());
        assertEquals(31, first.join().value());
        assertTrue(sharedProducer.completeFromRuntime(99));
    }

    @Test
    void readyFutureWinsBeforePerChannelQuotaIsEvaluated() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var waiting = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        try {
            var selected = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(OresFuture.completed(7)),
                    ChannelRuntime.read(channel))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();
            assertEquals(0, selected.index());
            assertEquals(7, selected.value());
            assertFalse(waiting.isDone());
        } finally {
            waiting.cancel(false);
        }
    }

    @Test
    void fullPendingQuotaCannotSuppressAnImmediatelyReadyFutureOrRead() {
        var pending = new java.util.ArrayList<OresFuture<ChannelRuntime.SelectResult>>();
        try {
            for (int i = 0; i < 4096; i++) {
                var channel = new ChannelRuntime.Channel<Integer>(0);
                pending.add(ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR));
            }
            var full = new ChannelRuntime.Channel<Integer>(0);
            var rejected = ChannelRuntime.SelectSet.of(ChannelRuntime.read(full)).selectAsync();
            var rejectedFailure = assertThrows(CompletionException.class, rejected::join);
            assertTrue(rejectedFailure.getCause()
                    instanceof ChannelRuntime.SelectAdmissionException);

            var readyFuture = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(OresFuture.completed(99))).selectAsync();
            assertEquals(99, readyFuture.join().value());

            var buffered = new ChannelRuntime.Channel<Integer>(1);
            assertTrue(buffered.tryWrite(100));
            var readyRead = ChannelRuntime.SelectSet.of(ChannelRuntime.read(buffered)).selectAsync();
            assertEquals(100, readyRead.join().value());
        } finally {
            for (var waiter : pending) waiter.cancel(false);
        }
    }

    @Test
    void selectableSourcesReduceToOneAtomicNextEventWithoutConsumingLosers() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var future = OresFuture.completed(55);
        ChannelRuntime.Selectable<Integer> nextChannelEvent =
                () -> ChannelRuntime.read(channel);
        ChannelRuntime.Selectable<Integer> futureEvent =
                () -> ChannelRuntime.await(future);
        var sources = java.util.List.of(nextChannelEvent, futureEvent);

        var first = ChannelRuntime.SelectSet.fromSelectables(sources)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, first.index());
        assertEquals(55, first.value());

        assertTrue(channel.tryWrite(77));
        var second = ChannelRuntime.SelectSet.fromSelectables(sources)
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();
        assertEquals(0, second.index());
        assertEquals(77, second.value());
    }

    @Test
    void selectableCannotForgeDefaultOrNullOperation() {
        ChannelRuntime.Selectable<Integer> forgedDefault = ChannelRuntime::defaultCase;
        ChannelRuntime.Selectable<Integer> nullOperation = () -> null;
        assertThrows(IllegalArgumentException.class, () ->
                ChannelRuntime.SelectSet.fromSelectables(java.util.List.of(forgedDefault)));
        assertThrows(NullPointerException.class, () ->
                ChannelRuntime.SelectSet.fromSelectables(java.util.List.of(nullOperation)));
    }

    @Test
    void immediateProbeOnPendingFutureRegistersNoWaiter() {
        var future = new OresFuture<Integer>();
        var set = ChannelRuntime.SelectSet.of(ChannelRuntime.await(future));

        assertTrue(set.trySelect().isEmpty());
        assertEquals(0, future.pendingRuntimeWaiterCount());

        assertTrue(future.completeFromRuntime(4));
        assertEquals(4, set.trySelect().orElseThrow().value());
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }
}
