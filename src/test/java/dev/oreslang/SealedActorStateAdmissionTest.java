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

    @Test void typedStateSchemaDoesNotBecomeAnOrdinaryActorField() {
        var program = Parser.parse("""
                define actor Ledger as
                  state { let int phase = 0; }
                end
                """);
        var actor = (dev.oreslang.ast.Ast.ClassDecl) program.modules().getFirst()
                .declarations().getFirst();
        assertTrue(actor.fields().isEmpty(), "sealed metadata cannot be ordinary actor fields");
        assertEquals(1, actor.sealedStateFields().size());
        assertEquals("phase", actor.sealedStateFields().getFirst().name());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.analyze(program));
        assertTrue(failure.getMessage().contains("ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"));
        var direct = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithDiagnostics(program));
        assertTrue(direct.getMessage().contains("ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"));
        var linked = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        program, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
        assertTrue(linked.getMessage().contains("ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"));
    }

    @Test void sealedStateRejectsDuplicateAndMixedLayouts() {
        rejected("""
                define actor Ledger as
                  state { let int x = 1; }
                  state { let int y = 2; }
                end
                """, "duplicate sealed actor state");
        rejected("""
                define actor Ledger as
                  let int legacy = 1;
                  state { let int x = 2; }
                end
                """, "cannot mix ordinary actor fields");
        rejected("""
                define actor Ledger as
                  state { let int x = 1; }
                  let int legacy = 2;
                end
                """, "cannot mix ordinary actor fields");
        rejected("""
                define actor Ledger as
                  state { pub let int x = 1; }
                end
                """, "requires private 'let'");
        rejected("""
                define actor Ledger as
                  state { let int x = 1; let int x = 2; }
                end
                """, "duplicate sealed actor state field");
    }

    @Test void sealedStateRejectsDynamicInitializersAndUnsafeValues() {
        rejected("""
                define actor Ledger as
                  state { let int x = acquire_secret(); }
                end
                """, "initializer must be a literal");
        rejected("""
                define actor Ledger as
                  state { let Map<int,int> x = 1; }
                end
                """, "requires scalar int/bool fields");
        rejected("""
                define actor Ledger as
                  state {}
                end
                """, "at least one field");
        // Literal AST kind is not a type: wrong scalar slots must fail
        // before actor allocation or the future physical state lowering.
        rejected("""
                define actor Ledger as
                  state { let int x = true; }
                end
                """, "initializer type must match int");
        rejected("""
                define actor Ledger as
                  state { let bool ready = 42; }
                end
                """, "initializer type must match bool");
        rejected("""
                define actor Ledger as
                  state { let int x = 1.5; }
                end
                """, "initializer type must match int");
        rejected("""
                define actor Ledger as
                  state { let bool ready = "true"; }
                end
                """, "initializer type must match bool");
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Ledger as
                  state { let int count = 0; let bool ready = false; }
                end
                """));
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
