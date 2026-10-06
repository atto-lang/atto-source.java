package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnBoundaryTest {

    @Test
    void spawnIsReservedAndParsesAsDedicatedActorBoundary() {
        assertEquals(Token.Type.SPAWN, new Lexer("spawn").scan().getFirst().type());

        Ast.Program program = Parser.parse("""
                actor Worker {
                  let int value = 1;
                }

                pub routine main(): void {
                  val worker = spawn Worker();
                  return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
        assertInstanceOf(Ast.SpawnExpr.class, binding.initializer());
        Ast.SpawnExpr spawned = (Ast.SpawnExpr) binding.initializer();
        assertInstanceOf(Ast.NameExpr.class, spawned.call().callee());
        assertEquals("Worker", ((Ast.NameExpr) spawned.call().callee()).name());
    }

    @Test
    void typeCheckerAcceptsActorClassSpawnButRejectsOrdinaryCallableSpawn() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor Worker {
                  let int value = 1;
                }

                pub routine main(): void {
                  val worker = spawn Worker();
                  return;
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc Worker(): void { return; }

                        pub routine main(): void {
                          val worker = spawn Worker();
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("not a known actor class"));
    }

    @Test
    void spawnArgumentsFailClosedUntilActorInitializerSemanticsAreConverged() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor Worker {
                          let int value = 1;
                        }

                        pub routine main(): void {
                          val worker = spawn Worker(42);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("spawn arguments are not enabled"));
    }

    @Test
    void runtimeFailsClosedUntilTypedMailboxConstructionIsLinked() throws Exception {
        String program = """
                actor Worker {
                  let int value = 1;
                }

                pub routine main(): void {
                  val worker = spawn Worker();
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-spawn-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        RuntimeException failure;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            failure = assertThrows(RuntimeException.class, () -> context.eval(source));
        }

        assertTrue(failure.getMessage().contains("spawn actor-class runtime lowering is not linked yet"));
    }
}
