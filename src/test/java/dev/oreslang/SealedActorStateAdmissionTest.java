package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed frontend admission until physical actor arenas, sealed state,
 * checked critical loans and runtime lowering are implemented together.
 * Parser errors are NOT native actor execution or loan correctness proof.
 */
final class SealedActorStateAdmissionTest {
    private static void rejected(String source, String expected) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(expected), failure::getMessage);
    }

    @Test void isolatedActorDoesNotSilentlyBecomeLegacyIsoactor() {
        rejected("""
                define isolated actor Ledger as
                end
                """, "arena isolation and sealed state-loan lowering");
    }

    @Test void typedStateSchemaGetsMarkedAstButCannotExecute() {
        String source = """
                define actor Ledger as
                  state { phase: String = "created"; }
                end
                """;
        var program = Parser.parse(source);
        var klass = (dev.oreslang.ast.Ast.ClassDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof dev.oreslang.ast.Ast.ClassDecl)
                .findFirst().orElseThrow();
        assertEquals(1, klass.fields().size());
        assertEquals("phase", klass.fields().getFirst().name());
        assertTrue(klass.fields().getFirst().annotations().stream()
                .anyMatch(a -> a.name().equals(Parser.SEALED_STATE_SCHEMA_MARKER)));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.check(program));
        assertTrue(rejected.getMessage().contains("compiler-enforced handle_state loans"),
                rejected::getMessage);
    }

    @Test void rejectsDuplicateOrMixedActorStateAndUninitializedFields() {
        rejected("""
                define actor Ledger as
                  state { phase: String = "created"; }
                  state { retry: int = 0; }
                end
                """, "only one state schema");
        rejected("""
                define actor Ledger as
                  let int legacy = 0;
                  state { phase: String = "created"; }
                end
                """, "cannot mix schema with ordinary fields");
        rejected("""
                define actor Ledger as
                  state { phase: String = "created"; }
                  let int legacy = 0;
                end
                """, "ordinary actor fields cannot follow state schema");
        rejected("""
                define actor Ledger as
                  state { phase: String; }
                end
                """, "explicit initializer");
        rejected("""
                define actor Ledger as
                  state { phase: String = "a"; phase: String = "b"; }
                end
                """, "duplicate actor state field");
    }

    @Test void userCannotSpoofSealedStateAuthorityWithActorMethod() {
        rejected("""
                define actor Ledger as
                  handle_state(): void { return; }
                end
                """, "actor handle_state is compiler-owned");
        rejected("""
                define actor Ledger as
                  pub fnc handle_state(): void { return; }
                end
                """, "actor handle_state is compiler-owned");
    }

    @Test void ordinaryActorMethodsAndLegacySpellingRemainAsBefore() {
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Ledger as
                  pub handle_stateful(): void { return; }
                end
                define isoactor Legacy as
                  pub process(): void { return; }
                end
                """));
    }
}
