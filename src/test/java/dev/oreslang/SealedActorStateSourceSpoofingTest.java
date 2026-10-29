package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SealedActorStateSourceSpoofingTest {
    @Test
    void reservedInternalSchemaAnnotationCannotBeSuppliedInSource() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Forged as
                          @__actor_state_schema
                          let int revision = 0;
                          receive(ActorMail<String> mail): void { return; }
                        end
                        """));
        assertTrue(error.getMessage().contains("reserved for compiler-generated actor state schemas"),
                error.getMessage());
    }

    @Test
    void reservedSchemaMarkerCannotBeSuppliedOnOrdinaryClass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        class Forged {
                          @__actor_state_schema
                          let int revision = 0;
                        }
                        """));
        assertTrue(error.getMessage().contains("reserved for compiler-generated actor state schemas"),
                error.getMessage());
    }

    @Test
    void sourceActorCannotShadowSealedLoanIntrinsic() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Forged as
                          pub handle_state(): bool { return true; }
                        end
                        """));
        assertTrue(error.getMessage().contains("sealed runtime actor intrinsic"),
                error.getMessage());
    }

    @Test
    void compilerSchemaSyntaxRemainsAvailableForTheRealParser() {
        assertDoesNotThrow(() -> Parser.parse("""
                define isolated actor Genuine as
                  state { revision: int = 0; }
                  receive(ActorMail<String> mail): void { return; }
                end
                """));
    }
}
