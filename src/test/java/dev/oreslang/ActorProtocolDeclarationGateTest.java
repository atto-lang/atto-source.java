package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Source protocol admission is distinct from actually installed transport adapters. */
final class ActorProtocolDeclarationGateTest {
    private static Ast.ClassDecl actor(String source) {
        Ast.Program program = Parser.parse(source);
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void parsesAllTransportFamiliesWithoutInventingRuntimeAdapters() {
        for (var entry : List.of(
                "http:HTTP", "ipc:IPC", "tcp:TCP",
                "ffi:FFI", "proxy:PROXY", "websocket:WEBSOCKET")) {
            String[] parts = entry.split(":");
            Ast.ClassDecl klass = actor("define " + parts[0] + " actor Work as end");
            assertEquals(Ast.ActorTransport.valueOf(parts[1]),
                    klass.actorSpecialization().transport(), entry);
            assertEquals(Ast.ActorReplyRole.STREAM, klass.actorSpecialization().replyRole());
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("define " + parts[0] + " actor Work as end")),
                    entry + " must not silently run as a mailbox actor");
        }
    }

    @Test
    void serialUnaryHttpIsOrthogonalAndFailsClosed() {
        Ast.ClassDecl klass = actor("""
                @Outbound
                define serial unary http actor Gateway
                    requires net.connect, tls.client
                    with io, suspend
                as end
                """);
        var spec = klass.actorSpecialization();
        assertEquals(Ast.ActorReplyRole.UNARY, spec.replyRole());
        assertEquals(Ast.ActorTransport.HTTP, spec.transport());
        assertEquals(Ast.ActorDirection.OUTBOUND, spec.direction());
        assertTrue(spec.serial());
        assertEquals(List.of("net.connect", "tls.client"), spec.requirements());
        assertEquals(List.of("io", "suspend"), spec.effects());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @Outbound
                        define serial unary http actor Gateway
                            requires net.connect, tls.client
                            with io, suspend
                        as end
                        """)));
        assertTrue(error.getMessage().contains("runtime-owned binding"), error.getMessage());
    }

    @Test
    void annotationExpansionNeverLosesTransportMetadata() {
        Ast.Program program = Parser.parse("""
                define module gateways as
                    @Duplex
                    define websocket actor Session
                        with(io, suspend)
                        requires(net.listen)
                    as end
                end
                """);
        Ast.ClassDecl before = (Ast.ClassDecl) program.modules().stream()
                .filter(m -> m.name().equals("gateways")).findFirst().orElseThrow()
                .declarations().getFirst();
        Ast.ClassDecl after = (Ast.ClassDecl) dev.oreslang.ast.AnnotationExpander.expand(program)
                .modules().stream().filter(m -> m.name().equals("gateways"))
                .findFirst().orElseThrow().declarations().getFirst();
        assertEquals(before.actorSpecialization(), after.actorSpecialization());
        assertEquals(Ast.ActorDirection.DUPLEX, after.actorSpecialization().direction());
    }

    @Test
    void rejectsConflictingAndUnenforcedMetadata() {
        for (String source : List.of(
                "define http tcp actor X as end",
                "define unary stream actor X as end",
                "define serial serial actor X as end",
                "@Inbound @Outbound define http actor X as end",
                "@Inbound define actor X as end",
                "@Route(\"/\") define http actor X as end",
                "define http actor X requires io requires io as end",
                "define http actor X with io, io as end",
                "define http actor X with() as end",
                "define http actor X requires(net.listen) requires(net.listen) as end")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void requirementsAndEffectsFailClosedEvenForPlainMailActors() {
        for (String source : List.of(
                "define actor X requires file.read as end",
                "define actor X with io as end",
                "define unary actor X as end")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains("requires/with")
                    || error.getMessage().contains("unary"), error.getMessage());
        }
    }

    @Test
    void legacyActorsRemainUnspecializedAndContextualNamesRemainIdentifiers() {
        Ast.ClassDecl klass = actor("define actor Worker as end");
        assertEquals(Ast.ActorSpecialization.defaultSpec(), klass.actorSpecialization());
        assertDoesNotThrow(() -> Parser.parse("""
                fnc http(): int { return 1; }
                fnc websocket(): int { return 2; }
                fnc proxy(): int { return 3; }
                """));
    }
}
