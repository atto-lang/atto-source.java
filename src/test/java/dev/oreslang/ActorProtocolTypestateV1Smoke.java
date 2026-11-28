package dev.oreslang;

import dev.oreslang.parser.ActorProtocolTypestateV1;
import java.util.List;
import java.util.Map;

/** Source-only contract probe. Not guest execution, actor runtime or JUnit. */
public final class ActorProtocolTypestateV1Smoke {
    private ActorProtocolTypestateV1Smoke() { }

    private static void fail(String diagnostic, Runnable run) {
        try { run.run(); } catch (IllegalArgumentException e) {
            if (e.getMessage().contains(diagnostic)) return;
            throw new AssertionError("unexpected diagnostic: " + e.getMessage(), e);
        }
        throw new AssertionError("expected " + diagnostic);
    }

    public static void main(String[] args) {
        String source = """
                define protocol ConnectionProtocol as
                  initial Disconnected
                  state Disconnected as
                    on Connect(Config) -> Connected replies ConnectedAck
                  end
                  state Connected as
                    on Subscribe(Topic) -> Connected streams DataEvent
                    on Close -> Closed replies ClosedAck
                  end
                  terminal state Closed
                end
                """;
        var catalog = ActorProtocolTypestateV1.parseSource(source);
        var connected = catalog.checkSend("ConnectionProtocol", List.of("Disconnected"), "Connect");
        var stream = catalog.checkSend("ConnectionProtocol", List.of("Connected"), "Subscribe");
        if (connected.mode() != ActorProtocolTypestateV1.Mode.UNARY
                || stream.mode() != ActorProtocolTypestateV1.Mode.STREAM
                || !stream.responseType().equals("DataEvent") || !stream.consumesAuthority())
            throw new AssertionError("unary/stream typed IR was not preserved");
        fail("E_PROTOCOL_ILLEGAL_MESSAGE_STATE", () -> catalog.checkSend(
                "ConnectionProtocol", List.of("Disconnected"), "Subscribe"));
        fail("E_PROTOCOL_UNKNOWN_STATE", () -> ActorProtocolTypestateV1.parseSource(
                source.replace("-> Connected replies ConnectedAck", "-> Missing replies ConnectedAck")));
        fail("E_PROTOCOL_RESPONSE_MODE", () -> catalog.verifyResolvedMessages(Map.of(
                "Connect", new ActorProtocolTypestateV1.NominalMessage(ActorProtocolTypestateV1.Mode.UNARY, "ConnectedAck"),
                "Subscribe", new ActorProtocolTypestateV1.NominalMessage(ActorProtocolTypestateV1.Mode.UNARY, "DataEvent"),
                "Close", new ActorProtocolTypestateV1.NominalMessage(ActorProtocolTypestateV1.Mode.UNARY, "ClosedAck"))));
        fail("E_PROTOCOL_UNVERIFIED_MESSAGE", () -> catalog.verifyResolvedMessages(Map.of()));
        System.out.println("ATTO_ACTOR_PROTOCOL_DECLARATION_SOURCE_ONLY=PASS");
        System.out.println("ATTO_ACTOR_PROTOCOL_GUEST_EXECUTION=NOT_EXECUTED");
    }
}
