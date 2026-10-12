package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class ChannelRuntimeTest {
    @Test
    void internalDrainReleasesBufferedCapacityAndWakesPendingWriter() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite("first"));
        OresFuture<Void> waitingWriter = channel.writeAsync("second");
        assertFalse(waitingWriter.isDone());

        java.util.concurrent.atomic.AtomicReference<String> delivered =
                new java.util.concurrent.atomic.AtomicReference<>();
        assertTrue(channel.drainOne(delivered::set));
        assertEquals("first", delivered.get());
        assertTrue(waitingWriter.isDone(),
                "opening capacity must commit a queued writer without a second wake");
        waitingWriter.join();
        assertEquals("second", channel.tryRead().orElseThrow());
        assertFalse(channel.drainOne(ignored -> {
            throw new AssertionError("empty drain must not invoke consumer");
        }));
    }

    @Test
    void drainConsumerFailureStillDeliversPendingWriterCompletion() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(1));
        OresFuture<Void> queued = channel.writeAsync(2);
        assertThrows(IllegalStateException.class,
                () -> channel.drainOne(ignored -> {
                    throw new IllegalStateException("consumer failed");
                }));
        assertTrue(queued.isDone(),
                "runtime completion cannot remain pending after an exception");
        queued.join();
        assertEquals(2, channel.tryRead().orElseThrow());
    }

    @Test
    void uncontendedImmediateProbesAndRendezvousPreserveChannelSemantics() {
        ChannelRuntime.Channel<Integer> buffered = new ChannelRuntime.Channel<>(1);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(buffered.tryWrite(i));
            assertFalse(buffered.tryWrite(-1), "full buffer must reject a probe");
            assertEquals(i, buffered.tryRead().orElseThrow());
            assertTrue(buffered.tryRead().isEmpty());
        }
        buffered.close();
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> buffered.tryWrite(1)).getCause());
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                assertThrows(java.util.concurrent.CompletionException.class,
                        buffered::tryRead).getCause());

        ChannelRuntime.Channel<Integer> rendezvous = new ChannelRuntime.Channel<>(0);
        assertFalse(rendezvous.tryWrite(17));
        assertTrue(rendezvous.tryRead().isEmpty());
        OresFuture<Integer> reader = rendezvous.readAsync();
        assertTrue(rendezvous.tryWrite(18),
                "registered rendezvous reader must force slow-path arbitration");
        assertEquals(18, reader.join());

        OresFuture<Void> writer = rendezvous.writeAsync(19);
        assertEquals(19, rendezvous.tryRead().orElseThrow(),
                "registered rendezvous writer must force slow-path arbitration");
        writer.join();
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
    void reusedThreeArmPlanRemainsFairAcrossTenThousandReadySelections() {
        ChannelRuntime.Channel<Integer> control = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> workers = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> timers = new ChannelRuntime.Channel<>(1);
        assertTrue(control.tryWrite(0));
        assertTrue(workers.tryWrite(1));
        assertTrue(timers.tryWrite(2));

        ChannelRuntime.SelectPlan plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(control),
                ChannelRuntime.read(workers),
                ChannelRuntime.read(timers));
        var descriptors = plan.cases();
        int[] selected = new int[3];
        for (int i = 0; i < 10_000; ++i) {
            ChannelRuntime.SelectResult result =
                    plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
            assertEquals(i % 3, result.index(),
                    "simultaneously-ready cases must rotate on the reused plan");
            assertEquals(result.index(), result.value());
            selected[result.index()]++;
            ChannelRuntime.Channel<Integer> won =
                    switch (result.index()) {
                        case 0 -> control;
                        case 1 -> workers;
                        case 2 -> timers;
                        default -> throw new AssertionError("invalid select arm");
                    };
            assertTrue(won.tryWrite(result.index()),
                    "every iteration must consume only the winning arm");
            assertSame(descriptors, plan.cases(),
                    "immutable plan descriptors must be reused each iteration");
        }
        assertArrayEquals(new int[] {3334, 3333, 3333}, selected);
        assertEquals(1, control.size());
        assertEquals(1, workers.size());
        assertEquals(1, timers.size());
    }

    @Test
    void cancelledGenerationCannotStealMessagesFromLaterPlanIteration() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan =
                ChannelRuntime.SelectPlan.of(ChannelRuntime.read(channel));
        for (int i = 0; i < 1_000; ++i) {
            OresFuture<ChannelRuntime.SelectResult> cancelled =
                    plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
            assertFalse(cancelled.isDone());
            assertTrue(cancelled.cancel(false));
            OresFuture<ChannelRuntime.SelectResult> next =
                    plan.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
            assertTrue(channel.tryWrite("turn-" + i));
            assertEquals("turn-" + i, next.join().value());
            assertThrows(CancellationException.class, cancelled::join);
            assertTrue(channel.tryRead().isEmpty(),
                    "cancelled generations must not retain a waiter or steal data");
        }
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
