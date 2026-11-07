package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ChannelRuntimeTest {
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
    @Test
    void publicSelectDescriptorsExposeOnlyDirectionalCapabilities() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.ReadChannel<Integer> output = channel.output();
        ChannelRuntime.WriteChannel<Integer> input = channel.input();

        assertSame(output, channel.output());
        assertSame(input, channel.input());
        assertSame(output, ChannelRuntime.read(channel).channel());
        assertSame(input, ChannelRuntime.write(channel, 29).channel());
        assertSame(output, new ChannelRuntime.ReadCase<>(channel).channel());
        assertSame(input, new ChannelRuntime.WriteCase<>(channel, 29).channel());

        assertEquals(
                ChannelRuntime.ReadChannel.class,
                ChannelRuntime.ReadCase.class.getMethod("channel").getReturnType());
        assertEquals(
                ChannelRuntime.WriteChannel.class,
                ChannelRuntime.WriteCase.class.getMethod("channel").getReturnType());
        assertThrows(
                NoSuchMethodException.class,
                () -> ChannelRuntime.ReadChannel.class.getMethod("backingChannel"));
        assertThrows(
                NoSuchMethodException.class,
                () -> ChannelRuntime.WriteChannel.class.getMethod("backingChannel"));
        assertThrows(
                NoSuchMethodException.class,
                () -> ChannelRuntime.ReadChannel.class.getMethod("tryWrite", Object.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> ChannelRuntime.WriteChannel.class.getMethod("tryRead"));
        assertThrows(
                NoSuchMethodException.class,
                () -> ChannelRuntime.ReadChannel.class.getMethod("close"));
        assertThrows(
                NoSuchMethodException.class,
                () -> ChannelRuntime.WriteChannel.class.getMethod("close"));

        assertTrue(channel.tryWrite(17));
        ChannelRuntime.SelectResult selectedRead = ChannelRuntime.SelectSet
                .of(ChannelRuntime.read(output))
                .trySelect(ChannelRuntime.SelectPolicy.PRIORITY)
                .orElseThrow();
        assertEquals(ChannelRuntime.SelectOperation.READ, selectedRead.operation());
        assertEquals(17, selectedRead.value());

        ChannelRuntime.SelectResult selectedWrite = ChannelRuntime.SelectSet
                .of(ChannelRuntime.write(input, 29))
                .trySelect(ChannelRuntime.SelectPolicy.PRIORITY)
                .orElseThrow();
        assertEquals(ChannelRuntime.SelectOperation.WRITE, selectedWrite.operation());
        assertEquals(29, output.tryRead().orElseThrow());
    }

    void priorityWriteSelectPublishesOnlyTheWinningPayload() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.write(first, 11),
                ChannelRuntime.write(second, 22));

        ChannelRuntime.SelectResult result =
                set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();

        assertEquals(0, result.index());
        assertEquals(ChannelRuntime.SelectOperation.WRITE, result.operation());
        assertEquals(11, first.tryRead().orElseThrow());
        assertTrue(second.tryRead().isEmpty(),
                "a losing write arm must never publish its payload");
        assertEquals(0, first.pendingRegistrations());
        assertEquals(0, second.pendingRegistrations());
    }

    @Test
    void cancellingMultiWriteSelectPublishesNoLosingPayloads() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.write(first, 11),
                        ChannelRuntime.write(second, 22))
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);

        assertEquals(1, first.pendingRegistrations());
        assertEquals(1, second.pendingRegistrations());
        assertTrue(selected.cancel(false));
        assertThrows(CancellationException.class, selected::join);
        assertEquals(0, first.pendingRegistrations());
        assertEquals(0, second.pendingRegistrations());
        assertTrue(first.tryRead().isEmpty());
        assertTrue(second.tryRead().isEmpty());
    }

    @Test
    void competingReadersAllowExactlyOneWinnerForOneWrite() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> first =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        OresFuture<ChannelRuntime.SelectResult> second =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertTrue(channel.tryWrite(31));
        int completed = (first.isDone() ? 1 : 0) + (second.isDone() ? 1 : 0);
        assertEquals(1, completed, "one rendezvous write may commit only one selection");

        OresFuture<ChannelRuntime.SelectResult> winner = first.isDone() ? first : second;
        OresFuture<ChannelRuntime.SelectResult> loser = first.isDone() ? second : first;
        assertEquals(31, winner.join().value());
        assertFalse(loser.isDone());
        assertTrue(loser.cancel(false));
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void cancellationVersusReadWinnerPreservesExactlyOneOutcome() throws Exception {
        for (int round = 0; round < 64; round++) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
            OresFuture<ChannelRuntime.SelectResult> selected =
                    ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                            .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            CountDownLatch race = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    if (!race.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancel race gate timed out");
                    }
                    selected.cancel(false);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            Thread writer = Thread.startVirtualThread(() -> {
                try {
                    if (!race.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("write race gate timed out");
                    }
                    assertTrue(channel.tryWrite(41));
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });

            race.countDown();
            canceller.join(5_000);
            writer.join(5_000);
            assertFalse(canceller.isAlive(), "canceller stalled: " + round);
            assertFalse(writer.isAlive(), "writer stalled: " + round);
            if (failure.get() != null) {
                throw new AssertionError("cancel/read race failed: " + round, failure.get());
            }

            if (selected.isCancelled()) {
                assertThrows(CancellationException.class, selected::join);
                assertEquals(41, channel.tryRead().orElseThrow(),
                        "cancel winner must preserve the later message");
            } else {
                assertEquals(41, selected.join().value(),
                        "read winner must receive the raced message");
                assertTrue(channel.tryRead().isEmpty(),
                        "a committed read may consume the message only once");
            }
            assertEquals(0, channel.pendingRegistrations(),
                    "race retained a channel registration: " + round);
        }
    }

    @Test
    void cancellationVersusWriteWinnerNeverPublishesAfterCancellation() throws Exception {
        for (int round = 0; round < 64; round++) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            OresFuture<ChannelRuntime.SelectResult> selected =
                    ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, 73))
                            .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            CountDownLatch race = new CountDownLatch(1);
            AtomicReference<java.util.Optional<Integer>> received = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    if (!race.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancel race gate timed out");
                    }
                    selected.cancel(false);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            Thread reader = Thread.startVirtualThread(() -> {
                try {
                    if (!race.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("read race gate timed out");
                    }
                    received.set(channel.tryRead());
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });

            race.countDown();
            canceller.join(5_000);
            reader.join(5_000);
            assertFalse(canceller.isAlive(), "canceller stalled: " + round);
            assertFalse(reader.isAlive(), "reader stalled: " + round);
            if (failure.get() != null) {
                throw new AssertionError("cancel/write race failed: " + round, failure.get());
            }

            if (selected.isCancelled()) {
                assertThrows(CancellationException.class, selected::join);
                assertTrue(received.get().isEmpty(),
                        "cancel winner must not publish the write payload");
            } else {
                assertEquals(ChannelRuntime.SelectOperation.WRITE,
                        selected.join().operation());
                assertEquals(73, received.get().orElseThrow(),
                        "write winner must publish exactly one payload");
            }
            assertEquals(0, channel.pendingRegistrations(),
                    "race retained a channel registration: " + round);
        }
    }


}
