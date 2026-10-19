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
    void serialTransportCombinationsDoNotFallBackToMailboxActors() {
        for (String transport : List.of("http", "ipc", "tcp", "ffi", "proxy", "websocket")) {
            String source = "define serial " + transport + " actor Worker as end";
            Ast.ClassDecl klass = actor(source);
            assertTrue(klass.actorSpecialization().serial(), source);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains("runtime-owned binding"), error.getMessage());
        }
    }

    @Test
    void unaryTransportIsNeverExecutableWithoutReplyCorrelation() {
        for (String name : List.of("http", "ipc", "tcp", "ffi", "proxy", "websocket")) {
            String source = "define unary " + name + " actor Gateway as end";
            Ast.ClassDecl parsed = actor(source);
            assertEquals(Ast.ActorReplyRole.UNARY,
                    parsed.actorSpecialization().replyRole(), source);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
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
                "define unary actor X as end",
                "define serial actor X as end")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains("requires/with")
                    || error.getMessage().contains("unary")
                    || error.getMessage().contains("serial (1,1)"), error.getMessage());
        }
    }

    @Test
    void serialMailAdmitsNoUnverifiedRequestOrMessageConcurrency() {
        String source = "define serial actor Strict as end";
        Ast.ClassDecl parsed = actor(source);
        assertTrue(parsed.actorSpecialization().serial());
        assertEquals(Ast.ActorTransport.MAIL, parsed.actorSpecialization().transport());
        assertEquals(Ast.ActorReplyRole.STREAM, parsed.actorSpecialization().replyRole());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("serial (1,1)"), error.getMessage());

        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl expanded = (Ast.ClassDecl) dev.oreslang.ast.AnnotationExpander
                .expand(program).modules().getFirst().declarations().getFirst();
        assertEquals(parsed.actorSpecialization(), expanded.actorSpecialization());
    }

    @Test
    void serialTransportMustNotMaskAbsentAdapterDiagnostic() {
        String source = "define serial http actor StrictGateway as end";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("runtime-owned binding"), error.getMessage());
    }

    @Test
    void manuallyConstructedMailboxAstCannotClaimRemoteDirection() {
        Ast.Program parsed = Parser.parse("define actor Ordinary as end");
        Ast.ModuleDecl module = parsed.modules().getFirst();
        Ast.ClassDecl ordinary = (Ast.ClassDecl) module.declarations().getFirst();
        for (Ast.ActorDirection direction : List.of(
                Ast.ActorDirection.INBOUND, Ast.ActorDirection.OUTBOUND,
                Ast.ActorDirection.DUPLEX)) {
            Ast.ActorSpecialization invalid = new Ast.ActorSpecialization(
                    Ast.ActorReplyRole.STREAM, Ast.ActorTransport.MAIL, direction,
                    false, List.of(), List.of(), List.of());
            Ast.ClassDecl forged = new Ast.ClassDecl(
                    ordinary.name(), ordinary.visibility(), ordinary.isAbstract(),
                    ordinary.actorKind(), ordinary.genericParameters(), ordinary.parents(),
                    ordinary.interfaces(), ordinary.fields(), ordinary.constructor(),
                    ordinary.methods(), invalid);
            Ast.ModuleDecl forgedModule = new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), List.of(forged));
            Ast.Program injected = new Ast.Program(
                    parsed.namespace(), parsed.imports(), List.of(forgedModule));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(injected), direction.toString());
            assertTrue(error.getMessage().contains("non-local routing"), error.getMessage());
        }
    }

    @Test
    void injectedOrdinaryClassCannotCarryActorTransportMetadata() {
        Ast.Program parsed = Parser.parse("define class Ordinary as end");
        Ast.ModuleDecl module = parsed.modules().getFirst();
        Ast.ClassDecl ordinary = (Ast.ClassDecl) module.declarations().getFirst();
        Ast.ActorSpecialization invalid = new Ast.ActorSpecialization(
                Ast.ActorReplyRole.UNARY, Ast.ActorTransport.HTTP,
                Ast.ActorDirection.INBOUND, true, List.of(), List.of(), List.of());
        Ast.ClassDecl forged = new Ast.ClassDecl(
                ordinary.name(), ordinary.visibility(), ordinary.isAbstract(),
                ordinary.actorKind(), ordinary.genericParameters(), ordinary.parents(),
                ordinary.interfaces(), ordinary.fields(), ordinary.constructor(),
                ordinary.methods(), invalid);
        Ast.ModuleDecl changed = new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(), List.of(forged));
        Ast.Program injected = new Ast.Program(
                parsed.namespace(), parsed.imports(), List.of(changed));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(injected));
        assertTrue(error.getMessage().contains("cannot carry actor transport specialization"));
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
