package dev.oreslang;

import dev.oreslang.parser.ActorProtocolTypestateV1;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Source grammar/type-contract tests; no claim of protocol guest execution. */
final class ActorProtocolTypestateV1Test {
    private static final String SOURCE = """
            define protocol ConnectionProtocol as
              initial Disconnected
              state Disconnected as
                on Connect(Config) -> Connected replies ConnectedAck
                on Close -> Closed replies ClosedAck
              end
              state Connected as
                on Query(Sql) -> Connected replies QueryReply
                on Subscribe(Topic) -> Connected streams DataEvent
                on Close -> Closed replies ClosedAck
              end
              terminal state Closed
            end
            define actor Connection implements ConnectionProtocol as
              receive(ActorMail<String> mail): void { return; }
            end
            """;

    @Test void parserBuildsTypedUnaryAndStreamDeclarationMetadata() {
        var c = ActorProtocolTypestateV1.parseSource(SOURCE);
        assertEquals(1, c.protocols().size());
        assertEquals("Disconnected", c.protocols().getFirst().initial());
        assertEquals(5, c.protocols().getFirst().transitions().size());
        assertEquals(List.of(new ActorProtocolTypestateV1.ActorBinding("Connection", "ConnectionProtocol")),
                c.actorBindings());
        assertEquals(ActorProtocolTypestateV1.Mode.UNARY,
                c.checkSend("ConnectionProtocol", List.of("Disconnected"), "Connect").mode());
        assertEquals(ActorProtocolTypestateV1.Mode.STREAM,
                c.checkSend("ConnectionProtocol", List.of("Connected"), "Subscribe").mode());
        assertEquals("DataEvent",
                c.checkSend("ConnectionProtocol", List.of("Connected"), "Subscribe").responseType());
        assertTrue(c.checkSend("ConnectionProtocol", List.of("Connected"), "Subscribe").consumesAuthority());
    }

    @Test void stateUnionsRequireTheSameResponseCardinalityAndPayload() {
        var c = ActorProtocolTypestateV1.parseSource(SOURCE);
        assertEquals(List.of("Closed"), c.checkSend("ConnectionProtocol",
                List.of("Disconnected", "Connected"), "Close").nextStates());
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                c.checkSend("ConnectionProtocol", List.of("Disconnected", "Connected"), "Query"))
                .getMessage().contains("E_PROTOCOL_ILLEGAL_MESSAGE_STATE"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                c.checkSend("ConnectionProtocol", List.of("Disconnected"), "Subscribe"))
                .getMessage().contains("E_PROTOCOL_ILLEGAL_MESSAGE_STATE"));
        String changed = SOURCE.replaceFirst("on Close -> Closed replies ClosedAck",
                "on Close -> Closed streams ClosedAck");
        var mixed = ActorProtocolTypestateV1.parseSource(changed);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> mixed.checkSend(
                "ConnectionProtocol", List.of("Disconnected", "Connected"), "Close"))
                .getMessage().contains("E_PROTOCOL_RESPONSE_MODE"));
    }

    @Test void malformedProtocolsFailClosedWithSpecificErrors() {
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                ActorProtocolTypestateV1.parseSource(SOURCE.replace("-> Connected replies QueryReply", "-> Missing replies QueryReply")))
                .getMessage().contains("E_PROTOCOL_UNKNOWN_STATE"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                ActorProtocolTypestateV1.parseSource(SOURCE.replace("on Subscribe(Topic) -> Connected streams DataEvent",
                        "on Query(Sql) -> Connected streams DataEvent")))
                .getMessage().contains("E_PROTOCOL_DUPLICATE"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                ActorProtocolTypestateV1.parseSource(SOURCE.replace("streams DataEvent", "yields DataEvent")))
                .getMessage().contains("E_PROTOCOL_RESPONSE_MODE"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                ActorProtocolTypestateV1.parseSource(SOURCE.replace("terminal state Closed", "terminal state Disconnected")))
                .getMessage().contains("E_PROTOCOL_DUPLICATE"));
    }

    @Test void nominalMessageModesMustMatchProtocolTransitions() {
        var c = ActorProtocolTypestateV1.parseSource(SOURCE);
        var nominal = Map.of(
                "Connect", new ActorProtocolTypestateV1.NominalMessage(
                        ActorProtocolTypestateV1.Mode.UNARY, "ConnectedAck"),
                "Close", new ActorProtocolTypestateV1.NominalMessage(
                        ActorProtocolTypestateV1.Mode.UNARY, "ClosedAck"),
                "Query", new ActorProtocolTypestateV1.NominalMessage(
                        ActorProtocolTypestateV1.Mode.UNARY, "QueryReply"),
                "Subscribe", new ActorProtocolTypestateV1.NominalMessage(
                        ActorProtocolTypestateV1.Mode.STREAM, "DataEvent"));
        assertDoesNotThrow(() -> c.verifyResolvedMessages(nominal));
        var missing = new java.util.HashMap<>(nominal);
        missing.remove("Subscribe");
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                c.verifyResolvedMessages(missing)).getMessage().contains("E_PROTOCOL_UNVERIFIED_MESSAGE"));
        var wrongMode = new java.util.HashMap<>(nominal);
        wrongMode.put("Subscribe", new ActorProtocolTypestateV1.NominalMessage(
                ActorProtocolTypestateV1.Mode.UNARY, "DataEvent"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                c.verifyResolvedMessages(wrongMode)).getMessage().contains("E_PROTOCOL_RESPONSE_MODE"));
        var wrongType = new java.util.HashMap<>(nominal);
        wrongType.put("Subscribe", new ActorProtocolTypestateV1.NominalMessage(
                ActorProtocolTypestateV1.Mode.STREAM, "WrongEvent"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                c.verifyResolvedMessages(wrongType)).getMessage().contains("E_PROTOCOL_RESPONSE_TYPE"));
    }

    @Test void parserRecognizesButDoesNotExecuteProtocolDeclarations() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Parser.parse(SOURCE));
        assertTrue(ex.getMessage().contains("E_UNSUPPORTED_PROTOCOL_LOWERING"));
    }
}
