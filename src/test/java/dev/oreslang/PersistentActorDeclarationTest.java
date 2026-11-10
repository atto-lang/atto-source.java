package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class PersistentActorDeclarationTest {
    @Test
    void parsesMandatoryMutableStateContainerAsDistinctDeclaration() {
        var program = Parser.parse("""
                define persistent actor Counter as
                  state {
                    let int count: 0;
                  }
                end
                """);
        var actor = assertInstanceOf(Ast.PersistentActorDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("Counter", actor.name());
        assertEquals(1, actor.stateFields().size());
        assertEquals("count", actor.stateFields().getFirst().name());
        assertTrue(actor.methods().isEmpty());
    }

    @Test
    void acceptsEmptyStateWithoutSilentlyConvertingToOrdinaryActor() {
        var program = Parser.parse("define persistent actor X as state {} end");
        assertInstanceOf(Ast.PersistentActorDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test
    void preservesProtectedMethodTokenProvenanceWithoutExecutingIt() {
        var program = Parser.parse("""
                define persistent actor Counter as
                  state { let int count: 0; }
                  increment(int delta) -> int :: return delta; end
                end
                """);
        var actor = (Ast.PersistentActorDecl) program.modules()
                .getFirst().declarations().getFirst();
        assertEquals("increment", actor.methods().getFirst().name());
        assertTrue(actor.methods().getFirst().bodyTokens().contains("return"));
        assertTrue(actor.methods().getFirst().bodyTokenKinds().contains("RETURN"));
    }

    @Test
    void typeCheckerFailsClosedBeforeBorrowOrRuntimeLowering() {
        var program = Parser.parse("define persistent actor X as state {} end");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("PERSISTENT_ACTOR_MIR_UNSUPPORTED"));
    }

    @Test
    void rejectsMissingStateAndDuplicateStateKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define persistent actor X as end"));
        var duplicate = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define persistent actor X as
                          state {
                            let int x: 0;
                            let int x: 1;
                          }
                        end
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate protected state field"));
    }

    @Test
    void persistentRemainsContextualOutsideActorDeclaration() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): int {
                  val persistent: int = 7;
                  return persistent;
                }
                """));
    }
}
