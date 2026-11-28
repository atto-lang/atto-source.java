package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Static namespace contracts must fail closed until their own ABI is admitted. */
final class StaticContractForwardPortAdmissionTest {
    @Test void unsupportedStaticContractDoesNotSilentlyBecomeInstanceContract() {
        String code = """
                define static contract Factory as
                  fnc create(int id) => int;
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(code));
    }

    @Test void OrdinaryModuleContractSyntaxRetainsDedicatedMeaning() {
        String code = """
                define contract Factory as
                  fnc create(int id) => int;
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(code));
    }
}
