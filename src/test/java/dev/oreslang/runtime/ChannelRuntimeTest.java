package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ChannelRuntimeTest {
    @Test
    void orderedSelectIsDeterministicAndUsesCaseOrder() throws Exception {
        var first = ChannelRuntime.<String>channel(4);
        var second = ChannelRuntime.<String>channel(4);
        assertTrue(first.tryWrite("first"));
        assertTrue(second.tryWrite("second"));

        var selected = ChannelRuntime.select(
                List.of(
                        new ChannelRuntime.ReadCase<>(first, "first"),
                        new ChannelRuntime.ReadCase<>(second, "second")),
                ChannelRuntime.SelectPolicy.ORDERED)
                .get(1, TimeUnit.SECONDS);

        assertEquals(0, selected.index());
        assertEquals("first", selected.key());
        assertEquals(ChannelRuntime.Operation.READ, selected.operation());
        assertEquals("first", selected.value());
        assertEquals("second", second.tryRead().orElseThrow());
    }

    @Test
    void fairSelectSetRotatesFirstProbeWithoutRandomness() throws Exception {
        var first = ChannelRuntime.<Integer>channel(4);
        var second = ChannelRuntime.<Integer>channel(4);
        first.tryWrite(1);
        second.tryWrite(2);

        var set = ChannelRuntime.selectSet(List.of(
                new ChannelRuntime.ReadCase<>(first, "a"),
                new ChannelRuntime.ReadCase<>(second, "b")));

        assertEquals(ChannelRuntime.SelectPolicy.FAIR, set.policy());

        var one = set.next().get(1, TimeUnit.SECONDS);
        assertEquals("a", one.key());

        first.tryWrite(3);
        var two = set.next().get(1, TimeUnit.SECONDS);
        assertEquals("b", two.key());
    }

    @Test
    void dynamicMapCasesPreserveExplicitMapOrderAndKeys() throws Exception {
        var alpha = ChannelRuntime.<String>channel(2);
        var beta = ChannelRuntime.<String>channel(2);
        alpha.tryWrite("a");
        beta.tryWrite("b");

        Map<String, ChannelRuntime.Channel<String>> channels = new LinkedHashMap<>();
        channels.put("beta", beta);
        channels.put("alpha", alpha);

        var selected = ChannelRuntime.select(ChannelRuntime.readCases(channels))
                .get(1, TimeUnit.SECONDS);

        assertEquals("beta", selected.key());
        assertEquals("b", selected.value());
    }

    @Test
    void pendingSelectIsNonblockingUntilAChannelBecomesReady() throws Exception {
        var incoming = ChannelRuntime.<String>channel(1);
        var selected = ChannelRuntime.nbSelect(List.of(
                new ChannelRuntime.ReadCase<>(incoming, "incoming")));

        assertFalse(selected.isDone(), "arming select must not park or complete without readiness");
        assertTrue(incoming.tryWrite("hello"));

        var result = selected.get(1, TimeUnit.SECONDS);
        assertEquals("incoming", result.key());
        assertEquals("hello", result.value());
    }

    @Test
    void cancellingASelectNeverConsumesALosingFutureValue() {
        var incoming = ChannelRuntime.<String>channel(1);
        var selected = ChannelRuntime.select(List.of(
                new ChannelRuntime.ReadCase<>(incoming, "incoming")));

        assertTrue(selected.cancel(false));
        assertTrue(incoming.tryWrite("still-there"));
        assertEquals("still-there", incoming.tryRead().orElseThrow());
    }

    @Test
    void rendezvousReadAndWriteSuspendAsFuturesWithoutParkingCarrier() throws Exception {
        var channel = ChannelRuntime.<String>rendezvous();

        var read = ChannelRuntime.nbRead(channel);
        assertFalse(read.isDone());

        var write = ChannelRuntime.nbWrite(channel, "payload");
        assertTrue(write.isDone());
        assertEquals("payload", read.get(1, TimeUnit.SECONDS));
        assertNull(write.get(1, TimeUnit.SECONDS));
    }

    @Test
    void closingChannelFailsPendingOperations() {
        var channel = ChannelRuntime.<String>rendezvous();
        var read = channel.read();

        channel.close();

        assertTrue(read.isDone());
        assertTrue(read.isCompletedExceptionally());
        assertThrows(ChannelRuntime.ChannelClosedException.class, () -> channel.tryWrite("nope"));
    }

    @Test
    void cancellingPendingReadDetachesItBeforeLaterWrites() {
        var channel = ChannelRuntime.<String>channel(1);
        var read = ChannelRuntime.nbRead(channel);

        assertTrue(read.cancel(false));
        assertTrue(channel.tryWrite("preserved"));
        assertEquals("preserved", channel.tryRead().orElseThrow());
    }

    @Test
    void cancellingPendingWritePreventsLaterReadFromObservingIt() {
        var channel = ChannelRuntime.<String>rendezvous();
        var write = ChannelRuntime.nbWrite(channel, "cancelled");

        assertTrue(write.cancel(false));
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test
    void cancellationCannotWinAfterChannelCommit() throws Exception {
        var channel = ChannelRuntime.<String>rendezvous();
        var read = channel.read();

        var write = channel.write("committed");
        assertFalse(read.cancel(false), "committed read must reject late cancellation");
        assertFalse(write.cancel(false), "committed write must reject late cancellation");
        assertEquals("committed", read.get(1, TimeUnit.SECONDS));
        assertNull(write.get(1, TimeUnit.SECONDS));
    }

    @Test
    void nbReadRegistersWithoutSuspendingAndCompletesLater() throws Exception {
        var channel = ChannelRuntime.<String>channel(1);

        var read = ChannelRuntime.nbRead(channel);
        assertFalse(read.isDone(), "nb readch must return immediately with a pending Future");

        assertTrue(channel.tryWrite("later"));
        assertEquals("later", read.get(1, TimeUnit.SECONDS));
    }

    @Test
    void nbWriteRegistersWithoutSuspendingAndCompletesWhenReceiverArrives() throws Exception {
        var channel = ChannelRuntime.<String>rendezvous();

        var write = ChannelRuntime.nbWrite(channel, "payload");
        assertFalse(write.isDone(), "nb writech must return immediately with a pending Future");

        assertEquals("payload", channel.tryRead().orElseThrow());
        assertNull(write.get(1, TimeUnit.SECONDS));
    }

    @Test
    void tryOperationsProbeOnlyAndNeverRegisterWaiters() {
        var channel = ChannelRuntime.<String>rendezvous();

        assertTrue(channel.tryRead().isEmpty());
        assertFalse(channel.tryWrite("not-registered"));

        assertTrue(channel.tryRead().isEmpty(),
                "failed try writech must not leave a pending writer behind");
    }

    @Test
    void defaultSelectSetIsFairWhileOrderedIsExplicitPriority() throws Exception {
        var first = ChannelRuntime.<Integer>channel(4);
        var second = ChannelRuntime.<Integer>channel(4);
        first.tryWrite(1);
        second.tryWrite(2);

        var fair = ChannelRuntime.selectSet(List.of(
                new ChannelRuntime.ReadCase<>(first, "first"),
                new ChannelRuntime.ReadCase<>(second, "second")));

        assertEquals("first", fair.next().get(1, TimeUnit.SECONDS).key());
        first.tryWrite(3);
        assertEquals("second", fair.next().get(1, TimeUnit.SECONDS).key());

        var ordered = ChannelRuntime.selectSet(
                List.of(
                        new ChannelRuntime.ReadCase<>(first, "first"),
                        new ChannelRuntime.ReadCase<>(second, "second")),
                ChannelRuntime.SelectPolicy.ORDERED);
        second.tryWrite(4);
        assertEquals("first", ordered.next().get(1, TimeUnit.SECONDS).key());
    }


}
