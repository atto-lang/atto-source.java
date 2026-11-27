package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProcessChannelCloseSemanticsTest {
    @Test void normalEofAndCausedCloseRemainDistinct() {
        var eof = new ChannelRuntime.Channel<String>(2);
        assertTrue(eof.tryWrite("queued"));
        eof.close();
        assertTrue(eof.isClosed());
        assertTrue(eof.isClosedNormally());
        assertEquals("queued", eof.tryRead().orElseThrow());

        var failed = new ChannelRuntime.Channel<String>(2);
        assertTrue(failed.tryWrite("queued"));
        failed.close(new IllegalStateException("synthetic stdin read failure"));
        assertTrue(failed.isClosed());
        assertFalse(failed.isClosedNormally());
        assertEquals("queued", failed.tryRead().orElseThrow());
    }
}
