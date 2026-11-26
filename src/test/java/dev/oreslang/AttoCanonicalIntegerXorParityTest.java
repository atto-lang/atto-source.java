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
 * Atto operator-spelling v1: integer XOR and independent named Boolean XOR.
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
    void strictCompoundXorUpdatesMutableIntegerOnce() throws Exception {
        assertEquals("rhs\n6\n", canonicalGuest("""
                fnc operand(): int { stdio.println("rhs"); return 10; }
                pub fnc main(): void {
                    let int x = 12;
                    x ^^= operand();
                    stdio.println(x);
                    return;
                }
                """));
    }

    @Test
    void compoundXorRejectsImmutableNonintegerAndSeparatedTokens() {
        strictRejects("pub fnc main(): int { val int x = 12; x ^^= 10; return x; }",
                "E_IMMUTABLE_REBIND");
        for (String body : new String[]{
                "let int x = 12; x ^= 10; return x;",
                "let int x = 12; x ^ ^= 10; return x;",
                "let int x = 12; x ^^ = 10; return x;",
                "let int x = 12; x ^^= true; return x;",
                "let bool x = true; x ^^= false; return 0;",
                "let int x = 12; (x + 1) ^^= 10; return x;",
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    OresCompiler.parseAndTypeCheckStrictAtto("pub fnc main(): int { " + body + " }"), body);
        }
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
    void canonicalIntegerXorRhsFailureExecutesEffectsOnlyOnce() throws Exception {
        String source = """
                fnc lhs(): int { stdio.println("left"); return 6; }
                fnc rhs(): int { stdio.println("right"); return [1][99]; }
                pub fnc main(): void {
                    stdio.println(lhs() ^^ rhs());
                    return;
                }
                """;
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        String name = "canonical-xor-rhs-failure.atto";
        var guestSource = Source.newBuilder(OresLanguage.ID, source, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            try (var lease = OresLanguage.openPrecheckedProgram(name, source, checked)) {
                assertThrows(org.graalvm.polyglot.PolyglotException.class,
                        () -> context.eval(guestSource));
            }
        }
        assertEquals("left\nright\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "failed eager XOR must not reevaluate either operand or publish a result");
    }

    @Test
    void defaultParserAndTypecheckerUseIntegerXorToo() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                pub fnc main(): bool { return true ^^ false; }
                """)));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                pub fnc main(): int { return 12 ^^ 10; }
                """)));
    }

    @Test
    void namedBooleanXorDefaultGuestStillWorks() throws Exception {
        var source = Source.newBuilder(OresLanguage.ID, """
                pub fnc main(): void {
                    stdio.println(Xor(true, false));
                    return;
                }
                """, "named-boolean-xor.atto").mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("true\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void strictSignedI64XorPrecedenceAndLeftAssociativeChain() throws Exception {
        // Independent regression cases from overlapping Java #70.
        // Preserve bit-pattern semantics, not a Boolean truthiness conversion.
        assertEquals("5\n-2\n1\n0\n", canonicalGuest("""
                pub fnc main(): void {
                    stdio.println(6 ^^ 3);
                    stdio.println(-1 ^^ 1);
                    stdio.println(1 | 2 ^^ 3 & 2);
                    stdio.println(1 ^^ 2 ^^ 3);
                    return;
                }
                """));
    }

    @Test
    void strictXorSuccessEvaluatesEachOperandExactlyOnceFromLeftToRight() throws Exception {
        assertEquals("left\nright\n5\n", canonicalGuest("""
                fnc lhs(): int { stdio.println("left"); return 6; }
                fnc rhs(): int { stdio.println("right"); return 3; }
                pub fnc main(): void {
                    stdio.println(lhs() ^^ rhs());
                    return;
                }
                """));
    }

    @Test
    void strictTripleCaretNeverBecomesXorOrReturn() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheckStrictAtto(
                        "pub fnc main(): int { return 6 ^^^ 3; }"));
    }

    @Test
    void strictNamedBooleanXorAndInequality() throws Exception {
        assertEquals("true\nfalse\n", canonicalGuest("""
                pub fnc main(): void {
                    stdio.println(Xor(true, false));
                    stdio.println(true neq true);
                    return;
                }
                """));
    }

    @Test
    void strictBooleanShortCircuitStillSkipsInvalidRightOperands() throws Exception {
        // ^^ must be eager without weakening the existing && and || semantics.
        assertEquals("false\ntrue\n", canonicalGuest("""
                pub fnc main(): void {
                    stdio.println(false && [true][99]);
                    stdio.println(true || [false][99]);
                    return;
                }
                """));
    }

}
