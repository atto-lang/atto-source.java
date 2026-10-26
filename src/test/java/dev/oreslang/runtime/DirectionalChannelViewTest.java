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
