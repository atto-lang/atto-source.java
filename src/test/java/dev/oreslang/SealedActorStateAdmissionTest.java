package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admitted sealed-schema path must retain its compiler marker and reject
 * direct access. Unsupported isolated actors stay fail-closed. Parsing alone
 * is not independent native-heap or runtime loan correctness proof.
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
        String source = """
                define actor Ledger as
                  state { phase: String = "created"; }
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(actor.fields().getFirst().annotations().stream()
                .anyMatch(annotation -> annotation.name().equals("__actor_state_schema")));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source.replace("end", """
                    receive(ActorMail<String> mail): void {
                      stdio.println(self.phase);
                      return;
                    }
                end
                """)));
        assertTrue(failure.getMessage().contains("sealed"), failure.getMessage());
    }

    @Test void userCannotSpoofSealedStateAuthorityWithActorMethod() {
        rejected("""
                define actor Ledger as
                  handle_state(): void { return; }
                end
                """, "sealed runtime actor intrinsic");
        rejected("""
                define actor Ledger as
                  pub fnc handle_state(): void { return; }
                end
                """, "sealed runtime actor intrinsic");
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
