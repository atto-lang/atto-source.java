package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Native Oreslang language parsing/type-and-ownership contracts, not a host arithmetic oracle. */
final class ImmutableDefaultScalarLocalTest {
    private static String program(String statements) {
        return "pub fnc main(): int { " + statements + " }";
    }

    private static void accepted(String statements) {
        assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse(program(statements))),
                statements);
    }

    private static void rejected(String statements) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program(statements))),
                statements);
    }

    @Test void builtinTypeFirstAndExistingConstAreReadonly() {
        // The equality here is the static observable value for integer 7;
        // it must not redefine arbitrary standalone const as runtime val.
        for (String body : new String[] {
                "int x = 7; return x;",
                "const x = 7; return x;",
                "const int x = 7; return x;",
                "const x: int = 7; return x;",
                "int x = 2 + 5; return x;",
                "int x = 7; int y = x * 3; return y;",
                "bool flag = true; return 7;",
                "string label = \"hello\"; return 7;",
                "int x = -9223372036854775808; return 7;"
        }) accepted(body);
    }

    @Test void implicitScalarLocalNeverGrantsRebindingOrMutableBorrow() {
        for (String body : new String[] {
                "int x = 7; x = 8; return x;",
                "int x = 7; x += 1; return x;",
                "bool flag = true; flag = false; return 7;",
                "string label = \"hi\"; label = \"bye\"; return 7;",
                "int x = 7; rt mut borrow x; return x;",
                "int x = 7; int x = 8; return x;",
                "int x = 7; const x = 8; return x;"
        }) rejected(body);
    }

    @Test void checksTypesAndInitializersWithoutImplicitConversion() {
        for (String body : new String[] {
                "int x = true; return 7;",
                "bool flag = 7; return 7;",
                "string label = 7; return 7;",
                "int x = unknown_name; return 7;",
                "int x = x + 1; return 7;",
                "int x = other; int other = 7; return 7;",
                "int x; return 7;",
                "int x = 7; return unknown_name;"
        }) rejected(body);
    }

    @Test void readonlyRuntimeInitializerFromHelperIsNotACopyOfConstSyntax()
            throws Exception {
        String source = """
                fnc increment(int p): int { return p + 1; }
                pub fnc main(): void {
                    int computed = increment(41);
                    stdio.println(computed);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        var program = Source.newBuilder(OresLanguage.ID, source,
                "readonly-runtime-helper.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertDoesNotThrow(() -> context.eval(program));
        }
        assertEquals("42" + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test void actualGraalGuestRunsImmutableDefaultBindings() throws Exception {
        // Unlike check-only assertions, each case evaluates Oreslang guest
        // source through the real Truffle language, capturing actual output.
        for (String declaration : new String[] {
                "int x = 7;",
                "const x = 7;",
                "const int x = 7;",
                "const x: int = 7;",
                "int x = 2 + 5;"
        }) {
            String source = "pub fnc main(): void { " + declaration
                    + " stdio.println(x); return; }";
            var program = Source.newBuilder(OresLanguage.ID, source,
                    "immutable-default-guest.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                assertDoesNotThrow(() -> context.eval(program), declaration);
            }
            assertEquals("7" + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8),
                    "guest value mismatch for " + declaration);
        }
    }

    @Test void explicitMutableLetSemanticsRemainSeparate() {
        accepted("let int x = 7; x = 8; return x;");
        rejected("const int x = 7; x = 8; return x;");
        // Existing lexical function calls must not be reclassified as
        // declarations merely because the callee resembles a type.
        rejected("int(7); return 7;");
        rejected("int missing = true; return 7;");
    }
}
