package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Removing HTTP queue entries never destroys ordinary FIFO messages. */
final class ChannelSelectiveRetirementTest {
    @Test void selectiveRetirementPreservesOrderOfAllOtherMessages() {
        var channel = new ChannelRuntime.Channel<String>(8);
        for (String s : List.of("ordinary-one", "http-one", "ordinary-two",
                "http-two", "ordinary-three"))
            assertTrue(channel.tryWrite(s));
        var removed = new ArrayList<String>();
        assertTrue(channel.removeBufferedIf(s -> s.startsWith("http-"), removed::add));
        assertEquals(List.of("http-one", "http-two"), removed);
        assertEquals(3, channel.size());
        assertEquals("ordinary-one", channel.tryRead().orElseThrow());
        assertEquals("ordinary-two", channel.tryRead().orElseThrow());
        assertEquals("ordinary-three", channel.tryRead().orElseThrow());
        assertTrue(channel.tryRead().isEmpty());
        assertFalse(channel.removeBufferedIf(s -> true, removed::add));
    }

    @Test void retirementCannotRecallDeliveredValues() {
        var channel = new ChannelRuntime.Channel<String>(4);
        assertTrue(channel.tryWrite("already-delivered"));
        assertEquals("already-delivered", channel.tryRead().orElseThrow());
        var removed = new ArrayList<String>();
        assertFalse(channel.removeBufferedIf(s -> true, removed::add));
        assertTrue(removed.isEmpty());
    }

    @Test void selectiveRetirementAfterClosureStillReleasesBufferedValues() {
        var channel = new ChannelRuntime.Channel<String>(4);
        assertTrue(channel.tryWrite("normal"));
        assertTrue(channel.tryWrite("http"));
        channel.close();
        var removed = new ArrayList<String>();
        assertTrue(channel.removeBufferedIf("http"::equals, removed::add));
        assertEquals(List.of("http"), removed);
        assertEquals("normal", channel.tryRead().orElseThrow());
    }
}
