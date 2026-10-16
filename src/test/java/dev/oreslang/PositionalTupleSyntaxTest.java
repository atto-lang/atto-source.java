package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parser acceptance only. Guest source parity requires separate lowering. */
final class PositionalTupleSyntaxTest {
    private static String declaration(String parameter) {
        return "define module app\n"
                + "  pub fnc run(" + parameter + "): void { }\n"
                + "end\n";
    }

    @Test
    void explicitAndParenthesizedSpellingAreTheSameAst() {
        var explicit = Parser.parse(declaration("tuple (int, int, string?) value"));
        var shorthand = Parser.parse(declaration("(int, int, string?) value"));
        assertEquals(explicit, shorthand);
    }

    @Test
    void structuralParameterRetainsItsExplicitReadonlyModifier() {
        var program = Parser.parse(declaration("structural (int, int, string?) value"));
        assertNotNull(program);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("not yet executable"), error.getMessage());
    }

    @Test
    void allOptionalTupleParametersFailClosedUntilGuestAbiIsImplemented() {
        for (String parameter : new String[] {
                "tuple (int, int, string?) value",
                "(int, int, string?) value",
                "structural (int, int, string?) value"
        }) {
            var parsed = Parser.parse(declaration(parameter));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(parsed), parameter);
            assertTrue(error.getMessage().contains("not yet executable"),
                    parameter + ": " + error.getMessage());
        }
    }

    @Test
    void structurallyWidenedTupleWithoutOptionalTailStillFailsClosed() {
        var parsed = Parser.parse(declaration("structural (int, int) value"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("not yet executable"), error.getMessage());
    }

    @Test
    void optionalMiddleTupleSlotIsRejectedImmediatelyByTheParser() {
        for (String parameter : new String[] {
                "tuple (int?, int, string?) value",
                "(int?, int, string?) value"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(declaration(parameter)));
        }
    }

    @Test
    void ordinaryExactTuplesContinueToUseTheExistingTypeChecker() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "define module app\n"
                        + "  fnc first((int, int) pair): int { return pair[0]; }\n"
                        + "  fnc test(): int { return first((5, 3)); }\n"
                        + "end\n")));
    }

    @Test
    void positionalTupleTypeHasBoundedStaticProofArity() {
        String slots = String.join(", ", java.util.Collections.nCopies(65, "int"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(declaration("tuple (" + slots + ") value")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(declaration("(" + slots + ") value")));
    }

    @Test
    void groupedSingletonTypeAndContextualTupleNameArePreserved() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "define module app\n"
                        + "  fnc tuple((int) value): int { return value; }\n"
                        + "  fnc test(): int { return tuple(5); }\n"
                        + "end\n")));
    }
}
