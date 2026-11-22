package dev.oreslang;

import dev.oreslang.parser.ActorProtocolNominalResolverV1;
import dev.oreslang.parser.ActorProtocolTypestateV1;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real-parser declaration admission only; no guest or affine runtime proof. */
final class ActorProtocolNominalResolverV1Test {
    private static final String SOURCE = """
            define protocol Subscription as
              initial Idle
              state Idle as
                on Connect(Config) -> Active replies Ack
              end
              state Active as
                on Subscribe(Topic) -> Active streams Event
                on Close -> Finished replies Ack
              end
              terminal state Finished
            end
            define class Config as end
            define class Topic as end
            define class Ack as end
            define class Event as end
            define interface ConnectContract extends UnaryRequest<Ack> as end
            define interface EventContract extends StreamRequest<Event> as end
            define class Connect implements ConnectContract as end
            define class Subscribe implements EventContract as end
            define class Close implements UnaryRequest<Ack> as end
            define actor Client implements Subscription as end
            """;

    private static ActorProtocolNominalResolverV1.ResolvedDeclarations resolve(String source) {
        var tokens = new Lexer(source).scan();
        var catalog = ActorProtocolTypestateV1.parseTokens(tokens);
        var withoutProtocol = ActorProtocolTypestateV1.withoutProtocolPrelude(tokens);
        return ActorProtocolNominalResolverV1.resolve(catalog,
                new Parser(withoutProtocol).parseProgram());
    }

    private static void denied(String source, String code) {
        var err = assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(err.getMessage().contains(code),
                () -> "expected " + code + ", found " + err.getMessage());
    }

    @Test void realParserResolvesTransitiveSourceLocalReplyModes() {
        var ir = resolve(SOURCE);
        assertEquals(3, ir.messages().size());
        assertEquals(ActorProtocolTypestateV1.Mode.UNARY, ir.messages().get("Connect").mode());
        assertEquals(ActorProtocolTypestateV1.Mode.STREAM, ir.messages().get("Subscribe").mode());
        assertEquals("Event", ir.messages().get("Subscribe").responseType());
        assertEquals(List.of(new ActorProtocolTypestateV1.ActorBinding("Client", "Subscription")),
                ir.actors());
        denied(SOURCE, "E_UNSUPPORTED_PROTOCOL_LOWERING");
    }

    @Test void nominalCardinalityAndResponseAreCheckedBeforeGuestGate() {
        denied(SOURCE.replace("StreamRequest<Event>", "UnaryRequest<Event>"),
                "E_PROTOCOL_RESPONSE_MODE");
        denied(SOURCE.replace("StreamRequest<Event>", "StreamRequest<Ack>"),
                "E_PROTOCOL_RESPONSE_TYPE");
        denied(SOURCE.replace("StreamRequest<Event>", "OneWayMessage"),
                "E_PROTOCOL_RESPONSE_MODE");
        denied(SOURCE.replace("StreamRequest<Event>", "StreamRequest"),
                "E_PROTOCOL_RESPONSE_TYPE");
        denied(SOURCE.replace("StreamRequest<Event>", "StreamRequest<Any>"),
                "E_PROTOCOL_UNVERIFIED_TYPE");
    }

    @Test void unresolvedOrForgedNominalDeclarationsNeverMintModes() {
        denied(SOURCE.replace("define class Subscribe implements EventContract as end",
                        "define class Subscribe as end"),
                "E_PROTOCOL_UNVERIFIED_MESSAGE");
        denied(SOURCE.replace("define interface EventContract extends StreamRequest<Event> as end",
                        "define interface EventContract extends MissingBase as end"),
                "E_PROTOCOL_UNVERIFIED_MESSAGE");
        denied(SOURCE.replace("define class Subscribe implements EventContract as end",
                        "define class Subscribe implements EventContract<T> as end"),
                "E_PROTOCOL_UNVERIFIED_MESSAGE");
        denied(SOURCE.replace("define class Event as end",
                        "define class Event<T> as end"),
                "E_PROTOCOL_UNVERIFIED_TYPE");
        denied(SOURCE.replace("define class Event as end",
                        "define interface Event as end"),
                "E_PROTOCOL_UNVERIFIED_TYPE");
        denied(SOURCE.replace("define class Event as end",
                        "define class StreamRequest as end"),
                "E_PROTOCOL_FORGED_MARKER");
    }

    @Test void inheritedModeConflictsAndCyclesRejectRatherThanPickFirst() {
        denied(SOURCE.replace("define class Subscribe implements EventContract as end",
                        "define class Subscribe implements EventContract, ConnectContract as end"),
                "E_PROTOCOL_RESPONSE_MODE");
        denied(SOURCE.replace("define interface EventContract extends StreamRequest<Event> as end",
                        "define interface EventContract extends Another as end\n"
                        + "define interface Another extends EventContract as end"),
                "E_PROTOCOL_CYCLE");
    }

    @Test void nestedOrTrailingProtocolCannotBeErasedFromSource() {
        denied("define class Prior as end\n" + SOURCE, "E_PROTOCOL_SCOPE");
        denied(SOURCE + "\ndefine protocol Late as initial Start "
                + "state Start as on Go -> End replies Ack end "
                + "terminal state End end", "E_PROTOCOL_SCOPE");
    }

    @Test void missingPayloadOrReplyTypesRejectBeforeRuntimeGate() {
        denied(SOURCE.replace("on Subscribe(Topic) ->", "on Subscribe(MissingType) ->"),
                "E_PROTOCOL_UNVERIFIED_TYPE");
        denied(SOURCE.replace("on Subscribe(Topic) -> Active streams Event",
                        "on Subscribe(Topic) -> Active streams MissingResponse"),
                "E_PROTOCOL_UNVERIFIED_TYPE");
    }

    @Test void otherNominalClassesCannotCollideAcrossModules() {
        denied(SOURCE + "\ndefine module Other\n"
                        + "define class Event as end\nend",
                "E_PROTOCOL_AMBIGUOUS_TYPE");
    }
}
