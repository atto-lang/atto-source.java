package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 admission tests: fail CLOSED until native sealed-state AST, loan/effect
 * checking, runtime RW permits and authenticating ingress exist. These are
 * targeted negative parsing diagnostics, NOT native semantic support.
 */
final class ActorSealedStateAdmissionTest {

    private static void denied(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void experimentalIsolatedActorSpellingMustNotDowngradeToOrdinaryActor() {
        denied("""
                define isolated actor ContextAssemblyActor as
                  state {
                    started: bool = false;
                  }
                end
                """, "isolated actor with sealed state requires");
        denied("""
                define module memory as
                  define isolated actor ContextAssemblyActor as
                    state { started: bool = false; }
                  end
                end
                """, "isolated actor with sealed state requires");
    }

    @Test
    void sealedStateSchemasCannotBeTreatedAsOrdinaryFieldsOrMethods() {
        for (String header : new String[] {"define actor", "define isoactor"}) {
            denied(header + """
                     Worker as
                       state {
                         task_id: String = "";
                         started: bool = false;
                       }
                     end
                     """, "actor sealed state { } requires compiler-enforced");
        }
    }

    @Test
    void scopedCriticalStateLoansAreNotOrdinarySelfMethodCalls() {
        denied("""
                define isoactor Worker as
                  pub read(): int {
                    return self.handle_state(|s| -> {
                      return 0;
                    });
                  }
                end
                """, "self.handle_state requires sealed actor state");
        denied("""
                define actor Worker as
                  pub write(): bool {
                    return self.handle_state(critical |s| -> {
                      return true;
                    });
                  }
                end
                """, "self.handle_state requires sealed actor state");
    }

    @Test
    void protectedStateEffectsCannotBeSilentlyDropped() {
        denied("""
                define isoactor Worker as
                  pub get(): int with state.read {
                    return 1;
                  }
                end
                """, "with state.read/state.write requires compiler-checked");
        denied("""
                define actor Worker as
                  pub set(): bool with state.read, state.write {
                    return true;
                  }
                end
                """, "with state.read/state.write requires compiler-checked");
    }

    @Test
    void ordinaryActorMethodsRemainAvailableForMigration() {
        assertDoesNotThrow(() -> Parser.parse("""
                define isoactor Worker as
                  pub run(): bool {
                    return true;
                  }
                end
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Worker as
                  val bool active = false;
                  pub run(): bool {
                    return true;
                  }
                end
                """));
    }

    @Test
    void ordinaryNamesInUnrelatedDeclarationsStayContextual() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                  val int isolated = 2;
                  val int state = 3;
                  return;
                }
                """));
    }

    @Test
    void admissionDoesNotClaimNativeProofOrEnableLegacyTokenEstimates() {
        denied("""
                define isolated actor ContextAssemblyActor as
                  state {
                    evidence_refs: Array<String> = new Array<String>();
                  }
                  pub add(String excerpt, int estimated_tokens): bool
                      with state.read, state.write {
                    return self.handle_state(critical |s| -> { return true; });
                  }
                end
                """, "isolated actor with sealed state requires");
    }
}
