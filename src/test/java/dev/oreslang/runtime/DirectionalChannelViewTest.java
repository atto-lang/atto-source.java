package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DirectionalChannelViewTest {
    @Test
    void viewsAreIdentityStableAndShareTheExactBackingChannel() {
        var channel = new ChannelRuntime.Channel<Integer>(1);

        assertSame(channel.input(), channel.input());
        assertSame(channel.output(), channel.output());
        // Neither public endpoint nor a select descriptor may reveal the
        // backing Channel<T>, which would grant close and opposite direction.
        assertSame(channel.input(), ChannelRuntime.write(channel.input(), 3).channel());
        assertSame(channel.output(), ChannelRuntime.read(channel.output()).channel());
        assertSame(channel.input(), ChannelRuntime.write(channel, 3).channel());
        assertSame(channel.output(), ChannelRuntime.read(channel).channel());

        assertTrue(channel.input().tryWrite(17));
        assertEquals(17, channel.output().tryRead().orElseThrow());
        assertEquals(0, channel.size());
    }

    @Test
    void publicJavaApiCannotRecoverFullChannelFromDirectionalCapabilities()
            throws Exception {
        // getMethod enumerates only public surface (including inherited methods).
        assertThrows(NoSuchMethodException.class, () ->
                ChannelRuntime.ReadChannel.class.getMethod("backingChannel"));
        assertThrows(NoSuchMethodException.class, () ->
                ChannelRuntime.WriteChannel.class.getMethod("backingChannel"));

        assertEquals(ChannelRuntime.ReadChannel.class,
                ChannelRuntime.ReadCase.class.getMethod("channel").getReturnType());
        assertEquals(ChannelRuntime.WriteChannel.class,
                ChannelRuntime.WriteCase.class.getMethod("channel").getReturnType());

        for (Class<?> type : java.util.List.of(
                ChannelRuntime.ReadChannel.class,
                ChannelRuntime.WriteChannel.class,
                ChannelRuntime.ReadCase.class,
                ChannelRuntime.WriteCase.class)) {
            for (var method : type.getMethods()) {
                assertNotEquals(ChannelRuntime.Channel.class, method.getReturnType(),
                        () -> type.getSimpleName() + "." + method.getName()
                                + " escapes the full channel capability");
            }
        }
    }

    @Test
    void publicSelectDescriptorsPreserveDirectionAndOneBackingQueue() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        ChannelRuntime.ReadCase<Integer> read = ChannelRuntime.read(channel.output());
        ChannelRuntime.WriteCase<Integer> write = ChannelRuntime.write(channel.input(), 29);

        assertSame(channel.output(), read.channel());
        assertSame(channel.input(), write.channel());
        assertEquals(29, write.value());
        assertSame(read.channel(), ChannelRuntime.read(channel).channel());
        assertSame(write.channel(), ChannelRuntime.write(channel, 29).channel());

        // The channel coordinator can still perform atomic select using only
        // these non-escalating descriptors.
        var chosen = ChannelRuntime.SelectSet.of(write)
                .trySelect(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals(ChannelRuntime.SelectOperation.WRITE, chosen.operation());
        assertEquals(29, channel.output().tryRead().orElseThrow());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void directionalViewsParticipateInAtomicSelectWithoutAnotherQueue() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel.output()))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertFalse(selected.isDone());
        assertTrue(channel.input().tryWrite(41));

        var winner = selected.join();
        assertEquals(ChannelRuntime.SelectOperation.READ, winner.operation());
        assertEquals(41, winner.value());
        assertEquals(0, channel.size());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void rendezvousOnDirectionalViewsHonorsSinglePendingArmBudget() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var writer = channel.input();
        var reader = channel.output();

        var pendingWrite = writer.writeAsync(71);
        assertFalse(pendingWrite.isDone());
        assertEquals(1, channel.pendingRegistrations());

        // This atomic handoff must not require a second retained waiter
        // even while the one-slot registration budget is full.
        var selected = ChannelRuntime.SelectSet.of(ChannelRuntime.read(reader))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var delivered = selected.join();
        assertEquals(ChannelRuntime.SelectOperation.READ, delivered.operation());
        assertEquals(71, delivered.value());
        pendingWrite.join();
        assertEquals(0, channel.size());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void cancelledDirectionalReadCannotStealFutureRendezvous() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var pendingRead = channel.output().readAsync();
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(pendingRead.cancel(false));
        assertEquals(0, channel.pendingRegistrations());

        // A canceled reader may never consume a later producer's payload.
        assertFalse(channel.input().tryWrite(83));
        var nextRead = channel.output().readAsync();
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(channel.input().tryWrite(83));
        assertEquals(83, nextRead.join());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void cancelledDirectionalWriteCannotPublishIntoLaterReader() {
        var channel = new ChannelRuntime.Channel<Integer>(0, 1);
        var pendingWrite = channel.input().writeAsync(101);
        assertEquals(1, channel.pendingRegistrations());
        assertTrue(pendingWrite.cancel(false));
        assertEquals(0, channel.pendingRegistrations());

        assertTrue(channel.output().tryRead().isEmpty(),
                "canceled write must not leave a phantom value");
        var replacementWrite = channel.input().writeAsync(102);
        assertEquals(102, channel.output().readAsync().join());
        replacementWrite.join();
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void closedDirectionalViewsDrainCommittedValuesButRejectNewOperations() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var writer = channel.input();
        var reader = channel.output();
        assertTrue(writer.tryWrite(97));
        var cause = new IllegalStateException("expected shutdown");
        channel.close(cause);

        assertTrue(writer.isClosed());
        assertTrue(reader.isClosed());
        assertEquals(97, reader.tryRead().orElseThrow());
        assertEquals(0, channel.size());

        var readFailure = assertThrows(ChannelRuntime.ChannelClosedException.class,
                () -> reader.readAsync().join());
        assertSame(cause, readFailure.getCause());
        var writeFailure = assertThrows(ChannelRuntime.ChannelClosedException.class,
                () -> writer.writeAsync(99).join());
        assertSame(cause, writeFailure.getCause());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void pendingDirectionalWriterCannotBeCommittedAfterOwnerClose() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var writer = channel.input();
        var reader = channel.output();

        assertTrue(writer.tryWrite(1));
        var pending = writer.writeAsync(2);
        assertFalse(pending.isDone(), "full channel must defer the second admission");
        assertEquals(1, channel.pendingRegistrations());

        var cause = new IllegalStateException("actor pipeline owner retired");
        channel.close(cause);

        // Already committed data survives close, but an uncommitted writer
        // must never appear later as a phantom message.
        assertEquals(1, reader.tryRead().orElseThrow());
        var writeFailure = assertThrows(ChannelRuntime.ChannelClosedException.class,
                pending::join);
        assertSame(cause, writeFailure.getCause());
        assertTrue(reader.tryRead().isEmpty());
        var readFailure = assertThrows(ChannelRuntime.ChannelClosedException.class,
                () -> reader.readAsync().join());
        assertSame(cause, readFailure.getCause());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void separateDirectionalChannelsNeverShareQueueOrPendingSelectArms() {
        var first = new ChannelRuntime.Channel<Integer>(1);
        var second = new ChannelRuntime.Channel<Integer>(1);
        var pending = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first.output()))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(pending.isDone());

        assertTrue(second.input().tryWrite(23));
        assertFalse(pending.isDone(), "other channel must not wake this selector");
        assertEquals(23, second.output().tryRead().orElseThrow());
        assertTrue(first.input().tryWrite(41));
        assertEquals(41, pending.join().value());
        assertEquals(0, first.pendingRegistrations());
        assertEquals(0, second.pendingRegistrations());
    }

    @Test
    void closeRemainsOwnedByTheFullChannelCapability() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertFalse(channel.input().isClosed());
        assertFalse(channel.output().isClosed());

        channel.close();

        assertTrue(channel.input().isClosed());
        assertTrue(channel.output().isClosed());
        assertThrows(ChannelRuntime.ChannelClosedException.class,
                () -> channel.input().writeAsync(1).join());
        assertThrows(ChannelRuntime.ChannelClosedException.class,
                () -> channel.output().readAsync().join());
    }
}
