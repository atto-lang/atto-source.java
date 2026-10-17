package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Built-in enum is validated, never interpreted as an arbitrary user enum. */
final class ActorMaxThreadsEnumDeclarationTest {
    private static final String CANONICAL = """
            define enum MaxActorThreads as
                LOW int = 1 // sane default
                MEDIUM int = 2
                HIGH int = 3
            end
            """;

    @Test void canonicalEnumAndExplicitLowProduceSingleFlightActorAst() {
        Ast.Program program = Parser.parse(CANONICAL + """
                @ActorMaxThreads(MaxActorThreads.LOW)
                define actor HungryActor as
                end
                """);
        assertEquals(1, program.modules().size());
        assertEquals(1, program.modules().getFirst().declarations().size(),
                "builtin type is not a user class or callable");
        var actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("HungryActor", actor.name());
        assertEquals(Ast.MaxActorThreads.LOW, actor.maxActorThreads());
        assertEquals(1, actor.maxActorThreads().value());
    }

    @Test void highAndMediumAreRecognizedButNeverSilentlyDowngraded() {
        for (String requested : new String[] {"MEDIUM", "HIGH"}) {
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> Parser.parse(CANONICAL
                            + "@ActorMaxThreads(MaxActorThreads." + requested + ") "
                            + "define actor HungryActor as end"));
            assertTrue(rejected.getMessage().contains("per-turn actor state isolation"),
                    rejected.getMessage());
        }
    }

    @Test void rejectReboundOrMalformedBuiltin() {
        for (String declaration : new String[] {
                CANONICAL + CANONICAL,
                CANONICAL.replace("LOW int = 1", "LOW int = 3"),
                CANONICAL.replace("MEDIUM int = 2", "MEDIUM int = 1"),
                CANONICAL.replace("HIGH int = 3", "HIGH int = 4"),
                CANONICAL.replace("MEDIUM int = 2", "MEDIUM float = 2"),
                CANONICAL.replace("LOW int = 1", "MIN int = 1"),
                CANONICAL.replace("MaxActorThreads", "OtherThreads"),
                CANONICAL.replace("HIGH int = 3", "HIGH int = 3 EXTRA int = 4"),
                CANONICAL.replace("MEDIUM int = 2", "MEDIUM int = -2")
        }) {
            assertThrows(RuntimeException.class, () -> Parser.parse(
                    declaration + "define actor A as end"), declaration);
        }
    }

    @Test void canonicalEnumInsideModuleMaintainsActorType() {
        Ast.Program program = Parser.parse("""
                define module work as
                    define enum MaxActorThreads as
                        LOW int = 1;
                        MEDIUM int = 2;
                        HIGH int = 3;
                    end
                    @ActorMaxThreads(MaxActorThreads.LOW)
                    define actor InModule as end
                end
                """);
        assertEquals("work", program.modules().getFirst().name());
        assertEquals(1, program.modules().getFirst().declarations().size());
        var actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.MaxActorThreads.LOW, actor.maxActorThreads());
    }

    @Test void enumSpellingMayBeOmittedBecauseTheTierIsLanguageOwned() {
        Ast.Program program = Parser.parse(
                "@ActorMaxThreads(MaxActorThreads.LOW) define actor A as end");
        var actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.MaxActorThreads.LOW, actor.maxActorThreads());
    }
}
