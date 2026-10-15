package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRoleTransportDeclarationTest {

    private static Ast.ClassDecl actor(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d)
                .findFirst().orElseThrow();
    }

    private static void rejects(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test
    void explicitStreamIsRealTypedGuestSourceNotJustAnIgnoredPrefix() {
        String source = """
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """;
        Ast.ClassDecl parsed = actor(source);
        assertEquals(Ast.ActorReplyRole.STREAM, parsed.actorReplyRole());
        assertEquals(Ast.ActorTransport.GENERIC, parsed.actorTransport());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void legacyStreamKeepsInferredRole() {
        String source = """
                define actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """;
        assertEquals(Ast.ActorReplyRole.INFER, actor(source).actorReplyRole());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void explicitUnaryMetadataSurvivesButExecutionIsNotFalselyEnabled() {
        String source = """
                define unary actor Worker as
                    run(ActorMail<int> mail): int { return mail.value * 5; }
                end
                """;
        Ast.ClassDecl parsed = actor(source);
        assertEquals(Ast.ActorReplyRole.UNARY, parsed.actorReplyRole());
        rejects(source, "request/reply source runtime integration");
    }

    @Test
    void networkRolesNormalizeIndependentlyOfReplyRole() {
        assertEquals(Ast.ActorTransport.HTTP,
                actor("define http actor H as end").actorTransport());
        assertEquals(Ast.ActorReplyRole.STREAM,
                actor("define http actor H as end").actorReplyRole());
        assertEquals(Ast.ActorTransport.TCP,
                actor("define tcp actor T as end").actorTransport());
        assertEquals(Ast.ActorTransport.WEBSOCKET,
                actor("define websocket actor W as end").actorTransport());
        Ast.ClassDecl unaryHttp = actor("define unary http actor H as end");
        assertEquals(Ast.ActorReplyRole.UNARY, unaryHttp.actorReplyRole());
        assertEquals(Ast.ActorTransport.HTTP, unaryHttp.actorTransport());
        Ast.ClassDecl moduleActor = Parser.parse("""
                define module net as
                    define stream websocket actor Events as end
                end
                """).modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        assertEquals(Ast.ActorReplyRole.STREAM, moduleActor.actorReplyRole());
        assertEquals(Ast.ActorTransport.WEBSOCKET, moduleActor.actorTransport());
    }

    @Test
    void networkActorsFailClosedInsteadOfBeingImplicitListeners() {
        rejects("define http actor H as end", "runtime-bound network actor adapter");
        rejects("define tcp actor T as end", "runtime-bound network actor adapter");
        rejects("define websocket actor W as end", "runtime-bound network actor adapter");
        rejects("define unary tcp actor T as end", "typed request framing");
        rejects("define unary websocket actor W as end", "typed request framing");
    }

    @Test
    void invalidModesCannotAccidentallyDispatchAcrossProtocols() {
        rejects("""
                define stream actor Worker as
                    run(int x): int { return x; }
                end
                """, "must use receive");
        rejects("""
                define unary actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """, "must declare run");
        rejects("""
                define unary actor Worker as
                    run(int job): void { return; }
                end
                """, "return non-void");
        rejects("""
                define stream actor Worker as
                    let int hidden;
                    receive(ActorMail<int> mail): void { return; }
                end
                """, "cannot declare ordinary instance fields");
    }

    @Test
    void inheritedSpecializationCannotDowngradeOrSmuggleFields() {
        rejects("""
                define stream actor Parent as
                    receive(ActorMail<int> mail): void { return; }
                end
                define actor Child extends Parent as
                end
                """, "explicitly preserve inherited actor reply role");

        rejects("""
                define actor Parent as
                    let int secret;
                end
                define stream actor Child extends Parent as
                    receive(ActorMail<int> mail): void { return; }
                end
                """, "cannot inherit ordinary persistent instance fields");

        rejects("""
                define stream actor Parent as
                    receive(ActorMail<int> mail): void { return; }
                end
                define unary actor Child extends Parent as
                    run(int value): int { return value; }
                end
                """, "actor reply role mismatch");
    }

    @Test
    void contextualKeywordsStayValidOrdinaryIdentifiers() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc unary(int stream): int { return stream; }
                """));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define unary class Foo as end"));
        assertTrue(ex.getMessage().contains("actor role/transport"), ex.getMessage());
    }

    @Test
    void metadataSurvivesAnnotationExpansion() {
        Ast.Program program = Parser.parse("""
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """);
        Ast.Program expanded = AnnotationExpander.expand(program);
        Ast.ClassDecl klass = expanded.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        assertEquals(Ast.ActorReplyRole.STREAM, klass.actorReplyRole());
    }
}
