package dev.oreslang;

import dev.oreslang.parser.ActorProtocolAffineCfgV1;
import dev.oreslang.parser.ActorProtocolNominalResolverV1;
import dev.oreslang.parser.ActorProtocolTypestateV1;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler IR/CFG oracle tests. NOT guest codegen or runtime authorization. */
final class ActorProtocolAffineCfgV1Test {
    private static final String SOURCE = """
            define protocol Connection as
              initial Disconnected
              state Disconnected as
                on Connect(Config) -> Connected replies Ack
              end
              state Connected as
                on Subscribe(Topic) -> Connected streams Event
                on Close -> Closed replies Ack
              end
              terminal state Closed
            end
            define class Config as end
            define class Topic as end
            define class Ack as end
            define class Event as end
            define class Connect implements UnaryRequest<Ack> as end
            define class Subscribe implements StreamRequest<Event> as end
            define class Close implements UnaryRequest<Ack> as end
            define actor Client implements Connection as end
            """;

    private static ActorProtocolNominalResolverV1.ResolvedDeclarations resolved(String source) {
        var tokens = new Lexer(source).scan();
        var catalog = ActorProtocolTypestateV1.parseTokens(tokens);
        var ast = new Parser(ActorProtocolTypestateV1.withoutProtocolPrelude(tokens)).parseProgram();
        return ActorProtocolNominalResolverV1.resolve(catalog, ast);
    }

    @Test void standaloneCfgOracleCoversBothSuccessfulAndHostilePaths() {
        assertDoesNotThrow(() -> ActorProtocolAffineCfgV1Smoke.main(new String[0]));
    }

    @Test void actualParsedNominalContractCanBeRecheckedBeforeCfgProof() {
        var declarations = resolved(SOURCE);
        var cfg = new ActorProtocolAffineCfgV1.Program(0, true, List.of(
                new ActorProtocolAffineCfgV1.Block(0, List.of(
                        new ActorProtocolAffineCfgV1.Init(1, "Connection"),
                        new ActorProtocolAffineCfgV1.Begin(1, 10, "Connect", "Config",
                                ActorProtocolTypestateV1.Mode.UNARY, "Ack"),
                        new ActorProtocolAffineCfgV1.AwaitUnary(10, 2)), List.of(1, 2, 3, 4)),
                new ActorProtocolAffineCfgV1.Block(1, List.of(
                        new ActorProtocolAffineCfgV1.ReturnHandle(2),
                        new ActorProtocolAffineCfgV1.Return()), List.of()),
                new ActorProtocolAffineCfgV1.Block(2, List.of(
                        new ActorProtocolAffineCfgV1.Return()), List.of()),
                new ActorProtocolAffineCfgV1.Block(3, List.of(
                        new ActorProtocolAffineCfgV1.Return()), List.of()),
                new ActorProtocolAffineCfgV1.Block(4, List.of(
                        new ActorProtocolAffineCfgV1.Return()), List.of())
        ));
        var proof = ActorProtocolAffineCfgV1.verifyNominallyResolved(declarations, cfg);
        assertEquals(5, proof.verifiedBlocks());
        assertEquals(7, proof.verifiedOperations());
    }

    @Test void unresolvedNominalModeNeverYieldsAnAffineProof() {
        String forged = SOURCE.replace(
                "define class Subscribe implements StreamRequest<Event> as end",
                "define class Subscribe as end");
        var bad = assertThrows(IllegalArgumentException.class, () -> resolved(forged));
        assertTrue(bad.getMessage().contains("E_PROTOCOL_UNVERIFIED_MESSAGE"),
                bad::getMessage);
    }

    @Test void sourceGuestProtocolExecutionRemainsFailClosed() {
        var fail = assertThrows(IllegalArgumentException.class, () -> Parser.parse(SOURCE));
        assertTrue(fail.getMessage().contains("E_UNSUPPORTED_PROTOCOL_LOWERING"),
                fail::getMessage);
    }
}
