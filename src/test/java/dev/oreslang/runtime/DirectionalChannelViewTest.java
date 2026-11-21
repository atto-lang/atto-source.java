package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DirectionalChannelViewTest {
    @Test
    void viewsAreIdentityStableAndShareTheExactBackingChannel() {
        var channel = new ChannelRuntime.Channel<Integer>(1);

        assertSame(channel.input(), channel.input());
        assertSame(channel.output(), channel.output());
        assertTrue(channel.input().tryWrite(17));
        assertEquals(17, channel.output().tryRead().orElseThrow());
        assertEquals(0, channel.size());
    }

    @Test
    void directionalViewsDoNotExposeBackingOrWrongDirectionAuthority() {
        assertThrows(NoSuchMethodException.class,
                () -> ChannelRuntime.ReadChannel.class.getMethod("backingChannel"));
        assertThrows(NoSuchMethodException.class,
                () -> ChannelRuntime.WriteChannel.class.getMethod("backingChannel"));
        assertThrows(NoSuchMethodException.class,
                () -> ChannelRuntime.ReadChannel.class.getMethod("tryWrite", Object.class));
        assertThrows(NoSuchMethodException.class,
                () -> ChannelRuntime.ReadChannel.class.getMethod("close"));
        assertThrows(NoSuchMethodException.class,
                () -> ChannelRuntime.WriteChannel.class.getMethod("tryRead"));
        assertThrows(NoSuchMethodException.class,
                () -> ChannelRuntime.WriteChannel.class.getMethod("close"));
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
