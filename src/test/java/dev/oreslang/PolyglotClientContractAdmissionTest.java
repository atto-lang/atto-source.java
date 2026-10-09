package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PolyglotClientContractAdmissionTest {
    private static final String VALID_DECLARATION = """
            pub define interface ClientContract
              fnc get_status(Unknown request_id) => Unknown;
              fnc get_items(Unknown page, Unknown page_size) => Unknown;
            end

            pub routine main(): void {
              return;
            }
            """;

    @Test
    void generatedClientContractSurfaceParsesAndTypeChecks() {
        assertDoesNotThrow(() -> Parser.parse(VALID_DECLARATION));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(VALID_DECLARATION));
    }

    @Test
    void rejectsReservedKeywordsInGeneratedMethodDeclarations() {
        String malformed = """
                define interface ClientContract
                  fnc done(Unknown request_id) => Unknown;
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(malformed));
    }
}
