package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.compiler.BuildOptions;
import java.util.Map;
import java.util.Set;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorMiddlewareDeclarationTest {
    private static Ast.ClassDecl actor(Ast.Program program) {
        return program.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl c && c.actorKind() != Ast.ActorKind.NONE)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
    }

    @Test
    void actorDecoratorIsStoredAndPreservedThroughExpansion() {
        String program = """
                @ActorMiddleware(Clean, Enrich)
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """;
        Ast.ClassDecl parsed = actor(Parser.parse(program));
        assertEquals(List.of("Clean", "Enrich"),
                parsed.annotations().getFirst().arguments().stream().map(Ast.TypeRef::name).toList());
        assertEquals("ActorMiddleware", parsed.annotations().getFirst().name());
        Ast.ClassDecl expanded = actor(AnnotationExpander.expand(Parser.parse(program)));
        assertEquals(parsed.annotations(), expanded.annotations());
        assertEquals(Ast.ActorReplyRole.STREAM, expanded.actorReplyRole());
    }

    @Test
    void decoratorWorksInsideModulesWithoutLeakingToOtherClasses() {
        Ast.Program program = Parser.parse("""
                define module App as
                    @ActorMiddleware(Clean)
                    define actor Foo as end
                    define class Clean as end
                end
                """);
        assertEquals("Clean", actor(program).annotations().getFirst().arguments().getFirst().name());
        Ast.ClassDecl ordinary = program.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.ClassDecl c && c.actorKind() == Ast.ActorKind.NONE)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        assertTrue(ordinary.annotations().isEmpty());
    }

    @Test
    void annotationsMustNotBeDiscardedOrIgnoredOnActorDeclarations() {
        var duplicate = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                @ActorMiddleware(Clean)
                @ActorMiddleware(Enrich)
                define actor Foo as end
                """));
        assertTrue(duplicate.getMessage().contains("duplicate @ActorMiddleware"), duplicate.getMessage());

        var empty = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                @ActorMiddleware()
                define actor Foo as end
                """));
        assertTrue(empty.getMessage().contains("at least one"), empty.getMessage());

        var supervisor = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                @SupervisorMiddleware(Clean)
                define actor Foo as end
                """));
        assertTrue(supervisor.getMessage().contains("supervisor middleware"), supervisor.getMessage());

        var wrong = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                @ActorMiddleware(Clean)
                define class Foo as end
                """));
        assertTrue(wrong.getMessage().contains("silently discarded"), wrong.getMessage());
    }

    @Test
    void treeShakingKeepsAnnotationOnlyMiddlewareDependencies() {
        Ast.Program program = Parser.parse("""
                define class Clean as end
                define class Unused as end

                @ActorMiddleware(Clean)
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """);
        TreeShaker.Result result = TreeShaker.shake(
                program, new BuildOptions(Map.of(), Set.of("Worker"), false));
        String root = Parser.ROOT_MODULE + ".";
        assertTrue(result.retained(root + "Worker"));
        assertTrue(result.retained(root + "Clean"),
                "middleware used exclusively through annotation must never be removed");
        assertTrue(result.removed(root + "Unused"));
        assertEquals("Clean", actor(result.program()).annotations()
                .getFirst().arguments().getFirst().name());
    }

    @Test
    void misplacedMiddlewareDecoratorsAreNeverSilentlyIgnored() {
        String[] sources = {
                """
                @ActorMiddleware(Clean)
                define module App as
                    pub fnc foo(): int { return 1; }
                end
                """,
                """
                define actor Foo as
                    @ActorMiddleware(Clean)
                    receive(ActorMail<int> mail): void { return; }
                end
                """,
                """
                @ActorMiddleware(Clean)
                pub fnc work(): int { return 1; }
                """,
                """
                define class Bag as
                    @ActorMiddleware(Clean)
                    let int field;
                end
                """
        };
        for (String source : sources) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(failure.getMessage().contains("@ActorMiddleware"), failure.getMessage());
        }
    }

    @Test
    void unknownMiddlewareCannotCompileAsAWorkingDecorator() {
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                @ActorMiddleware(Nonexistent)
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                """)));
        assertTrue(failure.getMessage().contains("unknown or actor class"), failure.getMessage());
    }
}
