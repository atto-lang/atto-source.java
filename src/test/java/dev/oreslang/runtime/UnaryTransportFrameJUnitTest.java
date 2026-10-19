package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises the strict unary framing and reply ownership contract in Maven. */
class UnaryTransportFrameJUnitTest {
    @Test @Timeout(25)
    void tcpFramingWebSocketBinaryEnvelopeAndTerminalRaces() throws Exception {
        UnaryTransportFrameCheck.main(new String[0]);
    }
}
