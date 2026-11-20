package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Atto operator-spelling v1: canonical int XOR versus legacy bool XOR.
 * Real Truffle Context guest tests use an exact-source, one-shot STRICT
 * prechecked AST lease. These are JVM guest tests, not Native Image AOT.
 */
final class AttoCanonicalIntegerXorParityTest {
    private static String canonicalGuest(String source) throws Exception {
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        String name = "canonical-integer-xor.atto";
        var guestSource = Source.newBuilder(OresLanguage.ID, source, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            try (var lease = OresLanguage.openPrecheckedProgram(name, source, checked)) {
                context.eval(guestSource);
            }
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void strictRejects(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        assertTrue(error.getMessage().contains(diagnostic),
                () -> "expected " + diagnostic + " in " + error.getMessage());
    }

    @Test
    void strictIntegerXorCompilesAndRunsInActualTruffleGuest() throws Exception {
        assertEquals("6\n12\n7\n", canonicalGuest("""
                pub fnc main(): void {
                    stdio.println(12 ^^ 10);
                    stdio.println(12 & 10 | 2 ^^ 6);
                    stdio.println(5 ^^ 2);
                    return;
                }
                """));
    }

    @Test
    void singleCaretReturnAndContiguousIntegerXorWorkTogether() throws Exception {
        assertEquals("6\n", canonicalGuest("""
                fnc result(): int { ^ 12 ^^ 10; }
                pub fnc main(): void {
                    stdio.println(result());
                    return;
                }
                """));
    }

    @Test
    void canonicalBooleanXorAndMixedScalarKindsFailDuringAdmission() {
        for (String expr : new String[]{
                "true ^^ false", "true ^^ 1", "1 ^^ false", "1.5 ^^ 2",
        }) {
            strictRejects("pub fnc main(): int { return " + expr + "; }",
                    "int");
        }
    }

    @Test
    void rejectedSingleOrSpacedCaretNeverCreatesCheckedAst() {
        for (String expr : new String[]{"12 ^ 10", "12 ^ ^ 10"}) {
            strictRejects("pub fnc main(): int { return " + expr + "; }",
                    "E_OPERATOR_CARET");
        }
    }

    @Test
    void legacyParserAndTypecheckerRetainEagerBooleanXor() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                pub fnc main(): bool {
                    return true ^^ false;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                pub fnc main(): int {
                    return 12 ^^ 10;
                }
                """)));
    }

    @Test
    void booleanXorLegacyGuestStillWorks() throws Exception {
        var source = Source.newBuilder(OresLanguage.ID, """
                pub fnc main(): void {
                    stdio.println(true ^^ false);
                    return;
                }
                """, "legacy-boolean-xor.ores").mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("true\n", output.toString(StandardCharsets.UTF_8));
    }
}
