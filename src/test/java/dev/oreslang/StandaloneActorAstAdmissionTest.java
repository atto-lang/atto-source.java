package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** New syntax is captured faithfully, but cannot execute before actor lowering. */
final class StandaloneActorAstAdmissionTest {
    private static Ast.Decl onlyDeclaration(String source) {
        Ast.Program program = Parser.parse(source);
        return program.modules().getFirst().declarations().getFirst();
    }

    @Test
    void retainsActorNominalIdentityAndReceiverBinding() {
        Ast.StandaloneActorDecl actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                onlyDeclaration("define actor Worker as worker;"));
        assertEquals("Worker", actor.name());
        assertEquals("worker", actor.receiverName());
        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
    }

    @Test
    void retainsVisibilityWithinModule() {
        Ast.StandaloneActorDecl actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                onlyDeclaration("define module workers as\n"
                        + "  pub define actor Worker as w;\n"
                        + "end\n"));
        assertEquals(Ast.Visibility.PUBLIC, actor.visibility());
    }

    @Test
    void oldActorClassBodyRemainsTheOldNodeType() {
        assertInstanceOf(Ast.ClassDecl.class, onlyDeclaration(
                "define actor Worker as\nend\n"));
    }

    @Test
    void typedAdmissionRejectsExecutableStandaloneActor() {
        Ast.Program parsed = Parser.parse("define actor Worker as worker;");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED"),
                error.getMessage());
        assertTrue(error.getMessage().contains("worker"), error.getMessage());
    }

    @Test
    void noGenericStandaloneActorBeforeWitnessLowering() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Worker<T> as worker;"));
        assertTrue(error.getMessage().contains("plain non-generic actor"), error.getMessage());
    }

    @Test
    void noHttpActorTransportInStandaloneSyntaxYet() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define http actor Worker as worker;"));
        assertTrue(error.getMessage().contains("without transport"), error.getMessage());
    }

    @Test
    void cannotBindActorTypeNameAsItsOwnReceiverName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Worker as Worker;"));
        assertTrue(error.getMessage().contains("distinct nominal type and receiver"), error.getMessage());
    }
}
