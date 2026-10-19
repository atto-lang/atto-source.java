package dev.oreslang.runtime;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for the peer-specific WebSocket unary envelope limit. */
class UnaryWebSocketBudgetJUnitTest {
    @Test void binaryEnvelopeMustRespectPerPeerBudget() throws Exception {
        var response = new UnaryTransportFrame.Envelope(
                UnaryTransportFrame.Kind.REPLY, 71, 1, 99, new byte[] {1, 2, 3, 4, 5});
        byte[] message = UnaryTransportFrame.encodeWebSocketBinary(response);
        assertEquals(5, UnaryTransportFrame.decodeWebSocketBinary(message, 5).payload().length);
        assertThrows(IOException.class, () -> UnaryTransportFrame.decodeWebSocketBinary(message, 4));
        assertThrows(IllegalArgumentException.class,
                () -> UnaryTransportFrame.decodeWebSocketBinary(message, -1));
        assertThrows(IOException.class, () -> UnaryTransportFrame.decodeWebSocketBinary(null, 5));

        var empty = new UnaryTransportFrame.Envelope(
                UnaryTransportFrame.Kind.REPLY, 71, 1, 100, new byte[0]);
        assertEquals(0, UnaryTransportFrame.decodeWebSocketBinary(
                UnaryTransportFrame.encodeWebSocketBinary(empty), 0).payload().length);
    }
}
